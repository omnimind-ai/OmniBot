package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the snapshot cases of ui/test/.../widgets/chat_tool_activity_strip_test.dart
 * and the anchor/elapsed rules of chat_message_anchor_bar.dart / agent_run_header.dart.
 */
class ChatActivityModelsTest {

    @Test
    fun `completed run keeps latest tool history pinned after folding`() {
        val messages = listOf(
            text("task-latest-text", "最终回答", meta("task-latest", "completed", 4, isFinal = true)),
            tool("task-latest-tool", "task-latest", "success", "最新工具", "tool_completed", 3),
            card("task-latest-thinking", mapOf("type" to "deep_thinking", "taskID" to "task-latest", "thinkingContent" to "思考中"),
                meta("task-latest", "thinking_snapshot", 2)),
            user("user-1", "上一条用户消息"),
        )
        val snapshot = resolveAgentToolActivitySnapshot(messages)
        assertFalse(snapshot.isActiveRun)
        assertEquals("task-latest", snapshot.taskId)
        assertEquals(listOf("task-latest-tool"), snapshot.messages.map { it.id })
    }

    @Test
    fun `completed tool history stays hidden until the matching run group expands`() {
        val messages = listOf(
            text("task-latest-text", "最终回答", meta("task-latest", "completed", 4, isFinal = true)),
            tool("task-latest-tool", "task-latest", "success", "最新工具", "tool_completed", 3),
        )
        val snapshot = resolveAgentToolActivitySnapshot(messages)
        assertFalse(shouldShowAgentToolActivitySnapshot(snapshot))
        assertTrue(shouldShowAgentToolActivitySnapshot(snapshot, setOf("task-latest")))
        assertFalse(shouldShowAgentToolActivitySnapshot(snapshot, setOf("task-other")))
    }

    @Test
    fun `preferred expanded run shows its own tool history instead of latest run`() {
        val messages = listOf(
            user("user-latest", "更新的用户问题"),
            text("task-latest-text", "最新回答", meta("task-latest", "completed", 8, isFinal = true)),
            tool("task-latest-tool", "task-latest", "success", "最新工具", "tool_completed", 7),
            text("task-older-text", "更早回答", meta("task-older", "completed", 4, isFinal = true)),
            tool("task-older-tool", "task-older", "success", "更早工具", "tool_completed", 3),
        )
        val snapshot = resolveAgentToolActivitySnapshot(messages, preferredCompletedTaskId = "task-older")
        assertFalse(snapshot.isActiveRun)
        assertEquals("task-older", snapshot.taskId)
        assertEquals(listOf("task-older-tool"), snapshot.messages.map { it.id })
        assertTrue(shouldShowAgentToolActivitySnapshot(snapshot, setOf("task-older")))
    }

    @Test
    fun `newer user turn clears pinned tool history from prior agent run`() {
        val messages = listOf(
            user("user-latest", "新的用户问题"),
            text("task-latest-text", "最终回答", meta("task-latest", "completed", 4, isFinal = true)),
            tool("task-latest-tool", "task-latest", "success", "最新工具", "tool_completed", 3),
        )
        val snapshot = resolveAgentToolActivitySnapshot(messages)
        assertFalse(snapshot.isActiveRun)
        assertTrue(snapshot.messages.isEmpty())
    }

    @Test
    fun `active run without tool cards does not reuse previous tool history`() {
        val messages = listOf(
            text("task-active-text", "新的回复", meta("task-active", "text_snapshot", 10, isFinal = false)),
            card("task-active-thinking", mapOf("type" to "deep_thinking", "taskID" to "task-active", "thinkingContent" to "思考中"),
                meta("task-active", "thinking_snapshot", 9)),
            tool("task-old-tool", "task-old", "success", "旧工具", "tool_completed", 3),
            text("task-old-text", "旧回答", meta("task-old", "completed", 4, isFinal = true)),
        )
        val snapshot = resolveAgentToolActivitySnapshot(messages, activeTaskIds = setOf("task-active"))
        assertTrue(snapshot.isActiveRun)
        assertTrue(snapshot.messages.isEmpty())
        assertFalse(shouldShowAgentToolActivitySnapshot(snapshot))
    }

