package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AgentConversationContextCompactorTest {
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
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>): String {
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
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>): String =
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
            override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>): String {
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
                override suspend fun requestCompactedSummary(messages: List<Map<String, Any>>): String {
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
            else assertEquals(messages, result.getOrThrow())
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
