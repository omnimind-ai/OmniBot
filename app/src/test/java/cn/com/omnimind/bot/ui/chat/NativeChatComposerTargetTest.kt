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

    @Test
    fun `an untargeted entry resumes the last visible conversation unless the user chose new`() = kotlinx.coroutines.runBlocking {
        val last = """{"conversationId":42,"mode":"agent","isNewConversation":false,"agentId":"codex-acp"}"""
        val stored = mapOf(42L to "旧对话")
        assertEquals(ChatStartupTarget.Existing(42, "agent", "旧对话"), resolveChatStartupTarget("resume_last", last, { stored[it] }))
        assertEquals(ChatStartupTarget.Existing(42, "agent", "旧对话"), resolveChatStartupTarget(null, last, { stored[it] }))
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("new_conversation", last, { stored[it] }))
        // Deleted, OpenClaw, remote and new targets open a new conversation.
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("resume_last", last) { null })
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("resume_last", """{"conversationId":42,"mode":"openclaw"}""", { stored[it] }))
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("resume_last", """{"conversationId":42,"mode":"agent","agentRuntime":"remote"}""", { stored[it] }))
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("resume_last", """{"isNewConversation":true,"mode":"agent"}""", { stored[it] }))
        assertEquals(ChatStartupTarget.NewConversation, resolveChatStartupTarget("resume_last", "broken", { stored[it] }))
    }

    @Test
    fun `a compaction result maps to the marker status like the Dart page`() {
        assertEquals("completed", compactionStatus(mapOf("compacted" to true), failed = false))
        assertEquals("noop", compactionStatus(mapOf("compacted" to false, "reason" to "no_candidate"), failed = false))
        assertEquals("noop", compactionStatus(mapOf("reason" to " no_prompt_messages "), failed = false))
        assertEquals("failed", compactionStatus(mapOf("reason" to "model_error"), failed = false))
        assertEquals("failed", compactionStatus(null, failed = true))
    }

    @Test
    fun `a shared draft's files become composer attachments`() {
        val attachments = sharedDraftAttachments(mapOf(
            "attachments" to listOf(
                mapOf("id" to "", "name" to "", "path" to "/data/shared/photo.jpg", "size" to 12, "isImage" to true),
                mapOf("id" to "w1", "name" to "report.pdf", "path" to "/w/report.pdf", "promptPath" to "/workspace/report.pdf", "sendToModel" to false),
                mapOf("name" to "no path"),
            ),
        ))
        assertEquals(listOf("/data/shared/photo.jpg", "w1"), attachments.map { it.id })
        assertEquals("photo.jpg", attachments[0].name)
        assertEquals(12L, attachments[0].size)
        assertEquals(true, attachments[0].isImage)
        assertEquals(false, attachments[1].sendToModel)
        assertEquals("/workspace/report.pdf", attachments[1].promptPath)
        assertEquals(emptyList<Any>(), sharedDraftAttachments(emptyMap()))
    }

    /** 5e-9: a Sub Agent run continues on the normal runtime with Xiaowan, no permission menu, its mode kept. */
    @Test fun subAgentRunsContinueLikeTheXiaowanTaskFlow() {
        val target = NativeChatComposerTarget.resolve("subagent", null, null)!!
        assertEquals("normal", target.runtimeMode)
        assertEquals("subagent", target.conversationMode)
        assertEquals("xiaowan-acp", target.agentId)
        assertEquals(false, target.showsPermission)
        assertEquals("agent", NativeChatComposerTarget.resolve("subagent", null, liveRuntimeMode = "agent")!!.runtimeMode)
    }
}
