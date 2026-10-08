package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ports `_respondToPendingAgentUserInput` / `_singleComposerElicitationContent` (5e-6). */
class AgentUserInputAnswerTest {
    private fun card(id: String, status: String = "pending", kind: String = "user_input", extra: Map<String, Any?> = emptyMap()) =
        ChatMessage(
            id = id, type = 2, user = 2,
            content = linkedMapOf(
                "id" to id,
                "cardData" to linkedMapOf<String, Any?>(
                    "type" to "agent_request", "requestKind" to kind, "status" to status, "requestId" to "req-$id",
                ).apply { putAll(extra) },
            ),
        )

    @Test
    fun `only a pending user-input request takes the composer's text`() {
        assertEquals("req-a", pendingUserInputCard(listOf(card("b", status = "submitted"), card("a")))?.get("requestId"))
        assertNull(pendingUserInputCard(listOf(card("a", kind = "approval"))))
        assertNull(pendingUserInputCard(listOf(card("a", status = "cancelled"))))
    }

    @Test
    fun `a legacy question is answered under its question id`() {
        val args = userInputResponseArgs(
            mapOf("requestId" to 7, "questionId" to "q1", "sessionId" to " s ", "agentId" to "codex-acp", "conversationId" to 9),
            "是",
        )
        assertEquals(
            mapOf(
                "requestId" to 7, "agentId" to "codex-acp", "conversationId" to 9, "sessionId" to "s",
                "response" to mapOf("answers" to mapOf("q1" to mapOf("answers" to listOf("是")))),
            ),
            args,
        )
        assertEquals(mapOf("answer" to mapOf("answers" to listOf("x"))), (userInputResponseArgs(mapOf("requestId" to 1), "x")["response"] as Map<*, *>)["answers"])
    }

    @Test
    fun `an elicitation types its single field from the schema`() {
        fun content(schema: String, text: String) = elicitationContent(
            mapOf("structuredElicitation" to true, "rawParamsJson" to """{"requestedSchema":$schema}"""), text,
        )
        assertEquals(mapOf("count" to 3), content("""{"properties":{"count":{"type":"integer"}}}""", " 3 "))
        assertEquals(mapOf("ok" to true), content("""{"properties":{"ok":{"type":"boolean"}},"required":["ok"]}""", "TRUE"))
        assertEquals(mapOf("tags" to listOf("a", "b")), content("""{"properties":{"tags":{"type":"array"}}}""", "a, b,"))
        assertEquals(mapOf("name" to "x"), content("""{"properties":{"name":{},"other":{}},"required":["name"]}""", "x"))
        // Several optional fields: the answer key, like Dart.
        assertEquals(mapOf("answer" to "x"), content("""{"properties":{"a":{},"b":{}}}""", "x"))
        // A schema nested under `params`, itself a JSON string.
        val nested = elicitationContent(
            mapOf("rawParamsJson" to """{"params":"{\"schema\":{\"properties\":{\"n\":{\"type\":\"number\"}}}}"}"""), "1.5",
        )
        assertEquals(mapOf("n" to 1.5), nested)
        val args = userInputResponseArgs(mapOf("requestId" to 1, "structuredElicitation" to true), "hi")
        assertEquals(mapOf("action" to "accept", "content" to mapOf("answer" to "hi")), args["response"])
    }
}
