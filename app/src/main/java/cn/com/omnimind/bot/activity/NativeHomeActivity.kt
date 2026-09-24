package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.com.omnimind.bot.ui.nativehome.LegacyHomeNavigator
import cn.com.omnimind.bot.ui.nativehome.NativeHomeViewModel
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.bot.manager.AppPermissionAccess
import cn.com.omnimind.bot.preferences.RecentTasksVisibility
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.ui.settings.NativePreferencesViewModel
import cn.com.omnimind.nativeui.settings.AppearanceScreen
import cn.com.omnimind.nativeui.settings.HomePreferencesScreen
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.bot.ui.settings.NativeAboutRoute
import cn.com.omnimind.bot.ui.settings.NativeAboutViewModel
import cn.com.omnimind.bot.ui.settings.NativePermissionsRoute
import cn.com.omnimind.bot.ui.settings.NativePermissionsViewModel
import cn.com.omnimind.bot.ui.settings.NativeMiscSettingsRoute
import cn.com.omnimind.bot.ui.settings.NativeMiscSettingsViewModel
import cn.com.omnimind.bot.ui.settings.NativeBackgroundViewModel
import cn.com.omnimind.bot.ui.settings.NativeBackgroundSettingsRoute
import cn.com.omnimind.bot.ui.settings.NativePetSettingsRoute
import cn.com.omnimind.bot.ui.settings.NativePetSettingsViewModel
import cn.com.omnimind.nativeui.NativeHomeApp
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.ThemePreference

/** Opt-in host for the first native slice. MainActivity still owns the Flutter compatibility pages. */
class NativeHomeActivity : ComponentActivity() {
    private lateinit var viewModel: NativeHomeViewModel
    private lateinit var backgroundViewModel: NativeBackgroundViewModel
    private val petViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativePetSettingsViewModel.Factory(this))[NativePetSettingsViewModel::class.java]
    }
    private var languageOption: String? = null
    private var localeTag: String? = null

    override fun attachBaseContext(newBase: Context) {
        languageOption = readLanguage(newBase)
        val locale = resolveNativeHomeLocale(languageOption)
        localeTag = locale.toLanguageTag()
        val localized = newBase.createConfigurationContext(
            Configuration(newBase.resources.configuration).apply { setLocale(locale) },
        )
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(StartupThemeResolver.resolveSplashTheme(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TaskRuntimeSettings.attachActivity(this)
        runCatching { RecentTasksVisibility.applySaved(this) }
            .onFailure { OmniLog.w("NativeHomeActivity", "Unable to apply recent-task visibility") }
        viewModel = ViewModelProvider(this, NativeHomeViewModel.Factory(this))[NativeHomeViewModel::class.java]
        val about = ViewModelProvider(this, NativeAboutViewModel.Factory(this))[NativeAboutViewModel::class.java]
        val permissions = ViewModelProvider(this, NativePermissionsViewModel.Factory(this))[NativePermissionsViewModel::class.java]
        val preferences = ViewModelProvider(this, NativePreferencesViewModel.Factory(this))[NativePreferencesViewModel::class.java]
        val miscSettings = ViewModelProvider(this, NativeMiscSettingsViewModel.Factory(this))[NativeMiscSettingsViewModel::class.java]
        backgroundViewModel = ViewModelProvider(this, NativeBackgroundViewModel.Factory(this))[NativeBackgroundViewModel::class.java]
        val permissionAccess = AppPermissionAccess(applicationContext)
        val navigator = LegacyHomeNavigator(this)
        val actions = NativeHomeActions(
            open = navigator::open,
            setLocalServiceEnabled = viewModel::setLocalServiceEnabled,
            refreshLocalServiceToken = viewModel::refreshLocalServiceToken,
            setArchived = viewModel::setArchived,
            setSectionExpanded = viewModel::setSectionExpanded,
            invokeWebAction = viewModel::invokeWebAction,
            refresh = viewModel::refresh,
        )
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val savedPreferences by preferences.state.collectAsStateWithLifecycle()
            val backgroundState by backgroundViewModel.state.collectAsStateWithLifecycle()
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let(backgroundViewModel.actions.importImage)
            }
            val petPackagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let(petViewModel.actions.importPackage)
            }
            LaunchedEffect(savedPreferences.loaded, savedPreferences.theme, savedPreferences.language) {
                if (savedPreferences.loaded) {
                    if (languageOption != savedPreferences.language.storageValue) recreate()
                    else StartupThemeResolver.applyApplicationNightMode(this@NativeHomeActivity, savedPreferences.theme.name.lowercase())
                }
            }
            val theme = if (savedPreferences.loaded) savedPreferences.theme else state.theme
            val systemDark = isSystemInDarkTheme()
            val dark = when (theme) {
                ThemePreference.System -> systemDark
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.isNavigationBarContrastEnforced = false
            }
            LaunchedEffect(state.error) {
                state.error?.let { Toast.makeText(this@NativeHomeActivity, it, Toast.LENGTH_LONG).show() }
            }
            LaunchedEffect(state.pendingDestination) {
                state.pendingDestination?.let {
                    viewModel.consumeDestination()
                    navigator.open(it)
                }
            }
            NativeHomeApp(
                state = state.copy(theme = theme),
                backgroundState = backgroundState,
                actions = actions,
                about = { onBack -> NativeAboutRoute(about, this@NativeHomeActivity, navigator::open, onBack) },
                appearance = { onBack, onBackground -> AppearanceScreen(savedPreferences, preferences.actions,
                    onBackground = onBackground, onBack = onBack) },
                background = { onBack, onPet -> NativeBackgroundSettingsRoute(backgroundViewModel,
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPet = onPet, onBack = onBack) },
                pet = { onBack -> NativePetSettingsRoute(petViewModel,
                    onPickPackage = { petPackagePicker.launch(arrayOf(
                        "application/zip", "application/x-zip-compressed", "application/octet-stream",
                    )) },
                    onBack = onBack) },
                homePreferences = { onBack -> HomePreferencesScreen(savedPreferences, preferences.actions, onBack) },
                miscellaneous = { onBack, onHomeSettings -> NativeMiscSettingsRoute(
                    miscSettings, permissionAccess, navigator::open, onHomeSettings, onBack,
                ) },
                permissions = { onBack -> NativePermissionsRoute(permissions, permissionAccess, this@NativeHomeActivity, onBack) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        TaskRuntimeSettings.onActivityResumed(this)
        if (languageOption != readLanguage(this) || localeTag != resolveNativeHomeLocale(readLanguage(this)).toLanguageTag()) {
            recreate()
            return
        }
        viewModel.refresh()
        backgroundViewModel.refresh()
    }

    override fun onPause() {
        if (::backgroundViewModel.isInitialized) {
            // Keep the latest slider/text draft when the Activity moves behind a compatibility page.
            backgroundViewModel.flush()
        }
        TaskRuntimeSettings.onActivityPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        TaskRuntimeSettings.detachActivity(this)
        super.onDestroy()
    }

    private fun readLanguage(context: Context): String =
        cn.com.omnimind.baselib.i18n.AppLocaleManager.readStoredLanguageMode(context).storageValue
}
