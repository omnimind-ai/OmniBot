package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SubagentProgressTextTest {
    @Test
    fun `progress text keeps a long subagent result intact`() {
        val importantTail = "必须保留的结论：迁移已完成，文件位于 /workspace/reports/final.md"
        val original = "步骤结果\n" + "细节 ".repeat(500) + importantTail

        val progress = normalizeSubagentProgressText(original)

        assertTrue(progress.length > 160)
        assertTrue(progress.endsWith(importantTail))
        assertTrue(!progress.endsWith("..."))
    }

    @Test
    fun `progress text normalizes whitespace without dropping content`() {
        assertEquals(
            "第一行 第二行 第三行",
            normalizeSubagentProgressText("  第一行\n\n第二行\t第三行  ")
        )
    }

    @Test
    fun `thinking keeps the final nonblank line with the existing whitespace rules`() {
        val samples = listOf(
            "", "\n", "\r\n\t \n", "single line", "\n  final line  \n\n",
            "first\r\n\tfinal\tline\r\n\t\n",
            "first\n\u00a0\u1680final\u2000line\u202f\u3000\n\u205f\n",
            "first\n\u0085\u200b\ufeff\n\u00a0\n",
            "first\n\u001c\u001d\u001e\u001f\u2028\u2029\n",
            "first\n\u000bfinal\u000cline\n",
            "first\n最后一行 🤖\n\t\n",
        )

        for (sample in samples) {
            assertEquals(legacyThinkingText(sample), compactSubagentThinkingText(sample))
            assertEquals(legacyProgressText(sample), normalizeSubagentProgressText(sample))
        }
    }

    @Test
    fun `thinking matches the original projection for varied unicode and empty lines`() {
        val random = Random(42)
        val fragments = listOf(
            "a", "结果", "🤖", "\n", "\r", " ", "\t", "\u000b", "\u000c",
            "\u001c", "\u0085", "\u00a0", "\u1680", "\u2000", "\u200b",
            "\u2028", "\u2029", "\u202f", "\u205f", "\u3000", "\ufeff",
        )

        repeat(500) {
            val sample = buildString {
                repeat(random.nextInt(1, 500)) {
                    append(fragments[random.nextInt(fragments.size)])
                }
            }
            assertEquals(legacyThinkingText(sample), compactSubagentThinkingText(sample))
            assertEquals(legacyProgressText(sample), normalizeSubagentProgressText(sample))
        }
    }

    @Test
    fun `thinking preserves a long final line after a large accumulated history`() {
        val importantTail = "完整结论：/workspace/reports/final.md 🤖"
        val finalLine = "详细结果\t".repeat(5_000) + importantTail
        val original = "早先的思考内容\n".repeat(50_000) + finalLine + "\n \n\u3000\n"

        val projected = compactSubagentThinkingText(original)

        assertEquals(legacyThinkingText(original), projected)
        assertEquals(legacyProgressText(finalLine), projected)
        assertTrue(projected.endsWith(importantTail))
    }

    private fun legacyProgressText(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun legacyThinkingText(text: String): String = legacyProgressText(
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull().orEmpty()
    )
}
