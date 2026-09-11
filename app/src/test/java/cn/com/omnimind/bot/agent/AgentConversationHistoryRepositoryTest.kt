package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Test

class AgentConversationHistoryRepositoryTest {
    @Test
    fun emptyPresentationPlaceholdersDoNotEraseLaterCanonicalToolMessages() {
        val keys = listOf("toolCallId", "sessionId", "turnId", "modelToolCallId",
            "modelAssistantMessageJson", "modelToolResultMessageJson")
        for (placeholder in listOf("", "  ")) {
            val existing = keys.associateWith { placeholder }
            val canonical = keys.associateWith { "canonical-$it" }
            assertEquals(canonical, AgentConversationHistoryRepository.preserveFullToolPayload(existing, canonical))
            assertEquals(canonical, AgentConversationHistoryRepository.preserveFullToolPayload(canonical, existing))
        }
    }

    @Test
    fun displaySnapshotCannotReplaceFullToolHistoryButLiveResultCanUpdateIt() {
        val body = "x".repeat(600_000)
        val original = mapOf<String, Any?>("toolCallId" to "call", "rawResultJson" to body, "status" to "success")
        val display = mapOf<String, Any?>("payloadCompacted" to true, "rawResultJson" to "preview")
        assertEquals(original, AgentConversationHistoryRepository.preserveFullToolPayload(original, display))
        val live = mapOf<String, Any?>("rawResultJson" to "new result", "status" to "success")
        val updated = AgentConversationHistoryRepository.preserveFullToolPayload(original, live)
        assertEquals("new result", updated["rawResultJson"])
        assertEquals("call", updated["toolCallId"])
    }

