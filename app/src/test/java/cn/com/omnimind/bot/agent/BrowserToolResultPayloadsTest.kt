package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.browser.BrowserToolResultPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserToolResultPayloadsTest {
    @Test
    fun `long browser text remains complete in the tool payload`() {
        val text = buildString {
            repeat(25_000) { append(('a'.code + it % 26).toChar()) }
        }

        val payload = BrowserToolResultPayloads.text(
            text = text,
            extra = mapOf("selectorUsed" to "article")
        )

        assertEquals(text, payload["text"])
        assertEquals(text.length, payload["textLength"])
        assertFalse(payload.containsKey("textSnippet"))
    }

    @Test
    fun `browser collection keeps every collected item in the tool payload`() {
        val items = (1..101).map { "result-$it" }

        val payload = BrowserToolResultPayloads.collection(
            selectorUsed = ".result",
            items = items
        )

        assertEquals(101, payload["itemCount"])
        assertEquals(items, payload["items"])
        assertFalse(payload.containsKey("itemsPreview"))
    }
}
