package cn.com.omnimind.baselib.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguageMode(val storageValue: String) {
    SYSTEM("system"),
    ZH_HANS("zhHans"),
    EN("en");

    companion object {
        fun fromStorageValue(raw: String?): AppLanguageMode {
            val normalized = raw?.trim()
            return entries.firstOrNull { it.storageValue == normalized } ?: SYSTEM
        }
    }
}

enum class PromptLocale(
    val tag: String,
    val locale: Locale
) {
    ZH_CN("zh-CN", Locale.SIMPLIFIED_CHINESE),
    EN_US("en-US", Locale.US);

    companion object {
        fun fromTag(raw: String?): PromptLocale? {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return when (normalized) {
                "zh", "zh-cn", "zh_hans", "zh-hans" -> ZH_CN
                "en", "en-us" -> EN_US
                else -> null
            }
        }
    }
}

data class LocalizedText(
    val zhCN: String,
    val enUS: String
) {
    fun resolve(locale: PromptLocale): String {
        return when (locale) {
            PromptLocale.ZH_CN -> zhCN
            PromptLocale.EN_US -> enUS
        }
    }

    fun resolve(context: Context): String = resolve(AppLocaleManager.resolvePromptLocale(context))
}

object AppLocaleManager {
    private const val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
    private const val FLUTTER_LANGUAGE_KEY = "flutter.language_option"

    fun readStoredLanguageMode(context: Context): AppLanguageMode {
        val prefs = context.applicationContext.getSharedPreferences(
            FLUTTER_PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return AppLanguageMode.fromStorageValue(prefs.getString(FLUTTER_LANGUAGE_KEY, null))
    }

    fun resolvePromptLocale(context: Context): PromptLocale {
        return resolvePromptLocale(
            mode = readStoredLanguageMode(context),
            systemLocale = systemLocale()
        )
    }

    fun resolvePromptLocale(
        mode: AppLanguageMode,
        systemLocale: Locale
    ): PromptLocale {
        return when (mode) {
            AppLanguageMode.ZH_HANS -> PromptLocale.ZH_CN
            AppLanguageMode.EN -> PromptLocale.EN_US
            AppLanguageMode.SYSTEM -> normalize(systemLocale)
        }
    }

    fun currentPromptLocale(): PromptLocale {
        return normalize(Locale.getDefault())
    }

    fun currentLocale(context: Context): Locale {
        return resolvePromptLocale(context).locale
    }

    fun currentLocale(): Locale {
        return currentPromptLocale().locale
    }

    fun isEnglish(context: Context): Boolean {
        return resolvePromptLocale(context) == PromptLocale.EN_US
    }

    fun isEnglish(): Boolean {
        return currentPromptLocale() == PromptLocale.EN_US
    }

    fun brandName(context: Context): String {
        return brandName(resolvePromptLocale(context))
    }

    fun brandName(): String {
        return brandName(currentPromptLocale())
    }

    fun brandName(locale: PromptLocale): String {
        return when (locale) {
            PromptLocale.ZH_CN -> "小万"
            PromptLocale.EN_US -> "Omnibot"
        }
    }

    fun applyAppLocale(context: Context): Locale {
        val locale = currentLocale(context)
        Locale.setDefault(locale)
        val resources = context.applicationContext.resources
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return locale
    }

    fun localizedContext(context: Context): Context {
        val locale = currentLocale(context)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(configuration)
    }

    fun systemLocale(): Locale {
        // Application resources may already contain our explicit language override.
        val configuration = android.content.res.Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            configuration.locale ?: Locale.getDefault()
        }
    }

    private fun normalize(locale: Locale): PromptLocale {                 
        return if (locale.language.lowercase() == "zh") {  
            PromptLocale.ZH_CN
        } else {
            PromptLocale.EN_US
        }                                                                 
    }
}
