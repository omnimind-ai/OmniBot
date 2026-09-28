package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.model.ProviderEditorRepository
import cn.com.omnimind.bot.model.ProviderModelCatalogService
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped editor; Provider and preferences stores remain the only persistent owners. */
internal class NativeModelProviderViewModel(private val context: Context) : ViewModel() {
    private val repository = ProviderEditorRepository(context)
    private val catalog = ProviderModelCatalogService(context)
    private val mutableState = MutableStateFlow(ModelProviderState())
    private var persisted: ModelProviderProfile? = null
    private var manualIds: List<String> = emptyList()
    private var hiddenIds: Set<String> = emptySet()
    private var remoteModels: List<ProviderModelOption> = emptyList()
    private var apiKeyDirty = false
    private var headersDirty = false
    private var nextHeaderId = 0L
    private var saveJob: Job? = null
    private var loadJob: Job? = null
    private var fetchJob: Job? = null
    val state = mutableState.asStateFlow()

    val actions = ModelProviderActions(
        refresh = ::refresh,
        setName = { value -> edit { it.copy(name = value) } },
        setBaseUrl = { value -> edit { it.copy(baseUrl = value) } },
        setApiKey = { value -> apiKeyDirty = true; edit { it.copy(apiKey = value) } },
        setSourceType = ::setSourceType,
        setWireApi = { value -> edit { it.copy(wireApi = value) }; scheduleSave() },
        addHeader = ::addHeader,
        setHeader = ::setHeader,
        removeHeader = ::removeHeader,
        save = { saveDraft() },
        selectProfile = ::selectProfile,
        addProfile = ::addProfile,
        deleteProfile = ::deleteProfile,
        fetchModels = ::fetchModels,
        addModel = ::addModel,
        removeModel = ::removeModel,
        setModelVisible = ::setModelVisible,
        hideAllRemote = ::hideAllRemote,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun loadIfNeeded() { if (!state.value.loaded && !state.value.loading) refresh() }

    fun refreshIfClean() { if (state.value.loaded && !state.value.dirty && !state.value.busy && !state.value.fetching) refresh() }

    fun onEditorBlur() { if (state.value.dirty) scheduleSave() }

    fun onLeave() { if (state.value.dirty) saveDraft() }

    fun refresh() {
        if (state.value.busy || state.value.fetching) return
        saveJob?.cancel()
        loadJob?.cancel()
        mutableState.update { it.copy(loading = true, loadFailed = false, notice = null) }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val profiles = repository.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
                    val editing = repository.editingId()
                    val current = profiles.firstOrNull { it.id == editing } ?: profiles.firstOrNull()
                    Triple(profiles, current, current?.let { loadLists(it) })
                }
                val (profiles, current, lists) = snapshot
                if (current == null) {
                    persisted = null
                    mutableState.value = ModelProviderState(loaded = true, profiles = emptyList())
                } else {
                    persisted = current
                    manualIds = lists!!.first
                    hiddenIds = lists.second.toSet()
                    remoteModels = lists.third
                    apiKeyDirty = false
                    headersDirty = false
                    mutableState.value = editorState(profiles, current)
                    if (current.isConfigured()) fetchModels()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, loadFailed = true,
                notice = R.string.omni_provider_load_failed) } }
        }
    }

    private fun loadLists(profile: ModelProviderProfile): Triple<List<String>, List<String>, List<ProviderModelOption>> =
        Triple(repository.manualIds(profile.id), repository.hiddenIds(profile.id),
            ModelProviderConfigStore.cachedModels(context, profile))

    private fun editorState(profiles: List<ModelProviderProfile>, current: ModelProviderProfile): ModelProviderState =
        ModelProviderState(loaded = true, profiles = profiles.map { it.summary() }, editingId = current.id,
            name = current.name, baseUrl = current.baseUrl,
            requestUrlHint = repository.requestUrl(current.baseUrl, current.protocolType, current.wireApi),
            apiKey = current.apiKey,
            headers = current.customHeaders.map { (name, value) -> ProviderHeaderDraft(++nextHeaderId, name, value) },
            sourceType = current.sourceType, protocolType = current.protocolType, wireApi = current.wireApi,
            models = displayModels())

    private fun displayModels(): List<ProviderModelRow> {
        val manual = manualIds.map { id -> ProviderModelRow(id, id, manual = true, visibleInChat = id !in hiddenIds) }
        val manualSet = manualIds.toSet()
        val remote = remoteModels.filter { it.id !in manualSet }.map { model ->
            ProviderModelRow(model.id, model.displayName.ifBlank { model.id }, manual = false,
                visibleInChat = model.id !in hiddenIds, group = model.group.orEmpty(),
                contextLimit = model.contextLimit, inputModalities = model.inputModalities,
                outputModalities = model.outputModalities, reasoning = model.reasoning,
                toolCall = model.toolCall, attachment = model.attachment)
        }
        return manual + remote
    }

    private fun edit(transform: (ModelProviderState) -> ModelProviderState) {
        if (!state.value.loaded || state.value.busy || state.value.fetching || state.value.current?.readOnly == true) return
        mutableState.update { old ->
            val updated = transform(old)
            updated.copy(dirty = true, notice = null,
                requestUrlHint = repository.requestUrl(updated.baseUrl, updated.protocolType, updated.wireApi))
        }
    }

    private fun setSourceType(source: String, protocol: String, wireApi: String, baseUrl: String?, name: String?) {
        edit { state -> state.copy(sourceType = source, protocolType = protocol, wireApi = wireApi,
            baseUrl = baseUrl ?: state.baseUrl, name = name ?: state.name) }
        scheduleSave()
    }

    private fun addHeader() {
        if (!state.value.loaded || state.value.busy || state.value.fetching || state.value.current?.readOnly == true) return
        mutableState.update { it.copy(headers = it.headers + ProviderHeaderDraft(++nextHeaderId)) }
    }
    private fun setHeader(id: Long, name: String?, value: String?) {
        headersDirty = true
        edit { state -> state.copy(headers = state.headers.map { row ->
            if (row.id == id) row.copy(name = name ?: row.name, value = value ?: row.value) else row
        }) }
    }
    private fun removeHeader(id: Long) {
        val row = state.value.headers.firstOrNull { it.id == id } ?: return
        if (row.name.isNotBlank() || row.value.isNotBlank()) headersDirty = true
        edit { it.copy(headers = it.headers.filterNot { row -> row.id == id }) }
        if (headersDirty) scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            saveDraft()
        }
    }

    private fun saveDraft(after: (() -> Unit)? = null) {
        saveJob?.cancel()
        if (!state.value.dirty) { after?.invoke(); return }
        if (state.value.busy || state.value.fetching) return
        val before = persisted ?: return
        val draft = state.value
        if (draft.baseUrl.isNotBlank() && !ModelProviderConfigStore.isValidBaseUrl(draft.baseUrl)) {
            mutableState.update { it.copy(notice = R.string.omni_provider_invalid_url) }
            return
        }
        if (headersDirty && runCatching { repository.validateHeaders(draft.headers.map { it.name to it.value }) }.isFailure) {
            mutableState.update { it.copy(notice = R.string.omni_provider_invalid_headers) }
            return
        }
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val current = ModelProviderConfigStore.getProfile(before.id)
                    check(current?.revision == before.revision) { "Provider changed in another editor" }
                    val headers = if (headersDirty)
                        repository.validateHeaders(draft.headers.map { it.name to it.value }) else null
                    repository.save(before.id, draft.name.ifBlank { before.name }, draft.baseUrl,
                        if (apiKeyDirty) draft.apiKey else null, headers,
                        draft.sourceType, draft.protocolType, draft.wireApi)
                }
                persisted = saved
                remoteModels = emptyList()
                apiKeyDirty = false
                headersDirty = false
                mutableState.update { current -> current.copy(
                    profiles = current.profiles.map { if (it.id == saved.id) saved.summary() else it },
                    models = displayModels(), dirty = false, busy = false,
                    notice = R.string.omni_provider_saved) }
                after?.invoke()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(busy = false,
                notice = if (ModelProviderConfigStore.getProfile(before.id)?.revision != before.revision)
                    R.string.omni_provider_changed_elsewhere else R.string.omni_provider_save_failed) } }
        }
    }

    private fun selectProfile(id: String) {
        if (id == state.value.editingId || state.value.busy) return
        stopFetch()
        saveDraft {
            mutateProfile {
                val selected = repository.select(id)
                selected to repository.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
            }
        }
    }

    private fun addProfile(name: String) {
        if (name.isBlank() || state.value.busy) return
        stopFetch()
        saveDraft {
            mutateProfile {
                val added = repository.save(null, name.trim(), "", "", emptyMap(),
                    "custom", "openai_compatible", "chat_completions")
                added to repository.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
            }
        }
    }

    private fun deleteProfile() {
        val id = state.value.editingId
        if (id.isBlank() || state.value.profiles.size <= 1 || state.value.busy) return
        stopFetch()
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val profiles = repository.delete(id).filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
                    val selected = profiles.firstOrNull { it.id == repository.editingId() } ?: profiles.first()
                    Triple(profiles, selected, loadLists(selected))
                }
                applySelected(snapshot.first, snapshot.second, snapshot.third)
                mutableState.update { it.copy(notice = R.string.omni_provider_deleted) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(busy = false,
                notice = R.string.omni_provider_delete_failed) } }
        }
    }

    private fun mutateProfile(action: () -> Pair<ModelProviderProfile, List<ModelProviderProfile>>) {
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val (selected, profiles) = action()
                    Triple(profiles, selected, loadLists(selected))
                }
                applySelected(snapshot.first, snapshot.second, snapshot.third)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(busy = false,
                notice = R.string.omni_provider_save_failed) } }
        }
    }

    private fun applySelected(profiles: List<ModelProviderProfile>, selected: ModelProviderProfile,
        lists: Triple<List<String>, List<String>, List<ProviderModelOption>>) {
        persisted = selected
        manualIds = lists.first
        hiddenIds = lists.second.toSet()
        remoteModels = lists.third
        apiKeyDirty = false
        headersDirty = false
        mutableState.value = editorState(profiles, selected)
        if (selected.isConfigured()) fetchModels()
    }

    private fun fetchModels() {
        if (persisted == null) return
        if (state.value.busy || state.value.fetching) return
        if (state.value.baseUrl.isBlank()) {
            mutableState.update { it.copy(notice = R.string.omni_provider_url_required) }
            return
        }
        saveDraft {
            val saved = persisted ?: return@saveDraft
            mutableState.update { it.copy(fetching = true, modelFetchFailed = false, notice = null) }
            fetchJob = viewModelScope.launch {
                try {
                    val models = withContext(Dispatchers.IO) {
                        catalog.fetch(saved.id, expectedRevision = saved.revision,
                            expectedBaseUrl = saved.baseUrl)
                    }
                    if (persisted?.id == saved.id && persisted?.revision == saved.revision) {
                        remoteModels = models
                        mutableState.update { it.copy(models = displayModels(), fetching = false,
                            modelFetchFailed = false, notice = R.string.omni_provider_models_fetched) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(fetching = false,
                    modelFetchFailed = true, notice = R.string.omni_provider_models_failed) } }
            }
        }
    }

    private fun stopFetch() {
        fetchJob?.cancel()
        fetchJob = null
        mutableState.update { it.copy(fetching = false) }
    }

    private fun addModel(id: String) {
        val normalized = id.trim()
        if (!SceneModelBindingStore.isValidModelName(normalized) || state.value.models.any { it.id == normalized }) {
            mutableState.update { it.copy(notice = R.string.omni_provider_invalid_model) }
            return
        }
        updateModels { profileId -> repository.saveManualIds(profileId, manualIds + normalized)
            manualIds = manualIds + normalized }
    }

    private fun removeModel(id: String) {
        updateModels { profileId ->
            repository.saveManualIds(profileId, manualIds.filterNot { it == id })
            manualIds = manualIds.filterNot { it == id }
            remoteModels = remoteModels.filterNot { it.id == id }
        }
    }

    private fun setModelVisible(id: String, visible: Boolean) {
        updateModels { profileId ->
            val next = if (visible) hiddenIds - id else hiddenIds + id
            repository.saveHiddenIds(profileId, next.toList())
            hiddenIds = next
        }
    }

    private fun hideAllRemote() {
        updateModels { profileId ->
            val next = hiddenIds + remoteModels.map { it.id }
            repository.saveHiddenIds(profileId, next.toList())
            hiddenIds = next
        }
    }

    private fun updateModels(change: (String) -> Unit) {
        val profileId = persisted?.id ?: return
        if (state.value.busy || state.value.fetching) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { change(profileId) }
                mutableState.update { it.copy(models = displayModels(), busy = false,
                    notice = R.string.omni_provider_models_saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(busy = false,
                notice = R.string.omni_provider_models_save_failed) } }
        }
    }

    private fun ModelProviderProfile.summary(): ProviderSummary = ProviderSummary(
        id, name, readOnly, isConfigured(), ready, statusText.orEmpty(), revision)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeModelProviderViewModel(app) as T
    }
}
