package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentRuntimeErrorSupport
import cn.com.omnimind.bot.agent.NativeAgentsRepository
import cn.com.omnimind.bot.agent.NativeAgentProfile
import cn.com.omnimind.bot.agent.NativeCustomAgentDraft
import cn.com.omnimind.bot.agent.classifyAgentError
import cn.com.omnimind.bot.agent.classifyAgentErrorText
import cn.com.omnimind.bot.agent.runtime.AcpAgentHealth
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.model.SceneModelSettingsRepository
import cn.com.omnimind.bot.ui.nativehome.NativeWebActionRepository
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.WebQuickAction
import cn.com.omnimind.nativeui.settings.AgentActionResult
import cn.com.omnimind.nativeui.settings.AgentEditorDraft
import cn.com.omnimind.nativeui.settings.AgentItem
import cn.com.omnimind.nativeui.settings.AgentsActions
import cn.com.omnimind.nativeui.settings.AgentsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agents settings page state. The existing runtime owns profiles, health,
 * installs and the ACP lifecycle; this ViewModel only triggers operations and
 * re-reads `agent/list`. Prepare busy flags are view-state markers, not a
 * second in-flight owner: `ManagedAcpPreparationGate` serializes installs.
 */
internal class NativeAgentsViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repository = NativeAgentsRepository(appContext)
    private val sceneModels = SceneModelSettingsRepository(appContext)
    private val webActions = NativeWebActionRepository(appContext)
    private val mutableState = MutableStateFlow(
        AgentsState(remoteBridgeEnabled = repository.cachedRemoteBridgeEnabled())
    )
    private var catalogRevision = 0
    val state = mutableState.asStateFlow()

    val actions = AgentsActions(
        refresh = { reload(probe = true) },
        retry = { load(force = true) },
        setQuery = { query -> mutableState.update { it.copy(query = query) } },
        setFilter = { filter -> mutableState.update { it.copy(filter = filter) } },
        openEditor = ::openEditor,
        editName = { updateEditor { draft -> draft.copy(name = it) } },
        editCommand = { updateEditor { draft -> draft.copy(command = it) } },
        editArguments = { updateEditor { draft -> draft.copy(arguments = it) } },
        editEnvironment = { updateEditor { draft -> draft.copy(environment = it) } },
        editEnabled = { updateEditor { draft -> draft.copy(enabled = it) } },
        dismissEditor = ::dismissEditor,
        saveEditor = ::saveEditor,
        testAgent = ::test,
        prepareAgent = ::prepare,
        dismissActionResult = { mutableState.update { it.copy(actionResult = null) } },
        invokeWebAction = ::invokeWebAction,
        consumeDestination = { mutableState.update { it.copy(pendingDestination = null) } },
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    /** First entry: cached health only; the toolbar refresh runs the full probe. */
    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || current.refreshing) return
        if (current.loaded && !force) return
        reload(probe = false, showLoading = true)
    }

    /** Returning from a compatibility config page re-reads cached state without probing. */
    fun resume() {
        val current = state.value
        if (current.loading || current.refreshing) return
        if (!current.loaded) {
            load()
            return
        }
        reload(probe = false, showLoading = false)
    }

    private fun reload(probe: Boolean, showLoading: Boolean = false) {
        val revision = ++catalogRevision
        mutableState.update {
            it.copy(loading = showLoading && !it.loaded, refreshing = probe, loadErrorRes = null)
        }
        viewModelScope.launch { reloadNow(revision, probe) }
    }

    private suspend fun reloadNow(revision: Int, probe: Boolean) {
        try {
            val catalog = withContext(Dispatchers.IO) { repository.listAgents(refresh = probe) }
            val sharedModel = withContext(Dispatchers.IO) { sceneModels.dispatchModelSummary() }
            val avatar = withContext(Dispatchers.IO) { loadAgentAvatarPreview(appContext) }
            // A Web-action discovery failure must not hide a previously loaded section.
            val actions = runCatching { webActions.listAgentSettingsActions() }
                .getOrElse { state.value.webActions }
            if (revision != catalogRevision) return
            mutableState.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    refreshing = false,
                    loadErrorRes = null,
                    agents = catalog.agents.map(::toItem),
                    sharedModelLabel = listOf(sharedModel.providerName.trim(), sharedModel.model.trim())
                        .filter(String::isNotEmpty)
                        .joinToString(" / "),
                    xiaowanAvatar = avatar,
                    webActions = actions,
                )
            }
            refreshRemoteBridge(revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativeAgents", "Agent catalog load failed", error)
            if (revision != catalogRevision) return
            mutableState.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    loadErrorRes = failureTextRes(classifyAgentError(error))
                        ?: R.string.omni_agent_error_unknown,
                )
            }
        }
    }

    private fun refreshRemoteBridge(revision: Int) {
        viewModelScope.launch {
            try {
                val enabled = withContext(Dispatchers.IO) { repository.readRemoteBridgeEnabled() }
                if (revision == catalogRevision) {
                    mutableState.update { it.copy(remoteBridgeEnabled = enabled) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OmniLog.w("NativeAgents", "Remote bridge status refresh failed; keeping cached value")
            }
        }
    }

    private fun test(agentId: String) {
        val agent = state.value.agents.firstOrNull { it.id == agentId } ?: return
        if (state.value.busyAgentId == agentId || agentId in state.value.preparingAgentIds || !agent.enabled) return
        mutableState.update { it.copy(busyAgentId = agentId, actionResult = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.testAgent(agentId) }
                reloadNow(++catalogRevision, probe = false)
                mutableState.update {
                    it.copy(
                        actionResult = AgentActionResult(
                            agentId,
                            if (result.ok) R.string.omni_agent_check_passed else R.string.omni_agent_check_failed,
                            if (result.ok) R.string.omni_agent_ready_message
                            else failureTextRes(result.errorKind) ?: fallbackErrorRes(installation = false),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        actionResult = AgentActionResult(
                            agentId,
                            R.string.omni_agent_check_failed,
                            failureTextRes(classifyAgentError(error)) ?: fallbackErrorRes(installation = false),
                        ),
                    )
                }
            } finally {
                mutableState.update { it.copy(busyAgentId = null) }
            }
        }
    }

    private fun prepare(agentId: String) {
        val agent = state.value.agents.firstOrNull { it.id == agentId } ?: return
        if (agentId in state.value.preparingAgentIds || !agent.enabled) return
        mutableState.update { it.copy(preparingAgentIds = it.preparingAgentIds + agentId, actionResult = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.prepareAgent(agentId) }
                reloadNow(++catalogRevision, probe = false)
                val installed = result.installed == true
                mutableState.update {
                    it.copy(
                        actionResult = AgentActionResult(
                            agentId,
                            when {
                                result.ok -> R.string.omni_agent_install_success
                                installed -> R.string.omni_agent_install_start_failed
                                else -> R.string.omni_agent_install_failed
                            },
                            if (result.ok) R.string.omni_agent_ready_message
                            else failureTextRes(result.errorKind) ?: fallbackErrorRes(installation = !installed),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        actionResult = AgentActionResult(
                            agentId,
                            R.string.omni_agent_install_incomplete,
                            failureTextRes(classifyAgentError(error)) ?: fallbackErrorRes(installation = true),
                        ),
                    )
                }
            } finally {
                mutableState.update { it.copy(preparingAgentIds = it.preparingAgentIds - agentId) }
            }
        }
    }

    private fun openEditor() {
        if (state.value.savingEditor || state.value.editor != null) return
        mutableState.update { it.copy(editor = AgentEditorDraft(), notice = null) }
    }

    private fun updateEditor(change: (AgentEditorDraft) -> AgentEditorDraft) {
        if (state.value.savingEditor) return
        mutableState.update { it.copy(editor = it.editor?.let(change)) }
    }

    private fun dismissEditor() {
        if (state.value.savingEditor) return
        mutableState.update { it.copy(editor = null) }
    }

    private fun saveEditor() {
        val draft = state.value.editor ?: return
        if (state.value.savingEditor) return
        val name = draft.name.trim()
        val command = draft.command.trim()
        if (name.isEmpty()) {
            mutableState.update { it.copy(notice = R.string.omni_agent_name_required) }
            return
        }
        if (command.isEmpty()) {
            mutableState.update { it.copy(notice = R.string.omni_agent_command_required) }
            return
        }
        catalogRevision++
        mutableState.update { it.copy(savingEditor = true, notice = null) }
        viewModelScope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) {
                    repository.saveAgent(
                        NativeCustomAgentDraft(
                            name = name,
                            command = command,
                            arguments = nonEmptyLines(draft.arguments),
                            environment = parseEnvironment(draft.environment),
                            enabled = draft.enabled,
                        ),
                    )
                }
                mutableState.update {
                    it.copy(
                        savingEditor = false,
                        editor = null,
                        loaded = true,
                        loadErrorRes = null,
                        agents = catalog.agents.map(::toItem),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgents", "Agent save failed", error)
                // A failed save leaves the editor draft open.
                mutableState.update { it.copy(savingEditor = false, notice = R.string.omni_agent_save_failed) }
            }
        }
    }

    private fun invokeWebAction(action: WebQuickAction, stop: Boolean) {
        if (state.value.busyWebActionKey != null) return
        mutableState.update { it.copy(busyWebActionKey = action.key, notice = null) }
        viewModelScope.launch {
            try {
                val result = webActions.invoke(action, stop)
                when {
                    stop && !result.stopped -> notice(R.string.omni_web_stop_failed)
                    stop || result.code == "OPENED" -> Unit
                    result.code == "RUNTIME_MISSING" -> mutableState.update {
                        it.copy(pendingDestination = LegacyDestination.TerminalPackage(result.packageId))
                    }
                    result.code == "PROVIDER_REQUIRED" || result.code == "MODEL_REQUIRED" -> {
                        notice(R.string.omni_web_provider_required)
                        mutableState.update {
                            it.copy(pendingDestination = LegacyDestination.Page.ModelProviders)
                        }
                    }
                    result.code == "UNSUPPORTED_PROVIDER" -> notice(R.string.omni_web_unsupported_provider)
                    result.code == "URL_TIMEOUT" -> notice(R.string.omni_web_timeout)
                    result.code == "STOP_FAILED" -> notice(R.string.omni_web_stop_failed)
                    result.code == "BROWSER_UNAVAILABLE" -> notice(R.string.omni_web_browser_unavailable)
                    else -> notice(R.string.omni_web_open_failed)
                }
                val refreshed = runCatching { webActions.listAgentSettingsActions() }
                    .getOrElse { state.value.webActions }
                mutableState.update { it.copy(webActions = refreshed) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgents", "Web action failed", error)
                notice(if (stop) R.string.omni_web_stop_failed else R.string.omni_web_open_failed)
            } finally {
                mutableState.update { it.copy(busyWebActionKey = null) }
            }
        }
    }

    private fun notice(resource: Int) {
        mutableState.update { it.copy(notice = resource) }
    }

    private fun toItem(agent: NativeAgentProfile): AgentItem {
        val capabilityRes = when {
            agent.pluginAuthoring && agent.pluginInstallViaHarness ->
                R.string.omni_agent_plugins_create_install
            agent.pluginAuthoring -> R.string.omni_agent_plugins_create
            agent.pluginInstallViaHarness -> R.string.omni_agent_plugins_install
            else -> null
        }
        val subtitleText: String?
        val subtitleMonospace: Boolean
        when {
            capabilityRes != null -> {
                subtitleText = null
                subtitleMonospace = false
            }
            agent.managedAdapter || agent.id == AcpAgentProfileStore.XIAOWAN_AGENT_ID -> {
                subtitleText = null
                subtitleMonospace = false
            }
            agent.description.isNotEmpty() -> {
                subtitleText = agent.description
                subtitleMonospace = false
            }
            else -> {
                subtitleText = (listOf(agent.command) + agent.arguments).joinToString(" ")
                subtitleMonospace = true
            }
        }
        val hasError = agent.lastCheckError != null && agent.status != AcpAgentHealth.STATUS_ONLINE
        return AgentItem(
            id = agent.id,
            name = agent.name,
            builtIn = agent.builtIn,
            enabled = agent.enabled,
            status = agent.status,
            installed = agent.installed,
            managedAdapter = agent.managedAdapter,
            subtitleRes = capabilityRes,
            subtitleText = subtitleText,
            subtitleMonospace = subtitleMonospace,
            errorRes = if (hasError) {
                agent.lastCheckError?.let { classifyAgentErrorText(it) }?.let(::failureTextRes)
                    ?: fallbackErrorRes(installation = agent.installed == false)
            } else null,
            searchText = listOf(agent.name, agent.description, agent.command)
                .joinToString(" ")
                .lowercase(),
        )
    }

    private fun fallbackErrorRes(installation: Boolean): Int =
        if (installation) R.string.omni_agent_error_install_fallback
        else R.string.omni_agent_error_start_fallback

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeAgentsViewModel(appContext) as T
    }
}

