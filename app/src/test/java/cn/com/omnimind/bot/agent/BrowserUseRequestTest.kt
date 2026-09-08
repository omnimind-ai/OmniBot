package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUseRequestTest {

    @Test
    fun `browser action does not require a private display title`() {
        val request = BrowserUseRequest.fromJson(buildJsonObject {
            put("action", "get_page_info")
        })

        assertEquals(BrowserUseAction.GET_PAGE_INFO, request.action)
        assertEquals("get_page_info", request.toolTitle)
    }

    @Test
    fun `browser keeps an optional caller supplied display title`() {
        val request = BrowserUseRequest.fromJson(buildJsonObject {
            put("action", "navigate")
            put("url", "https://example.com")
            put("tool_title", "Read documentation")
        })

        assertEquals("Read documentation", request.toolTitle)
    }
}
