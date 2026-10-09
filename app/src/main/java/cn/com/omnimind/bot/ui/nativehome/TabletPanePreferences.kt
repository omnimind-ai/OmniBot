package cn.com.omnimind.bot.ui.nativehome

import android.content.SharedPreferences
import cn.com.omnimind.nativeui.home.TabletPaneWidths

/**
 * The tablet pane widths (batch 5e-7d), stored where the Flutter chat keeps
 * them (`chat_hd_pad_left_pane_width` / `..._right_...` through
 * `StorageService.setDouble`), so both pages open at the same widths.
 * `shared_preferences_android` stores a double as a prefixed string.
 */
internal class TabletPanePreferences(private val preferences: SharedPreferences) {
    fun read() = TabletPaneWidths(readDouble(LEFT_KEY), readDouble(RIGHT_KEY))

    fun write(widths: TabletPaneWidths) {
        preferences.edit().apply {
            widths.left?.let { putString(LEFT_KEY, encode(it)) }
            widths.right?.let { putString(RIGHT_KEY, encode(it)) }
        }.apply()
    }

    private fun readDouble(key: String): Float? =
        decode(runCatching { preferences.getString(key, null) }.getOrNull())

    companion object {
        const val LEFT_KEY = "flutter.chat_hd_pad_left_pane_width"
        const val RIGHT_KEY = "flutter.chat_hd_pad_right_pane_width"
        private const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"

        fun encode(value: Float): String = DOUBLE_PREFIX + value.toDouble().toString()

        fun decode(raw: String?): Float? =
            raw?.takeIf { it.startsWith(DOUBLE_PREFIX) }?.removePrefix(DOUBLE_PREFIX)?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0 }?.toFloat()
    }
}
