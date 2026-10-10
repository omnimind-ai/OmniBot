package cn.com.omnimind.bot.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import cn.com.omnimind.bot.activity.StartupThemeResolver
import java.util.Locale

/**
 * Opens web pages from native screens (batch 5g). Documentation opens in a
 * Custom Tab tinted with the app's page color, so it stays in the app's
 * task and back returns to the page that opened it; without a Custom Tabs
 * browser it falls back to any browser.
 */
internal object WebLinks {
    private const val USER_GUIDE_ZH = "https://omnimind-ai.github.io/OmniBot-Docs"
    private const val USER_GUIDE_EN = "https://omnimind-ai.github.io/OmniBot-Docs/en/"

    /** Dart `UserGuidePage`: English docs for an English UI, Chinese otherwise. */
    fun userGuideUrl(locale: Locale): String = if (locale.language == "en") USER_GUIDE_EN else USER_GUIDE_ZH

    /** False when [url] is not http(s) or nothing can show it. */
    fun open(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
            ?.takeIf { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrBlank() } ?: return false
        val dark = StartupThemeResolver.isDark(context)
        val toolbar = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(if (dark) DARK_PAGE else LIGHT_PAGE)
            .build()
        val tab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setColorScheme(if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT)
            .setDefaultColorSchemeParams(toolbar)
            .build()
        return try {
            tab.launchUrl(context, uri)
            true
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
    }

    // OmniPalette.page, so the tab's toolbar continues the page it was opened from.
    private const val LIGHT_PAGE = 0xFFF4F7FB.toInt()
    private const val DARK_PAGE = 0xFF151617.toInt()
}
