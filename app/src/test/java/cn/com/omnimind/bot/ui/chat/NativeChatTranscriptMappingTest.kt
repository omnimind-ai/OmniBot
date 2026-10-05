package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.ChatMessage
import cn.com.omnimind.nativeui.chat.AgentToolCardStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NativeChatTranscriptMappingTest {
    private fun toolMessage(id: String, status: String) = ChatMessage(
        id = id,
        type = 2,
        user = 3,
        content = linkedMapOf(
            "cardData" to linkedMapOf(
                "type" to "agent_tool_summary", "toolCallId" to "call-1", "toolType" to "terminal",
                "toolTitle" to "运行测试", "status" to status,
            ),
        ),
        streamMeta = linkedMapOf("runId" to "run-1", "sessionId" to "s1", "turnId" to "t1"),
        createAtMillis = 42L,
    )

    @Test
    fun `runtime messages keep identity and stream meta in the ui model`() {
        val ui = toolMessage("m1", "running").toUi()
        assertEquals("m1", ui.id)
        assertEquals(2, ui.type)
        assertEquals(42L, ui.createAtMillis)
        assertEquals("run-1", ui.runId)
        assertEquals("s1", ui.sessionId)
        assertEquals("t1", ui.turnId)
        assertEquals("call-1", ui.toolCallId)
        assertNull(ui.toolCard)
    }

    @Test
    fun `tool summary cards are presented and reused until their content changes`() {
        val cache = ToolCardCache()
        val running = toolMessage("m1", "running")
        val first = running.toUi(cache).toolCard
        assertNotNull(first)
        assertEquals(AgentToolCardStyle.Capsule, first!!.style)
        assertEquals("运行测试", first.title)
        assertSame(first, running.copy(isLoading = true).toUi(cache).toolCard)
        val done = toolMessage("m1", "success").toUi(cache).toolCard!!
        assertEquals("success", done.status)
        assertEquals("成功", done.badgeLabel)
    }

    @Test
    fun `text messages carry no tool card`() {
        val cache = ToolCardCache()
        val text = ChatMessage(id = "t", type = 1, user = 2, content = linkedMapOf("text" to "hi"))
        assertNull(text.toUi(cache).toolCard)
    }
}
