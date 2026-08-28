package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesCallIdCodecTest {
    @Test
    fun `plan preserves short ids and deterministically bounds long ids`() {
        val firstLongId = "session/turn/" + "a".repeat(90)
        val secondLongId = "session/turn/" + "b".repeat(90)
        val messages = listOf(
            ChatCompletionMessage(
                role = "assistant",
                toolCalls = listOf(
                    AssistantToolCall(
                        id = firstLongId,
                        function = AssistantToolCallFunction("shell", "{}")
                    ),
                    AssistantToolCall(
                        id = secondLongId,
                        function = AssistantToolCallFunction("shell", "{}")
                    )
                )
            ),
            ChatCompletionMessage(
                role = "tool",
                toolCallId = firstLongId,
                content = JsonPrimitive("done")
            )
        )

        val firstPlan = OpenAiResponsesCallIdCodec.planFor(messages)
        val secondPlan = OpenAiResponsesCallIdCodec.planFor(messages)
        val firstWireId = firstPlan.encode(firstLongId)
        val secondWireId = firstPlan.encode(secondLongId)

        assertEquals("call_short", firstPlan.encode("call_short"))
        assertEquals(firstWireId, secondPlan.encode(firstLongId))
        assertTrue(firstWireId.length <= 64)
        assertTrue(secondWireId.length <= 64)
        assertNotEquals(firstWireId, secondWireId)
        assertEquals(firstWireId, firstPlan.encode(messages[1].toolCallId!!))
    }
}
