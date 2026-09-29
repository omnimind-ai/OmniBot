package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.llm.*
import cn.com.omnimind.bot.model.ProviderModelCatalogService
import cn.com.omnimind.bot.model.SceneModelSettingsRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class NativeSceneModelsViewModel(private val context: Context) : ViewModel() {
    private val repository = SceneModelSettingsRepository(context)
    private val catalog = ProviderModelCatalogService(context)
    private val mutableState = MutableStateFlow(SceneModelsState())
    private var refreshJob: Job? = null
    private var generation = 0
    val state = mutableState.asStateFlow()
    val actions = SceneModelsActions({ refresh(forceRefresh = true) }, ::selectModel, ::restoreDefault, ::setAutoPlay) {
        mutableState.update { it.copy(notice = null) }
    }

    fun refresh(forceRefresh: Boolean = false) {
        if (state.value.savingSceneId != null || state.value.voiceBusy) return
        refreshJob?.cancel()
        val currentGeneration = ++generation
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        refreshJob = viewModelScope.launch refresh@{
            try {
                val profiles = withContext(Dispatchers.IO) { repository.profiles() }
                val initial = withContext(Dispatchers.IO) {
                    val capabilities = setOf("text", "embedding")
                    val groups = profiles.map { profile ->
                        val manual = if (OmniOfficialProvider.isOfficialProfile(profile.id)) emptyList()
                            else repository.manualModelIds(profile.id)
                        SceneProviderGroup(profile.id, profile.name, profile.isConfigured(),
                            capabilities.associateWith { manual },
                            loadingCapabilities = if (profile.isConfigured() || OmniOfficialProvider.isOfficialProfile(profile.id))
                                capabilities else emptySet())
                    }
                    configuration(SceneModelsState(loaded = true, providers = groups, avatar = loadAvatar()))
                }
                if (currentGeneration != generation) return@refresh
                mutableState.value = initial
                profiles.forEach { profile ->
                    if (!profile.isConfigured() && !OmniOfficialProvider.isOfficialProfile(profile.id)) return@forEach
                    val capabilities = if (OmniOfficialProvider.isOfficialProfile(profile.id)) listOf("text", "embedding")
                        else listOf("text")
                    capabilities.forEach { capability ->
                        launch discovery@{
                            try {
                                val models = withContext(Dispatchers.IO) {
                                    val remote = catalog.fetch(profile.id, capability, forceRefresh = forceRefresh,
                                        expectedRevision = profile.revision, expectedBaseUrl = profile.baseUrl)
                                    val manual = if (OmniOfficialProvider.isOfficialProfile(profile.id)) emptyList()
                                        else repository.manualModelIds(profile.id)
                                    (remote.map { it.id } + manual).map(String::trim)
                                        .filter(SceneModelBindingStore::isValidModelName).distinct()
                                }
                                if (currentGeneration != generation) return@discovery
                                updateModels(profile.id, capability, models, failed = false,
                                    official = OmniOfficialProvider.isOfficialProfile(profile.id))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                if (currentGeneration == generation) updateModels(profile.id, capability, null,
                                    failed = true, official = OmniOfficialProvider.isOfficialProfile(profile.id))
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (currentGeneration == generation) mutableState.update {
                    it.copy(loading = false, loadFailed = true,
                        providers = it.providers.map { group -> group.copy(loadingCapabilities = emptySet()) },
                        notice = R.string.omni_scene_load_failed)
                }
            }
        }
    }

    private fun updateModels(providerId: String, capability: String, models: List<String>?, failed: Boolean, official: Boolean) {
        val affected = if (official) setOf(capability) else setOf("text", "embedding")
        mutableState.update { state -> state.copy(providers = state.providers.map { group ->
            if (group.id != providerId) group else group.copy(
                modelsByCapability = if (models == null) group.modelsByCapability else
                    group.modelsByCapability + affected.associateWith { models },
                loadingCapabilities = group.loadingCapabilities - affected,
                failedCapabilities = if (failed) group.failedCapabilities + affected else group.failedCapabilities - affected,
            )
        }) }
    }

    private fun selectModel(sceneId: String, providerId: String, modelId: String) {
        val provider = state.value.providers.firstOrNull { it.id == providerId } ?: return
        if (!provider.configured || !SceneModelBindingStore.isValidModelName(modelId)) return
        val row = state.value.scenes.firstOrNull { it.id == sceneId } ?: return
        if (row.providerId == providerId && row.modelId == modelId) return
        mutateBinding(sceneId) { repository.saveBinding(sceneId, providerId, modelId) }
    }

    private fun restoreDefault(sceneId: String) {
        if (state.value.scenes.none { it.id == sceneId && it.modelId != null }) return
        mutateBinding(sceneId) { repository.clearBinding(sceneId) }
    }

    private fun mutateBinding(sceneId: String, mutation: suspend () -> Unit) {
        if (!state.value.loaded || state.value.savingSceneId != null || state.value.voiceBusy || state.value.loading) return
        mutableState.update { it.copy(savingSceneId = sceneId, notice = null) }
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    mutation()
                    configuration(SceneModelsState())
                }
                mutableState.update { it.withConfiguration(config).copy(notice = R.string.omni_scene_saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = R.string.omni_scene_save_failed) } }
            finally { mutableState.update { it.copy(savingSceneId = null) } }
        }
    }

    private fun setAutoPlay(enabled: Boolean) {
        if (!state.value.loaded || !state.value.voiceAvailable || state.value.voiceBusy ||
            state.value.savingSceneId != null || state.value.loading) return
        mutableState.update { it.copy(voiceBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.setAutoPlay(enabled) }
                mutableState.update { it.copy(autoPlay = saved.autoPlay, notice = R.string.omni_scene_voice_saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = R.string.omni_scene_voice_save_failed) } }
            finally { mutableState.update { it.copy(voiceBusy = false) } }
        }
    }

    private fun configuration(base: SceneModelsState): SceneModelsState {
        val profiles = repository.profiles().associateBy { it.id }
        val bindings = SceneModelBindingStore.getBindingMap()
        val order = listOf("scene.dispatch.model", "scene.voice", "scene.compactor.context.chat",
            "scene.memory.embedding", "scene.memory.rollup")
        val scenes = SceneModelCatalogResolver.listCatalogItems().filter { it.sceneId != "scene.compactor.context" }
            .sortedWith(compareBy { order.indexOf(it.sceneId).takeIf { index -> index >= 0 } ?: order.size })
            .map { scene ->
                val binding = bindings[scene.sceneId]
                SceneModelRow(scene.sceneId, scene.description.orEmpty(), scene.defaultModel,
                    binding?.providerProfileId, binding?.providerProfileId?.let { profiles[it]?.name }, binding?.modelId)
            }
        val voice = SceneVoiceConfigStore.getConfig()
        val available = bindings[SceneVoiceConfigStore.SCENE_ID] != null ||
            (voice.ttsMode == SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL && voice.customCurlCommand.isNotBlank())
        return base.copy(scenes = scenes, autoPlay = voice.autoPlay, voiceAvailable = available,
            voiceId = voice.voiceId, stylePreset = voice.stylePreset)
    }

    private fun SceneModelsState.withConfiguration(other: SceneModelsState) = copy(
        scenes = other.scenes, autoPlay = other.autoPlay, voiceAvailable = other.voiceAvailable,
        voiceId = other.voiceId, stylePreset = other.stylePreset,
    )

    private fun loadAvatar(): ImageBitmap? = loadAgentAvatarPreview(context)

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val application = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeSceneModelsViewModel(application) as T
    }
}
