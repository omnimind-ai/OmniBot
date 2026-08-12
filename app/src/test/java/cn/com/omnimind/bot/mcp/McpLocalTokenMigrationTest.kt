package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.FailClosedSecretRepository
import cn.com.omnimind.baselib.util.LegacySecretStore
import cn.com.omnimind.baselib.util.SecureSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpLocalTokenMigrationTest {
    @Test
    fun legacyWeakVaultCiphertextIsOfferedForOneTimeSecureMigration() {
        val token = "a".repeat(43)
        val snapshot = buildLegacyMcpTokenSnapshot(
            weakVaultValue = "legacy-ciphertext",
            plaintextValue = null,
            decryptWeakVault = { token },
        )

        assertTrue(snapshot.existed)
        assertEquals(listOf(token), snapshot.candidates)
    }

    @Test
    fun oldPlaintextFallbackInVaultSlotIsRejectedAndErased() {
        val token = "b".repeat(43)
        var erased = false
        val secure = MemorySecureStore()
        val repository = FailClosedSecretRepository(
            key = "mcp.local_server.token.v1",
            secureStore = secure,
            legacyStore = object : LegacySecretStore {
                override fun snapshot() = buildLegacyMcpTokenSnapshot(
                    weakVaultValue = token,
                    plaintextValue = null,
                    decryptWeakVault = { null },
                )

                override fun erase() {
                    erased = true
                }
            },
            isValid = ::isValidMcpToken,
        )

        assertNull(repository.loadOrCreate { "c".repeat(43) })
        assertNull(secure.value)
        assertTrue(erased)
    }

    @Test
    fun legacyPlaintextKeyMigratesOnlyAfterVerifiedSecureWrite() {
        val token = "g".repeat(43)
        var erased = false
        val secure = MemorySecureStore()
        val repository = FailClosedSecretRepository(
            key = "mcp.local_server.token.v1",
            secureStore = secure,
            legacyStore = object : LegacySecretStore {
                override fun snapshot() = buildLegacyMcpTokenSnapshot(
                    weakVaultValue = null,
                    plaintextValue = token,
                    decryptWeakVault = { null },
                )

                override fun erase() {
                    erased = true
                }
            },
            isValid = ::isValidMcpToken,
        )

        assertEquals(token, repository.loadOrCreate { "h".repeat(43) })
        assertEquals(token, secure.value)
        assertTrue(erased)
    }

    @Test
    fun migrationWriteFailureErasesLegacyAndDoesNotReturnToken() {
        val token = "d".repeat(43)
        var erased = false
        val repository = FailClosedSecretRepository(
            key = "mcp.local_server.token.v1",
            secureStore = MemorySecureStore(writeSucceeds = false),
            legacyStore = object : LegacySecretStore {
                override fun snapshot() = buildLegacyMcpTokenSnapshot(
                    weakVaultValue = null,
                    plaintextValue = token,
                    decryptWeakVault = { null },
                )

                override fun erase() {
                    erased = true
                }
            },
            isValid = ::isValidMcpToken,
        )

        assertNull(repository.loadOrCreate { "e".repeat(43) })
        assertTrue(erased)
    }

    @Test
    fun undecipherableLegacyRecordFailsClosedWithoutGeneratingNewToken() {
        val snapshot = buildLegacyMcpTokenSnapshot(
            weakVaultValue = "not-a-valid-token",
            plaintextValue = null,
            decryptWeakVault = { null },
        )
        var generated = false
        val repository = FailClosedSecretRepository(
            key = "mcp.local_server.token.v1",
            secureStore = MemorySecureStore(),
            legacyStore = object : LegacySecretStore {
                override fun snapshot() = snapshot
                override fun erase() = Unit
            },
            isValid = ::isValidMcpToken,
        )

        assertNull(repository.loadOrCreate {
            generated = true
            "f".repeat(43)
        })
        assertFalse(generated)
    }

    private class MemorySecureStore(
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
