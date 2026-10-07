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
    @Serializable data class NativeNewChat(val requestKey: Long) : HomeRoute
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
    chatTranscript: @Composable (conversationId: Long?, mode: String, title: String, onBack: () -> Unit) -> Unit =
        { _, _, _, _ -> },
) {
    OmniTheme(state.theme) {
        val palette = LocalOmniPalette.current
        val backStack = rememberNavBackStack<HomeRoute>(HomeRoute.Home)
        val openTranscript: (ConversationSummary) -> Unit = { conversation ->
            backStack.add(HomeRoute.ChatTranscriptPreview(conversation.id, conversation.mode, conversation.title))
        }
        val openNativeNewChat: () -> Unit = { backStack.add(HomeRoute.NativeNewChat(System.currentTimeMillis())) }
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
                    actions = actions,
                )
            }
            entry<HomeRoute.Archive> {
                ConversationArchiveScreen(state, actions.copy(previewTranscript = openTranscript)) {
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
            entry<HomeRoute.ChatTranscriptPreview> { key ->
                chatTranscript(key.conversationId, key.mode, key.title) { backStack.removeLastOrNull() }
            }
            entry<HomeRoute.NativeNewChat> {
                chatTranscript(null, "agent", "") { backStack.removeLastOrNull() }
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
    actions: NativeHomeActions,
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
                                open = { destination -> navigate { actions.open(destination) } },
                                previewTranscript = { conversation -> navigate { onTranscript(conversation) } },
                            ),
                        )
                    }
                },
            ) {
                HomeScreen(state, backgroundState, { actions.refresh(); scope.launch { drawer.open() } },
                    onPet, onAgents, onTerminal, actions.open)
            }
        }
    }
}
