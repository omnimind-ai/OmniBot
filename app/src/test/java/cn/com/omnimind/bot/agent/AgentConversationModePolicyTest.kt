package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            requestedAgentId = "dsh",
            conversationAgentId = "xiaowan-acp",
            sessionAgentId = "xiaowan-acp",
            selectedAgentId = "dsh",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("xiaowan-acp", resolution.agentId)
        assertEquals("dsh", resolution.conflictWithAgentId)
    }

    @Test
    fun existingHarnessOwnerWinsWhenRequestOmitsAgent() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = null,
            conversationAgentId = "dsh",
            sessionAgentId = "xiaowan-acp",
            selectedAgentId = "codex",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("dsh", resolution.agentId)
        assertNull(resolution.conflictWithAgentId)
    }

    @Test
    fun existingHarnessOwnerRejectsDifferentRequestedHarness() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = "codex",
            conversationAgentId = "dsh",
            sessionAgentId = null,
            selectedAgentId = "codex",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("dsh", resolution.agentId)
        assertEquals("dsh", resolution.conflictWithAgentId)
    }

    @Test
    fun newConversationCanClaimRequestedHarness() {
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = "agent",
            requestedAgentId = "codex",
            conversationAgentId = null,
            sessionAgentId = null,
            selectedAgentId = "dsh",
            xiaowanAgentId = "xiaowan-acp",
        )

        assertEquals("codex", resolution.agentId)
        assertNull(resolution.conflictWithAgentId)
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
