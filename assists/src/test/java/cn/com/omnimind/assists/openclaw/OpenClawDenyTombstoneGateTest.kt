package cn.com.omnimind.assists.openclaw

import cn.com.omnimind.assists.api.bean.TaskParams
import cn.com.omnimind.baselib.util.SecureSecretStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawDenyTombstoneGateTest {
    @Test
    fun `successful confirmed save clears tombstone and survives restart`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val first = repository(secure, tombstone)

        val enabled = enable(first)
        assertTrue(enabled.enabled)
        assertFalse(tombstone.denied)

        val restarted = repository(secure, tombstone)
        assertTrue(restarted.isAuthorized(enabled.toTaskConfig()))
    }

    @Test
    fun `crash after deny write and failed encrypted disable stays denied after restart`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val initial = repository(secure, tombstone)
        val enabled = enable(initial)
        secure.failWrites = true
        secure.failDeletes = true

        val disabled = initial.disableAndAdvance()

        assertFalse(disabled.success)
        assertTrue(tombstone.denied)
        val restarted = repository(secure, tombstone)
        assertFalse(restarted.snapshot().enabled)
        assertFalse(restarted.isAuthorized(enabled.toTaskConfig()))
    }

    @Test
    fun `disable writes tombstone before an encrypted read failure`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val repo = repository(secure, tombstone)
        val enabled = enable(repo)
        secure.failReads = true

        val result = repo.disableAndAdvance()

        assertFalse(result.success)
        assertEquals(OpenClawConfigurationStatus.STORAGE_UNAVAILABLE, result.status)
        assertTrue(tombstone.denied)
        secure.failReads = false
        assertFalse(repository(secure, tombstone).isAuthorized(enabled.toTaskConfig()))
    }

    @Test
    fun `tombstone clear failure leaves verified encrypted config inactive`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true, failClear = true)
        val repo = repository(secure, tombstone)
        val plan = requireNotNull(repo.prepareDestination(ENDPOINT))

        val result = repo.saveConfirmed(
            requestId = plan.requestId,
            expectedGeneration = plan.expectedGeneration,
            confirmedOrigin = plan.canonicalOrigin,
            rawBaseUrl = plan.baseUrl,
            userId = USER_ID,
            credentialMutation = OpenClawCredentialMutation.REPLACE,
            replacementToken = "credential",
            enable = true,
        )

        assertFalse(result.success)
        assertEquals(
            OpenClawConfigurationStatus.DENY_TOMBSTONE_CLEAR_FAILED,
            result.status,
        )
        assertTrue(tombstone.denied)
        assertFalse(repository(secure, tombstone).snapshot().enabled)
    }

    @Test
    fun `tombstone read failure is denied before encrypted configuration gate`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val repo = repository(secure, tombstone)
        val enabled = enable(repo)
        tombstone.failReads = true

        assertFalse(repo.isAuthorized(enabled.toTaskConfig()))
        assertFalse(repository(secure, tombstone).snapshot().enabled)
    }

    @Test
    fun `legacy import writes deny tombstone and remains inactive after restart`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val first = repository(secure, tombstone)
        enable(first)

        val migrated = first.migrateLegacyInactive(
            rawBaseUrl = ENDPOINT,
            legacyGatewayToken = "legacy-credential",
            userId = "legacy-user",
        )

        assertTrue(migrated.success)
        assertTrue(tombstone.denied)
        assertFalse(repository(secure, tombstone).snapshot().enabled)
    }

    @Test
    fun `concurrent confirmed CAS permits one generation and clears only its tombstone`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val repo = repository(secure, tombstone)
        val firstPlan = requireNotNull(repo.prepareDestination(ENDPOINT))
        val secondPlan = requireNotNull(repo.prepareDestination(ENDPOINT))
        val start = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(
            mutableListOf<OpenClawConfigurationMutationResult>(),
        )
        val pool = Executors.newFixedThreadPool(2)
        listOf(firstPlan, secondPlan).forEach { plan ->
            pool.submit {
                start.await()
                outcomes += repo.saveConfirmed(
                    requestId = plan.requestId,
                    expectedGeneration = plan.expectedGeneration,
                    confirmedOrigin = plan.canonicalOrigin,
                    rawBaseUrl = plan.baseUrl,
                    userId = USER_ID,
                    credentialMutation = OpenClawCredentialMutation.KEEP,
                    replacementToken = null,
                    enable = true,
                )
            }
        }
        start.countDown()
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, outcomes.count { it.success })
        assertEquals(1, outcomes.count { it.status == OpenClawConfigurationStatus.STALE_GENERATION })
        assertFalse(tombstone.denied)
    }

    @Test
    fun `failed tombstone write still persists encrypted disabled fallback`() {
        val secure = FakeSecureStore()
        val tombstone = FakeTombstoneStore(denied = true)
        val repo = repository(secure, tombstone)
        enable(repo)
        tombstone.failMarks = true

        val result = repo.disableAndAdvance()

        assertFalse(result.success)
        assertEquals(
            OpenClawConfigurationStatus.DENY_TOMBSTONE_PERSIST_FAILED,
            result.status,
        )
        tombstone.denied = false
        assertFalse(repository(secure, tombstone).snapshot().enabled)
    }

    private fun repository(
        secure: FakeSecureStore,
        tombstone: FakeTombstoneStore,
    ): OpenClawConfigurationRepository = OpenClawConfigurationRepository(
        secureStore = secure,
        denyTombstoneStore = tombstone,
    )

    private fun enable(repo: OpenClawConfigurationRepository): OpenClawConfigurationSnapshot {
        val plan = requireNotNull(repo.prepareDestination(ENDPOINT))
        val result = repo.saveConfirmed(
            requestId = plan.requestId,
            expectedGeneration = plan.expectedGeneration,
            confirmedOrigin = plan.canonicalOrigin,
            rawBaseUrl = plan.baseUrl,
            userId = USER_ID,
            credentialMutation = OpenClawCredentialMutation.REPLACE,
            replacementToken = "credential",
            enable = true,
        )
        assertTrue(result.success)
        return requireNotNull(result.snapshot)
    }

    private fun OpenClawConfigurationSnapshot.toTaskConfig() = TaskParams.OpenClawConfig(
        baseUrl = baseUrl,
        userId = userId,
        generation = generation,
        canonicalOrigin = allowedOrigin,
    )

    private class FakeSecureStore : SecureSecretStore {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        var failDeletes = false
        var failReads = false

        override fun isAvailable(): Boolean = true

        override fun read(key: String): String? {
            if (failReads) error("read failed")
            return values[key]
        }

        override fun write(key: String, value: String): Boolean {
            if (failWrites) return false
            values[key] = value
            return true
        }

        override fun delete(key: String): Boolean {
            if (failDeletes) return false
            values.remove(key)
            return true
        }
    }

    private class FakeTombstoneStore(
        var denied: Boolean,
        var generation: Long = 0L,
        var failReads: Boolean = false,
        var failMarks: Boolean = false,
        var failClear: Boolean = false,
    ) : OpenClawDenyTombstoneStore {
        override fun read(): OpenClawDenyTombstone? = if (failReads) {
            null
        } else {
            OpenClawDenyTombstone(denied, generation)
        }

        override fun markDenied(generation: Long): Boolean {
            if (failMarks) return false
            denied = true
            this.generation = maxOf(this.generation, generation)
            return true
        }

        override fun clearDenied(committedGeneration: Long): Boolean {
            if (failClear || generation > committedGeneration) return false
            denied = false
            this.generation = committedGeneration
            return true
        }
    }

    private companion object {
        const val ENDPOINT = "https://gateway.example"
        const val USER_ID = "user-1"
    }
}
