package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.AgentPermissionMode
import cn.com.omnimind.bot.agent.projection.CHAT_RUNTIME_MODE_AGENT
import cn.com.omnimind.bot.agent.projection.CHAT_RUNTIME_MODE_NORMAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the native composer sends (5d-1b), mirroring the Flutter page's dispatch split. */
class NativeChatComposerTargetTest {
    @Test
    fun `an Agent conversation sends through its own Harness on the Agent runtime`() {
        val target = NativeChatComposerTarget.resolve("agent", "codex-acp", liveRuntimeMode = null)!!
        assertEquals(CHAT_RUNTIME_MODE_AGENT, target.runtimeMode)
        assertEquals("codex-acp", target.agentId)
        assertEquals("agent", target.conversationMode)
        assertTrue(target.showsPermission)
        // Legacy `normal` rows are Xiaowan Agent conversations.
        assertEquals("xiaowan-acp", NativeChatComposerTarget.resolve("normal", null, null)!!.agentId)
    }

    @Test
    fun `pure chat sends with no Harness and no permission menu`() {
        val target = NativeChatComposerTarget.resolve("chat_only", "xiaowan-acp", null)!!
        assertEquals(CHAT_RUNTIME_MODE_NORMAL, target.runtimeMode)
        assertNull(target.agentId)
        assertFalse(target.showsPermission)
    }

    @Test
    fun `a live runtime keeps its mode, so a turn never lands on a second runtime`() {
        assertEquals(CHAT_RUNTIME_MODE_NORMAL, NativeChatComposerTarget.resolve("agent", null, CHAT_RUNTIME_MODE_NORMAL)!!.runtimeMode)
    }

    @Test
    fun `surfaces with their own Flutter flows are not sent from here`() {
        assertNull(NativeChatComposerTarget.resolve("openclaw", null, null))
        assertNull(NativeChatComposerTarget.resolve("subagent", null, null))
        assertNull(NativeChatComposerTarget.resolve("agent", "codex-remote", null))
    }

    @Test
    fun `local Harnesses offer no auto review`() {
        assertEquals(AgentPermissionMode.Default, AgentPermissionMode.AutoReview.forLocalHarness())
        assertEquals(AgentPermissionMode.ReadOnly, AgentPermissionMode.ReadOnly.forLocalHarness())
        for (mode in AgentPermissionMode.entries) assertEquals(mode, mode.toComposer().toAgent())
    }

    @Test
    fun `pure chat reads the conversation override and effort like the Dart services`() {
        val overrides = """{"7":{"conversationId":7,"providerProfileId":"p1","modelId":" deepseek-chat "},"8":{"modelId":"x"}}"""
        assertEquals("deepseek-chat", pureChatModelOverride(overrides, 7))
        assertNull(pureChatModelOverride(overrides, 8))
        assertNull(pureChatModelOverride("not json", 7))
        assertNull(pureChatModelOverride(null, 7))
        val efforts = """{"7":"no","8":"MED","9":"ultra"}"""
        assertEquals("none", pureChatReasoningEffort(efforts, 7))
        assertEquals("medium", pureChatReasoningEffort(efforts, 8))
        assertNull(pureChatReasoningEffort(efforts, 9))
    }

    @Test
    fun `a new conversation is titled by its first user text like the Dart page`() {
        assertEquals("你好", newConversationTitle(" 你好 "))
        assertEquals("12345678901234567890...", newConversationTitle("1234567890123456789012"))
        assertEquals("新对话", newConversationTitle("  "))
    }

    @Test
    fun `a conversation keeps its Harness, a new page is retargeted, a running turn refuses`() {
        assertEquals(HarnessSwitchPlan.OpenNewConversation, planHarnessSwitch("xiaowan-acp", "codex-acp", hasConversation = true, anyTurnRunning = false))
        assertEquals(HarnessSwitchPlan.ReplaceTarget, planHarnessSwitch("xiaowan-acp", "codex-acp", hasConversation = false, anyTurnRunning = false))
        assertEquals(HarnessSwitchPlan.Busy, planHarnessSwitch("xiaowan-acp", "codex-acp", hasConversation = false, anyTurnRunning = true))
        assertEquals(HarnessSwitchPlan.Ignore, planHarnessSwitch("codex-acp", "codex-acp", hasConversation = true, anyTurnRunning = true))
        assertEquals(HarnessSwitchPlan.Ignore, planHarnessSwitch("codex-acp", " ", hasConversation = false, anyTurnRunning = false))
    }
}
