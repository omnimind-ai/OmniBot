package cn.com.omnimind.bot.activity

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater

enum class AppIconVariant(
    val storageValue: String,
    val launcherActivity: Class<out LauncherActivity>,
    @StyleRes val lightSplashThemeRes: Int,
    @StyleRes val darkSplashThemeRes: Int,
    @DrawableRes val mipmapRes: Int,
    val defaultEnabledInManifest: Boolean = false,
) {
    SPARKLE(
        storageValue = "sparkle",
        launcherActivity = LauncherActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Dark,
        mipmapRes = R.mipmap.ic_launcher,
        defaultEnabledInManifest = true,
    ),
    DOT(
        storageValue = "dot",
        launcherActivity = LauncherDotActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Dot_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Dot_Dark,
        mipmapRes = R.mipmap.ic_launcher_dot,
    ),
    CURSOR(
        storageValue = "cursor",
        launcherActivity = LauncherCursorActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Cursor_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Cursor_Dark,
        mipmapRes = R.mipmap.ic_launcher_cursor,
    ),
    WAVE(
        storageValue = "wave",
        launcherActivity = LauncherWaveActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Wave_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Wave_Dark,
        mipmapRes = R.mipmap.ic_launcher_wave,
    ),
    OPEN(
        storageValue = "open",
        launcherActivity = LauncherOpenActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Open_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Open_Dark,
        mipmapRes = R.mipmap.ic_launcher_open,
    ),
    CLASSIC(
        storageValue = "classic",
        launcherActivity = LauncherClassicActivity::class.java,
        lightSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Classic_Light,
        darkSplashThemeRes = R.style.Theme_OmnibotApp_Splash_Classic_Dark,
        mipmapRes = R.mipmap.ic_launcher_classic,
    );

    companion object {
        val DEFAULT = SPARKLE

        fun parseStorageValue(raw: String?): AppIconVariant? {
            val normalized = raw?.trim()?.lowercase()
            return entries.firstOrNull { it.storageValue == normalized }
        }

        fun fromStorageValue(raw: String?): AppIconVariant = parseStorageValue(raw) ?: DEFAULT
    }
}

object AppIconManager {
    private const val TAG = "AppIconManager"
    private const val FLUTTER_SHARED_PREFS_NAME = "FlutterSharedPreferences"
    internal const val KEY_APP_ICON_VARIANT = "flutter.app_icon_variant"

    private fun readStoredVariant(context: Context): AppIconVariant {
        val raw = runCatching {
            context
                .getSharedPreferences(FLUTTER_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_APP_ICON_VARIANT, null)
        }.getOrNull()
        return AppIconVariant.fromStorageValue(raw)
    }

    @Synchronized
    fun getActiveVariant(context: Context): AppIconVariant {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val stored = readStoredVariant(appContext)
        fun isLauncherEnabled(variant: AppIconVariant): Boolean {
            val component = ComponentName(appContext, variant.launcherActivity)
            val state = runCatching { pm.getComponentEnabledSetting(component) }.getOrNull()
            return state != null && isEnabled(variant, state)
        }

        if (isLauncherEnabled(stored)) return stored
        return AppIconVariant.entries.firstOrNull { it != stored && isLauncherEnabled(it) } ?: stored
    }

    @Synchronized
    fun applyVariant(context: Context, variant: AppIconVariant): AppIconVariant {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val prefs = appContext.getSharedPreferences(FLUTTER_SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        // Older Android releases apply each change separately. Keep a launcher enabled
        // by placing the requested activity first, then disabling the other entries.
        val orderedVariants = listOf(variant) + AppIconVariant.entries.filter { it != variant }
        val changes = orderedVariants.mapNotNull { candidate ->
            val component = ComponentName(appContext, candidate.launcherActivity)
            val previousState = pm.getComponentEnabledSetting(component)
            val shouldEnable = candidate == variant
            if (isEnabled(candidate, previousState) == shouldEnable) return@mapNotNull null
            val state = if (shouldEnable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            ComponentChange(component, previousState, state)
        }

        if (changes.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.setComponentEnabledSettings(changes.map { change ->
                    PackageManager.ComponentEnabledSetting(
                        change.component,
                        change.state,
                        PackageManager.DONT_KILL_APP,
                    )
                })
            } else {
                applySequentially(pm, changes)
            }
        }

        // Native startup and Flutter's SharedPreferences cache use the same preference.
        val previousVariant = readStoredVariant(appContext)
        if (previousVariant != variant) {
            prefs.edit().putString(KEY_APP_ICON_VARIANT, variant.storageValue).apply()
        }
        if (changes.isNotEmpty() || previousVariant != variant) {
            runCatching { QuickLogWidgetUpdater.updateAll(appContext) }.onFailure { error ->
                OmniLog.w(TAG, "Failed to refresh widget launcher intents: ${error.message}")
            }
        }
        return variant
    }

    fun createLauncherIntent(context: Context): Intent {
        return Intent(context, getActiveVariant(context).launcherActivity)
    }

    fun applyTaskDescription(activity: Activity, variant: AppIconVariant = getActiveVariant(activity)) {
        runCatching {
            val label = activity.getString(R.string.omni_app_name)
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription.Builder()
                    .setLabel(label)
                    .setIcon(variant.mipmapRes)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label, variant.mipmapRes)
            }
            activity.setTaskDescription(description)
        }.onFailure { error ->
            OmniLog.w(TAG, "Failed to update task description icon: ${error.message}")
        }
    }

    private fun isEnabled(variant: AppIconVariant, state: Int): Boolean = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> variant.defaultEnabledInManifest
        else -> false
    }

    private fun applySequentially(pm: PackageManager, changes: List<ComponentChange>) {
        val applied = mutableListOf<ComponentChange>()
        try {
            for (change in changes) {
                pm.setComponentEnabledSetting(change.component, change.state, PackageManager.DONT_KILL_APP)
                applied += change
            }
        } catch (error: RuntimeException) {
            try {
                // Restore previously enabled entries before removing the new one.
                for (change in applied.asReversed()) {
                    pm.setComponentEnabledSetting(
                        change.component,
                        change.previousState,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    private data class ComponentChange(val component: ComponentName, val previousState: Int, val state: Int)
}
