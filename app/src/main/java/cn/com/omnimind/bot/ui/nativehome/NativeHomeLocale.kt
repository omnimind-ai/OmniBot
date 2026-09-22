package cn.com.omnimind.bot.ui.nativehome

import java.util.Locale

/** Matches AppLanguageMode/resolveAppLocale, including the persisted `zhHans` value. */
internal fun resolveNativeHomeLocale(stored: String?, system: Locale): Locale = when (stored?.trim()) {
    "zhHans" -> Locale.SIMPLIFIED_CHINESE
    "en" -> Locale.US
    else -> if (system.language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.US
}
