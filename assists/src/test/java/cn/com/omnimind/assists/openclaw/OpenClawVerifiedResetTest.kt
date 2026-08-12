package cn.com.omnimind.assists.openclaw

import cn.com.omnimind.baselib.util.FailClosedSecretRepository
import cn.com.omnimind.baselib.util.LegacySecretSnapshot
import cn.com.omnimind.baselib.util.LegacySecretStore
import cn.com.omnimind.baselib.util.SecureSecretStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawVerifiedResetTest {
    @Test
    fun `has existing identity never creates one`() {
        val secure = FakeSecureStore()
        val repository = repository(secure)
        val reset = OpenClawVerifiedIdentityReset(repository, FakeMetadata(), {})

        assertFalse(reset.hasExistingIdentity())
        assertFalse(reset.hasExistingIdentity())
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun `identity reset clears secret metadata and cache then verifies absence`() {
        val secure = FakeSecureStore(mutableMapOf("identity" to "private"))
        val metadata = FakeMetadata(clear = false)
        var cacheClears = 0
        val reset = OpenClawVerifiedIdentityReset(repository(secure), metadata) {
            cacheClears++
        }

        assertTrue(reset.hasExistingIdentity())
        assertTrue(reset.reset())
        assertFalse(reset.hasExistingIdentity())
        assertTrue(metadata.isClear())
        assertEquals(2, cacheClears)
    }

    @Test
    fun `identity reset fails closed when secure deletion fails`() {
        val secure = FakeSecureStore(
            values = mutableMapOf("identity" to "private"),
            deleteSucceeds = false,
        )
        val reset = OpenClawVerifiedIdentityReset(repository(secure), FakeMetadata(false), {})

        assertFalse(reset.reset())
        assertTrue(reset.hasExistingIdentity())
    }

    @Test
    fun `identity inspection and reset are serialized`() {
        val secure = FakeSecureStore(mutableMapOf("identity" to "private"))
        val reset = OpenClawVerifiedIdentityReset(repository(secure), FakeMetadata(false), {})
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        val outcomes = Collections.synchronizedList(mutableListOf<Boolean>())
        repeat(24) { index ->
            pool.submit {
                start.await()
                outcomes += if (index == 0) reset.reset() else {
                    reset.hasExistingIdentity()
                    true
                }
            }
        }
        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(outcomes.all { it })
        assertFalse(reset.hasExistingIdentity())
    }

    @Test
    fun `pairing reset clears device data and preserves gateway token`() {
        val secure = FakeSecureStore(
            mutableMapOf("device" to "device-secret", "gateway" to "gateway-secret"),
        )
        val metadata = FakeMetadata(false)
        val reset = OpenClawVerifiedPairingReset(secure, "device", "gateway", metadata)

        assertTrue(reset.reset())
        assertNull(secure.read("device"))
        assertEquals("gateway-secret", secure.read("gateway"))
        assertTrue(metadata.isClear())
    }

    @Test
    fun `pairing reset fails when Keystore is unavailable`() {
        val secure = FakeSecureStore(available = false)
        val reset = OpenClawVerifiedPairingReset(secure, "device", "gateway", FakeMetadata(false))

        assertFalse(reset.reset())
    }

    private fun repository(secure: FakeSecureStore): FailClosedSecretRepository =
        FailClosedSecretRepository(
            key = "identity",
            secureStore = secure,
            legacyStore = object : LegacySecretStore {
                override fun snapshot() = LegacySecretSnapshot(false, emptyList())
                override fun erase() = Unit
            },
            isValid = String::isNotBlank,
        )

    private class FakeMetadata(private var clear: Boolean = true) : OpenClawResetMetadataStore {
        override fun clear(): Boolean {
            clear = true
            return true
        }

        override fun isClear(): Boolean = clear
    }

    private class FakeSecureStore(
        val values: MutableMap<String, String> = mutableMapOf(),
        private val available: Boolean = true,
        private val deleteSucceeds: Boolean = true,
    ) : SecureSecretStore {
        override fun isAvailable(): Boolean = available
        override fun read(key: String): String? {
            check(available)
            return values[key]
        }

        override fun write(key: String, value: String): Boolean {
            if (!available) return false
            values[key] = value
            return true
        }

        override fun delete(key: String): Boolean {
            if (!available || !deleteSucceeds) return false
            values.remove(key)
            return true
        }
    }
}
