package cn.com.omnimind.bot.activity

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import cn.com.omnimind.bot.R

object StartupThemeResolver {
    private const val FLUTTER_SHARED_PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_THEME_OPTION = "flutter.theme_option"

    enum class StartupThemeMode(val storageValue: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        companion object {
            fun fromStorageValue(raw: String?): StartupThemeMode {
                return entries.firstOrNull { it.storageValue == raw?.trim() } ?: SYSTEM
            }
        }
    }

    /** Whether the stored theme preference resolves to dark right now. */
    fun isDark(context: Context): Boolean = when (readStoredThemeMode(context)) {
        StartupThemeMode.DARK -> true
        StartupThemeMode.LIGHT -> false
        StartupThemeMode.SYSTEM -> isSystemDark(context)
    }

    fun resolveSplashTheme(context: Context): Int {
        return if (isDark(context)) {
            R.style.Theme_OmnibotApp_Splash_Dark
        } else {
            R.style.Theme_OmnibotApp_Splash
        }
    }

    fun applyStoredApplicationNightMode(context: Context) {
        applyApplicationNightMode(context, readStoredThemeMode(context))
    }

    fun applyApplicationNightMode(context: Context, rawMode: String?) {
        applyApplicationNightMode(context, StartupThemeMode.fromStorageValue(rawMode))
    }

    fun readStoredThemeMode(context: Context): StartupThemeMode {
        val storedPreference = runCatching {
            context.applicationContext.getSharedPreferences(
                FLUTTER_SHARED_PREFS_NAME,
                Context.MODE_PRIVATE
            ).getString(KEY_THEME_OPTION, StartupThemeMode.SYSTEM.storageValue)
        }.getOrNull()

        return StartupThemeMode.fromStorageValue(storedPreference)
    }

    private fun applyApplicationNightMode(context: Context, mode: StartupThemeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val nightMode = when (mode) {
                StartupThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                StartupThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                StartupThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            }
            context.applicationContext
                .getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(nightMode)
            return
        }

        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                StartupThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                StartupThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                StartupThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun isSystemDark(context: Context): Boolean {
        val nightModeFlags =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }
}
