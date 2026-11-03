package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationModePolicyTest {

    @Test
    fun sharedAgentRuntimeUsesCanonicalAgentStorageMode() {
        assertEquals("agent", AgentConversationModePolicy.AGENT_MODE)
    }

    @Test
    fun normalConversationAlwaysUsesXiaowanAndRejectsHarnessSwitch() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = AgentConversationModePolicy.NORMAL_MODE,
            requestedAgentId = "opencode-acp",
            conversationAgentId = "xiaowan-acp",
            sessionAgentId = "xiaowan-acp",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("xiaowan-acp", resolution.agentId)
        assertEquals("xiaowan-acp", resolution.conflictWithAgentId)
    }

    @Test
    fun existingHarnessOwnerWinsWhenRequestOmitsAgent() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = null,
            conversationAgentId = "opencode-acp",
            sessionAgentId = "opencode-acp",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("opencode-acp", resolution.agentId)
        assertNull(resolution.conflictWithAgentId)
    }

    @Test
    fun existingHarnessOwnerRejectsDifferentRequestedHarness() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = "codex",
            conversationAgentId = "opencode-acp",
            sessionAgentId = null,
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("opencode-acp", resolution.agentId)
        assertEquals("opencode-acp", resolution.conflictWithAgentId)
    }

    @Test
    fun newConversationCanClaimRequestedHarness() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = "codex",
            conversationAgentId = null,
            sessionAgentId = null,
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("codex", resolution.agentId)
        assertNull(resolution.conflictWithAgentId)
    }

    @Test
    fun newAgentRequestsRequireAnExplicitOwner() {
        for (mode in listOf(null, "agent", "codex", "acp", "coding", "subagent")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AgentConversationModePolicy.resolveHarness(
                    conversationMode = mode,
                    requestedAgentId = " ",
                    conversationAgentId = null,
                    sessionAgentId = null,
                    xiaowanAgentId = "xiaowan-acp",
                )
            }
            assertTrue(error.message.orEmpty().startsWith("agent_identity_required:"))
        }
    }

    @Test
    fun sessionOwnerRecoversAnOlderConversationWithoutChoosingAnotherAgent() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = null,
            conversationAgentId = null,
            sessionAgentId = " codex-remote ",
            xiaowanAgentId = "xiaowan-acp",
        )
        assertEquals("codex-remote", resolution.agentId)
        assertNull(resolution.conflictWithAgentId)
    }

    @Test
    fun conflictingConversationAndSessionOwnersAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentConversationModePolicy.resolveHarness(
                conversationMode = "agent",
                requestedAgentId = null,
                conversationAgentId = "xiaowan-acp",
                sessionAgentId = "opencode-acp",
                xiaowanAgentId = "xiaowan-acp",
            )
        }
        assertTrue(error.message.orEmpty().startsWith("agent_owner_conflict:"))
    }

    @Test
    fun explicitProductModesSelectXiaowanWithoutAGlobalPreference() {
        for (mode in listOf("normal", "chat_only")) {
            val resolution = AgentConversationModePolicy.resolveHarness(
                conversationMode = mode,
                requestedAgentId = null,
                conversationAgentId = null,
                sessionAgentId = null,
                xiaowanAgentId = "xiaowan-acp",
            )
            assertEquals("xiaowan-acp", resolution.agentId)
            assertNull(resolution.conflictWithAgentId)
        }
    }

    @Test
    fun explicitProductModesCannotReplaceExistingOwners() {
        for (mode in listOf("normal", "chat_only")) {
            for ((conversationOwner, sessionOwner) in listOf(
                "opencode-acp" to null,
                null to "codex-remote",
                "xiaowan-acp" to "opencode-acp",
            )) {
                val error = assertThrows(IllegalArgumentException::class.java) {
                    AgentConversationModePolicy.resolveHarness(
                        conversationMode = mode,
                        requestedAgentId = "xiaowan-acp",
                        conversationAgentId = conversationOwner,
                        sessionAgentId = sessionOwner,
                        xiaowanAgentId = "xiaowan-acp",
                    )
                }
                assertTrue(error.message.orEmpty().startsWith("agent_owner_conflict:"))
            }
        }
    }

    @Test
    fun subagentModeKeepsTheHarnessToolCatalogIntact() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.memoryTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.subagentTools(PromptLocale.ZH_CN)

        val filtered = AgentConversationModePolicy.filterToolDefinitionsForConversationMode(
            definitions = definitions,
            conversationMode = AgentConversationModePolicy.SUBAGENT_MODE
        )
        val toolNames = filtered.mapNotNull { definition ->
            ((definition["function"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.contentOrNull)
        }

        assertTrue(toolNames.contains("schedule_task_create"))
        assertTrue(toolNames.contains("alarm_reminder_create"))
        assertTrue(toolNames.contains("calendar_event_create"))
        assertTrue(toolNames.contains("subagent_dispatch"))
        assertTrue(toolNames.contains("memory_search"))
    }

    @Test
    fun chatOnlyModeExposesNoTools() {
        val definitions = AgentToolDefinitions.staticTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.memoryTools(PromptLocale.ZH_CN) +
            AgentToolDefinitions.subagentTools(PromptLocale.ZH_CN)

        val filtered = AgentConversationModePolicy.filterToolDefinitionsForConversationMode(
            definitions = definitions,
            conversationMode = AgentConversationModePolicy.CHAT_ONLY_MODE
        )

        assertTrue(filtered.isEmpty())
    }
}
