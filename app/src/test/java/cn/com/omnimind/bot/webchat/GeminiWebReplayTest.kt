package cn.com.omnimind.bot.webchat

import org.junit.Assert.*
import org.junit.Test

class GeminiWebReplayTest {
    @Test fun nativeProtocolReplaySurvivesOfficialAcpMessageAndToolUpdates() {
        val replay = mapOf("modelAssistantMessageJson" to "canonical-json", "modelAssistantMessageId" to "message")
        fun event(update: Map<String, Any?>) = mapOf<String, Any?>(
            "method" to "session/update", "turnId" to "turn",
            "params" to mapOf("sessionId" to "session", "update" to (update + ("_meta" to mapOf("cn.com.omnimind.agent" to mapOf("modelReplay" to replay))))),
        )
        val answer = parseWebAgentEvent(event(mapOf("sessionUpdate" to "agent_message_chunk", "messageId" to "answer", "content" to mapOf("type" to "text", "text" to ""))))
        assertEquals(replay, answer.modelReplay)
        assertEquals("turn", answer.parentTaskId)
        assertNull(answer.terminalKind)
        val tool = parseWebAgentEvent(event(mapOf("sessionUpdate" to "tool_call_update", "toolCallId" to "call"))).tool!!
        assertEquals("canonical-json", tool.raw["modelAssistantMessageJson"])
        assertEquals("message", tool.raw["modelAssistantMessageId"])
    }
}
