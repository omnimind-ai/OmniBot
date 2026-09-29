package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.mcp.RemoteMcpConfigService
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.RemoteMcpEditorDraft
import cn.com.omnimind.nativeui.settings.RemoteMcpServerItem
import cn.com.omnimind.nativeui.settings.RemoteMcpSettingsActions
import cn.com.omnimind.nativeui.settings.RemoteMcpSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal class NativeRemoteMcpViewModel(context: Context) : ViewModel() {
    private val service = RemoteMcpConfigService(context)
    private val mutableState = MutableStateFlow(RemoteMcpSettingsState())
    private var configs: Map<String, RemoteMcpServerConfig> = emptyMap()
    private var editorBase: RemoteMcpServerConfig? = null
    private var listRevision = 0
    val state = mutableState.asStateFlow()

    val actions = RemoteMcpSettingsActions(
        refresh = { load(force = true) },
        toggle = ::toggle,
        refreshTools = ::refreshTools,
        openEditor = ::openEditor,
        editName = { updateEditor { draft -> draft.copy(name = it) } },
        editEndpoint = { updateEditor { draft -> draft.copy(endpointUrl = it) } },
        editToken = { updateEditor { draft -> draft.copy(bearerToken = it) } },
        editEnabled = { updateEditor { draft -> draft.copy(enabled = it) } },
        dismissEditor = ::dismissEditor,
        saveEditor = ::saveEditor,
        confirmDelete = { id -> mutableState.update { it.copy(deletingId = id) } },
        deleteConfirmed = ::deleteConfirmed,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || current.busyIds.isNotEmpty() || current.savingEditor || current.editor != null) return
        if (current.loaded && !force) return
        val revision = ++listRevision
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { service.listServers() }
                if (revision == listRevision) showServers(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == listRevision) mutableState.update { it.copy(
                    loading = false, loadFailed = !it.loaded,
                    notice = if (it.loaded) R.string.omni_mcp_load_failed else it.notice,
                ) }
            }
        }
    }

    private fun showServers(result: List<RemoteMcpServerConfig>) {
        configs = result.associateBy { it.id }
        mutableState.update { state -> state.copy(
            loaded = true,
            loading = false,
            loadFailed = false,
            servers = result.map { config -> RemoteMcpServerItem(
                config.id, config.name, config.endpointUrl, config.enabled,
                config.lastHealth.value, config.toolCount, config.lastError,
            ) },
        ) }
    }

    private suspend fun refreshList() {
        val revision = ++listRevision
        val result = withContext(Dispatchers.IO) { service.listServers() }
        if (revision == listRevision) showServers(result)
    }

    private fun toggle(id: String, enabled: Boolean) {
        val expected = configs[id] ?: return
        operate(id, R.string.omni_mcp_toggle_failed) {
            service.setServerEnabled(id, enabled, expected)
                ?: throw IllegalStateException("Server not found")
        }
    }

    private fun refreshTools(id: String) = operate(id, R.string.omni_mcp_refresh_failed,
        success = R.string.omni_mcp_refreshed) { service.refreshServerTools(id) }

    private fun operate(id: String, failure: Int, success: Int? = null, operation: suspend () -> Unit) {
        if (id in state.value.busyIds || state.value.loading || state.value.savingEditor) return
        listRevision++
        mutableState.update { it.copy(busyIds = it.busyIds + id, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { operation() }
                mutableState.update { it.copy(notice = success) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = failure) } }
            finally {
                runCatching { refreshList() }
                mutableState.update { it.copy(busyIds = it.busyIds - id) }
            }
        }
    }

    private fun openEditor(id: String?) {
        if (state.value.loading || state.value.savingEditor ||
            (id != null && id in state.value.busyIds)) return
        val base = id?.let(configs::get)
        if (id != null && base == null) return
        editorBase = base
        mutableState.update { it.copy(editor = RemoteMcpEditorDraft(
            id = id, name = base?.name.orEmpty(), endpointUrl = base?.endpointUrl.orEmpty(),
            bearerToken = base?.bearerToken.orEmpty(), enabled = base?.enabled ?: true,
        ), notice = null) }
    }

    private fun updateEditor(change: (RemoteMcpEditorDraft) -> RemoteMcpEditorDraft) {
        if (state.value.savingEditor) return
        mutableState.update { it.copy(editor = it.editor?.let(change)) }
    }

    private fun dismissEditor() {
        if (state.value.savingEditor) return
        editorBase = null
        mutableState.update { it.copy(editor = null) }
        load(force = true)
    }

    private fun saveEditor() {
        val draft = state.value.editor ?: return
        if (state.value.savingEditor || state.value.busyIds.isNotEmpty()) return
        val name = draft.name.trim()
        val endpoint = draft.endpointUrl.trim()
        if (name.isEmpty() || endpoint.isEmpty()) {
            mutableState.update { it.copy(notice = R.string.omni_mcp_required) }
            return
        }
        val uri = runCatching { Uri.parse(endpoint) }.getOrNull()
        if (uri == null || uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) {
            mutableState.update { it.copy(notice = R.string.omni_mcp_invalid_url) }
            return
        }
        val base = editorBase
        val config = (base ?: RemoteMcpServerConfig(id = UUID.randomUUID().toString(), name = name,
            endpointUrl = endpoint)).copy(name = name, endpointUrl = endpoint,
            bearerToken = draft.bearerToken.trim(), enabled = draft.enabled)
        listRevision++
        mutableState.update { it.copy(savingEditor = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { service.upsertServer(config, base) }
                editorBase = null
                mutableState.update { it.copy(editor = null, notice = R.string.omni_mcp_saved) }
                runCatching { refreshList() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (changed: IllegalStateException) {
                mutableState.update { it.copy(notice = R.string.omni_mcp_changed_elsewhere) }
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = R.string.omni_mcp_save_failed) }
            } finally { mutableState.update { it.copy(savingEditor = false) } }
        }
    }

    private fun deleteConfirmed() {
        val id = state.value.deletingId ?: return
        val expected = configs[id] ?: return
        mutableState.update { it.copy(deletingId = null) }
        operate(id, R.string.omni_mcp_delete_failed, R.string.omni_mcp_deleted) {
            service.deleteServer(id, expected)
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeRemoteMcpViewModel(appContext) as T
    }
}
