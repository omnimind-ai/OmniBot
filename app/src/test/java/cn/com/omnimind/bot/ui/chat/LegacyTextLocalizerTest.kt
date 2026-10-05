package cn.com.omnimind.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyTextLocalizerTest {
    @Test
    fun `exact map keeps every Dart entry`() {
        assertEquals(423, LegacyTextLocalizer.exactEntryCount)
    }

    @Test
    fun `exact entries translate in english`() {
        assertEquals("Pet", LegacyTextLocalizer.localize("宠物", english = true))
        assertEquals("Settings", LegacyTextLocalizer.localize("设置", english = true))
        assertEquals(
            "[Only the most recent terminal output is shown]\n",
            LegacyTextLocalizer.localize("[只显示最近的部分终端输出]\n", english = true),
        )
        assertEquals("Hi, I'm Omnibot", LegacyTextLocalizer.localize("Hi，我是小万", english = true))
    }

    @Test
    fun `regex rewriters translate in english`() {
        assertEquals("MCP enabled: foo", LegacyTextLocalizer.localize("MCP 已开启：foo", english = true))
        assertEquals("3m 5s", LegacyTextLocalizer.localize("3 分 5 秒", english = true))
        assertEquals(
            "2 conversations · 1/4",
            LegacyTextLocalizer.localize("2 次对话 · 1/4", english = true),
        )
        assertEquals("Searching pdf skill", LegacyTextLocalizer.localize("正在搜索 pdf 技能", english = true))
        assertEquals("User: hi\n", LegacyTextLocalizer.localize("用户: hi\n", english = true))
        assertEquals(
            "I can't generate a reply right now. Please try again.",
            LegacyTextLocalizer.localize("暂时无法生成回复，请重试。  ", english = true),
        )
        // JavaScript `$` does not match before a trailing newline.
        assertEquals("MCP 已开启：foo\n", LegacyTextLocalizer.localize("MCP 已开启：foo\n", english = true))
    }

    @Test
    fun `chinese passes through unchanged`() {
        assertEquals("宠物", LegacyTextLocalizer.localize("宠物", english = false))
        assertEquals("3 分 5 秒", LegacyTextLocalizer.localize("3 分 5 秒", english = false))
        assertEquals("unknown", LegacyTextLocalizer.localize("unknown", english = true))
    }
}
