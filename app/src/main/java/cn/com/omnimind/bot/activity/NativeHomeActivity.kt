package cn.com.omnimind.bot.activity

import android.content.Intent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.ui.chat.ChatStartupTarget
import cn.com.omnimind.bot.ui.chat.resolveChatStartupTarget
import cn.com.omnimind.nativeui.chat.AgentToolActionUi
import cn.com.omnimind.bot.ui.chat.NativeChatTranscriptRoute
import cn.com.omnimind.bot.ui.chat.NativeChatTranscriptViewModel
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
import cn.com.omnimind.bot.ui.nativehome.TabletPanePreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.com.omnimind.bot.ui.workspace.NativeWorkspaceBrowserRoute
import cn.com.omnimind.bot.ui.workspace.NativeWorkspaceBrowserViewModel
import cn.com.omnimind.bot.ui.workspace.NativeWorkspaceFileRoute
import cn.com.omnimind.bot.ui.workspace.NativeWorkspaceFileViewModel
import cn.com.omnimind.bot.ui.workspace.WorkspaceResourcePaths
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
import cn.com.omnimind.nativeui.opensNativePage
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
import cn.com.omnimind.bot.ui.settings.NativeScheduledTasksViewModel
import cn.com.omnimind.bot.ui.settings.NativeScheduledTasksRoute
import cn.com.omnimind.bot.ui.settings.NativeSkillStoreViewModel
import cn.com.omnimind.bot.ui.settings.NativeSkillStoreRoute
import cn.com.omnimind.bot.ui.settings.NativeMemoryCenterViewModel
import cn.com.omnimind.bot.ui.settings.NativeMemoryCenterRoute
import cn.com.omnimind.bot.ui.settings.NativeTerminalSettingsViewModel
import cn.com.omnimind.bot.ui.settings.NativeTerminalSettingsRoute
import cn.com.omnimind.bot.ui.settings.NativePluginMarketViewModel
import cn.com.omnimind.bot.ui.settings.NativePluginMarketRoute
import cn.com.omnimind.bot.ui.settings.NativePluginDetailViewModel
import cn.com.omnimind.bot.ui.settings.NativePluginDetailRoute
import cn.com.omnimind.bot.ui.settings.NativeUsageStatisticsRoute
import cn.com.omnimind.bot.ui.settings.NativeUsageStatisticsViewModel
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
    private val usageStatisticsViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeUsageStatisticsViewModel.Factory(this))[NativeUsageStatisticsViewModel::class.java]
    }
    private val scheduledTasksViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeScheduledTasksViewModel.Factory(this))[NativeScheduledTasksViewModel::class.java]
    }
    private val skillStoreViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeSkillStoreViewModel.Factory(this))[NativeSkillStoreViewModel::class.java]
    }
    private val pluginMarketViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativePluginMarketViewModel.Factory(this))[NativePluginMarketViewModel::class.java]
    }
    private val memoryCenterViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, NativeMemoryCenterViewModel.Factory(this))[NativeMemoryCenterViewModel::class.java]
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
        // A recreated activity already handled its launch intent.
        if (savedInstanceState == null) consumeSharedDraftIntent(intent)
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
            resolveStartupChat = ::resolveStartupChat,
            open = navigator::open,
            consumeDestination = viewModel::consumeDestination,
            setLocalServiceEnabled = viewModel::setLocalServiceEnabled,
            refreshLocalServiceToken = viewModel::refreshLocalServiceToken,
            setArchived = viewModel::setArchived,
            setSectionExpanded = viewModel::setSectionExpanded,
            invokeWebAction = viewModel::invokeWebAction,
            refresh = viewModel::refresh,
        )
        val tabletPanePreferences = TabletPanePreferences(getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE))
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            var tabletWidths by remember { mutableStateOf(tabletPanePreferences.read()) }
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
                    // NativeHomeApp consumes the destinations it opens natively.
                    if (!it.opensNativePage) {
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
                agents = { onBack, onModelProviders, onAgentConfig, onRemoteBridge, onTerminalFocus -> NativeAgentsRoute(
                    agentsViewModel, navigator::open, onModelProviders, onAgentConfig, onRemoteBridge, onTerminalFocus, onBack) },
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
                scheduledTasks = { onBack -> NativeScheduledTasksRoute(scheduledTasksViewModel, onBack) },
                skills = { onBack -> NativeSkillStoreRoute(skillStoreViewModel, onBack) },
                memory = { onBack -> NativeMemoryCenterRoute(memoryCenterViewModel, onBack) },
                chatTranscript = { conversationId, mode, title, key, draft, sharedDraftKey, onNewConversation, onBack ->
                    // The key comes from the route, so a new page finds its ViewModel (and the
                    // conversation it created, kept in SavedStateHandle) after process death.
                    val transcriptViewModel = remember(key) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeChatTranscriptViewModel.Factory(
                                this@NativeHomeActivity, conversationId, mode, title, draft, sharedDraftKey,
                            ))[
                                key, NativeChatTranscriptViewModel::class.java]
                    }
                    NativeChatTranscriptRoute(
                        transcriptViewModel, ::openTranscriptLink, ::runTranscriptToolAction, onNewConversation,
                        onOpenInChat = { handoff ->
                            // The Flutter chat owns these flows until they move (5e-6).
                            navigator.open(
                                handoff.conversationId?.let { id ->
                                    LegacyDestination.Conversation(id, handoff.mode, handoff.agentId, handoff.draft)
                                } ?: LegacyDestination.NewConversation(handoff.draft),
                            )
                        },
                        onBack = onBack,
                    )
                },
                workspace = { path, key, onOpenFile, onBack ->
                    val workspaceViewModel = remember(key) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeWorkspaceBrowserViewModel.Factory(this@NativeHomeActivity, path))[
                                key, NativeWorkspaceBrowserViewModel::class.java]
                    }
                    NativeWorkspaceBrowserRoute(workspaceViewModel, onOpenFile, onBack)
                },
                workspacePane = { onOpenFile, onClose ->
                    val paneViewModel = remember {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeWorkspaceBrowserViewModel.Factory(this@NativeHomeActivity, null))[
                                "workspace-pane", NativeWorkspaceBrowserViewModel::class.java]
                    }
                    NativeWorkspaceBrowserRoute(paneViewModel, onOpenFile, onClose, embedded = true)
                },
                tabletWidths = tabletWidths,
                onTabletWidthsChange = { widths ->
                    tabletWidths = widths
                    tabletPanePreferences.write(widths)
                },
                workspaceFile = { path, edit, key, onBack ->
                    val fileViewModel = remember(key) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeWorkspaceFileViewModel.Factory(this@NativeHomeActivity, path, edit))[
                                key, NativeWorkspaceFileViewModel::class.java]
                    }
                    NativeWorkspaceFileRoute(fileViewModel, this@NativeHomeActivity, ::openTranscriptLink, onBack)
                },
                terminal = { focusPackageId, onBack ->
                    val terminalViewModel = remember(focusPackageId) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativeTerminalSettingsViewModel.Factory(this@NativeHomeActivity, focusPackageId))[
                                "terminal:${focusPackageId.orEmpty()}", NativeTerminalSettingsViewModel::class.java]
                    }
                    NativeTerminalSettingsRoute(terminalViewModel, this@NativeHomeActivity, onBack)
                },
                plugins = { onBack, onPlugin -> NativePluginMarketRoute(pluginMarketViewModel, onPlugin, onBack) },
                pluginDetail = { pluginId, onBack ->
                    val detailViewModel = remember(pluginId) {
                        ViewModelProvider(this@NativeHomeActivity,
                            NativePluginDetailViewModel.Factory(this@NativeHomeActivity, pluginId))[
                                "pluginDetail:$pluginId", NativePluginDetailViewModel::class.java]
                    }
                    NativePluginDetailRoute(detailViewModel, navigator::open, onBack)
                },
                executionHistory = { onBack -> NativeUsageStatisticsRoute(usageStatisticsViewModel, onBack) },
                permissions = { onBack -> NativePermissionsRoute(permissions, permissionAccess, this@NativeHomeActivity, onBack) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedDraftIntent(intent)
    }

    /** A share from another app opens a native page with the pending draft (5e-7). */
    private fun consumeSharedDraftIntent(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_SHARED_DRAFT_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        intent.removeExtra(EXTRA_SHARED_DRAFT_KEY)
        viewModel.requestDestination(LegacyDestination.SharedDraft(key))
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

    /**
     * Tool-card follow-ups (Dart `_runAgentToolAction`). App routes hand off to
     * Flutter like plugin routes; workspace, preview and open actions open the
     * native workspace pages (5e-8a); save opens the preview, whose toolbar
     * saves to the device.
     */
    private fun runTranscriptToolAction(action: AgentToolActionUi) {
        val type = action.type.trim().lowercase()
        val target = action.target.trim()
        val path = (action.payload["path"] ?: action.payload["workspacePath"])?.toString()?.trim().orEmpty()
        val shellPath = (action.payload["shellPath"] ?: action.payload["workspaceShellPath"])?.toString()?.trim().orEmpty()
        when {
            type == "route" && target.startsWith("/") -> LegacyHomeNavigator(this).open(LegacyDestination.PluginRoute(target))
            type == "workspace" -> openWorkspaceResource(path.ifEmpty { null }, shellPath.ifEmpty { null }, target, directory = true)
            type in setOf("save", "preview", "open") && path.isNotEmpty() ->
                viewModel.requestDestination(LegacyDestination.WorkspaceFile(path))
            target.isNotEmpty() -> openTranscriptLink(target)
        }
    }

    /**
     * Opens a workspace path or `omnibot://` resource natively (Dart
     * `OmnibotResourceService.openUri`/`openWorkspace`): folders in the
     * browser, files in the preview. Public storage still needs the
     * all-files permission, so those paths keep the Flutter flow that asks.
     */
    private fun openWorkspaceResource(path: String?, shellPath: String?, uri: String, directory: Boolean = false) {
        val paths = WorkspaceResourcePaths.from(this)
        val resolved = path ?: shellPath?.let(paths::androidPathForShellPath) ?: paths.resolveUriToPath(uri)
            ?: if (directory) paths.rootPath else return
        if (paths.isPublicPath(resolved) && !cn.com.omnimind.bot.workspace.PublicStorageAccess.isGranted()) {
            Toast.makeText(this, cn.com.omnimind.nativeui.R.string.omni_tool_action_flutter_only, Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(resolved)
        val insideWorkspace = cn.com.omnimind.nativeui.workspace.isSelfOrDescendant(resolved, paths.rootPath)
        val destination = when {
            // The native browser is rooted at the workspace; public folders keep the Flutter page.
            (file.isDirectory || directory) && !insideWorkspace -> {
                Toast.makeText(this, cn.com.omnimind.nativeui.R.string.omni_tool_action_flutter_only, Toast.LENGTH_SHORT).show()
                return
            }
            file.isDirectory -> LegacyDestination.Workspace(resolved)
            // A workspace action on a file shows its folder (Dart opened the browser on the file and found nothing).
            directory && file.isFile -> LegacyDestination.Workspace(file.parent)
            // Dart: a missing workspace target opens the workspace instead of a dead preview.
            !file.exists() && (directory || uri.startsWith("omnibot://workspace/")) -> LegacyDestination.Workspace(resolved)
            else -> LegacyDestination.WorkspaceFile(resolved)
        }
        viewModel.requestDestination(destination)
    }

    /** Home's untargeted chat entry, resolved like the Flutter chat page's bootstrap (5e-4). */
    private suspend fun resolveStartupChat(): ConversationSummary? = withContext(Dispatchers.IO) {
        val preferences = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val target = resolveChatStartupTarget(
            preferences.getString("flutter.chat_startup_behavior", null),
            preferences.getString("flutter.last_visible_conversation_target", null),
        ) { id -> DatabaseHelper.getConversationById(id)?.takeIf { !it.isArchived }?.title?.ifBlank { " " } }
        (target as? ChatStartupTarget.Existing)?.let {
            ConversationSummary(
                id = it.conversationId, title = it.title.trim(), preview = "", mode = it.mode,
                updatedAt = 0L, pinned = false,
            )
        }
    }

    private fun openTranscriptLink(link: String) {
        if (link.startsWith("omnibot://")) {
            openWorkspaceResource(null, null, link)
            return
        }
        val uri = runCatching { android.net.Uri.parse(link) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https")) return
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
    }

    companion object {
        /** The request key of a pending `SharedOpenDraftStore` draft to open natively. */
        const val EXTRA_SHARED_DRAFT_KEY = "native_shared_draft_key"
    }
}
