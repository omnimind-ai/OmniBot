package cn.com.omnimind.bot.ui.nativehome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabletPanePreferencesTest {
    /** The Flutter chat saves with `StorageService.setDouble`; the native shell must read those values. */
    @Test fun readsAndWritesTheFlutterDoubleEncoding() {
        val flutterValue = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + "312.5"
        assertEquals(312.5f, TabletPanePreferences.decode(flutterValue))
        assertEquals(flutterValue, TabletPanePreferences.encode(312.5f))
    }

    @Test fun ignoresMissingOrInvalidValues() {
        assertNull(TabletPanePreferences.decode(null))
        assertNull(TabletPanePreferences.decode("312.5"))
        assertNull(TabletPanePreferences.decode("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + "NaN"))
        assertNull(TabletPanePreferences.decode("VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu" + "-4"))
    }
}
