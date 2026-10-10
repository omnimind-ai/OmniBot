package cn.com.omnimind.bot.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WebLinksTest {
    @Test fun theGuideFollowsTheUiLanguage() {
        assertEquals("https://omnimind-ai.github.io/OmniBot-Docs/en/", WebLinks.userGuideUrl(Locale.ENGLISH))
        assertEquals("https://omnimind-ai.github.io/OmniBot-Docs", WebLinks.userGuideUrl(Locale.SIMPLIFIED_CHINESE))
        // Any other language reads the Chinese docs, as in Dart.
        assertEquals("https://omnimind-ai.github.io/OmniBot-Docs", WebLinks.userGuideUrl(Locale.JAPANESE))
    }
}
