package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserObservationScriptsTest {
    @Test
    fun `collection observation has no fixed item or text sampling`() {
        val script = BrowserObservationScripts.scrollAndCollect("\".result\"")

        assertTrue(script.contains("items: items"))
        assertFalse(script.contains("slice(0,"))
    }

    @Test
    fun `element and backbone observations retain all selected fields`() {
        val elements = BrowserObservationScripts.findElements("\"a\"")
        val backbone = BrowserObservationScripts.backbone(maxDepth = 9)

        assertFalse(elements.contains("slice(0,"))
        assertTrue(backbone.contains("depth > 9"))
        assertFalse(backbone.contains("slice(0,"))
    }
}
