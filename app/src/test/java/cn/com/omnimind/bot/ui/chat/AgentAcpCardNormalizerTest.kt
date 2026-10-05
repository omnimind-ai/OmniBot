package cn.com.omnimind.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Port of `ui/test/services/agent_acp_card_normalizer_test.dart`. */
class AgentAcpCardNormalizerTest {
    @Test
    fun `turns markdown ACP plans into structured mutable entries`() {
        val card = AgentAcpCardNormalizer.normalize(
            mapOf(
                "type" to "plan",
                "entries" to emptyList<Map<String, Any?>>(),
                "plan" to "# Plan\n\n- [x] Inspect workspace\n- [ ] Suggest improvement",
            ),
        )

        assertEquals("agent_tool_summary", card["type"])
        assertEquals("plan", card["toolType"])
        assertEquals(
            listOf(
                mapOf("content" to "Inspect workspace", "status" to "completed"),
                mapOf("content" to "Suggest improvement", "status" to "pending"),
            ),
            card["planEntries"],
        )
    }

    @Test
    fun `prefers structured ACP entries when both forms are present`() {
        val card = AgentAcpCardNormalizer.normalize(
            mapOf(
                "type" to "plan",
                "entries" to listOf(mapOf("content" to "Structured task", "status" to "in_progress")),
                "plan" to "- [ ] Markdown fallback",
            ),
        )

        assertEquals(
            listOf(mapOf("content" to "Structured task", "status" to "in_progress")),
            card["planEntries"],
        )
    }

    @Test
    fun `reads entries nested in an ACP plan object`() {
        val card = AgentAcpCardNormalizer.normalize(
            mapOf(
                "type" to "plan",
                "plan" to mapOf(
                    "type" to "entries",
                    "entries" to listOf(mapOf("content" to "Nested task", "status" to "pending")),
                ),
            ),
        )

        assertEquals(
            listOf(mapOf("content" to "Nested task", "status" to "pending")),
            card["planEntries"],
        )
    }

    @Test
    fun `normalizes reducer-created plan cards with markdown summaries`() {
        val card = AgentAcpCardNormalizer.normalize(
            mapOf(
                "type" to "agent_tool_summary",
                "toolType" to "plan",
                "summary" to "- [ ] Inspect the workspace\n- [x] Report the result",
                "planEntries" to emptyList<Map<String, Any?>>(),
            ),
        )

        assertEquals(
            listOf(
                mapOf("content" to "Inspect the workspace", "status" to "pending"),
                mapOf("content" to "Report the result", "status" to "completed"),
            ),
            card["planEntries"],
        )
    }

    @Test
    fun `uses the ACP lifecycle status instead of raw tool output`() {
        val card = AgentAcpCardNormalizer.normalize(
            mapOf(
                "type" to "tool_call_update",
                "status" to "pending",
                "success" to false,
                "rawOutput" to mapOf("success" to false, "question" to "请确认执行高权限操作"),
            ),
        )

        assertEquals("pending", card["status"])
    }
}
