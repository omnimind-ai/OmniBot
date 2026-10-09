package cn.com.omnimind.nativeui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which drawer rows open the native chat page (5e-5), matching NativeChatComposerTarget. */
class OpensNativelyTest {
    private fun row(mode: String, agentId: String? = null, parentId: Long? = null, task: String? = null) =
        ConversationSummary(1, "t", "", mode, 0, false, parentId = parentId, scheduledTaskId = task, agentId = agentId)

    @Test
    fun `agent and pure chat conversations open natively`() {
        assertTrue(opensNatively(row("agent", "codex-acp")))
        assertTrue(opensNatively(row("normal")))
        assertTrue(opensNatively(row("chat_only")))
        // A scheduled Sub Agent run (5e-9) has a parent and a task id and still opens natively.
        assertTrue(opensNatively(row("subagent")))
        assertTrue(opensNatively(row("subagent", parentId = 3, task = "task-1")))
    }

    @Test
    fun `openclaw, scheduled runs and remote codex keep their Flutter pages`() {
        assertFalse(opensNatively(row("openclaw")))
        assertFalse(opensNatively(row("subagent", "codex-remote")))
        assertFalse(opensNatively(row("agent", parentId = 3)))
        assertFalse(opensNatively(row("agent", task = "task-1")))
        assertFalse(opensNatively(row("agent", "codex-remote")))
    }
}