    @Test
    fun `active tool history remains visible without requiring expansion`() {
        val messages = listOf(tool("task-active-tool", "task-active", "running", "运行中工具", "tool_running", 2))
        val snapshot = resolveAgentToolActivitySnapshot(messages, activeTaskIds = setOf("task-active"))
        assertTrue(snapshot.isActiveRun)
        assertEquals("task-active", snapshot.taskId)
        assertTrue(shouldShowAgentToolActivitySnapshot(snapshot))
    }

    @Test
    fun `active card is the first running one, else the newest`() {
        val done = tool("a", "t", "success", "A", "tool_completed", 3)
        val running = tool("b", "t", "running", "B", "tool_running", 2)
        assertEquals("b", resolveActiveAgentToolMessage(listOf(done, running))?.id)
        assertEquals("a", resolveActiveAgentToolMessage(listOf(done))?.id)
    }

    @Test
    fun `anchors run oldest first with one anchor per run`() {
        val messages = listOf(
            text("run-2-text", "第二轮回答\n第二行", meta("run-2", "completed", 6, isFinal = true)),
            user("user-2", "第二个问题"),
            text("run-1-text", "  \n第一轮回答", meta("run-1", "completed", 3, isFinal = true)),
            tool("run-1-tool", "run-1", "success", "工具", "tool_completed", 2),
            user("user-1", "x".repeat(60)),
        )
        val anchors = buildChatMessageAnchors(buildAgentRunTimelineEntries(messages))
        assertEquals(listOf(true, false, true, false), anchors.map { it.isUser })
        assertEquals("x".repeat(50), anchors[0].preview)
        assertEquals("第一轮回答", anchors[1].preview)
        assertEquals("第二轮回答", anchors[3].preview)
        assertEquals("agent-run-run-1", anchors[1].entryKey)
    }

    @Test
    fun `a run with only process cards anchors with an empty preview`() {
        val messages = listOf(tool("run-tool", "run", "running", "工具", "tool_running", 1))
        val anchor = buildChatMessageAnchors(buildAgentRunTimelineEntries(messages, activeTaskIds = setOf("run"))).single()
        assertEquals("", anchor.preview)
        assertFalse(anchor.isUser)
    }

    @Test
    fun `elapsed labels match the Flutter header`() {
        assertEquals("", formatRunElapsed(0))
        assertEquals("47s", formatRunElapsed(47))
        assertEquals("1m", formatRunElapsed(60))
        assertEquals("1m 23s", formatRunElapsed(83))
        assertEquals("1h", formatRunElapsed(3600))
        assertEquals("1h 5m", formatRunElapsed(3900))
    }

    // Dart `ChatMessageModel` factories as used by the ported tests.
    private fun meta(taskId: String, kind: String, seq: Int, isFinal: Boolean? = null): Map<String, Any?> =
        buildMap {
            put("parentTaskId", taskId)
            put("kind", kind)
            put("seq", seq)
            if (isFinal != null) put("isFinal", isFinal)
        }

    private fun user(id: String, text: String) = ChatMessageUi(
        id = id, type = 1, user = 1, content = mapOf("text" to text, "id" to id),
        createAtMillis = System.currentTimeMillis(),
    )

    private fun text(id: String, text: String, streamMeta: Map<String, Any?>) = ChatMessageUi(
        id = id, type = 1, user = 2, content = mapOf("text" to text, "id" to id),
        streamMeta = streamMeta, createAtMillis = System.currentTimeMillis(),
    )

    private fun card(id: String, cardData: Map<String, Any?>, streamMeta: Map<String, Any?>) = ChatMessageUi(
        id = id, type = 2, user = 3, content = mapOf("cardData" to cardData, "id" to id),
        streamMeta = streamMeta, createAtMillis = System.currentTimeMillis(),
    )

    private fun tool(id: String, taskId: String, status: String, title: String, kind: String, seq: Int) = card(
        id,
        mapOf("type" to "agent_tool_summary", "taskId" to taskId, "status" to status, "toolTitle" to title),
        meta(taskId, kind, seq),
    )
}
