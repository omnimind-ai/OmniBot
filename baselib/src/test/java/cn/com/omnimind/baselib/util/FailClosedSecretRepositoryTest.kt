package cn.com.omnimind.baselib.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailClosedSecretRepositoryTest {
    @Test
    fun migratesLegacyValueAndErasesEveryLegacyRepresentation() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            LegacySecretSnapshot(existed = true, candidates = listOf("legacy-secret")),
        )
        val repository = repository(secure, legacy)

        assertEquals("legacy-secret", repository.loadOrCreate { "new-secret" })
        assertEquals("legacy-secret", secure.values[SECRET_KEY])
        assertTrue(legacy.erased)
    }

    @Test
    fun failedMigrationErasesLegacyAndReturnsNoSecret() {
        val secure = FakeSecureStore(writeSucceeds = false)
        val legacy = FakeLegacyStore(
            LegacySecretSnapshot(existed = true, candidates = listOf("legacy-secret")),
        )
        val repository = repository(secure, legacy)

        assertNull(repository.loadOrCreate { "new-secret" })
        assertTrue(legacy.erased)
        assertFalse(secure.values.containsKey(SECRET_KEY))
    }

    @Test
    fun unavailableSecureStoreErasesLegacyWithoutGeneratingPlaintextFallback() {
        val secure = FakeSecureStore(available = false)
        val legacy = FakeLegacyStore(
            LegacySecretSnapshot(existed = true, candidates = listOf("legacy-secret")),
        )
        var generated = false
        val repository = repository(secure, legacy)

        assertNull(repository.loadOrCreate {
            generated = true
            "new-secret"
        })
        assertFalse(generated)
        assertTrue(legacy.erased)
        assertTrue(secure.values.isEmpty())
    }

    @Test
    fun corruptLegacyValueFailsClosedInsteadOfSilentlyReplacingIdentity() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            LegacySecretSnapshot(existed = true, candidates = listOf("invalid")),
        )
        var generated = false
        val repository = repository(secure, legacy)

        assertNull(repository.loadOrCreate {
            generated = true
            "new-secret"
        })
        assertFalse(generated)
        assertTrue(legacy.erased)
    }

    @Test
    fun freshSecretIsReturnedOnlyAfterVerifiedSecureReadback() {
        val secure = FakeSecureStore(readbackSucceeds = false)
        val legacy = FakeLegacyStore(LegacySecretSnapshot(false, emptyList()))
        val repository = repository(secure, legacy)

        assertNull(repository.loadOrCreate { "new-secret" })
        assertFalse(secure.values.containsKey(SECRET_KEY))
        assertTrue(legacy.erased)
    }

    @Test
    fun secureReadFailureErasesLegacyAndDoesNotGenerateReplacement() {
        val secure = FakeSecureStore(readThrows = true)
        val legacy = FakeLegacyStore(
            LegacySecretSnapshot(existed = true, candidates = listOf("legacy-secret")),
        )
        var generated = false
        val repository = repository(secure, legacy)

        assertNull(repository.loadOrCreate {
            generated = true
            "new-secret"
        })
        assertFalse(generated)
        assertTrue(legacy.erased)
    }

    @Test
    fun migrationOnlyPathDoesNotCreateSecretWhenNoLegacyValueExists() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(LegacySecretSnapshot(false, emptyList()))
        val repository = repository(secure, legacy)

        assertNull(repository.loadExisting())
        assertTrue(secure.values.isEmpty())
        assertTrue(legacy.erased)
    }

    private fun repository(
        secure: FakeSecureStore,
        legacy: FakeLegacyStore,
    ) = FailClosedSecretRepository(
        key = SECRET_KEY,
        secureStore = secure,
        legacyStore = legacy,
        isValid = { it.endsWith("-secret") },
    )

    private class FakeSecureStore(
        private val available: Boolean = true,
        private val writeSucceeds: Boolean = true,
        private val readbackSucceeds: Boolean = true,
        private val readThrows: Boolean = false,
    ) : SecureSecretStore {
        val values = mutableMapOf<String, String>()

        override fun isAvailable(): Boolean = available

        override fun read(key: String): String? {
            if (readThrows) error("simulated secure-store read failure")
            return if (readbackSucceeds) values[key] else null
        }

        override fun write(key: String, value: String): Boolean {
            if (!available || !writeSucceeds) return false
            values[key] = value
            return true
        }

        override fun delete(key: String): Boolean {
            values.remove(key)
            return available
        }
    }

    private class FakeLegacyStore(
        private val value: LegacySecretSnapshot,
    ) : LegacySecretStore {
        var erased: Boolean = false

        override fun snapshot(): LegacySecretSnapshot = value

        override fun erase() {
            erased = true
        }
    }

    private companion object {
        const val SECRET_KEY = "test.secret"
    }
}
