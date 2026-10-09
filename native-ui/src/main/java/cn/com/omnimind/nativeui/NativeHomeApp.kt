package cn.com.omnimind.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.home.ConversationArchiveScreen
import cn.com.omnimind.nativeui.home.HomeDrawer
import cn.com.omnimind.nativeui.home.HomeScreen
import cn.com.omnimind.nativeui.settings.SettingsScreen
import cn.com.omnimind.nativeui.settings.BackgroundSettingsState
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import cn.com.omnimind.nativeui.theme.OmniTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius

@Serializable
internal sealed interface HomeRoute : NavKey {
    @Serializable data object Home : HomeRoute
    @Serializable data object Settings : HomeRoute
    @Serializable data object Archive : HomeRoute
    @Serializable data object About : HomeRoute
    @Serializable data object Appearance : HomeRoute
    @Serializable data object HomePreferences : HomeRoute
    @Serializable data object Miscellaneous : HomeRoute
    @Serializable data object Background : HomeRoute
    @Serializable data object Pet : HomeRoute
    @Serializable data object Permissions : HomeRoute
    @Serializable data object Storage : HomeRoute
    @Serializable data object RequestLogs : HomeRoute
    @Serializable data object RuntimeLogs : HomeRoute
    @Serializable data object WorkspaceMemory : HomeRoute
    @Serializable data object SceneModels : HomeRoute
    @Serializable data object ModelProviders : HomeRoute
    @Serializable data object McpTools : HomeRoute
    @Serializable data object Agents : HomeRoute
    @Serializable data class AgentConfig(val agentId: String) : HomeRoute
    @Serializable data object AlarmSettings : HomeRoute
    @Serializable data object OpenWith : HomeRoute
    @Serializable data object RemoteBridge : HomeRoute
    @Serializable data object ScheduledTasks : HomeRoute
    @Serializable data object ExecutionHistory : HomeRoute
    @Serializable data object Skills : HomeRoute
    @Serializable data object Plugins : HomeRoute
    @Serializable data class PluginDetail(val pluginId: String) : HomeRoute
    @Serializable data object Memory : HomeRoute
    @Serializable data class Terminal(val focusPackageId: String? = null) : HomeRoute
    @Serializable data class ChatTranscriptPreview(
        val conversationId: Long,
        val mode: String,
        val title: String,
    ) : HomeRoute
    /** A new conversation on the native chat page; created by its first send (5e-1). */
    /** [sharedDraftKey] names a pending shared draft the page adopts once (5e-7). */
    /** The workspace browser (5e-8a); [path] null opens the root. */
    @Serializable data class Workspace(val path: String? = null) : HomeRoute
    @Serializable data class WorkspaceFile(val path: String, val edit: Boolean = false) : HomeRoute
    @Serializable data class NativeNewChat(
        val requestKey: Long,
        val draft: String = "",
        val sharedDraftKey: String = "",
    ) : HomeRoute
}

