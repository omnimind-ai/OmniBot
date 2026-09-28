package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes

data class RemoteMcpServerItem(
    val id: String,
    val name: String,
    val endpointUrl: String,
    val enabled: Boolean,
    val health: String,
    val toolCount: Int,
    val lastError: String?,
)

data class RemoteMcpEditorDraft(
    val id: String?,
    val name: String = "",
    val endpointUrl: String = "",
    val bearerToken: String = "",
    val enabled: Boolean = true,
) {
    override fun toString(): String = "RemoteMcpEditorDraft(id=$id, enabled=$enabled)"
}

data class RemoteMcpSettingsState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadFailed: Boolean = false,
    val servers: List<RemoteMcpServerItem> = emptyList(),
    val busyIds: Set<String> = emptySet(),
    val editor: RemoteMcpEditorDraft? = null,
    val savingEditor: Boolean = false,
    val deletingId: String? = null,
    @StringRes val notice: Int? = null,
) {
    override fun toString(): String = "RemoteMcpSettingsState(loaded=$loaded, loading=$loading, servers=${servers.size})"
}

data class RemoteMcpSettingsActions(
    val refresh: () -> Unit,
    val toggle: (String, Boolean) -> Unit,
    val refreshTools: (String) -> Unit,
    val openEditor: (String?) -> Unit,
    val editName: (String) -> Unit,
    val editEndpoint: (String) -> Unit,
    val editToken: (String) -> Unit,
    val editEnabled: (Boolean) -> Unit,
    val dismissEditor: () -> Unit,
    val saveEditor: () -> Unit,
    val confirmDelete: (String?) -> Unit,
    val deleteConfirmed: () -> Unit,
    val dismissNotice: () -> Unit,
)
