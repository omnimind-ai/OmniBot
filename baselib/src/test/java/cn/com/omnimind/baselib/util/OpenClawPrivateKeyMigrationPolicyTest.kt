package cn.com.omnimind.baselib.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawPrivateKeyMigrationPolicyTest {
    @Test
    fun rawLegacyPrivateKeyIsErasedAfterVerifiedEncryptedMigration() {
        val encodedPrivateKey = "private-key-material-encoded-for-test"
        val secure = MemoryStore()
        var legacyErased = false
        val repository = FailClosedSecretRepository(
            key = "openclaw.device_identity.ed25519.private.v1",
            secureStore = secure,
            legacyStore = legacy(encodedPrivateKey) { legacyErased = true },
            isValid = { it == encodedPrivateKey },
        )

        assertEquals(encodedPrivateKey, repository.loadOrCreate { "replacement" })
        assertEquals(encodedPrivateKey, secure.value)
        assertTrue(legacyErased)
    }

    @Test
    fun privateKeyMigrationFailureErasesRawLegacyKeyAndFailsClosed() {
        val encodedPrivateKey = "private-key-material-encoded-for-test"
        val secure = MemoryStore(writeSucceeds = false)
        var legacyErased = false
        val repository = FailClosedSecretRepository(
            key = "openclaw.device_identity.ed25519.private.v1",
            secureStore = secure,
            legacyStore = legacy(encodedPrivateKey) { legacyErased = true },
            isValid = { it == encodedPrivateKey },
        )

        assertNull(repository.loadOrCreate { "replacement" })
        assertNull(secure.value)
        assertTrue(legacyErased)
    }

    private fun legacy(value: String, erase: () -> Unit): LegacySecretStore =
        object : LegacySecretStore {
            override fun snapshot() = LegacySecretSnapshot(true, listOf(value))

            override fun erase() = erase()
        }

    private class MemoryStore(
        private val writeSucceeds: Boolean = true,
    ) : SecureSecretStore {
        var value: String? = null

        override fun isAvailable(): Boolean = true

        override fun read(key: String): String? = value

        override fun write(key: String, value: String): Boolean {
            if (!writeSucceeds) return false
            this.value = value
            return true
        }

        override fun delete(key: String): Boolean {
            value = null
            return true
        }
    }
}
