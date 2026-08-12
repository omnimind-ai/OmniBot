package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserBridgeSecurityTest {
    @Test
    fun bridgeTokenMustMatchExactly() {
        assertTrue(BrowserBridgeSecurity.tokenMatches("expected-token", "expected-token"))
        assertFalse(BrowserBridgeSecurity.tokenMatches("expected-token", "other-token"))
        assertFalse(BrowserBridgeSecurity.tokenMatches("expected-token", null))
    }

    @Test
    fun inlineDataUrlHasEncodedSizeLimit() {
        assertTrue(BrowserBridgeSecurity.acceptsInlineDataUrl("data:text/plain,hello"))
        assertFalse(BrowserBridgeSecurity.acceptsInlineDataUrl("https://example.test/file"))
        assertFalse(
            BrowserBridgeSecurity.acceptsInlineDataUrl(
                "data:application/octet-stream," +
                    "x".repeat(BrowserBridgeSecurity.MAX_INLINE_DATA_URL_CHARS),
            ),
        )
    }
}
