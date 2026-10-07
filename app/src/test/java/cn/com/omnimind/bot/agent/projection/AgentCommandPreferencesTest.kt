package cn.com.omnimind.bot.agent.projection

import cn.com.omnimind.bot.agent.projection.AgentCommandPreferences.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native reader of the Agent command preferences (5d-1a), on the Flutter keys. */
class AgentCommandPreferencesTest {
    private val values = LinkedHashMap<String, String>()
    private val preferences = AgentCommandPreferences(object : AgentCommandPreferences.KeyValueStore {
        override fun getString(key: String) = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    })

    @Test
    fun `writes use the Flutter keys, global plus the conversation scope`() {
        preferences.write(Kind.ReasoningEffort, " high ", conversationId = 7, modelSource = "local-codex-acp")
        preferences.write(Kind.Model, "gpt-5-codex", conversationId = 7, modelSource = "local-codex-acp")
        assertEquals(
            mapOf(
                "flutter.chat_agent_command_preference.reasoning_effort.global" to "high",
                "flutter.chat_agent_command_preference.reasoning_effort.conversation.7" to "high",
                "flutter.chat_agent_command_preference.model.local-codex-acp.global" to "gpt-5-codex",
                "flutter.chat_agent_command_preference.model.local-codex-acp.conversation.7" to "gpt-5-codex",
            ),
            values,
        )
    }

    @Test
    fun `a conversation value wins over the global one, which is the fallback`() {
        values["flutter.chat_agent_command_preference.permission_mode.global"] = "read-only"
        values["flutter.chat_agent_command_preference.permission_mode.conversation.7"] = "auto-review"
        assertEquals("auto-review", preferences.read(Kind.PermissionMode, 7, "remote"))
        assertEquals("read-only", preferences.read(Kind.PermissionMode, 8, "remote"))
        assertEquals("read-only", preferences.read(Kind.PermissionMode, null, "remote"))
    }

    @Test
    fun `a model stored for another source is never read back`() {
        preferences.write(Kind.Model, "remote-model", conversationId = 7, modelSource = "remote")
        assertNull(preferences.read(Kind.Model, 7, "local-codex-acp"))
        assertEquals("remote-model", preferences.read(Kind.Model, 7, "remote"))
    }

    @Test
    fun `legacy codex keys are never revived, blank values fall back to global`() {
        // Dart's legacy fallback was unreachable (defaultValue: ''), so a
        // cleared value must stay cleared.
        values["flutter.chat_codex_command_preference.collaboration_mode.conversation.7"] = "plan"
        assertNull(preferences.read(Kind.CollaborationMode, 7, "remote"))
        values["flutter.chat_agent_command_preference.collaboration_mode.conversation.7"] = "  "
        values["flutter.chat_agent_command_preference.collaboration_mode.global"] = "default"
        assertEquals("default", preferences.read(Kind.CollaborationMode, 7, "remote"))
    }

    @Test
    fun `clear removes the global and the conversation value`() {
        preferences.write(Kind.CollaborationMode, "plan", conversationId = 7, modelSource = "remote")
        preferences.clear(Kind.CollaborationMode, conversationId = 7, modelSource = "remote")
        assertTrue(values.isEmpty())
    }

    @Test
    fun `turn settings normalize effort and default permission to full access`() {
        values["flutter.chat_agent_command_preference.reasoning_effort.global"] = "Extra-High"
        val settings = preferences.turnSettings(conversationId = null, modelSource = "local-agent")
        assertEquals("xhigh", settings.reasoningEffort)
        assertEquals(AgentPermissionMode.FullAccess, settings.permission)
        assertNull(settings.model)
        values["flutter.chat_agent_command_preference.permission_mode.global"] = "workspace_write"
        assertEquals(AgentPermissionMode.Default, preferences.turnSettings(null, "local-agent").permission)
    }

    @Test
    fun `effort spellings match the Dart normalizer`() {
        assertEquals("none", normalizeAgentReasoningEffort("off"))
        assertEquals("minimal", normalizeAgentReasoningEffort("MIN"))
        assertEquals("medium", normalizeAgentReasoningEffort("med"))
        assertEquals("xhigh", normalizeAgentReasoningEffort("x high"))
        assertEquals("max", normalizeAgentReasoningEffort(" max "))
        assertNull(normalizeAgentReasoningEffort(" "))
    }
}