/** Miuix owns the saved page stack, transitions, and predictive back; Android owns back-to-home. */
@Composable
fun NativeHomeApp(
    state: NativeHomeState,
    actions: NativeHomeActions,
    backgroundState: BackgroundSettingsState = BackgroundSettingsState(),
    about: @Composable (onBack: () -> Unit, onRequestLogs: () -> Unit, onRuntimeLogs: () -> Unit) -> Unit,
    storage: @Composable (onBack: () -> Unit) -> Unit,
    requestLogs: @Composable (onBack: () -> Unit) -> Unit,
    runtimeLogs: @Composable (onBack: () -> Unit) -> Unit,
    workspaceMemory: @Composable (onBack: () -> Unit, onSceneModels: () -> Unit) -> Unit,
    sceneModels: @Composable (onBack: () -> Unit, onProviders: () -> Unit, onEditAvatar: () -> Unit) -> Unit,
    modelProviders: @Composable (onBack: () -> Unit) -> Unit,
    mcpTools: @Composable (onBack: () -> Unit) -> Unit,
    agents: @Composable (onBack: () -> Unit, onModelProviders: () -> Unit, onAgentConfig: (String) -> Unit, onRemoteBridge: () -> Unit, onTerminalFocus: (String) -> Unit) -> Unit,
    agentConfig: @Composable (agentId: String, onBack: () -> Unit) -> Unit,
    remoteBridge: @Composable (onBack: () -> Unit) -> Unit,
    scheduledTasks: @Composable (onBack: () -> Unit) -> Unit,
    skills: @Composable (onBack: () -> Unit) -> Unit,
    plugins: @Composable (onBack: () -> Unit, onPlugin: (String) -> Unit) -> Unit,
    pluginDetail: @Composable (pluginId: String, onBack: () -> Unit) -> Unit,
    memory: @Composable (onBack: () -> Unit) -> Unit,
    terminal: @Composable (focusPackageId: String?, onBack: () -> Unit) -> Unit,
    executionHistory: @Composable (onBack: () -> Unit) -> Unit,
    permissions: @Composable (onBack: () -> Unit) -> Unit,
    appearance: @Composable (onBack: () -> Unit, onBackground: () -> Unit) -> Unit,
    homePreferences: @Composable (onBack: () -> Unit) -> Unit,
    miscellaneous: @Composable (onBack: () -> Unit, onHomeSettings: () -> Unit, onAlarmSettings: () -> Unit, onOpenWith: () -> Unit) -> Unit,
    alarmSettings: @Composable (onBack: () -> Unit) -> Unit,
    openWith: @Composable (onBack: () -> Unit) -> Unit,
    background: @Composable (onBack: () -> Unit, onPet: () -> Unit) -> Unit,
    pet: @Composable (onBack: () -> Unit) -> Unit,
    /**
     * [instanceKey] identifies the page across rotation and process death;
     * [onNewConversation] replaces the page with a new conversation (5e-3).
     */
    chatTranscript: @Composable (
        conversationId: Long?, mode: String, title: String, instanceKey: String, draft: String,
        sharedDraftKey: String, onNewConversation: () -> Unit, onBack: () -> Unit,
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    /** [instanceKey] scopes the page's ViewModel to this back-stack entry (5e-8a). */
    workspace: @Composable (path: String?, instanceKey: String, onOpenFile: (String, Boolean) -> Unit, onBack: () -> Unit) -> Unit =
        { _, _, _, _ -> },
    workspaceFile: @Composable (path: String, edit: Boolean, instanceKey: String, onBack: () -> Unit) -> Unit =
        { _, _, _, _ -> },
) {
    OmniTheme(state.theme) {
        val palette = LocalOmniPalette.current
        val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Home)
        val openTranscript: (ConversationSummary) -> Unit = { conversation ->
            backStack.add(HomeRoute.ChatTranscriptPreview(conversation.id, conversation.mode, conversation.title))
        }
        val openNativeNewChat: () -> Unit = { backStack.add(HomeRoute.NativeNewChat(System.currentTimeMillis())) }
        // Home's composer and quick prompts open the native page (5e-4); a draft only fills it.
        val openNativeChatWithDraft: (String) -> Unit = { draft ->
            backStack.add(HomeRoute.NativeNewChat(System.currentTimeMillis(), draft))
        }
        LaunchedEffect(state.pendingDestination) {
            when (val destination = state.pendingDestination) {
                LegacyDestination.Page.ModelProviders -> {
                    actions.consumeDestination()
                    if (HomeRoute.ModelProviders !in backStack) backStack.add(HomeRoute.ModelProviders)
                }
                is LegacyDestination.TerminalPackage -> {
                    actions.consumeDestination()
                    backStack.add(HomeRoute.Terminal(
                        destination.packageId.ifBlank { null }))
                }
                is LegacyDestination.SharedDraft -> {
                    actions.consumeDestination()
                    backStack.add(HomeRoute.NativeNewChat(System.currentTimeMillis(), sharedDraftKey = destination.requestKey))
                }
                is LegacyDestination.Workspace -> {
                    actions.consumeDestination()
                    backStack.add(HomeRoute.Workspace(destination.path))
                }
                is LegacyDestination.WorkspaceFile -> {
                    actions.consumeDestination()
                    backStack.add(HomeRoute.WorkspaceFile(destination.path, destination.edit))
                }
                else -> Unit
            }
        }
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().background(palette.page),
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
                backdropColor = palette.page,
            ),
        ) {
            entry<HomeRoute.Home> {
                HomeWithDrawer(
                    state = state,
                    backgroundState = backgroundState,
                    onSettings = { backStack.add(HomeRoute.Settings) },
                    onArchive = { backStack.add(HomeRoute.Archive) },
                    onPet = { backStack.add(HomeRoute.Pet) },
                    onAgents = { backStack.add(HomeRoute.Agents) },
                    onTerminal = { backStack.add(HomeRoute.Terminal()) },
                    onScheduledTasks = { backStack.add(HomeRoute.ScheduledTasks) },
                    onExecutionHistory = { backStack.add(HomeRoute.ExecutionHistory) },
                    onSkills = { backStack.add(HomeRoute.Skills) },
                    onPlugins = { backStack.add(HomeRoute.Plugins) },
                    onMemory = { backStack.add(HomeRoute.Memory) },
                    onTranscript = openTranscript,
                    onNativeNewChat = openNativeNewChat,
                    onNativeChatWithDraft = openNativeChatWithDraft,
                    actions = actions,
                    onWorkspace = { backStack.add(HomeRoute.Workspace()) },
                )
            }
            entry<HomeRoute.Archive> {
                ConversationArchiveScreen(state, actions.copy(
                    previewTranscript = openTranscript,
                    open = { destination ->
                        val native = (destination as? LegacyDestination.Conversation)
                            ?.let { target -> state.conversations.firstOrNull { it.id == target.id && it.mode == target.mode } }
                            ?.takeIf(::opensNatively)
                        if (native != null) openTranscript(native) else actions.open(destination)
                    },
                )) {
                    backStack.removeLastOrNull()
                }
            }
            entry<HomeRoute.Settings> {
                SettingsScreen(
                    state, { backStack.removeLastOrNull() }, actions,
                    onAbout = { backStack.add(HomeRoute.About) },
                    onPermissions = { backStack.add(HomeRoute.Permissions) },
                    onAppearance = { backStack.add(HomeRoute.Appearance) },
                    onHomePreferences = { backStack.add(HomeRoute.HomePreferences) },
                    onMiscellaneous = { backStack.add(HomeRoute.Miscellaneous) },
                    onStorage = { backStack.add(HomeRoute.Storage) },
                    onWorkspaceMemory = { backStack.add(HomeRoute.WorkspaceMemory) },
                    onSceneModels = { backStack.add(HomeRoute.SceneModels) },
                    onModelProviders = { backStack.add(HomeRoute.ModelProviders) },
                    onMcpTools = { backStack.add(HomeRoute.McpTools) },
                    onAgents = { backStack.add(HomeRoute.Agents) },
                    onTerminal = { backStack.add(HomeRoute.Terminal()) },
                )
            }
            entry<HomeRoute.Appearance> { appearance(
                { backStack.removeLastOrNull() }, { backStack.add(HomeRoute.Background) },
            ) }
            entry<HomeRoute.Background> { background(
                { backStack.removeLastOrNull() }, { backStack.add(HomeRoute.Pet) },
            ) }
            entry<HomeRoute.Pet> { pet { backStack.removeLastOrNull() } }
            entry<HomeRoute.HomePreferences> { homePreferences { backStack.removeLastOrNull() } }
            entry<HomeRoute.Miscellaneous> { miscellaneous(
                { backStack.removeLastOrNull() },
                { backStack.add(HomeRoute.HomePreferences) },
                { backStack.add(HomeRoute.AlarmSettings) },
                { backStack.add(HomeRoute.OpenWith) },
            ) }
            entry<HomeRoute.About> { about(
                { backStack.removeLastOrNull() },
                { backStack.add(HomeRoute.RequestLogs) },
                { backStack.add(HomeRoute.RuntimeLogs) },
            ) }
            entry<HomeRoute.Storage> { storage { backStack.removeLastOrNull() } }
            entry<HomeRoute.RequestLogs> { requestLogs { backStack.removeLastOrNull() } }
            entry<HomeRoute.RuntimeLogs> { runtimeLogs { backStack.removeLastOrNull() } }
            entry<HomeRoute.WorkspaceMemory> { workspaceMemory(
                { backStack.removeLastOrNull() }, { backStack.add(HomeRoute.SceneModels) },
            ) }
            entry<HomeRoute.SceneModels> { sceneModels(
                { backStack.removeLastOrNull() },
                { backStack.add(HomeRoute.ModelProviders) },
                { actions.open(LegacyDestination.Page.SceneModels) },
            ) }
            entry<HomeRoute.ModelProviders> { modelProviders { backStack.removeLastOrNull() } }
            entry<HomeRoute.McpTools> { mcpTools { backStack.removeLastOrNull() } }
            entry<HomeRoute.Agents> {
                agents(
                    { backStack.removeLastOrNull() },
                    { if (HomeRoute.ModelProviders !in backStack) backStack.add(HomeRoute.ModelProviders) },
                    { agentId -> backStack.add(HomeRoute.AgentConfig(agentId)) },
                    { backStack.add(HomeRoute.RemoteBridge) },
                    { packageId -> backStack.add(HomeRoute.Terminal(packageId.ifBlank { null })) },
                )
            }
            entry<HomeRoute.AgentConfig> { key ->
                agentConfig(key.agentId) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.AlarmSettings> { alarmSettings { backStack.removeLastOrNull() } }
            entry<HomeRoute.OpenWith> { openWith { backStack.removeLastOrNull() } }
            entry<HomeRoute.RemoteBridge> { remoteBridge { backStack.removeLastOrNull() } }
            entry<HomeRoute.ScheduledTasks> { scheduledTasks { backStack.removeLastOrNull() } }
            entry<HomeRoute.Skills> { skills { backStack.removeLastOrNull() } }
            entry<HomeRoute.Plugins> {
                plugins({ backStack.removeLastOrNull() }, { backStack.add(HomeRoute.PluginDetail(it)) })
            }
            entry<HomeRoute.PluginDetail> { key ->
                pluginDetail(key.pluginId) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.Memory> { memory { backStack.removeLastOrNull() } }
            entry<HomeRoute.Terminal> { key ->
                terminal(key.focusPackageId) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.ExecutionHistory> { executionHistory { backStack.removeLastOrNull() } }
            entry<HomeRoute.Permissions> { permissions { backStack.removeLastOrNull() } }
            // A new conversation replaces the current chat page instead of stacking on it.
            val replaceWithNewChat: () -> Unit = {
                backStack.removeLastOrNull()
                openNativeNewChat()
            }
            entry<HomeRoute.ChatTranscriptPreview> { key ->
                chatTranscript(
                    key.conversationId, key.mode, key.title, "transcript:${key.mode}:${key.conversationId}", "", "",
                    replaceWithNewChat,
                ) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.Workspace> { key ->
                workspace(key.path, "workspace:${key.path.orEmpty()}:${backStack.indexOf(key)}",
                    { path, edit -> backStack.add(HomeRoute.WorkspaceFile(path, edit)) },
                ) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.WorkspaceFile> { key ->
                workspaceFile(key.path, key.edit, "workspace-file:${key.path}:${key.edit}:${backStack.indexOf(key)}") {
                    backStack.removeLastOrNull()
                }
            }
            entry<HomeRoute.NativeNewChat> { key ->
                chatTranscript(null, "agent", "", "new-chat:${key.requestKey}", key.draft, key.sharedDraftKey, replaceWithNewChat) {
                    backStack.removeLastOrNull()
                }
            }
        }
    }
}

@Composable
private fun HomeWithDrawer(
    state: NativeHomeState,
    backgroundState: BackgroundSettingsState,
    onSettings: () -> Unit,
    onArchive: () -> Unit,
    onPet: () -> Unit,
    onAgents: () -> Unit,
    onTerminal: () -> Unit,
    onScheduledTasks: () -> Unit,
    onExecutionHistory: () -> Unit,
    onSkills: () -> Unit,
    onPlugins: () -> Unit,
    onMemory: () -> Unit,
    onTranscript: (ConversationSummary) -> Unit,
    onNativeNewChat: () -> Unit,
    onNativeChatWithDraft: (String) -> Unit,
    actions: NativeHomeActions,
    onWorkspace: () -> Unit = {},
) {
    val palette = LocalOmniPalette.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navigate: (() -> Unit) -> Unit = { action ->
        scope.launch {
            drawer.close()
            action()
        }
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = palette.page) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val drawerWidth = maxWidth * .8f
            ModalNavigationDrawer(
                drawerState = drawer,
                scrimColor = palette.scrim,
                drawerContent = {
                    ModalDrawerSheet(
                        // This overload owns drawer back handling, including gesture cancel/commit.
                        drawerState = drawer,
                        modifier = Modifier.width(drawerWidth), drawerShape = RectangleShape,
                        drawerContainerColor = palette.drawer, drawerTonalElevation = 0.dp,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        HomeDrawer(
                            state = state,
                            onSettings = { navigate(onSettings) },
                            onArchive = { navigate(onArchive) },
                            // The native chat page (5e-1); the composer entry on Home keeps the Flutter chat.
                            onNewConversation = { navigate(onNativeNewChat) },
                            onScheduledTasks = { navigate(onScheduledTasks) },
                            onExecutionHistory = { navigate(onExecutionHistory) },
                            onSkills = { navigate(onSkills) },
                            onPlugins = { navigate(onPlugins) },
                            onMemory = { navigate(onMemory) },
                            actions = actions.copy(
                                open = { destination ->
                                    navigate {
                                        // The native page has the composer, message actions and the
                                        // Harness switcher (5e-5), so the drawer opens it for every
                                        // conversation it can send to; the rest keep their Flutter flows.
                                        val native = (destination as? LegacyDestination.Conversation)
                                            ?.let { target -> state.conversations.firstOrNull { it.id == target.id && it.mode == target.mode } }
                                            ?.takeIf(::opensNatively)
                                        if (native != null) onTranscript(native) else actions.open(destination)
                                    }
                                },
                                previewTranscript = { conversation -> navigate { onTranscript(conversation) } },
                            ),
                        )
                    }
                },
            ) {
                // Home's composer entry and quick prompts open the native chat page (5e-4).
                // The untargeted entry follows the startup preference like the Flutter chat;
                // a draft only fills the composer.
                val openHome: (LegacyDestination) -> Unit = { destination ->
                    when (destination) {
                        LegacyDestination.Page.Chat -> scope.launch {
                            actions.resolveStartupChat()?.let(onTranscript) ?: onNativeNewChat()
                        }
                        is LegacyDestination.NewConversation -> onNativeChatWithDraft(destination.draft)
                        is LegacyDestination.Workspace -> onWorkspace()
                        else -> actions.open(destination)
                    }
                }
                HomeScreen(state, backgroundState, { actions.refresh(); scope.launch { drawer.open() } },
                    onPet, onAgents, onTerminal, openHome)
            }
        }
    }
}

/**
 * Conversations the native chat page sends to (its composer target rules,
 * `NativeChatComposerTarget`): Agent and pure-chat conversations on a local
 * Harness. OpenClaw, scheduled Sub Agent runs and remote Codex sessions keep
 * their Flutter pages.
 */
internal fun opensNatively(conversation: ConversationSummary): Boolean {
    val mode = conversation.mode.trim().lowercase()
    val agentMode = mode in setOf("agent", "codex", "acp", "coding", "normal", "")
    val chatOnly = mode in setOf("chat_only", "chat", "chatonly", "chat-only")
    if (!agentMode && !chatOnly) return false
    if (conversation.parentId != null || conversation.scheduledTaskId != null) return false
    return conversation.agentId?.trim() != "codex-remote"
}
