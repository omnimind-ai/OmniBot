package cn.com.omnimind.bot.ui.nativehome

import cn.com.omnimind.baselib.i18n.AppLanguageMode
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import java.util.Locale

/** Reuse the existing locale owner, including the stored zhHans and system modes. */
internal fun resolveNativeHomeLocale(stored: String?, system: Locale = AppLocaleManager.systemLocale()): Locale =
    AppLocaleManager.resolvePromptLocale(AppLanguageMode.fromStorageValue(stored), system).locale
