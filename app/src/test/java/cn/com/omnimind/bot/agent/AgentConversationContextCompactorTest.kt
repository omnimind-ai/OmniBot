package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AgentConversationContextCompactorTest {
    @Test
    fun lowUserTriggerDoesNotBecomeSummaryModelCapacity() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var summaries = 0
        val compactor = object : AgentConversationContextCompactor(repo, modelOverride = AgentModelOverride(
            providerProfileId = "test", apiBase = "https://example.invalid/v1", apiKey = "fixture",
            modelId = "test", contextLimit = 128000)) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                summaries++
                assertTrue(messages.toString().contains("past-answer"))
                return "Earlier work completed; preserve the current request."
            }
        }
        val history = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("old request")),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("past-answer ".repeat(6000))),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("recent task")),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("r".repeat(25000))),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("continue exactly")))
        val result = compactor.compactIfNeeded(null, "agent", null, history, null, 12000, null)
        assertEquals(1, summaries)
        assertTrue(AgentContextBudget.estimate(result) < 9952)
        assertEquals(history.last(), result.last())
        assertTrue((history[1].content as JsonPrimitive).content.length > 60000)
    }

    @Test
    fun irreducibleSchemaAndOversizedSummaryInputFailBeforeSummaryNetworkRequest() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var requests = 0
        val compactor = object : AgentConversationContextCompactor(repo, modelOverride = AgentModelOverride(
            providerProfileId = "test", apiBase = "https://example.invalid/v1", apiKey = "fixture",
            modelId = "test", contextLimit = 12000)) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                requests++
                error("No oversized request should reach the summarizer")
            }
        }
        val tiny = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello")))
        val schemas = runCatching { compactor.compactIfNeeded(null, "agent", null, tiny,
            contextTokens = 1, requestOverheadTokens = 12000) }.exceptionOrNull()
        assertTrue(schemas?.message.orEmpty().contains("工具定义"))
        val history = listOf(tiny.single(), ChatCompletionMessage(role = "assistant",
            content = JsonPrimitive("中".repeat(20000))), tiny.single(),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("r".repeat(25000))),
            tiny.single().copy(content = JsonPrimitive("continue")))
        val oversized = runCatching { compactor.compactIfNeeded(null, "agent", null, history) }.exceptionOrNull()
        assertTrue(oversized?.message.orEmpty().contains("摘要请求预算"))
        assertEquals(0, requests)
        assertTrue(org.mockito.Mockito.mockingDetails(repo).invocations.none { it.method.name == "updateContextSummary" })
    }

    @Test
    fun aggregateToolBudgetDoesNotDependOnToolNamesOrFileExtensions() = kotlinx.coroutines.runBlocking {
        for (name in listOf("file_read", "context_apps_query", "browser_use", "vlm_task", "plugin_custom_read")) {
            val original = "<html>完整结果</html>".repeat(20000)
            val offloads = mutableListOf<String>()
            val compactor = AgentConversationContextCompactor(
                org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
                offloadToolOutput = { offloads += it; "/workspace/offloads/$name.txt" })
            val call = cn.com.omnimind.baselib.llm.AssistantToolCall(id = "t1", type = "function",
                function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name = name, arguments = "{}"))
            val history = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect")),
                ChatCompletionMessage(role = "assistant", toolCalls = listOf(call)),
                ChatCompletionMessage(role = "tool", toolCallId = call.id, content = JsonPrimitive(original)))
            val result = compactor.compactIfNeeded(null, "agent", null, history)
            assertEquals(listOf(original), offloads)
            assertEquals(history[1], result[1])
            assertEquals("t1", result.last().toolCallId)
            assertTrue(result.last().content.toString().contains("/workspace/offloads/$name.txt"))
            assertTrue(AgentContextBudget.estimate(result) < 112000)
        }
    }

    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun partialOrTruncatedSummaryCannotBecomeCheckpoint() {
        fun stream(finish: String?): AgentLlmStreamAccumulator {
            val accumulator = AgentLlmStreamAccumulator(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            accumulator.consume("""{"choices":[{"delta":{"content":"unfinished checkpoint"}}]}""")
            if (finish != null) accumulator.consume("""{"choices":[{"delta":{},"finish_reason":"$finish"}]}""")
            return accumulator
        }
        assertTrue(runCatching { AgentConversationContextCompactor.completedSummary(stream(null)) }.isFailure)
        assertTrue(runCatching { AgentConversationContextCompactor.completedSummary(stream("length")) }.isFailure)
        assertEquals("unfinished checkpoint", AgentConversationContextCompactor.completedSummary(stream("stop")))
    }

    @Test
    fun repeatedCompactionKeepsOneCheckpointExactTaskAndPairedRecentTools() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var summaries = 0
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                summaries++
                if (summaries > 1) assertTrue(messages.any { it["content"].toString().contains("checkpoint-${summaries - 1}") })
                return "checkpoint-$summaries: preserve blue export and do not repeat completed writes"
            }
        }
        val task = "Export blue only; never repeat completed writes."
        var messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive(task)))
        repeat(30) { index ->
            val id = "read-$index"
            val added = listOf(ChatCompletionMessage(role = "assistant", toolCalls = listOf(
                cn.com.omnimind.baselib.llm.AssistantToolCall(id = id, type = "function",
                    function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name = "file_read", arguments = "{}")))),
                ChatCompletionMessage(role = "tool", toolCallId = id, content = JsonPrimitive("x".repeat(5000))))
            // The actual loop checks after EACH returned tool batch, not after nine requests.
            messages = compactor.compactIfNeeded(null, "agent", null, messages + added, null, 12000, null)
            if (summaries > 0) assertEquals(1, messages.count(AgentConversationHistorySupport::isContextSummaryMessage))
            assertEquals(task, (messages.single { it.role == "user" }.content as JsonPrimitive).content)
            assertEquals(messages.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet(),
                messages.filter { it.role == "tool" }.map { it.toolCallId }.toSet())
            assertTrue(AgentContextBudget.estimate(messages) <= 9952)
        }
        assertTrue(summaries >= 3)
    }

    @Test
    fun restoredLongHistoryDoesNotAccumulateOffloadedExcerpts() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var offloads = 0
        val body = "历史文件内容".repeat(11000)
        val compactor = object : AgentConversationContextCompactor(repo, offloadToolOutput = {
            "/workspace/offloads/${++offloads}.txt"
        }) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String =
                "Old file results are saved; continue the current task."
        }
        val messages = mutableListOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect files")))
        repeat(120) { index ->
            val call = cn.com.omnimind.baselib.llm.AssistantToolCall(id = "restored-$index", type = "function",
                function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name = "file_read", arguments = "{}"))
            messages += ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
            messages += ChatCompletionMessage(role = "tool", toolCallId = call.id, content = JsonPrimitive(body))
        }
        messages += ChatCompletionMessage(role = "user", content = JsonPrimitive("continue"))
        val bounded = compactor.compactIfNeeded(null, "agent", null, messages, null, 128000, null)
        assertTrue(offloads > 0)
        assertTrue(AgentContextBudget.estimate(bounded) <= 112000)
        assertEquals("continue", (bounded.last().content as JsonPrimitive).content)
        assertEquals(body, (messages[2].content as JsonPrimitive).content)
    }

    @Test
    fun modelCapacityCapsCallerBudgetWithoutMutatingUserPreference() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val conversation = cn.com.omnimind.baselib.database.Conversation(id = 42, title = "test", promptTokenThreshold = 64000)
        org.mockito.Mockito.`when`(repo.getConversation(42)).thenReturn(conversation)
        var requested = false
        val compactor = object : AgentConversationContextCompactor(repo, modelOverride = AgentModelOverride(
            providerProfileId = "test", apiBase = "https://example.invalid/v1", apiKey = "fixture", modelId = "test", contextLimit = 12000
        )) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                requested = true
                return "checkpoint"
            }
        }
        assertEquals(12000, compactor.resolvePromptTokenThreshold(42))
        assertEquals(2048, compactor.resolveOutputTokenBudget(42))
        val messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("old")),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("done")),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("continue")))
        compactor.compactIfNeeded(42, "agent", null, messages, 13000, 128000, null)
        assertTrue(requested)
        assertEquals(64000, conversation.promptTokenThreshold)
    }

    @Test
    fun oneLongUserTurnWithoutUsageCompactsBeforeContinuation() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var summaries = 0
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                summaries++
                assertTrue(maxOutputTokens in 1..2048)
                return "Original task: inspect files. Earlier files inspected; continue with recent results."
            }
        }
        val messages = mutableListOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect files")))
        for (i in 1..9) {
            val call = cn.com.omnimind.baselib.llm.AssistantToolCall(id = "r$i", type = "function",
                function = cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name = "file_read", arguments = "{}"))
            messages += ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
            messages += ChatCompletionMessage(role = "tool", toolCallId = call.id, content = JsonPrimitive("x".repeat(5000)))
        }
        val compacted = compactor.compactIfNeeded(null, "agent", null, messages, null, 12000, null)
        assertEquals(1, summaries)
        assertTrue(AgentContextBudget.estimate(compacted) < 9952)
        assertEquals(1, compacted.count { it.role == "user" })
        assertTrue(org.mockito.Mockito.mockingDetails(repo).invocations.none { it.method.name == "updateContextSummary" })
    }

    @Test
    fun repeatedStructuredOutputsKeepCurrentCursorWithinSharedBudget() = kotlinx.coroutines.runBlocking {
        var offloads = 0
        val compactor = object : AgentConversationContextCompactor(
            org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
            offloadToolOutput = { "/workspace/offloads/${++offloads}.txt" }) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String =
                "Earlier pages were inspected; continue the current request."
        }
        var messages = listOf(ChatCompletionMessage(role="user", content=JsonPrimitive("inspect pages")))
        repeat(60) { index ->
            val id="page-$index"
            val body=JsonObject(mapOf("content" to JsonPrimitive("x".repeat(65536)),
                "nextCursor" to JsonPrimitive("cursor-$index"))).toString()
            val wire=AgentEventAdapter(Json).toolResultContent(
                AgentToolRegistry.RuntimeToolDescriptor("custom_page", "Read", "context"),
                ToolExecutionResult.ContextResult("custom_page","Read",body,body))
            val call=cn.com.omnimind.baselib.llm.AssistantToolCall(id=id,type="function",
                function=cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name="custom_page",arguments="{}"))
            messages=compactor.compactIfNeeded(null,"agent",null,messages + listOf(
                ChatCompletionMessage(role="assistant",toolCalls=listOf(call)),
                ChatCompletionMessage(role="tool",toolCallId=id,content=JsonPrimitive(wire))),
                50000,32000,null,requestOverheadTokens=10000)
            assertTrue(messages.last().content.toString().contains("cursor-$index"))
            assertTrue(AgentContextBudget.estimate(messages,toolTokens=10000) <= 27904)
            assertEquals("inspect pages",(messages.single { it.role=="user" }.content as JsonPrimitive).content)
        }
        assertTrue(offloads >= 60)
    }

    @Test
    fun smallerOriginalPagesRemainReadableAfterLargeResultOffload() = kotlinx.coroutines.runBlocking {
        for (name in listOf("file_read", "plugin_document")) {
            val saved = mutableListOf<String>()
            val compactor = object : AgentConversationContextCompactor(
                org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
                offloadToolOutput = { saved += it; "/workspace/offloads/result-${saved.size}.txt" }) {
                override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String =
                    "Earlier large result is stored; inspect the current original page."
            }
            fun message(id: String, text: String): List<ChatCompletionMessage> {
                val body = JsonObject(mapOf("content" to JsonPrimitive(text),
                    "nextOffset" to JsonPrimitive(text.length))).toString()
                val wire = AgentEventAdapter(Json).toolResultContent(
                    AgentToolRegistry.RuntimeToolDescriptor(name, "Read", "context"),
                    ToolExecutionResult.ContextResult(name, "Read", body, body))
                val call = cn.com.omnimind.baselib.llm.AssistantToolCall(id=id,type="function",
                    function=cn.com.omnimind.baselib.llm.AssistantToolCallFunction(name=name,arguments="{}"))
                return listOf(ChatCompletionMessage(role="assistant",toolCalls=listOf(call)),
                    ChatCompletionMessage(role="tool",toolCallId=id,content=JsonPrimitive(wire)))
            }
            var history = listOf(ChatCompletionMessage(role="user",content=JsonPrimitive("Inspect original body"))) +
                message("large", "x".repeat(65536))
            history = compactor.compactIfNeeded(null,"agent",null,history,50000,32000,null,requestOverheadTokens=10000)
            assertEquals(1,saved.size)
            for ((index, text) in listOf("body-value-7391" + "x".repeat(2000),
                "中文正文值7391" + "汉字".repeat(240)).withIndex()) {
                val latest = message("small-$index",text)
                history = compactor.compactIfNeeded(null,"agent",null,history + message("pressure-$index", "x".repeat(65536)) + latest,50000,32000,null,requestOverheadTokens=10000)
                assertEquals("Small original body was unnecessarily offloaded",latest.last(),history.last())
                assertTrue(AgentContextBudget.estimate(history,toolTokens=10000) <= 27904)
                assertEquals("Inspect original body",(history.single { it.role=="user" }.content as JsonPrimitive).content)
            }
        }
    }

    @Test
    fun offloadedCurrentResultKeepsPagingMetadataAcrossToolNames() = kotlinx.coroutines.runBlocking {
        for (name in listOf("file_read", "plugin_document", "browser_result")) {
            val body = JsonObject(mapOf("path" to JsonPrimitive("/workspace/test.html"),
                "content" to JsonPrimitive("x".repeat(65536)), "nextOffset" to JsonPrimitive(65536),
                "hasMore" to JsonPrimitive(true), "cursor" to JsonPrimitive("page-2"))).toString()
            val result = ToolExecutionResult.ContextResult(name, "Read", body, body)
            val wire = AgentEventAdapter(Json).toolResultContent(
                AgentToolRegistry.RuntimeToolDescriptor(name, "Read", "context"), result)
            val saved = mutableListOf<String>()
            val compactor = AgentConversationContextCompactor(
                org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
                offloadToolOutput = { saved += it; "/workspace/offloads/full.txt" })
            val messages = listOf(ChatCompletionMessage(role="user", content=JsonPrimitive("continue reading")),
                ChatCompletionMessage(role="tool", toolCallId="page", content=JsonPrimitive(wire)))
            val compacted = compactor.compactIfNeeded(null,"agent",null,messages,50000,32000,null,
                requestOverheadTokens=10000)
            val projected = (compacted.last().content as JsonPrimitive).content
            assertTrue("Paging cursor lost for $name", projected.contains("nextOffset") && projected.contains("65536"))
            assertTrue(projected.contains("page-2"))
            assertFalse(projected.contains("x".repeat(1024)))
            assertEquals(listOf(wire), saved)
            assertTrue(AgentContextBudget.estimate(compacted,toolTokens=10000) < 28000)
        }
    }

    @Test
    fun oversizedToolBodyIsSavedInFullAndModelGetsRetrievableReference() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val body = "line\n".repeat(230000)
        val offloads = mutableListOf<String>()
        val compactor = object : AgentConversationContextCompactor(repo, offloadToolOutput = {
            offloads += it
            "/workspace/offloads/result.txt"
        }) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String =
                error("Offloaded result already fits; do not make an unnecessary summary request")
        }
        val messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("check log")),
            ChatCompletionMessage(role = "tool", toolCallId = "t1", content = JsonPrimitive(body)))
        val compacted = compactor.compactIfNeeded(null, "agent", null, messages, null, 128000, null)
        assertEquals(listOf(body), offloads)
        assertTrue(compacted.last().content.toString().contains("/workspace/offloads/result.txt"))
        assertEquals(body, messages.last().content?.let { (it as JsonPrimitive).content })
        assertEquals("t1", compacted.last().toolCallId)
    }

    @Test
    fun irreducibleInputAndOversizedSummaryNeverCommitOrSendOriginal() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        var requests = 0
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                requests++
                return "bad summary".repeat(10000)
            }
        }
        val hugeUser = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("中".repeat(20000))))
        assertTrue(runCatching { compactor.compactIfNeeded(42, "agent", null, hugeUser, null, 12000, null) }.isFailure)
        assertEquals(0, requests)
        val history = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("old")),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("answer")),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("current")))
        assertTrue(runCatching { compactor.compactIfNeeded(42, "agent", 12000, history, 12000, 12000, null) }.isFailure)
        assertEquals(1, requests)
        assertTrue(org.mockito.Mockito.mockingDetails(repo).invocations.none { it.method.name == "updateContextSummary" })
    }

    @Test
    fun manualCompactionUsesTheSameDurableCheckpointAndRetainsPreviousSummary() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val conversation = cn.com.omnimind.baselib.database.Conversation(
            id = 42, title = "test", contextSummary = "previous checkpoint")
        val entry = cn.com.omnimind.baselib.database.AgentConversationEntry(
            id = 7, conversationId = 42, conversationMode = "agent", entryId = "u1",
            entryType = AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            status = AgentConversationHistoryRepository.STATUS_SUCCESS,
            summary = "pending blue export",
            payloadJson = """{"id":"u1","type":1,"user":1,"content":{"text":"pending blue export","id":"u1"}}""",
        )
        org.mockito.Mockito.`when`(repo.getContextCompactionCandidate(42, "agent")).thenReturn(
            AgentConversationHistoryRepository.ContextCompactionCandidate(conversation, listOf(entry), 7))
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                assertTrue(messages.any { it["content"].toString().contains("previous checkpoint") })
                assertTrue(messages.any { it["content"].toString().contains("pending blue export") })
                return "replacement checkpoint"
            }
        }
        val result = compactor.compactConversationContext(42, "agent")
        assertTrue(result.compacted)
        assertEquals(7L, result.cutoffEntryDbId)
        val commits = org.mockito.Mockito.mockingDetails(repo).invocations.filter { it.method.name == "updateContextSummary" }
        assertEquals(1, commits.size)
        assertEquals(listOf(42L, "replacement checkpoint", 7L), commits.single().arguments.take(3))
    }

    @Test
    fun manualCompactionWithoutCandidatesDoesNotRequestOrReplaceSummary() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String =
                error("No model request is needed for empty history")
        }
        val result = compactor.compactConversationContext(42, "agent")
        assertEquals(false, result.compacted)
        assertEquals("no_candidate", result.reason)
        assertTrue(org.mockito.Mockito.mockingDetails(repo).invocations.none { it.method.name == "updateContextSummary" })
    }

    @Test
    fun automaticCompactionPersistsCheckpointButPreservesCurrentUserMessage() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val conversation = cn.com.omnimind.baselib.database.Conversation(id = 42, title = "test")
        org.mockito.Mockito.`when`(repo.getConversation(42)).thenReturn(conversation)
        org.mockito.Mockito.`when`(repo.getContextCompactionCandidate(42, "agent")).thenReturn(
            AgentConversationHistoryRepository.ContextCompactionCandidate(conversation, emptyList(), 7))
        var requests = 0
        val compactor = object : AgentConversationContextCompactor(repo) {
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                requests++
                assertTrue(messages.any { it["content"] == "old answer" })
                return "durable checkpoint"
            }
        }
        val messages = listOf(
            ChatCompletionMessage(role = "system", content = JsonPrimitive("system")),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("old question")),
            ChatCompletionMessage(role = "assistant", content = JsonPrimitive("old answer")),
            ChatCompletionMessage(role = "user", content = JsonPrimitive("current question")),
        )
        assertEquals(messages, compactor.compactIfNeeded(42, "agent", 1000, messages, 1000, 128000, null))
        assertEquals(0, requests)
        val compacted = compactor.compactIfNeeded(42, "agent", 120000, messages, 121000, 128000, null)
        assertEquals(1, requests)
        assertEquals(listOf("current question"), compacted.filter { it.role == "user" }.map { it.content.toString().trim('"') })
        assertTrue(compacted.any { it.content.toString().contains("durable checkpoint") })
        val commits = org.mockito.Mockito.mockingDetails(repo).invocations.filter { it.method.name == "updateContextSummary" }
        assertEquals(1, commits.size)
        assertEquals(listOf(42L, "durable checkpoint", 7L), commits.single().arguments.take(3))
    }

    @Test
    fun failedOrCancelledCompactionNeverCommitsAReplacementCheckpoint() = kotlinx.coroutines.runBlocking {
        for (cancel in listOf(false, true)) {
            val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
            val conversation = cn.com.omnimind.baselib.database.Conversation(id = 42, title = "test")
            org.mockito.Mockito.`when`(repo.getContextCompactionCandidate(42, "agent")).thenReturn(
                AgentConversationHistoryRepository.ContextCompactionCandidate(conversation, emptyList(), 7))
            val compactor = object : AgentConversationContextCompactor(repo) {
                override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>, maxOutputTokens: Int): String {
                    if (cancel) throw kotlinx.coroutines.CancellationException("cancel")
                    throw IllegalStateException("offline")
                }
            }
            val messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("old")),
                ChatCompletionMessage(role = "assistant", content = JsonPrimitive("answer")),
                ChatCompletionMessage(role = "user", content = JsonPrimitive("current")),
            )
            val result = runCatching { compactor.compactIfNeeded(42, "agent", 120000, messages, 121000, 128000, null) }
            if (cancel) assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            else assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertTrue(org.mockito.Mockito.mockingDetails(repo).invocations.none { it.method.name == "updateContextSummary" })
        }
    }

    @Test
    fun automaticTriggerReservesCapacityAndHonorsTheSmallerConfiguredLimit() {
        assertEquals(112000, AgentConversationContextCompactor.resolveAutoCompactionTrigger(128000))
        assertEquals(983616, AgentConversationContextCompactor.resolveAutoCompactionTrigger(1000000))
        assertEquals(128000, AgentConversationContextCompactor.resolveEffectiveContextCapacity(null, null))
        assertEquals(32000, AgentConversationContextCompactor.resolveEffectiveContextCapacity(128000, 32000))
        assertEquals(16000, AgentConversationContextCompactor.resolveEffectiveContextCapacity(16000, 32000))
        assertEquals(1, AgentConversationContextCompactor.resolveAutoCompactionTrigger(1))
    }

    @Test
    fun contextAccountingIncludesOutputAndDoesNotOverflowInteger() {
        assertEquals(120000, AgentConversationContextCompactor.resolveReportedContextTokens(110000, 10000, 110000))
        assertEquals(Int.MAX_VALUE, AgentConversationContextCompactor.resolveReportedContextTokens(Int.MAX_VALUE, 10000, null))
        assertEquals(null, AgentConversationContextCompactor.resolveReportedContextTokens(null, null, null))
    }

    @Test
    fun `buildCompactionRequestMessages keeps a summary out of the user role`() {
        val requestMessages = AgentConversationContextCompactor.buildCompactionRequestMessages(
            existingSummary = "旧总结",
            messagesToCompact = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive("新问题")
                )
            )
        )

        val firstMessage = requestMessages.first()
        assertEquals("system", firstMessage["role"])
        val systemPromptContent = firstMessage["content"].toString()
        assertTrue(systemPromptContent.contains("type=text"))
        assertTrue(systemPromptContent.contains("context compaction engine"))
        assertTrue(systemPromptContent.contains("cache_control={type=ephemeral}"))
        assertTrue(systemPromptContent.contains("## Goal"))
        assertTrue(systemPromptContent.contains("## Constraints & Preferences"))
        assertTrue(systemPromptContent.contains("## Critical Context"))
        assertTrue(systemPromptContent.contains("Do NOT continue the conversation"))

        val summaryMessage = requestMessages[1]
        assertEquals("assistant", summaryMessage["role"])
        assertTrue(
            (summaryMessage["content"] as? String).orEmpty().startsWith(
                "<context-summary> Earlier conversation context, retained as an assistant history checkpoint."
            )
        )
        assertTrue((summaryMessage["content"] as? String).orEmpty().contains("旧总结"))

        val compactedUserMessage = requestMessages[2]
        assertEquals("user", compactedUserMessage["role"])
        assertEquals("新问题", compactedUserMessage["content"])

        val finalPrompt = requestMessages[3]
        assertEquals("user", finalPrompt["role"])
        assertEquals(
            "Generate the replacement context summary now.",
            finalPrompt["content"]
        )
    }

    @Test
    fun `parseChatMessageContent preserves cache_control in text blocks`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "parseChatMessageContent",
            Any::class.java
        )
        method.isAccessible = true

        val content = method.invoke(
            HttpController,
            listOf(
                mapOf(
                    "type" to "text",
                    "text" to "需要缓存的系统提示",
                    "cache_control" to mapOf("type" to "ephemeral")
                )
            )
        )

        val blocks = content as JsonArray
        val firstBlock = blocks.first() as JsonObject
        assertEquals("text", firstBlock["type"]?.toString()?.trim('"'))
        assertEquals(
            "ephemeral",
            firstBlock["cache_control"]
                ?.let { it as? JsonObject }
                ?.get("type")
                ?.toString()
                ?.trim('"')
        )
    }

}
