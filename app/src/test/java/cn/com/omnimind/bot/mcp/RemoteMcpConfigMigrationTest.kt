package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpConfigMigrationTest {
    @Test
    fun plaintextBearerIsVerifiedThenRemovedFromMetadata() {
        val raw = """
            [{
              "id":"server-1",
              "name":"Remote",
              "endpointUrl":"https://mcp.example.com/sse",
              "bearerToken":"sensitive-value",
              "enabled":true
            }]
        """.trimIndent()
        val persisted = linkedMapOf<String, String>()

        val outcome = RemoteMcpConfigMigration.migrate(listOf(raw)) { id, token ->
            persisted[id] = token
            persisted[id] == token
        }

        assertTrue(outcome.succeeded)
        assertTrue(outcome.credentialIds.contains("server-1"))
        assertTrue(persisted.containsKey("server-1"))
        assertFalse(outcome.metadataJson.contains("sensitive-value"))
        assertFalse(outcome.metadataJson.contains("bearerToken"))
        assertTrue(outcome.metadataJson.contains("hasBearerToken"))
    }

    @Test
    fun encryptedWriteFailureProducesDisabledCredentialFreeMetadata() {
        val raw = """
            [{
              "id":"server-1",
              "name":"Remote",
              "endpointUrl":"https://mcp.example.com/sse",
              "bearerToken":"sensitive-value",
              "enabled":true
            }]
        """.trimIndent()

        val outcome = RemoteMcpConfigMigration.migrate(listOf(raw)) { _, _ -> false }
        val records = RemoteMcpConfigMigration.decodeMetadata(outcome.metadataJson)

        assertFalse(outcome.succeeded)
        assertFalse(outcome.metadataJson.contains("sensitive-value"))
        assertFalse(outcome.metadataJson.contains("bearerToken"))
        assertTrue(records.size == 1)
        assertFalse(records.single().enabled)
        assertFalse(records.single().hasBearerToken)
    }

    @Test
    fun failClosedPreservesCredentialFreeServersButDisablesCredentialServers() {
        val raw = """
            [
              {"id":"public","name":"Public","endpointUrl":"https://public.example.com","bearerToken":"","enabled":true},
              {"id":"private","name":"Private","endpointUrl":"https://private.example.com","bearerToken":"sensitive-value","enabled":true}
            ]
        """.trimIndent()

        val records = RemoteMcpConfigMigration.decodeMetadata(
            RemoteMcpConfigMigration.failClosed(listOf(raw)).metadataJson
        )

        assertTrue(records.first { it.id == "public" }.enabled)
        assertFalse(records.first { it.id == "private" }.enabled)
        assertFalse(records.any(RemoteMcpMetadataRecord::hasBearerToken))
    }

    @Test
    fun legacySensitiveQueryIsRemovedEvenWithoutSeparateBearerToken() {
        val raw = """
            [{
              "id":"server-1",
              "name":"Remote",
              "endpointUrl":"https://mcp.example.com/sse?access_token=embedded",
              "bearerToken":"",
              "enabled":true
            }]
        """.trimIndent()

        val outcome = RemoteMcpConfigMigration.migrate(listOf(raw)) { _, _ -> true }
        val record = RemoteMcpConfigMigration.decodeMetadata(outcome.metadataJson).single()

        assertTrue(outcome.succeeded)
        assertEquals("", record.endpointUrl)
        assertFalse(record.enabled)
        assertFalse(outcome.metadataJson.contains("embedded"))
    }
}
