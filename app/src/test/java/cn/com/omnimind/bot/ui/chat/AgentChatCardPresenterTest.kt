package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.nativeui.chat.AgentPlanEntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports the presentation decisions of `agent_request_card_test` / `deep_thinking_card_test`. */
class AgentChatCardPresenterTest {
    @Test
    fun `historical requests are not actionable`() {
        for (extra in listOf(mapOf("status" to "expired"), mapOf("status" to "pending", "interactionUnavailable" to true))) {
            val card = presentAgentRequestCard(
                mapOf("type" to "agent_request", "requestKind" to "approval", "requestId" to "old-request",
                    "title" to "Implement this plan?") + extra,
            )
            assertFalse(card.isPending && !card.interactionUnavailable)
        }
    }

    @Test
    fun `restored requests keep their outcome`() {
        for (status in listOf("accepted", "declined", "submitted", "expired")) {
            val card = presentAgentRequestCard(
                mapOf("requestKind" to "approval", "title" to "Implement this plan?", "status" to status,
                    "interactionUnavailable" to true),
            )
            assertEquals(status, card.status)
            assertTrue(card.interactionUnavailable)
        }
    }

    @Test
    fun `ended sessions and missing request ids are unavailable`() {
        val ended = presentAgentRequestCard(
            mapOf("requestKind" to "user_input", "status" to "pending", "title" to "Implement this plan?",
                "interactionUnavailable" to true, "interactionUnavailableReason" to "session_ended"),
        )
        assertTrue(ended.sessionEnded)
        val missing = presentAgentRequestCard(mapOf("requestKind" to "approval", "status" to "pending", "title" to "Permission"))
        assertTrue(missing.interactionUnavailable)
        assertFalse(missing.sessionEnded)
        assertTrue(missing.isPending)
        val blank = presentAgentRequestCard(mapOf("requestKind" to "approval", "requestId" to "  "))
        assertTrue(blank.interactionUnavailable)
        assertEquals("pending", blank.status)
    }

    @Test
    fun `generic notice resolves the live ACP schema question`() {
        val raw = DartJson.encode(
            mapOf(
                "message" to "The agent needs your input.",
                "requestedSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "details" to mapOf(
                            "type" to "string", "title" to "插件详情", "description" to "请提供插件名称和用途",
                            "oneOf" to listOf(mapOf("const" to "android", "title" to "Android 插件")),
                        ),
                    ),
                ),
            ),
        )
        val card = presentAgentRequestCard(
            mapOf("type" to "agent_request", "requestKind" to "user_input", "requestId" to "elicitation-live",
                "title" to "The agent needs your input.", "detail" to "The agent needs your input.", "rawParamsJson" to raw),
        )
        assertEquals("插件详情", card.title)
        assertEquals("请提供插件名称和用途\n可选：Android 插件", card.detail)
        assertFalse(card.interactionUnavailable)
    }

    @Test
    fun `specific titles survive a schema`() {
        val raw = DartJson.encode(mapOf("request" to DartJson.encode(mapOf("properties" to mapOf("mode" to mapOf("enum" to listOf("Plan", "Chat")))))))
        val (title, detail) = compactRequestPresentation(mapOf("title" to "Choose mode", "rawParamsJson" to raw))
        assertEquals("Choose mode", title)
        assertEquals("可选：Plan、Chat", detail)
    }

    @Test
    fun `thinking card defaults follow the factory`() {
        val streaming = presentDeepThinkingCard(mapOf("thinkingContent" to "历史思考内容", "stage" to 2), english = false)
        assertTrue(streaming.isLoading)
        assertTrue(streaming.isActivelyThinking)
        assertTrue(streaming.showAvatar)
        val restored = presentDeepThinkingCard(mapOf("thinkingContent" to "历史思考内容", "stage" to 4), english = false)
        assertFalse(restored.isLoading)
        assertTrue(restored.hasContent)
        assertNull(restored.completedElapsedSeconds)
    }

    @Test
    fun `completed thinking reports elapsed only with both boundaries`() {
        val done = presentDeepThinkingCard(
            mapOf("thinkingContent" to "", "stage" to "4", "isLoading" to "false",
                "startTime" to 1_700_000_001_000L, "endTime" to 1_700_000_013_500.0),
            english = false,
        )
        assertEquals(12L, done.completedElapsedSeconds)
        // Empty finished reasoning renders nothing (Dart: SizedBox.shrink).
        assertFalse(done.hasContent || done.isActivelyThinking)
        val stillLoading = presentDeepThinkingCard(
            mapOf("stage" to 4, "isLoading" to true, "startTime" to 1000, "endTime" to 2000), english = false,
        )
        assertNull(stillLoading.completedElapsedSeconds)
    }

    @Test
    fun `only the primary thinking card of a run shows the avatar`() {
        assertTrue(presentDeepThinkingCard(mapOf("taskID" to "t1", "cardId" to "t1-thinking"), false).showAvatar)
        assertFalse(presentDeepThinkingCard(mapOf("taskID" to "t1", "cardId" to "t1-thinking-2"), false).showAvatar)
        assertTrue(presentDeepThinkingCard(mapOf("cardId" to "t1-thinking-2"), false).showAvatar)
    }

    @Test
    fun `english thinking text is localized line by line`() {
        val card = presentDeepThinkingCard(mapOf("thinkingContent" to "正在思考\nplain", "stage" to 1), english = true)
        assertEquals("Thinking\nplain", card.text)
    }

    @Test
    fun `plan entries keep order, status and fallbacks`() {
        val entries = presentPlanEntries(
            mapOf(
                "planEntries" to listOf(
                    mapOf("content" to "读代码", "status" to "completed"),
                    mapOf("title" to "写测试", "status" to "in_progress"),
                    mapOf("text" to "提交", "status" to "pending"),
                    "not a map",
                ),
            ),
        )
        assertEquals(listOf("读代码", "写测试", "提交"), entries.map { it.text })
        assertEquals(
            listOf(AgentPlanEntryState.Completed, AgentPlanEntryState.InProgress, AgentPlanEntryState.Pending),
            entries.map { it.state },
        )
        assertEquals("任务 1", presentPlanEntries(mapOf("entries" to listOf(mapOf("status" to "done")))).single().text)
    }

    @Test
    fun `plan capsules carry their entries`() {
        val card = presentAgentToolCard(
            mapOf("type" to "plan", "entries" to listOf(mapOf("content" to "第一步", "status" to "pending"))),
            english = false,
            useAgentToolPresentation = false,
        )
        assertEquals(listOf("第一步"), card.planEntries.map { it.text })
    }
}
