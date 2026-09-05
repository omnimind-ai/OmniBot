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
