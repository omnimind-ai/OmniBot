package cn.com.omnimind.bot.activity

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import android.os.PersistableBundle
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
import androidx.compose.runtime.remember
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
import cn.com.omnimind.bot.ui.settings.NativeStorageUsageViewModel
import cn.com.omnimind.bot.ui.settings.NativeStorageUsageRoute
import cn.com.omnimind.bot.ui.settings.NativeLogsViewModel
import cn.com.omnimind.bot.ui.settings.NativeRequestLogsRoute
import cn.com.omnimind.bot.ui.settings.NativeRuntimeLogsRoute
import cn.com.omnimind.bot.ui.settings.NativeWorkspaceMemoryViewModel
import cn.com.omnimind.bot.ui.settings.NativeWorkspaceMemoryRoute
import cn.com.omnimind.bot.ui.settings.NativeSceneModelsViewModel
import cn.com.omnimind.bot.ui.settings.NativeSceneModelsRoute
import cn.com.omnimind.bot.ui.settings.NativeModelProviderViewModel
import cn.com.omnimind.bot.ui.settings.NativeModelProviderRoute
import cn.com.omnimind.bot.ui.settings.NativeRemoteMcpViewModel
import cn.com.omnimind.bot.ui.settings.NativeRemoteMcpRoute
import cn.com.omnimind.bot.ui.settings.NativeAgentsViewModel
import cn.com.omnimind.bot.ui.settings.NativeAgentsRoute
import cn.com.omnimind.bot.ui.settings.NativeAgentConfigViewModel
import cn.com.omnimind.bot.ui.settings.NativeAgentConfigRoute
import cn.com.omnimind.bot.ui.settings.NativeAlarmSettingsViewModel
import cn.com.omnimind.bot.ui.settings.NativeAlarmSettingsRoute
import cn.com.omnimind.bot.ui.settings.NativeOpenWithViewModel
import cn.com.omnimind.bot.ui.settings.NativeOpenWithRoute
import cn.com.omnimind.bot.ui.settings.NativeRemoteBridgeViewModel
import cn.com.omnimind.bot.ui.settings.NativeRemoteBridgeRoute
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
    private val workspaceMemoryViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeWorkspaceMemoryViewModel.Factory(this))[NativeWorkspaceMemoryViewModel::class.java]
    }
    private val sceneModelsViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeSceneModelsViewModel.Factory(this))[NativeSceneModelsViewModel::class.java]
    }
    private val modelProviderViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeModelProviderViewModel.Factory(this))[NativeModelProviderViewModel::class.java]
    }
    private val remoteMcpViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeRemoteMcpViewModel.Factory(this))[NativeRemoteMcpViewModel::class.java]
    }
    private val agentsViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeAgentsViewModel.Factory(this))[NativeAgentsViewModel::class.java]
    }
    private val alarmSettingsViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeAlarmSettingsViewModel.Factory(this))[NativeAlarmSettingsViewModel::class.java]
    }
    private val openWithViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeOpenWithViewModel.Factory(this))[NativeOpenWithViewModel::class.java]
    }
    private val remoteBridgeViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeRemoteBridgeViewModel.Factory(this))[NativeRemoteBridgeViewModel::class.java]
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
        val storage = ViewModelProvider(this, NativeStorageUsageViewModel.Factory(this))[NativeStorageUsageViewModel::class.java]
        val logs = ViewModelProvider(this)[NativeLogsViewModel::class.java]
        backgroundViewModel = ViewModelProvider(this, NativeBackgroundViewModel.Factory(this))[NativeBackgroundViewModel::class.java]
        val permissionAccess = AppPermissionAccess(applicationContext)
        val navigator = LegacyHomeNavigator(this)
        val actions = NativeHomeActions(
            open = navigator::open,
            consumeDestination = viewModel::consumeDestination,
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
                    if (it != LegacyDestination.Page.ModelProviders) {
                        viewModel.consumeDestination()
                        navigator.open(it)
                    }
                }
            }
            NativeHomeApp(
                state = state.copy(theme = theme),
                backgroundState = backgroundState,
                actions = actions,
                about = { onBack, onRequestLogs, onRuntimeLogs -> NativeAboutRoute(
                    about, this@NativeHomeActivity, navigator::open, onRequestLogs, onRuntimeLogs, onBack) },
                storage = { onBack -> NativeStorageUsageRoute(storage, onBack) },
                requestLogs = { onBack -> NativeRequestLogsRoute(logs, ::copyLogText, onBack) },
                runtimeLogs = { onBack -> NativeRuntimeLogsRoute(logs, ::copyLogText, onBack) },
                workspaceMemory = { onBack, onSceneModels -> NativeWorkspaceMemoryRoute(
                    workspaceMemoryViewModel, onSceneModels, onBack) },
                sceneModels = { onBack, onProviders, onEditAvatar -> NativeSceneModelsRoute(
                    sceneModelsViewModel, onProviders, onEditAvatar, onBack) },
                modelProviders = { onBack -> NativeModelProviderRoute(modelProviderViewModel, onBack) },
                mcpTools = { onBack -> NativeRemoteMcpRoute(remoteMcpViewModel, onBack) },
                agents = { onBack, onModelProviders, onAgentConfig, onRemoteBridge -> NativeAgentsRoute(
                    agentsViewModel, navigator::open, onModelProviders, onAgentConfig, onRemoteBridge, onBack) },
                agentConfig = { agentId, onBack ->
                    val configViewModel = remember(agentId) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeAgentConfigViewModel.Factory(this@NativeHomeActivity, agentId))[
                                "agentConfig:$agentId", NativeAgentConfigViewModel::class.java]
                    }
                    NativeAgentConfigRoute(configViewModel, onBack)
                },
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
                miscellaneous = { onBack, onHomeSettings, onAlarmSettings, onOpenWith -> NativeMiscSettingsRoute(
                    miscSettings, permissionAccess, navigator::open, onHomeSettings, onAlarmSettings, onOpenWith, onBack,
                ) },
                alarmSettings = { onBack -> NativeAlarmSettingsRoute(alarmSettingsViewModel, permissionAccess, onBack) },
                openWith = { onBack -> NativeOpenWithRoute(openWithViewModel, onBack) },
                remoteBridge = { onBack -> NativeRemoteBridgeRoute(remoteBridgeViewModel, navigator::open, onBack) },
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

    private fun copyLogText(value: String) {
        if (value.isBlank()) return
        val clip = ClipData.newPlainText("Omnibot log", value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        Toast.makeText(this, cn.com.omnimind.nativeui.R.string.omni_log_copied, Toast.LENGTH_SHORT).show()
    }
}
