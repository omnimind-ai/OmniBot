package cn.com.omnimind.bot.agent

import java.security.MessageDigest

internal object BrowserBridgeSecurity {
    const val MAX_INLINE_DOWNLOAD_BYTES = 10 * 1024 * 1024
    const val MAX_INLINE_DATA_URL_CHARS = 14 * 1024 * 1024
    const val MAX_USERSCRIPT_KEY_CHARS = 256
    const val MAX_USERSCRIPT_VALUE_CHARS = 256 * 1024

    fun tokenMatches(expected: String, supplied: String?): Boolean {
        if (expected.isBlank() || supplied.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    fun acceptsInlineDataUrl(raw: String?): Boolean {
        return raw != null &&
            raw.length <= MAX_INLINE_DATA_URL_CHARS &&
            raw.trimStart().startsWith("data:")
    }
}
