package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHostStoreTest {
    @Test
    fun normalizeHttpUrlDropsDefaultPortsAndLowercasesHost() {
        assertEquals(
            "https://example.com/path?q=1",
            BrowserUrlNormalizer.normalizeHttpUrl("HTTPS://Example.com:443/path?q=1")
        )
        assertEquals(
            "http://example.com/",
            BrowserUrlNormalizer.normalizeHttpUrl("HTTP://Example.com:80")
        )
        assertEquals(null, BrowserUrlNormalizer.normalizeHttpUrl("about:blank"))
        assertEquals(null, BrowserUrlNormalizer.normalizeHttpUrl("data:text/plain,hello"))
    }

    @Test
    fun bookmarksHistoryAndDesktopModePersistWithNormalization() {
        val store = BrowserHostStore(
            workspaceId = "workspace",
            keyValueStore = FakeBrowserHostKeyValueStore(),
            clock = { 1000L }
        )

        val bookmarked = store.toggleBookmark(
            "https://example.com:443/path",
            "Example Page"
        )
        assertTrue(bookmarked)
        assertTrue(store.isBookmarked("https://example.com/path"))
        assertEquals("https://example.com/path", store.listBookmarks().single().url)

        store.recordVisit(
            "https://example.com:443/path",
            "Visited Title",
            isReload = false
        )
        assertEquals(1, store.listHistory().size)
        assertEquals("Visited Title", store.listHistory().single().title)

        store.setDesktopModeEnabled(false)
        assertFalse(store.getDesktopModeEnabled())
    }

    @Test
    fun browserHistoryRetainsVisitsBeyondTheFormerHostCap() {
        var now = 0L
        val store = BrowserHostStore(
            workspaceId = "workspace",
            keyValueStore = FakeBrowserHostKeyValueStore(),
            clock = { ++now }
        )

        repeat(512) { index ->
            store.recordVisit(
                url = "https://example.com/page-$index",
                title = "Page $index",
                isReload = false
            )
        }

        val history = store.listHistory()
        assertEquals(512, history.size)
        assertTrue(history.any { it.url == "https://example.com/page-0" })
        assertTrue(history.any { it.url == "https://example.com/page-511" })
    }
}

private class FakeBrowserHostKeyValueStore : BrowserHostKeyValueStore {
    private val strings = linkedMapOf<String, String>()
    private val booleans = linkedMapOf<String, Boolean>()
    private val longs = linkedMapOf<String, Long>()

    override fun getString(key: String): String? = strings[key]

    override fun putString(
        key: String,
        value: String?
    ) {
        if (value == null) {
            strings.remove(key)
        } else {
            strings[key] = value
        }
    }

    override fun getBoolean(
        key: String,
        defaultValue: Boolean
    ): Boolean = booleans[key] ?: defaultValue

    override fun putBoolean(
        key: String,
        value: Boolean
    ) {
        booleans[key] = value
    }

    override fun getLong(
        key: String,
        defaultValue: Long
    ): Long = longs[key] ?: defaultValue

    override fun putLong(
        key: String,
        value: Long
    ) {
        longs[key] = value
    }
}
