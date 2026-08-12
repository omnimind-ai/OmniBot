package cn.com.omnimind.bot.agent.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpAgentProfileSecurityTest {
    @Test
    fun payloadExposesEnvironmentNamesButNeverValues() {
        val payload = AcpAgentProfile(
            id = "custom",
            name = "Custom",
            command = "agent",
            environment = linkedMapOf(
                "API_TOKEN" to "secret-value",
                "REGION" to "cn",
            ),
        ).toPayload()

        assertEquals(
            mapOf("API_TOKEN" to "", "REGION" to ""),
            payload["environment"],
        )
        assertEquals(listOf("API_TOKEN", "REGION"), payload["environmentSecretKeys"])
        assertFalse(payload.toString().contains("secret-value"))
        assertTrue(payload.toString().contains("API_TOKEN"))
    }

    @Test
    fun officialIdCannotBypassManagedRuntimeWithCommandOverride() {
        val injected = AcpAgentProfile(
            id = AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID,
            name = "Codex",
            command = "/root/.npm-global/bin/codex-acp",
            arguments = listOf("--unsafe")
        )

        val runtime = AcpAgentProfileStore.officialRuntime(injected)

        assertEquals(MANAGED_CODEX_ACP_PACKAGE_SPEC, runtime?.managedAdapterPackage)
        val definition = AcpAgentProfileStore.OFFICIAL_AGENTS.single {
            it.id == AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
        }
        assertEquals("codex-acp", definition.command)
        assertTrue(definition.arguments.isEmpty())
    }

    @Test
    fun customIdRemainsCustomAndCannotBorrowOfficialManagedRuntime() {
        val custom = AcpAgentProfile(
            id = "my-private-agent",
            name = "Private",
            command = "/opt/my-agent",
            arguments = listOf("serve")
        )

        assertEquals(null, AcpAgentProfileStore.officialRuntime(custom))
        assertEquals("/opt/my-agent", custom.command)
        assertEquals(listOf("serve"), custom.arguments)
    }

    @Test
    fun profilePersistenceSnapshotsSecretsVerifiesMetadataAndRollsBackOnFailure() {
        val source = File(
            "src/main/java/cn/com/omnimind/bot/agent/runtime/AcpAgentProfileStore.kt"
        ).readText()
        val writeStart = source.indexOf("private fun writeProfiles")
        val writeEnd = source.indexOf("private fun migrateLegacyEnvironmentValues", writeStart)
        val writeBody = source.substring(writeStart, writeEnd)

        assertTrue(writeBody.contains("AppSecretStore.readWithStatus"))
        assertTrue(writeBody.contains("preferences.getString(KEY_PROFILES, null) != desiredMetadataJson"))
        assertTrue(writeBody.contains("snapshots.forEach"))
        assertTrue(writeBody.contains("oldMetadataJson"))
        assertTrue(writeBody.contains("ACP_AGENT_PROFILE_PERSIST_FAILED"))
        assertFalse(writeBody.contains(".apply()"))
    }
}
