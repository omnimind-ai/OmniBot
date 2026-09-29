package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class AgentConfigProviderGroup(
    val id: String,
    val name: String,
    val configured: Boolean,
    val models: List<String> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** Editable draft. Launch environment and raw file content may carry credentials. */
data class AgentConfigDraft(
    val content: String = "",
    val command: String = "",
    val arguments: String = "",
    val environment: String = "",
    val enabled: Boolean = true,
    val reasoningEffort: String? = null,
    val permissionMode: String? = null,
) {
    override fun toString(): String = "AgentConfigDraft(enabled=$enabled, reasoningEffort=$reasoningEffort)"
}

/** Presentation-only editor state; the runtime owns profiles, config files and revisions. */
@Immutable
data class AgentConfigState(
    val agentId: String = "",
    val agentName: String = "",
    val builtIn: Boolean = true,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    @StringRes val loadErrorRes: Int? = null,
    val kind: String = "",
    val revision: Long = 0,
    val configPath: String = "",
    val authPath: String = "",
    val draft: AgentConfigDraft = AgentConfigDraft(),
    val saving: Boolean = false,
    val sharedLoading: Boolean = false,
    val sharedSaving: Boolean = false,
    val providers: List<AgentConfigProviderGroup> = emptyList(),
    val boundProviderId: String? = null,
    val boundModelId: String? = null,
    val pickerOpen: Boolean = false,
    val confirmDelete: Boolean = false,
    val deleted: Boolean = false,
    @StringRes val notice: Int? = null,
) {
    val hasSharedModelSelector: Boolean
        get() = kind == "codex" || kind == "json" || kind == "jsonc" || kind == "deepseek-harness"

    override fun toString(): String =
        "AgentConfigState(agentId=$agentId, kind=$kind, loaded=$loaded)"
}

data class AgentConfigActions(
    val retry: () -> Unit,
    val editContent: (String) -> Unit,
    val editCommand: (String) -> Unit,
    val editArguments: (String) -> Unit,
    val editEnvironment: (String) -> Unit,
    val editEnabled: (Boolean) -> Unit,
    val setReasoningEffort: (String) -> Unit,
    val setPermissionMode: (String) -> Unit,
    val save: () -> Unit,
    val openPicker: () -> Unit,
    val closePicker: () -> Unit,
    val selectSharedModel: (providerId: String, modelId: String) -> Unit,
    val retryProvider: (String) -> Unit,
    val showDeleteConfirm: (Boolean) -> Unit,
    val deleteAgent: () -> Unit,
    val dismissNotice: () -> Unit,
)
