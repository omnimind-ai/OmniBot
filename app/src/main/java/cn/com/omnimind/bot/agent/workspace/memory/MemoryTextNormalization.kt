package cn.com.omnimind.bot.agent

import java.util.Locale

// Search/index identity is data normalization, not UI-language formatting.
internal fun normalizeMemoryText(text: String): String =
    text.lowercase(Locale.ROOT).replace(Regex("\\s+"), "").trim()

internal fun tokenizeMemoryText(text: String): List<String> =
    text.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
