package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