/** Maps the runtime's failure classification to localized text; unknown kinds use the fallback. */
private fun failureTextRes(kind: String?): Int? = when (kind) {
    AgentRuntimeErrorSupport.PROVIDER_QUOTA_EXCEEDED -> R.string.omni_agent_error_quota
    AgentRuntimeErrorSupport.PROVIDER_RATE_LIMITED -> R.string.omni_agent_error_rate_limit
    AgentRuntimeErrorSupport.PROVIDER_REQUEST_LIMITED -> R.string.omni_agent_error_request_limited
    AgentRuntimeErrorSupport.PROVIDER_SERVICE_UNAVAILABLE -> R.string.omni_agent_error_service_unavailable
    AgentRuntimeErrorSupport.PROVIDER_REQUEST_REJECTED -> R.string.omni_agent_error_request_rejected
    AgentRuntimeErrorSupport.PROVIDER_TLS_CERTIFICATE_FAILURE -> R.string.omni_agent_error_certificate
    AgentRuntimeErrorSupport.PROVIDER_AUTHENTICATION_FAILED,
    AgentRuntimeErrorSupport.PROVIDER_UNAVAILABLE -> R.string.omni_agent_error_credentials
    AgentRuntimeErrorSupport.PROVIDER_NOT_BOUND -> R.string.omni_agent_error_model_required
    AgentRuntimeErrorSupport.PROVIDER_MODEL_UNAVAILABLE -> R.string.omni_agent_error_model_unavailable
    AgentRuntimeErrorSupport.PROVIDER_STREAM_IDLE_TIMEOUT,
    AgentRuntimeErrorSupport.PROVIDER_REQUEST_TIMEOUT -> R.string.omni_agent_error_timeout
    AgentRuntimeErrorSupport.PROVIDER_STREAM_INTERRUPTED -> R.string.omni_agent_error_interrupted
    AgentRuntimeErrorSupport.PROVIDER_TOOL_CALL_INCOMPLETE -> R.string.omni_agent_error_incomplete_tool
    AgentRuntimeErrorSupport.HARNESS_PREPARATION_IN_PROGRESS -> R.string.omni_agent_error_install_busy
    else -> null
}

private fun nonEmptyLines(source: String): List<String> =
    source.split('\n').map(String::trim).filter(String::isNotEmpty)

private fun parseEnvironment(source: String): Map<String, String> {
    val environment = linkedMapOf<String, String>()
    source.split('\n').forEach { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        if (key.isEmpty()) return@forEach
        environment[key] = line.substring(separator + 1)
    }
    return environment
}
