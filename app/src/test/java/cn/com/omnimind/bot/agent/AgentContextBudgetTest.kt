package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentContextBudgetTest {
    private fun msg(role: String, text: String) = ChatCompletionMessage(role = role, content = JsonPrimitive(text))

    @Test fun usageIncludesNewToolResultsAndMissingUsageStillEstimates() {
        val messages = listOf(msg("user", "task"), msg("assistant", "working"), msg("tool", "x".repeat(12000)))
        assertEquals(123004, AgentContextBudget.estimate(messages, 120000, 2))
        assertTrue(AgentContextBudget.estimate(messages) >= 3000)
        assertEquals(Int.MAX_VALUE, AgentContextBudget.estimate(messages, Int.MAX_VALUE, 2))
    }

    @Test fun unicodeAndImagesAreNotCountedAsBase64Text() {
        assertEquals(150000L, AgentContextBudget.textTokens("中".repeat(100000)))
        val image = ChatCompletionMessage(role = "tool", content = buildJsonArray {
            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject {
                put("url", "data:image/png;base64," + "a".repeat(1000000))
            }) })
        })
        assertTrue(AgentContextBudget.messageTokens(image) < 5000)
    }

    @Test fun splitTurnPreservesRecentPairsAndOriginalUser() {
        val messages = mutableListOf(msg("system", "system"), msg("user", "original task"))
        for (i in 1..9) {
            val call = AssistantToolCall(id = "call-$i", type = "function",
                function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))
            messages += ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
            messages += ChatCompletionMessage(role = "tool", toolCallId = call.id, content = JsonPrimitive("x".repeat(5000)))
        }
        val cut = AgentContextBudget.cutPoint(messages, 4000)!!
        assertTrue(cut > 2)
        assertEquals("assistant", messages[cut].role)
        val rebuilt = AgentContextBudget.rebuild(messages, cut, "checkpoint")
        assertEquals("original task", rebuilt.single { it.role == "user" }.contentText())
        assertEquals(rebuilt.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet(),
            rebuilt.filter { it.role == "tool" }.map { it.toolCallId }.toSet())
        assertTrue(AgentContextBudget.estimate(rebuilt) < AgentContextBudget.estimate(messages))
    }

    @Test fun pendingParallelCallGroupCannotBeDiscarded() {
        val calls = listOf("a", "b").map { AssistantToolCall(id = it, type = "function",
            function = AssistantToolCallFunction(name = "read", arguments = "{}")) }
        val messages = listOf(msg("user", "task"), ChatCompletionMessage(role = "assistant", toolCalls = calls),
            ChatCompletionMessage(role = "tool", toolCallId = "a", content = JsonPrimitive("x".repeat(20000))))
        val cut = AgentContextBudget.cutPoint(messages, 20)
        assertTrue(cut == null || cut == 1)
    }
}
