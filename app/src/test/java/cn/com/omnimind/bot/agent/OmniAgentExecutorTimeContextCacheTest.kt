package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniAgentExecutorTimeContextCacheTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val baseTime = ZonedDateTime.of(2026, 6, 13, 10, 3, 0, 0, zoneId)

    @Test
    fun resolveTimeContextSnapshotReusesCachedSnapshotWithinOneHour() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = baseTime.plusMinutes(59).plusSeconds(59),
            locale = PromptLocale.EN_US
        )

        assertSame(cached, resolved)
    }

    @Test
    fun resolveTimeContextSnapshotRefreshesAtOneHourBoundary() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )
        val refreshTime = baseTime.plusHours(1)

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = refreshTime,
            locale = PromptLocale.EN_US
        )

        assertNotSame(cached, resolved)
        assertEquals(refreshTime, resolved.generatedAt)
    }

    @Test
    fun resolveTimeContextSnapshotRefreshesWhenLocaleChanges() {
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = baseTime,
            content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)
        )

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = baseTime.plusMinutes(1),
            locale = PromptLocale.ZH_CN
        )

        assertNotSame(cached, resolved)
        assertEquals(PromptLocale.ZH_CN, resolved.locale)
    }

    @Test
    fun resolveTimeContextSnapshotRefreshesAtLocalDateBoundary() {
        val late = baseTime.withHour(23).withMinute(50)
        val cached = OmniAgentExecutor.TimeContextSnapshot(
            locale = PromptLocale.EN_US,
            zoneId = zoneId.id,
            generatedAt = late,
            content = OmniAgentExecutor.buildTimeContextContent(late, PromptLocale.EN_US)
        )

        val resolved = OmniAgentExecutor.resolveTimeContextSnapshot(
            cached = cached,
            now = late.plusMinutes(15),
            locale = PromptLocale.EN_US
        )

        assertNotSame(cached, resolved)
    }

    @Test
    fun timeContextContainsOnlyCoarseDateInformation() {
        val content = OmniAgentExecutor.buildTimeContextContent(baseTime, PromptLocale.EN_US)

        assertTrue(content.contains("Local date: 2026-06-13"))
        assertTrue(content.contains("Timezone: Asia/Shanghai"))
        assertTrue(content.contains("context_time_now"))
        assertFalse(content.contains("10:03"))
        assertFalse(content.contains("Current local time"))
    }

    @Test
    fun mergeInitialPromptMessagesKeepsLatestUserWhenContinuingAfterFirstTurnFailure() {
        val messages = OmniAgentExecutor.mergeInitialPromptMessages(
            leadingMessages = listOf(
                message("system", "system prompt"),
                message("system", "time context")
            ),
            historyMessages = listOf(message("user", "original prompt")),
            currentUserMessage = message("user", "runtime fallback prompt"),
            continueMode = true
        )

        assertEquals("original prompt", text(messages.last()))
        assertEquals(
            listOf("system", "system", "user"),
            messages.map { it.role }
        )
        assertFalse(messages.any { text(it) == "runtime fallback prompt" })
    }

    @Test
    fun mergeInitialPromptMessagesDoesNotDuplicateUserAfterToolContinuationContext() {
        val messages = OmniAgentExecutor.mergeInitialPromptMessages(
            leadingMessages = listOf(message("system", "system prompt")),
            historyMessages = listOf(
                message("user", "original prompt"),
                message("tool", "tool result")
            ),
            currentUserMessage = message("user", "runtime fallback prompt"),
            continueMode = true
        )

        assertEquals("tool", messages.last().role)
        assertEquals("tool result", text(messages.last()))
        assertEquals(1, messages.count { it.role == "user" })
        assertFalse(messages.any { text(it) == "runtime fallback prompt" })
    }

    @Test
    fun mergeInitialPromptMessagesKeepsTheOriginalImageUserMessageAfterToolContinuation() {
        val originalUserMessage = ChatCompletionMessage(
            role = "user",
            content = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("text"),
                            "text" to JsonPrimitive("识别附件中的全部内容后继续")
                        )
                    ),
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("image_url"),
                            "image_url" to JsonObject(
                                mapOf("url" to JsonPrimitive("data:image/png;base64,ORIGINAL_IMAGE"))
                            )
                        )
                    )
                )
            )
        )
        val messages = OmniAgentExecutor.mergeInitialPromptMessages(
            leadingMessages = listOf(message("system", "system prompt")),
            historyMessages = listOf(
                originalUserMessage,
                message("tool", "OCR result")
            ),
            currentUserMessage = message("user", "runtime fallback prompt"),
            continueMode = true
        )

        assertEquals(listOf("system", "user", "tool"), messages.map { it.role })
        assertEquals(originalUserMessage.content, messages[1].content)
        assertEquals("tool", messages.last().role)
        assertFalse(messages.any { text(it) == "runtime fallback prompt" })
    }

    @Test
    fun filterChatOnlyHistoryMessagesRemovesAgentToolReplay() {
        val assistantWithToolCall = ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive("先查一下"),
            toolCalls = listOf(
                AssistantToolCall(
                    id = "call-1",
                    function = AssistantToolCallFunction(
                        name = "tools_search",
                        arguments = "{}"
                    )
                )
            ),
            reasoningContent = "内部思考"
        )
        val filtered = OmniAgentExecutor.filterChatOnlyHistoryMessages(
            listOf(
                message("user", "之前的问题"),
                assistantWithToolCall,
                message("tool", "工具结果"),
                message("assistant", "之前的回答")
            )
        )

        assertEquals(listOf("user", "assistant", "assistant"), filtered.map { it.role })
        assertEquals("先查一下", text(filtered[1]))
        assertTrue(filtered[1].toolCalls == null)
        assertTrue(filtered[1].reasoningContent == null)
        assertFalse(filtered.any { it.role == "tool" })
    }

    private fun message(role: String, content: String): ChatCompletionMessage {
        return ChatCompletionMessage(role = role, content = JsonPrimitive(content))
    }

    private fun text(message: ChatCompletionMessage): String {
        return (message.content as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
