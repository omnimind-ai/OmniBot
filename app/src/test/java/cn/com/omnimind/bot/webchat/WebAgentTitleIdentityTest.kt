package cn.com.omnimind.bot.webchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebAgentTitleIdentityTest {
    @Test fun `ACP answer identity remains scoped to its turn for title input`() {
        fun update(turn: String, message: String?) = parseWebAgentEvent(mapOf(
            "method" to "session/update", "turnId" to turn, "threadId" to "session",
            "params" to mapOf("update" to mapOf(
                "sessionUpdate" to "agent_message_chunk", "messageId" to message,
                "content" to mapOf("type" to "text", "text" to "Visible answer"),
            )),
        ))
        val first = update("turn-1", "message-1")
        assertEquals("turn-1-message-1-agent-message", first.assistantEntryId)
        assertEquals("Visible answer", first.assistantDelta)
        assertNotEquals(first.assistantEntryId, update("turn-2", "message-1").assistantEntryId)
        assertEquals("turn-1-agent-message", update("turn-1", null).assistantEntryId)
    }
}
