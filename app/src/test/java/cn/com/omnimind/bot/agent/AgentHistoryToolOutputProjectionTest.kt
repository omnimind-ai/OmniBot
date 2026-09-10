package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryToolOutputProjectionTest {
    private val gson = Gson()
    private fun entry(id: Long, payload: Map<String, Any?>) = AgentConversationEntry(
        id = id, conversationId = 15, conversationMode = "agent", entryId = "tool-$id",
        entryType = AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT,
        status = "success", summary = "read complete", payloadJson = gson.toJson(payload),
        createdAt = id, updatedAt = id,
    )

    @Test fun `repeated large legacy results are offloaded before replay construction`() {
        var saved = 0
        val body = "<html>大文件</html>".repeat(100_000)
        val encodedBody = gson.toJson(body)
        val projection = AgentHistoryToolOutputProjection(offload = {
            assertTrue(it.payloadJson.contains(encodedBody))
            saved++
            "/workspace/offloads/${it.id}.json"
        })
        // Process one database page at a time; do not retain the raw entries.
        val projected = (120L downTo 1L).map { id ->
            val original = entry(id, mapOf("toolName" to "plugin_any_read", "argsJson" to "{}",
                "rawResultJson" to body, "terminalOutput" to body, "resultPreviewJson" to body))
            val result = projection.project(original)
            assertTrue(original.payloadJson.contains(encodedBody))
            result
        }.asReversed()
        val replay = AgentConversationHistorySupport.buildPromptSeedFromEntries(projected).historyMessages
        assertEquals(120, saved)
        assertEquals(240, replay.size)
        assertTrue(replay.sumOf { it.content.toString().length } < 150_000)
        replay.chunked(2).forEach { pair ->
            assertEquals(pair[0].toolCalls!!.single().id, pair[1].toolCallId)
            assertTrue(pair[1].content.toString().contains("/workspace/offloads/"))
        }
    }

    @Test fun `canonical multi tool identity survives projection and checkpoint matching`() {
        val assistant = """{"role":"assistant","tool_calls":[{"id":"a","type":"function","function":{"name":"file_read","arguments":"{}"}},{"id":"b","type":"function","function":{"name":"browser_use","arguments":"{}"}}]}"""
        val projection = AgentHistoryToolOutputProjection(offload = { "/workspace/${it.id}.json" }, budgetTokens = 1)
        val entries = listOf("a", "b").mapIndexed { index, id -> projection.project(entry(index + 1L, mapOf(
            "toolName" to "file_read", "modelToolCallId" to id,
            "sessionId" to "session", "turnId" to "turn", "modelAssistantMessageJson" to assistant,
            "modelToolResultMessageJson" to """{"role":"tool","tool_call_id":"$id","content":"result"}""",
        ))) }
        val replay = AgentConversationHistorySupport.buildPromptSeedFromEntries(entries).historyMessages
        assertEquals(listOf("assistant", "tool", "tool"), replay.map { it.role })
        assertEquals(listOf("a", "b"), replay.drop(1).map { it.toolCallId })
        assertEquals(2L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(entries, replay, 0))
    }

    @Test fun `budget is shared and offload failures cannot omit history`() {
        val original = entry(1, mapOf("toolName" to "file_read", "rawResultJson" to "x".repeat(1000)))
        val projection = AgentHistoryToolOutputProjection(offload = { "/workspace/full.json" },
            budgetTokens = AgentContextBudget.textTokens(original.payloadJson))
        assertSame(original, projection.project(original))
        assertNotEquals(original.payloadJson, projection.project(original.copy(id = 2)).payloadJson)
        val failing = AgentHistoryToolOutputProjection(offload = { throw java.io.IOException("disk full") }, budgetTokens = 1)
        try { failing.project(original); fail("must propagate storage failure") }
        catch (expected: java.io.IOException) { assertEquals("disk full", expected.message) }
    }

    @Test fun `checkpoint awaits committed tool identity instead of dropping a summary during projection lag`() = kotlinx.coroutines.runBlocking {
        val original = entry(42, mapOf("toolName" to "file_read", "toolCallId" to "call-a",
            "sessionId" to "session", "turnId" to "turn"))
        val messages = listOf(
            cn.com.omnimind.baselib.llm.ChatCompletionMessage(role = "assistant", toolCalls = listOf(
                cn.com.omnimind.baselib.llm.AssistantToolCall(id = "call-a", function =
                    cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name = "file_read", arguments = "{}")))),
            cn.com.omnimind.baselib.llm.ChatCompletionMessage(role = "tool", toolCallId = "call-a"),
        )
        var writes = 0
        val journal = kotlinx.coroutines.flow.flow {
            emit(emptyList<AgentConversationEntry>())
            writes++
            emit(listOf(original.copy(status = "running")))
            writes++
            emit(listOf(original))
        }
        assertEquals(42L, AgentConversationHistoryRepository.awaitCompactionToolCutoff(journal, messages, 0))
        assertEquals(2, writes)
        assertNull(AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(original.copy(status = "running")), messages, 0))
    }
}
