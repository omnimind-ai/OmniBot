package cn.com.omnimind.bot.mcp

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebChatJsonRequestTest {
    @Test
    fun parsesHeterogeneousJsonObject() {
        val payload = parseWebChatJsonObject(
            """
            {
              "title": "Telegram",
              "enabled": true,
              "count": 3,
              "attachments": [{"name": "photo.jpg", "size": 42}],
              "metadata": {"source": "telegram"}
            }
            """.trimIndent()
        )

        assertEquals("Telegram", payload["title"])
        assertEquals(true, payload["enabled"])
        assertEquals(3.0, payload["count"])
        assertTrue(payload["attachments"] is List<*>)
        assertTrue(payload["metadata"] is Map<*, *>)
    }

    @Test
    fun rejectsBlankOrNonObjectBodies() {
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("") }
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("[]") }
        assertThrows(JsonSyntaxException::class.java) { parseWebChatJsonObject("null") }
    }
}
