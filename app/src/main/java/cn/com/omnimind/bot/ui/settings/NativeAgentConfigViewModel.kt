package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.NativeAgentsRepository
import cn.com.omnimind.bot.agent.NativeAgentProfile
import cn.com.omnimind.bot.agent.NativeCustomAgentDraft
import cn.com.omnimind.bot.agent.isAgentConfigRevisionConflict
import cn.com.omnimind.bot.model.ProviderEditorRepository
import cn.com.omnimind.bot.model.ProviderModelCatalogService
import cn.com.omnimind.bot.model.SceneModelSettingsRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.AgentConfigActions
import cn.com.omnimind.nativeui.settings.AgentConfigDraft
import cn.com.omnimind.nativeui.settings.AgentConfigProviderGroup
import cn.com.omnimind.nativeui.settings.AgentConfigState
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-Agent configuration editor. `agent/config/read|write` (with the runtime's
 * expectedRevision optimistic lock) and `agent/save`/`agent/delete` stay behind
 * [NativeAgentsRepository]; the shared dispatch binding stays behind
 * [SceneModelSettingsRepository], whose saveBinding already invalidates the
 * shared runtime. Like the Flutter page, a successful binding save then calls
 * the runtime's existing disconnect so the next ACP start picks it up.
 */
internal class NativeAgentConfigViewModel(
    context: Context,
    private val agentId: String,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val repository = NativeAgentsRepository(appContext)
    private val sceneRepository = SceneModelSettingsRepository(appContext)
    private val catalog = ProviderModelCatalogService(appContext)
    private var catalogGeneration = 0
    private var profileDescription = ""
    private val mutableState = MutableStateFlow(AgentConfigState(agentId = agentId))
    val state = mutableState.asStateFlow()

    val actions = AgentConfigActions(
        retry = { load(force = true) },
        editContent = { updateDraft { draft -> draft.copy(content = it) } },
        editCommand = { updateDraft { draft -> draft.copy(command = it) } },
        editArguments = { updateDraft { draft -> draft.copy(arguments = it) } },
        editEnvironment = { updateDraft { draft -> draft.copy(environment = it) } },
        editEnabled = { updateDraft { draft -> draft.copy(enabled = it) } },
        setReasoningEffort = { updateDraft { draft -> draft.copy(reasoningEffort = it) } },
        setPermissionMode = { updateDraft { draft -> draft.copy(permissionMode = it) } },
        save = ::save,
        openPicker = ::openPicker,
        closePicker = { mutableState.update { it.copy(pickerOpen = false) } },
        selectSharedModel = ::selectSharedModel,
        retryProvider = ::retryProvider,
        showDeleteConfirm = { show ->
            if (!state.value.saving) mutableState.update { it.copy(confirmDelete = show) }
        },
        deleteAgent = ::deleteAgent,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || current.saving) return
        if (current.loaded && !force) return
        mutableState.update { it.copy(loading = true, loadErrorRes = null, notice = null) }
        viewModelScope.launch {
            try {
                val agent = withContext(Dispatchers.IO) { repository.readProfile(agentId) }
                profileDescription = agent.description
                if (!agent.builtIn) {
                    mutableState.update {
                        it.copy(
                            agentName = agent.name,
                            builtIn = false,
                            loaded = true,
                            loading = false,
                            kind = "profile",
                            draft = profileDraft(agent),
                        )
                    }
                    return@launch
                }
                val config = withContext(Dispatchers.IO) { repository.readAgentConfig(agentId) }
                mutableState.update {
                    it.copy(
                        agentName = agent.name,
                        builtIn = true,
                        loaded = true,
                        loading = false,
                        kind = config.kind,
                        revision = config.revision,
                        configPath = config.configPath,
                        authPath = config.authPath,
                        draft = AgentConfigDraft(
                            content = config.content,
                            command = agent.command,
                            arguments = agent.arguments.joinToString("\n"),
                            environment = agent.environment.entries
                                .joinToString("\n") { entry -> "${entry.key}=${entry.value}" },
                            enabled = agent.enabled,
                            reasoningEffort = config.reasoningEffort,
                            permissionMode = config.permissionMode,
                        ),
                    )
                }
                if (state.value.hasSharedModelSelector) loadSharedModelSelection()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgentConfig", "Agent config load failed", error)
                mutableState.update {
                    it.copy(loading = false, loadErrorRes = R.string.omni_agent_config_load_failed)
                }
            }
        }
    }

    private fun profileDraft(agent: NativeAgentProfile) = AgentConfigDraft(
        command = agent.command,
        arguments = agent.arguments.joinToString("\n"),
        environment = agent.environment.entries.joinToString("\n") { "${it.key}=${it.value}" },
        enabled = agent.enabled,
    )

    /** Page load reads persisted catalogs only; live fetches wait for the picker. */
    private suspend fun loadSharedModelSelection() {
        mutableState.update { it.copy(sharedLoading = true) }
        try {
            val (providers, binding) = withContext(Dispatchers.IO) {
                val profiles = sceneRepository.profiles()
                val groups = profiles.map { profile ->
                    AgentConfigProviderGroup(
                        id = profile.id,
                        name = profile.name,
                        configured = profile.isConfigured() ||
                            OmniOfficialProvider.isOfficialProfile(profile.id),
                        models = persistedModels(profile),
                    )
                }
                groups to sceneRepository.dispatchBinding()
            }
            mutableState.update {
                it.copy(
                    sharedLoading = false,
                    providers = providers,
                    boundProviderId = binding?.providerProfileId,
                    boundModelId = binding?.modelId,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativeAgentConfig", "Shared model selection load failed", error)
            mutableState.update { it.copy(sharedLoading = false) }
        }
    }

    private fun persistedModels(profile: ModelProviderProfile): List<String> {
        if (OmniOfficialProvider.isOfficialProfile(profile.id)) return emptyList()
        val cached = ModelProviderConfigStore.cachedModels(appContext, profile).map { it.id }
        val manual = ProviderEditorRepository(appContext).manualIds(profile.id)
        return (cached + manual).map(String::trim)
            .filter(SceneModelBindingStore::isValidModelName)
            .distinct()
    }

    private fun openPicker() {
        if (state.value.sharedSaving || state.value.saving) return
        mutableState.update { it.copy(pickerOpen = true) }
        // Opening the picker refreshes configured Providers live, as the Flutter
        // selector does; a failure keeps the persisted/manual list.
        val generation = ++catalogGeneration
        state.value.providers.filter { it.configured }.forEach { provider ->
            fetchProvider(provider.id, generation)
        }
    }

    private fun retryProvider(providerId: String) = fetchProvider(providerId, catalogGeneration)

    private fun fetchProvider(providerId: String, generation: Int) {
        val group = state.value.providers.firstOrNull { it.id == providerId } ?: return
        if (!group.configured || group.loading) return
        mutableState.update { state ->
            state.copy(providers = state.providers.map {
                if (it.id == providerId) it.copy(loading = true, failed = false) else it
            })
        }
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    sceneRepository.profiles().firstOrNull { it.id == providerId }
                } ?: return@launch
                val models = withContext(Dispatchers.IO) {
                    val remote = catalog.fetch(
                        profile.id, "text", forceRefresh = false,
                        expectedRevision = profile.revision, expectedBaseUrl = profile.baseUrl,
                    )
                    val manual = if (OmniOfficialProvider.isOfficialProfile(profile.id)) {
                        emptyList()
                    } else {
                        ProviderEditorRepository(appContext).manualIds(profile.id)
                    }
                    (remote.map { it.id } + manual).map(String::trim)
                        .filter(SceneModelBindingStore::isValidModelName)
                        .distinct()
                }
                if (generation != catalogGeneration) return@launch
                mutableState.update { state ->
                    state.copy(providers = state.providers.map {
                        if (it.id == providerId) it.copy(models = models, loading = false) else it
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgentConfig", "Provider catalog refresh failed", error)
                if (generation != catalogGeneration) return@launch
                mutableState.update { state ->
                    state.copy(providers = state.providers.map {
                        if (it.id == providerId) it.copy(loading = false, failed = true) else it
                    })
                }
            }
        }
    }

    private fun selectSharedModel(providerId: String, modelId: String) {
        val current = state.value
        if (current.sharedSaving || current.sharedLoading || current.saving) return
        val provider = current.providers.firstOrNull { it.id == providerId } ?: return
        if (!provider.configured || !SceneModelBindingStore.isValidModelName(modelId)) return
        if (current.boundProviderId == providerId && current.boundModelId == modelId) return
        mutableState.update { it.copy(pickerOpen = false, sharedSaving = true, notice = null) }
        viewModelScope.launch {
            try {
                val bindings = withContext(Dispatchers.IO) {
                    sceneRepository.saveBinding(SCENE_DISPATCH_MODEL, providerId, modelId)
                }
                // Same owner as the Flutter page: the next ACP start re-reads the binding.
                runCatching {
                    withContext(Dispatchers.IO) { repository.disconnectRuntime() }
                }.onFailure {
                    OmniLog.w("NativeAgentConfig", "Runtime disconnect after binding save failed")
                }
                val binding = bindings.firstOrNull { it.sceneId == SCENE_DISPATCH_MODEL }
                mutableState.update {
                    it.copy(
                        sharedSaving = false,
                        boundProviderId = binding?.providerProfileId,
                        boundModelId = binding?.modelId,
                        notice = R.string.omni_agent_shared_model_updated,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgentConfig", "Shared model save failed", error)
                mutableState.update {
                    it.copy(sharedSaving = false, notice = R.string.omni_agent_shared_model_failed)
                }
            }
        }
    }

    private fun save() {
        val current = state.value
        if (current.saving || !current.loaded || current.kind.isEmpty()) return
        when (current.kind) {
            "json" -> {
                // Same pre-check as the Claude adapter: the file must stay a JSON object.
                val valid = runCatching {
                    JsonParser.parseString(current.draft.content).isJsonObject
                }.getOrDefault(false)
                if (!valid) {
                    mutableState.update { it.copy(notice = R.string.omni_agent_config_invalid_json) }
                    return
                }
            }
            "profile" -> {
                if (current.draft.command.trim().isEmpty()) {
                    mutableState.update { it.copy(notice = R.string.omni_agent_command_required) }
                    return
                }
            }
        }
        mutableState.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            try {
                when (current.kind) {
                    "profile" -> saveProfile()
                    else -> saveHarnessConfig()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgentConfig", "Agent config save failed", error)
                val notice = if (isAgentConfigRevisionConflict(error)) {
                    R.string.omni_agent_config_changed_elsewhere
                } else {
                    R.string.omni_agent_config_save_failed
                }
                // A failed save keeps the draft intact.
                mutableState.update { it.copy(saving = false, notice = notice) }
            }
        }
    }

    private suspend fun saveHarnessConfig() {
        val draft = state.value.draft
        val config = withContext(Dispatchers.IO) {
            repository.writeAgentConfig(
                agentId = agentId,
                content = when (state.value.kind) {
                    "json", "jsonc" -> draft.content
                    else -> null
                },
                reasoningEffort = draft.reasoningEffort,
                permissionMode = draft.permissionMode,
                expectedRevision = state.value.revision.takeIf { it > 0 },
            )
        }
        mutableState.update {
            it.copy(
                saving = false,
                revision = config.revision,
                configPath = config.configPath,
                authPath = config.authPath,
                draft = it.draft.copy(
                    content = config.content,
                    reasoningEffort = config.reasoningEffort,
                    permissionMode = config.permissionMode,
                ),
                notice = R.string.omni_agent_config_saved,
            )
        }
    }

    private suspend fun saveProfile() {
        val current = state.value
        val catalogResult = withContext(Dispatchers.IO) {
            repository.saveAgent(
                NativeCustomAgentDraft(
                    id = agentId,
                    name = current.agentName,
                    description = profileDescription,
                    command = current.draft.command.trim(),
                    arguments = nonEmptyLines(current.draft.arguments),
                    environment = parseEnvironment(current.draft.environment),
                    enabled = current.draft.enabled,
                ),
            )
        }
        val saved = catalogResult.agents.firstOrNull { it.id == agentId }
        mutableState.update {
            it.copy(
                saving = false,
                draft = if (saved != null) it.draft.copy(
                    command = saved.command,
                    arguments = saved.arguments.joinToString("\n"),
                    environment = saved.environment.entries
                        .joinToString("\n") { entry -> "${entry.key}=${entry.value}" },
                    enabled = saved.enabled,
                ) else it.draft,
                notice = R.string.omni_agent_config_saved,
            )
        }
    }

    private fun deleteAgent() {
        val current = state.value
        if (current.builtIn || current.saving || !current.confirmDelete) return
        mutableState.update { it.copy(confirmDelete = false, saving = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.deleteAgent(agentId) }
                mutableState.update { it.copy(saving = false, deleted = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAgentConfig", "Agent delete failed", error)
                mutableState.update {
                    it.copy(saving = false, notice = R.string.omni_agent_delete_failed)
                }
            }
        }
    }

    private fun updateDraft(change: (AgentConfigDraft) -> AgentConfigDraft) {
        if (state.value.saving) return
        mutableState.update { it.copy(draft = change(it.draft)) }
    }

    class Factory(context: Context, private val agentId: String) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeAgentConfigViewModel(appContext, agentId) as T
    }

    private companion object {
        const val SCENE_DISPATCH_MODEL = "scene.dispatch.model"
    }
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
