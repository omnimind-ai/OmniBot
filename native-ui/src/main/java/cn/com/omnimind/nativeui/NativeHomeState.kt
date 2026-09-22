package cn.com.omnimind.nativeui

import androidx.compose.runtime.Immutable

/** Presentation data only. Conversation/ACP lifecycle remains owned by the host runtime. */
@Immutable
data class NativeHomeState(
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val theme: ThemePreference = ThemePreference.System,
    val localServiceEnabled: Boolean = false,
    val localServiceBusy: Boolean = false,
    val workspaceMemoryConfigured: Boolean = false,
    val greetingEnabled: Boolean = true,
    val quickPrompts: List<QuickPrompt> = emptyList(),
    val scheduledTasks: List<ScheduledConversationTask> = emptyList(),
    val expandedSections: Map<String, Boolean> = emptyMap(),
    val busyConversationIds: Set<Long> = emptySet(),
    val localService: LocalServiceDetails = LocalServiceDetails(),
    val webActions: List<WebQuickAction> = emptyList(),
    val busyWebAction: String? = null,
    val pendingDestination: LegacyDestination? = null,
    val recentConversationsOnly: Boolean = false,
    val leftHanded: Boolean = false,
)

@Immutable
data class ConversationSummary(
    val id: Long,
    val title: String,
    val preview: String,
    val mode: String,
    val updatedAt: Long,
    val pinned: Boolean,
    val parentId: Long? = null,
    val parentMode: String? = null,
    val scheduledTaskId: String? = null,
    val agentId: String? = null,
    val createdAt: Long = updatedAt,
    val archived: Boolean = false,
) {
    val key: String get() = "$mode:$id"
}

@Immutable
data class ScheduledConversationTask(val id: String, val parentId: Long, val parentMode: String?)

/** Ephemeral service details; never save the token in navigation/saved-instance state. */
@Immutable
data class LocalServiceDetails(val endpoint: String = "", val token: String = "") {
    override fun toString(): String = "LocalServiceDetails(endpoint=$endpoint, token=<redacted>)"
}

enum class WebProcessStatus { Unknown, Stopped, Starting, Running }

@Immutable
data class WebQuickAction(
    val pluginId: String,
    val actionId: String,
    val label: String,
    val agentId: String,
    val status: WebProcessStatus = WebProcessStatus.Unknown,
    val canStop: Boolean = false,
) {
    val key: String get() = "$pluginId/$actionId"
    val active: Boolean get() = status == WebProcessStatus.Starting || status == WebProcessStatus.Running
}

/** Actions stay with their existing host services; UI state contains no runtime owner. */
data class NativeHomeActions(
    val open: (LegacyDestination) -> Unit,
    val setLocalServiceEnabled: (Boolean) -> Unit,
    val refreshLocalServiceToken: () -> Unit,
    val setArchived: (ConversationSummary, Boolean) -> Unit,
    val setSectionExpanded: (String, Boolean) -> Unit,
    val invokeWebAction: (WebQuickAction, Boolean) -> Unit,
    val refresh: () -> Unit,
)

@Immutable
data class QuickPrompt(val id: String, val title: String, val prompt: String)

enum class ThemePreference { System, Light, Dark }

/** Typed hand-off to pages whose existing implementation still owns their behavior. */
sealed interface LegacyDestination {
    data class Conversation(val id: Long, val mode: String, val agentId: String? = null) : LegacyDestination
    data class NewConversation(val draft: String = "") : LegacyDestination
    data class TerminalPackage(val packageId: String) : LegacyDestination

    enum class Page : LegacyDestination {
        Account, ModelProviders, SceneModels, WorkspaceMemory, Agents, Terminal,
        McpTools, Appearance, Miscellaneous, Permissions, Storage, About,
        Memory, Plugins, Skills, ExecutionHistory, ScheduledTasks, Workspace,
    }
}
