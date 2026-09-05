package cn.com.omnimind.bot.webchat

import android.content.ContextWrapper
import cn.com.omnimind.bot.agent.BrowserUseAction
import cn.com.omnimind.bot.agent.BrowserUseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserMirrorServiceTest {

    @Test
    fun `browser mirror forwards explicit traversal settings without host clamping`() {
        val request = buildRequest(
            mapOf(
                "action" to "get_backbone",
                "max_depth" to 73
            )
        )

        assertEquals(BrowserUseAction.GET_BACKBONE, request.action)
        assertEquals(73, request.maxDepth)
    }

    @Test
    fun `browser mirror leaves optional browser resources unspecified`() {
        val request = buildRequest(
            mapOf(
                "action" to "navigate",
                "url" to "https://example.com"
            )
        )

        assertNull(request.amount)
        assertNull(request.maxDepth)
        assertNull(request.scrollCount)
    }

    private fun buildRequest(arguments: Map<String, Any?>): BrowserUseRequest {
        val service = BrowserMirrorService(ContextWrapper(null))
        return service.buildRequest(arguments)
    }
}
