package cn.com.omnimind.nativeui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTextTest {
    @Test
    fun `AnsiTextSpanBuilder applies color and bold to sgr spans`() {
        val text = ansiAnnotatedString("\u001B[31;1merror\u001B[0m")
        assertEquals("error", text.text)
        val style = text.spanStyles.single()
        assertEquals(0 until 5, style.start until style.end)
        assertEquals(FontWeight.Bold, style.item.fontWeight)
        assertEquals(Color(0xFFE06C75), style.item.color)
    }

    @Test
    fun `reset returns to the base style and unsupported sequences are dropped`() {
        val text = ansiAnnotatedString("\u001B[2Kok \u001B[32mpass\u001B[39m done")
        assertEquals("ok pass done", text.text)
        val style = text.spanStyles.single()
        assertEquals("pass", text.text.substring(style.start, style.end))
        assertEquals(Color(0xFF98C379), style.item.color)
    }

    @Test
    fun `plain text has no spans`() {
        val text = ansiAnnotatedString("hello\nworld")
        assertEquals("hello\nworld", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }
}
