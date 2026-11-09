package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.GeminiInteractionsRequest
import cn.com.omnimind.assists.controller.http.GeminiInteractionsStream
import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.llm.*
import com.google.gson.Gson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiHistoryReplayTest {
    private val source = "provider@https://example.test/v1"
    private val gson = Gson()
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(raw: String) = json.parseToJsonElement(raw).jsonObject
    private fun assistant(steps: String, text: String? = null, calls: List<AssistantToolCall>? = null) = ChatCompletionMessage(
        "assistant", text?.let(::JsonPrimitive), toolCalls = calls,
        protocolState = ChatCompletionProtocolState(gemini = GeminiMessageProtocolState(sourceProvider = source, steps = json.parseToJsonElement(steps).jsonArray)),
    )
    private fun row(id: Long, type: String, payload: Map<String, Any?>) = AgentConversationEntry(
        id = id, conversationId = 1, conversationMode = "agent", entryId = "entry-$id", entryType = type,
        status = "success", summary = "", payloadJson = gson.toJson(payload), createdAt = id, updatedAt = id,
    )

    @Test fun `wire decoder integrates with the common accumulator and signed replay`() {
        val interaction = obj("""{"status":"completed","steps":[{"type":"thought","signature":"opaque"},{"type":"model_output","content":[{"type":"text","text":"answer"}]}],"usage":{"total_input_tokens":1,"total_output_tokens":2,"total_thought_tokens":3,"total_tokens":6}}""")
        val accumulator = AgentLlmStreamAccumulator(json)
        GeminiInteractionsStream(source).complete(interaction).forEach(accumulator::consume)
        val turn = accumulator.buildTurn()
        assertEquals("answer", turn.message.contentText())
        assertEquals(interaction["steps"], turn.message.protocolState!!.gemini!!.steps)
        assertEquals(5, turn.usage!!.completionTokens)
    }

    @Test fun `snapshot echoes preserve ordinary final reply signatures`() {
        val message = assistant("""[{"type":"thought","signature":"opaque"},{"type":"model_output","content":[{"type":"text","text":"answer"}]}]""", "answer")
        val payload = mapOf("content" to mapOf("text" to "answer", "modelReplay" to mapOf("modelAssistantMessageJson" to json.encodeToString(message))))
        val merged = AgentConversationHistoryRepository.preserveModelReplay(payload, mapOf("content" to mapOf("text" to "answer")))
        val restored = AgentConversationHistorySupport.buildPromptSeedFromEntries(listOf(row(1, "assistant_message", merged))).historyMessages.single()
        assertEquals(message.protocolState, restored.protocolState)
        assertEquals(message, restored)
        val emptyEcho = mapOf("content" to mapOf("text" to "answer", "modelReplay" to emptyMap<String, Any?>()))
        assertEquals(payload, AgentConversationHistoryRepository.preserveModelReplay(payload, emptyEcho))
    }

    @Test fun `one model message with multiple tools restores once before final message`() {
        val callA = AssistantToolCall("a", function = AssistantToolCallFunction("read", "{}"))
        val callB = AssistantToolCall("b", function = AssistantToolCallFunction("read", "{}"))
        val model = assistant("""[{"type":"thought","signature":"signed"},{"type":"function_call","id":"a","name":"read","arguments":{}},{"type":"function_call","id":"b","name":"read","arguments":{}}]""", calls = listOf(callA, callB))
        val answer = assistant("""[{"type":"thought","signature":"final-signature"},{"type":"model_output","content":[{"type":"text","text":"done"}]}]""", "done")
        fun tool(id: Long, call: String) = row(id, "tool_event", mapOf(
            "taskId" to "turn", "modelAssistantMessageId" to "model-message", "modelToolCallId" to call,
            "modelAssistantMessageJson" to json.encodeToString(model),
            "modelToolResultMessageJson" to json.encodeToString(ChatCompletionMessage("tool", JsonPrimitive("result-$call"), toolCallId = call)),
        ))
        // The display answer is inserted before the tools but carries final model content.
        val rows = listOf(row(1, "assistant_message", mapOf(
            "streamMeta" to mapOf("parentTaskId" to "turn"),
            "content" to mapOf("text" to "done", "modelReplay" to mapOf("modelAssistantMessageJson" to json.encodeToString(answer))),
        )), tool(2, "a"), tool(3, "b"))
        val messages = AgentConversationHistorySupport.buildPromptSeedFromEntries(rows).historyMessages
        assertEquals(listOf("assistant", "tool", "tool", "assistant"), messages.map { it.role })
        assertEquals(model, messages.first())
        assertEquals(answer, messages.last())
        val input = GeminiInteractionsRequest.history(messages, source)
        assertEquals(2, input.count { it.jsonObject["type"] == JsonPrimitive("function_call") })
        assertEquals(2, input.count { it.jsonObject["type"] == JsonPrimitive("function_result") })
        assertTrue(input.toString().contains("final-signature"))
    }

    @Test fun `compaction keeps the current signed tool loop as an atomic suffix`() {
        val signed = assistant("""[{"type":"thought","signature":"keep"},{"type":"function_call","id":"a","name":"read","arguments":{}}]""")
        val current = listOf(ChatCompletionMessage("user", JsonPrimitive("current")), signed, ChatCompletionMessage("tool", JsonPrimitive("ok"), toolCallId = "a"))
        val history = listOf(ChatCompletionMessage("user", JsonPrimitive("old")), ChatCompletionMessage("assistant", JsonPrimitive("old answer"))) + current
        val compacted = AgentConversationHistorySupport.rebuildMessagesWithCompactedSummary(history, "summary")
        assertEquals(current, compacted.takeLast(3))
    }
}
