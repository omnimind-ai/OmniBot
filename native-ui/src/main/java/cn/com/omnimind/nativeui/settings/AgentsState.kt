package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.WebQuickAction

enum class AgentFilter { All, Available, Unavailable }

/** Presentation-only Agent row. The runtime owns profiles, health and installs. */
@Immutable
data class AgentItem(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val enabled: Boolean,
    val status: String,
    val installed: Boolean?,
    val managedAdapter: Boolean,
    @StringRes val subtitleRes: Int? = null,
    val subtitleText: String? = null,
    val subtitleMonospace: Boolean = false,
    @StringRes val errorRes: Int? = null,
    val searchText: String = "",
)

@Immutable
data class AgentActionResult(
    val agentId: String,
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
)

data class AgentEditorDraft(
    val name: String = "",
    val command: String = "",
    val arguments: String = "",
    val environment: String = "",
    val enabled: Boolean = true,
) {
    // Launch arguments/environment may carry credentials; keep them out of logs.
    override fun toString(): String = "AgentEditorDraft(name=$name, enabled=$enabled)"
}

@Immutable
data class AgentsState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    @StringRes val loadErrorRes: Int? = null,
    val agents: List<AgentItem> = emptyList(),
    val query: String = "",
    val filter: AgentFilter = AgentFilter.All,
    val busyAgentId: String? = null,
    val preparingAgentIds: Set<String> = emptySet(),
    val sharedModelLabel: String = "",
    val xiaowanAvatar: ImageBitmap? = null,
    val webActions: List<WebQuickAction> = emptyList(),
    val busyWebActionKey: String? = null,
    val remoteBridgeEnabled: Boolean = false,
    val editor: AgentEditorDraft? = null,
    val savingEditor: Boolean = false,
    val actionResult: AgentActionResult? = null,
    val pendingDestination: LegacyDestination? = null,
    @StringRes val notice: Int? = null,
) {
    override fun toString(): String = "AgentsState(loaded=$loaded, agents=${agents.size})"
}

data class AgentsActions(
    val refresh: () -> Unit,
    val retry: () -> Unit,
    val setQuery: (String) -> Unit,
    val setFilter: (AgentFilter) -> Unit,
    val openEditor: () -> Unit,
    val editName: (String) -> Unit,
    val editCommand: (String) -> Unit,
    val editArguments: (String) -> Unit,
    val editEnvironment: (String) -> Unit,
    val editEnabled: (Boolean) -> Unit,
    val dismissEditor: () -> Unit,
    val saveEditor: () -> Unit,
    val testAgent: (String) -> Unit,
    val prepareAgent: (String) -> Unit,
    val dismissActionResult: () -> Unit,
    val invokeWebAction: (WebQuickAction, Boolean) -> Unit,
    val consumeDestination: () -> Unit,
    val dismissNotice: () -> Unit,
)
