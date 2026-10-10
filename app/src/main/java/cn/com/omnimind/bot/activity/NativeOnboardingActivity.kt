package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.manager.AppPermissionAccess
import cn.com.omnimind.bot.ui.nativehome.LegacyHomeNavigator
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.bot.ui.onboarding.NativeOnboardingViewModel
import cn.com.omnimind.bot.ui.settings.NativePermissionsViewModel
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.ThemePreference
import cn.com.omnimind.nativeui.onboarding.OnboardingScreen
import cn.com.omnimind.nativeui.settings.PermissionSetting
import cn.com.omnimind.nativeui.settings.PermissionsActions
import cn.com.omnimind.nativeui.theme.OmniTheme

/**
 * Native first-use onboarding (batch 5f-1b). The launcher opens it while
 * `welcome_completed` is unset and native Home is on; completion opens
 * native Home. With [EXTRA_REPLAY] it is the settings "quick start" again and
 * only closes. Account sign-in is still the Flutter page.
 */
class NativeOnboardingActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val locale = resolveNativeHomeLocale(AppLocaleManager.readStoredLanguageMode(newBase).storageValue)
        super.attachBaseContext(newBase.createConfigurationContext(
            Configuration(newBase.resources.configuration).apply { setLocale(locale) },
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        AppEntryBehaviors.applyResponsiveOrientation(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val replay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        // A first launch is an app open (terminal auto-start, update check); a replay is not.
        if (savedInstanceState == null && !replay) AppEntryBehaviors.onAppOpen(this, lifecycleScope)
        val viewModel = ViewModelProvider(this, NativeOnboardingViewModel.Factory(this, replay))[NativeOnboardingViewModel::class.java]
        val permissions = ViewModelProvider(this, NativePermissionsViewModel.Factory(this))[NativePermissionsViewModel::class.java]
        val access = AppPermissionAccess(applicationContext)
        val navigator = LegacyHomeNavigator(this)
        val theme = when (StartupThemeResolver.readStoredThemeMode(this)) {
            StartupThemeResolver.StartupThemeMode.DARK -> ThemePreference.Dark
            StartupThemeResolver.StartupThemeMode.LIGHT -> ThemePreference.Light
            StartupThemeResolver.StartupThemeMode.SYSTEM -> ThemePreference.System
        }
        val actions = viewModel.actions.copy(openAccount = { navigator.open(LegacyDestination.Page.Account) })
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val permissionState by permissions.state.collectAsStateWithLifecycle()
            val dark = when (theme) {
                ThemePreference.System -> isSystemInDarkTheme()
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.isNavigationBarContrastEnforced = false
            }
            // Grants happen in system settings; read them again on every return.
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissions.refresh() }
            LaunchedEffect(permissionState.pendingSetting) {
                val setting = permissionState.pendingSetting ?: return@LaunchedEffect
                permissions.consumeSetting()
                try {
                    when (setting) {
                        PermissionSetting.Background -> access.openBatterySettings(this@NativeOnboardingActivity)
                        PermissionSetting.Overlay -> access.openOverlaySettings(this@NativeOnboardingActivity)
                        PermissionSetting.InstalledApps -> access.openInstalledAppsSettings(this@NativeOnboardingActivity)
                        PermissionSetting.PublicStorage -> access.openPublicStorageSettings(this@NativeOnboardingActivity)
                        PermissionSetting.Accessibility -> access.openAccessibilitySettings()
                        PermissionSetting.Shizuku -> Unit
                    }
                } catch (error: Exception) {
                    permissions.settingFailed(error)
                }
            }
            LaunchedEffect(state.finished) {
                if (state.finished) leave(replay)
            }
            val permissionActions = remember(permissions) {
                PermissionsActions(
                    refresh = permissions::refresh,
                    select = permissions::select,
                    setNotificationsEnabled = permissions::setNotifications,
                    dismissPrompt = permissions::dismissPrompt,
                    confirmPrompt = permissions::confirmPrompt,
                )
            }
            OmniTheme(theme) {
                OnboardingScreen(state, permissionState, permissionActions, actions, onExit = ::finish)
            }
        }
    }

    private fun leave(replay: Boolean) {
        if (!replay) {
            // Replaces the whole task, so no onboarding page is left below Home to come back to.
            startActivity(Intent(this, NativeHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(NativeHomeActivity.EXTRA_FIRST_USE_TOUR, true))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }

    companion object {
        /** Opened from settings: back on the first page and completion close it. */
        const val EXTRA_REPLAY = "onboarding_replay"
    }
}
