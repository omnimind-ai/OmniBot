@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.ToolCallId
import com.agentclientprotocol.model.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper is the single ACP notification serializer shared by local ACP
 * agents, so its official envelope is a contract worth pinning down.
 */
class AcpSessionUpdateMapperTest {

    @Test
    fun agentMessageChunkKeepsItsMessageIdAsTheItemId() {
        val event = SessionUpdate.AgentMessageChunk(
            content = ContentBlock.Text("hello"),
            messageId = MessageId("msg_a")
        ).toAcpSessionNotification("thread-1")

        assertEquals("thread-1", event?.sessionId)
        assertEquals("agent_message_chunk", event?.update?.get("sessionUpdate"))
        assertEquals("msg_a", event?.update?.get("messageId"))
        assertEquals(mapOf("type" to "text", "text" to "hello"), event?.update?.get("content"))
    }

    @Test
    fun agentMessageChunkWithoutMessageIdKeepsTheOfficialOptionalFieldAbsent() {
        val event = SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("hello"))
            .toAcpSessionNotification("thread-1")

        assertFalse(event?.update?.containsKey("messageId") == true)
    }

    @Test
    fun agentThoughtChunkUsesTheReasoningDeltaContract() {
        val event = SessionUpdate.AgentThoughtChunk(
            content = ContentBlock.Text("先检查消息顺序"),
            messageId = MessageId("msg_thinking")
        ).toAcpSessionNotification("thread-1")

        assertEquals("agent_thought_chunk", event?.update?.get("sessionUpdate"))
        assertEquals("msg_thinking", event?.update?.get("messageId"))
        assertEquals(
            mapOf("type" to "text", "text" to "先检查消息顺序"),
            event?.update?.get("content")
        )
    }

    @Test
    fun toolCallUpdateOnlyCompletesOnATerminalStatus() {
        fun statusFor(status: ToolCallStatus?): String? = SessionUpdate.ToolCallUpdate(
            toolCallId = ToolCallId("call-1"),
            status = status
        ).toAcpSessionNotification("thread-1")?.update?.get("status") as String?

        assertEquals("completed", statusFor(ToolCallStatus.COMPLETED))
        assertEquals("failed", statusFor(ToolCallStatus.FAILED))
        assertEquals("in_progress", statusFor(ToolCallStatus.IN_PROGRESS))
        assertEquals("pending", statusFor(ToolCallStatus.PENDING))
        assertNull(statusFor(null))
    }

    @Test
    fun userMessageChunkProducesNothing() {
        // The client authored the user's message; replaying it back adds nothing
        // to the timeline.
        assertNull(
            SessionUpdate.UserMessageChunk(content = ContentBlock.Text("hi"))
                .toAcpSessionNotification("thread-1")
        )
    }

    @Test
    fun sessionInfoUpdateWithoutATitleProducesNothing() {
        assertNull(SessionUpdate.SessionInfoUpdate(title = null).toAcpSessionNotification("thread-1"))
        assertNull(SessionUpdate.SessionInfoUpdate(title = "  ").toAcpSessionNotification("thread-1"))

        val renamed = SessionUpdate.SessionInfoUpdate(title = "Renamed")
            .toAcpSessionNotification("thread-1")
        assertEquals("session_info_update", renamed?.update?.get("sessionUpdate"))
        assertEquals("Renamed", renamed?.update?.get("title"))
    }

    @Test
    fun onlyTimelineUpdatesAreTurnScoped() {
        // A turn-scoped update with no resolvable turn is dropped rather than
        // rendered as its own pseudo turn; session-scoped ones still go through
        // between turns. Getting this wrong is what produced one agent avatar
        // and one "processing" row per streamed item.
        assertTrue(
            SessionUpdate.AgentMessageChunk(content = ContentBlock.Text("x")).isTurnScoped()
        )
        assertTrue(
            SessionUpdate.AgentThoughtChunk(content = ContentBlock.Text("x")).isTurnScoped()
        )
        assertTrue(
            SessionUpdate.ToolCall(toolCallId = ToolCallId("c"), title = "t").isTurnScoped()
        )
        assertTrue(
            SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("c")).isTurnScoped()
        )

        assertFalse(SessionUpdate.SessionInfoUpdate(title = "t").isTurnScoped())
        assertFalse(
            SessionUpdate.AvailableCommandsUpdate(availableCommands = emptyList())
                .isTurnScoped()
        )
    }
}
