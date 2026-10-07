package cn.com.omnimind.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dart `_loadSharedProviderModelIds` merge rules (5d-1c). */
class NativeChatModelCatalogTest {
    @Test
    fun `discovered then manual ids, trimmed and deduplicated`() {
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner", "custom"),
            NativeChatModelCatalog.mergeCatalog(listOf(" deepseek-chat", "deepseek-reasoner"), listOf("custom", "deepseek-chat", " "), "x"),
        )
    }

    @Test
    fun `a cold catalog keeps the bound model visible`() {
        assertEquals(listOf("bound"), NativeChatModelCatalog.mergeCatalog(emptyList(), emptyList(), " bound "))
        assertEquals(emptyList<String>(), NativeChatModelCatalog.mergeCatalog(emptyList(), emptyList(), null))
    }
}