    @Test
    fun sharedAcpCardIdentitySurvivesPersistenceAndRestoredReplay() {
        val payload = AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(mapOf(
            "id" to "audit-card", "type" to 2, "streamMeta" to mapOf("sessionId" to "session-1", "turnId" to "turn-1"),
            "content" to mapOf("cardData" to mapOf("type" to "agent_tool_summary",
                "cardId" to "audit-card", "toolName" to "file_read", "toolCallId" to "acp-call",
                "status" to "success", "resultPreviewJson" to "{}"))))!!
        assertEquals("acp-call", payload["toolCallId"])
        assertEquals("session-1", payload["sessionId"])
        assertEquals("turn-1", payload["turnId"])
        val row = entry("audit-card", "agent", "tool_event", 100, 7).copy(payloadJson = com.google.gson.Gson().toJson(payload))
        val displayed = AgentConversationHistorySupport.buildDisplaySafeToolCardData(row, payload)
        val resaved = AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(mapOf(
            "type" to 2, "content" to mapOf("cardData" to displayed)))!!
        assertEquals("acp-call", resaved["toolCallId"])
        assertEquals("session-1", resaved["sessionId"])
        assertEquals("turn-1", resaved["turnId"])
        val replay = AgentConversationHistorySupport.buildPromptSeedFromEntries(listOf(row), null, null).historyMessages
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(row), replay, 0))
        val call = AssistantToolCall(id = "acp-call", type = "function", function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))
        val live = listOf(ChatCompletionMessage(role = "assistant", toolCalls = listOf(call)),
            ChatCompletionMessage(role = "tool", toolCallId = "acp-call", content = JsonPrimitive("file reference")))
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(row), live, 0))
    }

    @Test
    fun offloadingResultMustNotLoseItsDurableCheckpointIdentity() = kotlinx.coroutines.runBlocking {
        val codec = Json { encodeDefaults = true }
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(
            AssistantToolCall(id = "audit-call", type = "function",
                function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))))
        val result = ChatCompletionMessage(role = "tool", toolCallId = "audit-call",
            content = JsonPrimitive("x".repeat(600_000)))
        val rows = listOf(entry("audit-tool", "agent", "tool_event", 100, 7).copy(payloadJson = buildJsonObject {
            put("taskId", "audit-turn")
            put("modelToolCallId", result.toolCallId)
            put("modelAssistantMessageJson", codec.encodeToString(assistant))
            put("modelToolResultMessageJson", codec.encodeToString(result))
        }.toString()))
        val original = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("read")), assistant, result)
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, original, 0))
        val compactor = AgentConversationContextCompactor(
            org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
            offloadToolOutput = { "/workspace/offloads/audit.txt" })
        val bounded = compactor.compactIfNeeded(null, "agent", null, original, null, 128000, null)
        assertFalse(bounded == original)
        // A later compaction sees this bounded context, while durable history
        // correctly retains the original result. Identity must survive that change.
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, bounded, 0))
    }

    @Test
    fun compactionCheckpointRequiresCompleteUniqueCanonicalGroupAndRestoresOnlySuffix() {
        val codec = Json { encodeDefaults = true }
        fun call(id: String) = AssistantToolCall(id = id, type = "function",
            function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call("a"), call("b")))
        val results = listOf("a", "b").map { ChatCompletionMessage(role = "tool", toolCallId = it, content = JsonPrimitive("result-$it")) }
        val keptAssistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call("c")))
        val keptResult = ChatCompletionMessage(role = "tool", toolCallId = "c", content = JsonPrimitive("recent"))
        fun row(id: Long, turn: String, assistantMessage: ChatCompletionMessage, result: ChatCompletionMessage): AgentConversationEntry =
            entry("$turn-${result.toolCallId}", "agent", "tool_event", id * 100, id).copy(
                payloadJson = buildJsonObject {
                    put("taskId", turn)
                    put("modelToolCallId", result.toolCallId)
                    put("modelAssistantMessageJson", codec.encodeToString(assistantMessage))
                    put("modelToolResultMessageJson", codec.encodeToString(result))
                }.toString())
        val prefix = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect")), assistant) + results
        val rows = listOf(row(2, "turn-1", assistant, results[0]), row(3, "turn-1", assistant, results[1]),
            row(4, "turn-1", keptAssistant, keptResult))
        assertNull(AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows.take(1), prefix, 0))
        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, prefix, 0)
        assertEquals(3L, cutoff)
        val restored = AgentConversationHistorySupport.buildPromptSeedFromEntries(rows, "Task inspect; a/b completed", cutoff).historyMessages
        assertEquals(listOf("c"), restored.filter { it.role == "tool" }.map { it.toolCallId })
        val ambiguous = rows + listOf(row(5, "turn-2", assistant, results[0]), row(6, "turn-2", assistant, results[1]))
        assertNull(AgentConversationHistoryRepository.resolveCompactionToolCutoff(ambiguous, prefix, 0))
        assertEquals(3, rows.size) // Checkpoint never mutates/deletes original journal entries.
    }

    @Test
    fun `fork snapshot prefers canonical rows and keeps chronological visible cards`() {
        val snapshot = AgentConversationHistoryRepository.entriesForFork(
            listOf(
                entry("normal-user", "normal", "user_message", 100, 1),
                entry("assistant", "agent", "assistant_message", 200, 2),
                // This is the stale pre-ACP copy of the canonical assistant.
                entry("assistant", "normal", "assistant_message", 200, 3),
                entry("hidden", "agent", "stream_event", 300, 4),
                entry("tool", "agent", "tool_event", 400, 5),
            )
        )

        assertEquals(listOf("normal-user", "assistant", "tool"), snapshot.map { it.entryId })
        assertEquals("agent", snapshot[1].conversationMode)
        assertEquals(3, snapshot.size)
    }

    @Test
    fun `paged merged history advances beyond duplicate compatibility rows`() {
        val canonical = (1..100).map { index ->
            entry(
                entryId = "shared-$index",
                mode = "agent",
                type = "assistant_message",
                createdAt = 1_000L - index,
                id = index.toLong(),
            )
        }
        val legacyCopies = canonical.map { entry ->
            entry.copy(conversationMode = "normal", id = entry.id + 1_000L)
        }
        val legacyOnly = entry(
            entryId = "legacy-only",
            mode = "normal",
            type = "user_message",
            createdAt = 1L,
            id = 2_000L,
        )

        val (page, hasMore) = AgentConversationHistoryRepository.pageConversationEntries(
            entries = canonical + legacyCopies + legacyOnly,
            limit = 50,
            offset = 100,
        )

        assertEquals(listOf("legacy-only"), page.map { it.entryId })
        assertFalse(hasMore)
    }

    private fun entry(
        entryId: String,
        mode: String,
        type: String,
        createdAt: Long,
        id: Long,
    ) = AgentConversationEntry(
        id = id,
        conversationId = 1,
        conversationMode = mode,
        entryId = entryId,
        entryType = type,
        status = AgentConversationHistoryRepository.STATUS_SUCCESS,
        summary = entryId,
        payloadJson = "{}",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
