package cn.com.omnimind.bot.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OfficialProviderRegistry
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.model.ProviderEditorRepository
import cn.com.omnimind.bot.model.ProviderModelCatalogService
import cn.com.omnimind.bot.model.SceneModelSettingsRepository
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import cn.com.omnimind.bot.terminal.NativeTerminalSettingsRepository
import cn.com.omnimind.nativeui.onboarding.ONBOARDING_SCENES
import cn.com.omnimind.nativeui.onboarding.OnboardingActions
import cn.com.omnimind.nativeui.onboarding.OnboardingFlow
import cn.com.omnimind.nativeui.onboarding.OnboardingNotice
import cn.com.omnimind.nativeui.onboarding.OnboardingNotice.Kind
import cn.com.omnimind.nativeui.onboarding.OnboardingPage
import cn.com.omnimind.nativeui.onboarding.OnboardingProfile
import cn.com.omnimind.nativeui.onboarding.OnboardingState
import cn.com.omnimind.nativeui.onboarding.PROVIDER_OPTIONS
import cn.com.omnimind.nativeui.onboarding.ProviderFormError
import cn.com.omnimind.nativeui.onboarding.decodeOnboardingFlow
import cn.com.omnimind.nativeui.onboarding.defaultSceneSelections
import cn.com.omnimind.nativeui.onboarding.encode
import cn.com.omnimind.nativeui.onboarding.environmentPhase
import cn.com.omnimind.nativeui.onboarding.nextEnvironmentProgress
import cn.com.omnimind.nativeui.onboarding.onboardingPackageIds
import cn.com.omnimind.nativeui.onboarding.profileToOverwrite
import cn.com.omnimind.nativeui.onboarding.providerOptionFor
import cn.com.omnimind.nativeui.onboarding.rebalancedSceneSelections
import cn.com.omnimind.nativeui.onboarding.resumableOnboardingProfile
import cn.com.omnimind.nativeui.onboarding.validateProviderForm
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native first-use onboarding (batch 5f-1b) over the owners the Flutter
 * controllers reached through channels: [EmbeddedTerminalInitCoordinator]
 * for the install, [ProviderEditorRepository] and
 * [ProviderModelCatalogService] for the connection, and
 * [SceneModelSettingsRepository] for the scene bindings. The page, its back
 * stack and the form survive process death in [SavedStateHandle].
 */
internal class NativeOnboardingViewModel(
    context: Context,
    replay: Boolean,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val providers = ProviderEditorRepository(appContext)
    private val catalog = ProviderModelCatalogService(appContext)
    private val scenes = SceneModelSettingsRepository(appContext)
    private val terminal = NativeTerminalSettingsRepository(appContext)
    private val preferences = appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private val mutableState = MutableStateFlow(restore(replay))
    val state = mutableState.asStateFlow()

    private var nativeProgress = 0f
    private var environmentJob: Job? = null
    private var tickerJob: Job? = null
    private var providerLoaded = false
    private val progressListener: (Map<String, Any?>) -> Unit = { readSnapshot() }

    val actions = OnboardingActions(
        goTo = ::goTo,
        next = { state.value.nextPage?.let(::goTo) },
        back = ::back,
        jumpTo = { page -> setFlow(state.value.flow.jumpToVisited(page)) },
        selectDistribution = { id -> editEnvironment { it.copy(distribution = id) } },
        selectPreset = { id -> editEnvironment { it.copy(presetId = id) } },
        toggleTool = { id -> editEnvironment { it.copy(toolIds = if (id in it.toolIds) it.toolIds - id else it.toolIds + id) } },
        startEnvironment = ::startEnvironment,
        cancelEnvironment = { EmbeddedTerminalInitCoordinator.cancelCurrent() },
        openAccount = {}, // The host hands sign-in to the Flutter account page.
        chooseProvider = ::chooseProvider,
        updateName = { value -> editForm { it.copy(name = value, notice = null) } },
        updateBaseUrl = { value -> editForm { it.copy(baseUrl = value, notice = null) } },
        updateApiKey = { value -> editForm { it.copy(apiKey = value, notice = null) } },
        connect = ::connect,
        addModel = ::addModel,
        selectSceneModel = { sceneId, modelId ->
            mutableState.update { it.copy(provider = it.provider.copy(sceneSelections = it.provider.sceneSelections + (sceneId to modelId),
                pickedScenes = it.provider.pickedScenes + sceneId)) }
        },
        saveScenes = ::saveScenes,
        complete = ::complete,
    )

    init {
        EmbeddedTerminalInitCoordinator.addListener(progressListener)
        viewModelScope.launch {
            val distribution = runCatching { withContext(Dispatchers.IO) { terminal.selectedDistribution().id } }.getOrNull()
            mutableState.update { current ->
                // A restored choice wins over the stored distribution.
                val chosen = savedState.get<String>(KEY_DISTRIBUTION) ?: distribution ?: current.environment.distribution
                current.copy(environment = current.environment.copy(distributionLoading = false, distribution = chosen))
            }
        }
        // An install that outlived the process (or the Activity) is followed, not restarted.
        val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
        if (snapshot["running"] == true) {
            mutableState.update { it.copy(environment = it.environment.copy(busy = true, progress = 0.02f)) }
            readSnapshot()
            startTicker()
            environmentJob = viewModelScope.launch { awaitRunningInstall() }
        }
        if (state.value.flow.page.needsProvider) loadProvider()
    }

    private fun restore(replay: Boolean): OnboardingState {
        val option = PROVIDER_OPTIONS.firstOrNull { it.id == savedState.get<String>(KEY_OPTION) } ?: PROVIDER_OPTIONS.first()
        return OnboardingState(
            flow = decodeOnboardingFlow(savedState.get<String>(KEY_FLOW)),
            replay = replay,
            accountAvailable = BuildConfig.BASE_URL.isNotBlank(),
        ).let { state ->
            state.copy(
                environment = state.environment.copy(
                    presetId = savedState.get<String>(KEY_PRESET) ?: state.environment.presetId,
                    toolIds = savedState.get<ArrayList<String>>(KEY_TOOLS)?.toSet() ?: emptySet(),
                    // A finished install must still read as done after process death.
                    ready = savedState.get<Boolean>(KEY_ENV_READY) == true,
                    progress = if (savedState.get<Boolean>(KEY_ENV_READY) == true) 1f else 0f,
                ),
                provider = state.provider.copy(
                    optionId = option.id,
                    name = savedState.get<String>(KEY_NAME) ?: if (option.id == "custom") "" else option.label,
                    baseUrl = savedState.get<String>(KEY_BASE_URL) ?: option.baseUrl,
                    // The key stays in memory only; SavedStateHandle is written to disk.
                ),
            )
        }
    }

    private val OnboardingPage.needsProvider: Boolean
        get() = ordinal >= OnboardingPage.Provider.ordinal && this != OnboardingPage.Completion

    // ---- Navigation --------------------------------------------------------

    private fun setFlow(flow: OnboardingFlow) {
        if (flow == state.value.flow || state.value.locked) return
        savedState[KEY_FLOW] = flow.encode()
        // A notice belongs to the page that raised it.
        mutableState.update { it.copy(flow = flow, provider = it.provider.copy(notice = null)) }
        if (flow.page.needsProvider) loadProvider()
    }

    private fun goTo(page: OnboardingPage) = setFlow(state.value.flow.goTo(page))

    private fun back() {
        val previous = state.value.flow.goBack() ?: return
        setFlow(previous)
    }

    // ---- Environment ------------------------------------------------------

    private fun editEnvironment(change: (cn.com.omnimind.nativeui.onboarding.EnvironmentSetup) -> cn.com.omnimind.nativeui.onboarding.EnvironmentSetup) {
        val current = state.value.environment
        if (current.busy) return
        val next = change(current).copy(ready = false, failed = false, progress = 0f, stage = "")
        savedState[KEY_DISTRIBUTION] = next.distribution
        savedState[KEY_PRESET] = next.presetId
        savedState[KEY_TOOLS] = ArrayList(next.toolIds)
        savedState[KEY_ENV_READY] = false
        mutableState.update { it.copy(environment = next) }
    }

    private fun startEnvironment() {
        val environment = state.value.environment
        if (environment.busy || environment.distributionLoading) return
        if (state.value.flow.page != OnboardingPage.EnvironmentProgress) goTo(OnboardingPage.EnvironmentProgress)
        nativeProgress = 0.02f
        mutableState.update {
            it.copy(environment = it.environment.copy(busy = true, ready = false, failed = false, progress = 0.02f,
                stage = appContext.getString(cn.com.omnimind.nativeui.R.string.omni_onb_env_saving)))
        }
        startTicker()
        environmentJob = viewModelScope.launch {
            try {
                val target = TerminalDistribution.fromId(environment.distribution)
                // Dart only wrote the setting and closed sessions; the install below prepares the system.
                withContext(Dispatchers.IO) { selectDistribution(target) }
                mutableState.update {
                    it.copy(environment = it.environment.copy(distribution = target.id,
                        stage = appContext.getString(cn.com.omnimind.nativeui.R.string.omni_onb_env_preparing_system, target.displayName)))
                }
                val result = EmbeddedTerminalInitCoordinator.prepare(appContext,
                    onboardingPackageIds(environment.presetId, environment.toolIds))
                finishEnvironment(result.success, result.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e(TAG, "Environment setup failed", error)
                finishEnvironment(false, error.message.orEmpty())
            }
        }
    }

    private fun selectDistribution(target: TerminalDistribution.Spec) {
        if (TerminalDistribution.selected().id == target.id) return
        com.rk.settings.Settings.terminal_distribution = target.workingMode
        com.rk.settings.Settings.working_Mode = target.workingMode
        com.ai.assistance.operit.terminal.TerminalManager.getInstance(appContext).closeAllSessions()
    }

    private suspend fun awaitRunningInstall() {
        while (viewModelScope.isActive) {
            val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
            if (snapshot["running"] != true) {
                finishEnvironment(snapshot["success"] == true, snapshot["stage"]?.toString().orEmpty())
                return
            }
            delay(TICK_MS)
        }
    }

    private suspend fun finishEnvironment(success: Boolean, message: String) {
        tickerJob?.cancel()
        if (success) {
            // A short eased run to 100% instead of a jump (Dart `_completeProgressAnimation`).
            val start = state.value.environment.progress
            for (frame in 1..12) {
                delay(45)
                val t = frame / 12f
                val eased = 1 - (1 - t) * (1 - t)
                mutableState.update { it.copy(environment = it.environment.copy(progress = start + (1 - start) * eased)) }
            }
        }
        savedState[KEY_ENV_READY] = success
        mutableState.update {
            it.copy(environment = it.environment.copy(
                busy = false, ready = success, failed = !success,
                progress = if (success) 1f else it.environment.progress,
                stage = when {
                    success -> it.environment.stage
                    message.isNotBlank() -> message.trim()
                    else -> appContext.getString(cn.com.omnimind.nativeui.R.string.omni_onb_env_failed)
                },
            ))
        }
    }

    private fun readSnapshot() {
        if (!state.value.environment.busy) return
        val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
        if (snapshot["running"] != true) return
        nativeProgress = (snapshot["progress"] as? Number)?.toFloat() ?: nativeProgress
        val stage = snapshot["stage"]?.toString()?.trim().orEmpty()
        if (stage.isNotEmpty()) mutableState.update { it.copy(environment = it.environment.copy(stage = stage)) }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive && state.value.environment.busy) {
                delay(TICK_MS)
                readSnapshot()
                mutableState.update { current ->
                    val environment = current.environment
                    val phase = environmentPhase(environment.stage, maxOf(nativeProgress, environment.progress), false)
                    current.copy(environment = environment.copy(
                        progress = nextEnvironmentProgress(environment.progress, nativeProgress, phase)))
                }
            }
        }
    }

    // ---- Provider ---------------------------------------------------------

    private fun ModelProviderProfile.toOnboarding() = OnboardingProfile(
        id = id, name = name, baseUrl = baseUrl, apiKey = apiKey, sourceType = sourceType,
        builtIn = OfficialProviderRegistry.findByProfileId(id) != null,
    )

    private fun loadProvider() {
        if (providerLoaded || state.value.provider.loading) return
        providerLoaded = true
        mutableState.update { it.copy(provider = it.provider.copy(loading = true)) }
        viewModelScope.launch {
            try {
                val (profiles, editing) = withContext(Dispatchers.IO) {
                    providers.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) } to providers.editingId()
                }
                val resumed = resumableOnboardingProfile(profiles.map { it.toOnboarding() }, editing)
                val profile = resumed?.let { r -> profiles.first { it.id == r.id } }
                if (profile == null || state.value.provider.connected) {
                    mutableState.update { it.copy(provider = it.provider.copy(loading = false)) }
                    return@launch
                }
                val models = withContext(Dispatchers.IO) { storedModels(profile) }.ifEmpty {
                    // Same one-shot discovery as Dart when the cache is still empty.
                    runCatching { fetchModels(profile, explicit = false) }.getOrDefault(emptyList())
                }
                val bindings = withContext(Dispatchers.IO) {
                    SceneModelBindingStore.getBindingEntries().filter { it.providerProfileId == profile.id }
                        .associate { it.sceneId to it.modelId }
                }
                val option = providerOptionFor(profile.toOnboarding(), ModelProviderConfigStore::sameCanonicalEndpoint)
                mutableState.update {
                    it.copy(provider = it.provider.copy(
                        loading = false, optionId = option.id, name = profile.name, baseUrl = profile.baseUrl,
                        apiKey = profile.apiKey, profileId = profile.id, profileName = profile.name, models = models,
                        sceneSelections = defaultSceneSelections(models, bindings),
                    ))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to read Provider settings: ${error.message}")
                mutableState.update { it.copy(provider = it.provider.copy(loading = false, notice = OnboardingNotice(Kind.LoadFailed))) }
            }
        }
    }

    private fun storedModels(profile: ModelProviderProfile): List<String> =
        (ModelProviderConfigStore.cachedModels(appContext, profile).map { it.id } + providers.manualIds(profile.id))
            .map(String::trim).filter { it.isNotEmpty() }.distinct()

    private suspend fun fetchModels(profile: ModelProviderProfile, explicit: Boolean): List<String> =
        withContext(Dispatchers.IO) {
            val fetched = catalog.fetch(profile.id, capability = "text", forceRefresh = explicit,
                expectedRevision = profile.revision, expectedBaseUrl = profile.baseUrl).map { it.id }
            (fetched + providers.manualIds(profile.id)).map(String::trim).filter { it.isNotEmpty() }.distinct()
        }

    private fun editForm(change: (cn.com.omnimind.nativeui.onboarding.ProviderSetup) -> cn.com.omnimind.nativeui.onboarding.ProviderSetup) {
        if (state.value.provider.busy) return
        mutableState.update { it.copy(provider = change(it.provider)) }
        val provider = state.value.provider
        savedState[KEY_NAME] = provider.name
        savedState[KEY_BASE_URL] = provider.baseUrl
    }

    private fun chooseProvider(optionId: String) {
        val provider = state.value.provider
        if (provider.busy || provider.loading) return
        // Choosing the connected option again keeps its form; another option starts a fresh one.
        if (!(provider.connected && provider.optionId == optionId)) {
            val option = PROVIDER_OPTIONS.first { it.id == optionId }
            savedState[KEY_OPTION] = option.id
            mutableState.update {
                it.copy(provider = it.provider.copy(
                    optionId = option.id, name = if (option.id == "custom") "" else option.label, baseUrl = option.baseUrl,
                    apiKey = "", profileId = null, profileName = "", models = emptyList(), sceneSelections = emptyMap(), pickedScenes = emptySet(), notice = null,
                ))
            }
            savedState[KEY_NAME] = state.value.provider.name
            savedState[KEY_BASE_URL] = option.baseUrl
        }
        goTo(OnboardingPage.ProviderConnection)
    }

    private fun connect() {
        val form = state.value.provider
        if (form.busy) return
        val error = validateProviderForm(form.option, form.name, form.baseUrl, form.apiKey, ModelProviderConfigStore::isValidBaseUrl)
        if (error != null) {
            val kind = when (error) {
                ProviderFormError.MissingName -> Kind.MissingName
                ProviderFormError.InvalidBaseUrl -> Kind.InvalidBaseUrl
                ProviderFormError.MissingApiKey -> Kind.MissingApiKey
            }
            mutableState.update { it.copy(provider = it.provider.copy(notice = OnboardingNotice(kind))) }
            return
        }
        mutableState.update { it.copy(provider = it.provider.copy(busy = true, notice = null)) }
        viewModelScope.launch {
            val option = form.option
            val saved = try {
                withContext(Dispatchers.IO) {
                    val profiles = providers.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) }
                    val existing = profileToOverwrite(option, form.baseUrl.trim(), profiles.map { it.toOnboarding() },
                        ModelProviderConfigStore::sameCanonicalEndpoint)
                    providers.save(existing?.id, form.name.trim(), form.baseUrl.trim(), form.apiKey.trim(), null,
                        option.sourceType, option.protocolType, "chat_completions").also { providers.select(it.id) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to save Provider: ${error.message}")
                mutableState.update {
                    it.copy(provider = it.provider.copy(busy = false, notice = OnboardingNotice(Kind.SaveFailed, error.message.orEmpty())))
                }
                return@launch
            }
            var notice: OnboardingNotice? = null
            val models = try {
                fetchModels(saved, explicit = true).also { if (it.isEmpty()) notice = OnboardingNotice(Kind.NoModels) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to fetch Provider models: ${error.message}")
                notice = OnboardingNotice(Kind.FetchFailed)
                withContext(Dispatchers.IO) { providers.manualIds(saved.id) }
            }
            mutableState.update {
                it.copy(provider = it.provider.copy(
                    busy = false, profileId = saved.id, profileName = saved.name, models = models,
                    sceneSelections = defaultSceneSelections(models), pickedScenes = emptySet(), notice = notice,
                ))
            }
            // The fetch outcome shows on the model page; a save failure stays on the form.
            goTo(OnboardingPage.ModelInventory)
            mutableState.update { it.copy(provider = it.provider.copy(notice = notice)) }
        }
    }

    private fun addModel(raw: String) {
        val provider = state.value.provider
        val profileId = provider.profileId ?: return
        val modelId = raw.trim()
        if (modelId.isEmpty()) return
        if (!SceneModelBindingStore.isValidModelName(modelId) || modelId.any(Char::isWhitespace)) {
            mutableState.update { it.copy(provider = it.provider.copy(notice = OnboardingNotice(Kind.InvalidModelId))) }
            return
        }
        if (modelId in provider.models) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { providers.saveManualIds(profileId, providers.manualIds(profileId) + modelId) }
                mutableState.update {
                    val models = it.provider.models + modelId
                    it.copy(provider = it.provider.copy(models = models, notice = null,
                        sceneSelections = rebalancedSceneSelections(models, it.provider.sceneSelections, it.provider.pickedScenes)))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(provider = it.provider.copy(notice = OnboardingNotice(Kind.AddModelFailed, error.message.orEmpty())))
                }
            }
        }
    }

    private fun saveScenes() {
        val provider = state.value.provider
        val profileId = provider.profileId ?: return
        if (provider.savingScenes || provider.models.isEmpty()) return
        if (ONBOARDING_SCENES.any { provider.sceneSelections[it.id].isNullOrBlank() }) {
            mutableState.update { it.copy(provider = it.provider.copy(notice = OnboardingNotice(Kind.MissingScene))) }
            return
        }
        mutableState.update { it.copy(provider = it.provider.copy(savingScenes = true, notice = null)) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ONBOARDING_SCENES.forEach { scene -> scenes.saveBinding(scene.id, profileId, provider.sceneSelections.getValue(scene.id)) }
                }
                mutableState.update { it.copy(provider = it.provider.copy(savingScenes = false)) }
                goTo(OnboardingPage.Completion)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to save scene models: ${error.message}")
                mutableState.update {
                    it.copy(provider = it.provider.copy(savingScenes = false,
                        notice = OnboardingNotice(Kind.SceneSaveFailed, error.message.orEmpty())))
                }
            }
        }
    }

    // ---- Completion -------------------------------------------------------

    private fun complete() {
        if (state.value.completing) return
        mutableState.update { it.copy(completing = true) }
        viewModelScope.launch {
            // commit(): the launcher reads this flag on the next cold start.
            withContext(Dispatchers.IO) { preferences.edit().putBoolean(KEY_WELCOME_COMPLETED, true).commit() }
            mutableState.update { it.copy(finished = true) }
        }
    }

    override fun onCleared() {
        EmbeddedTerminalInitCoordinator.removeListener(progressListener)
    }

    class Factory(context: Context, private val replay: Boolean) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            NativeOnboardingViewModel(appContext, replay, extras.createSavedStateHandle()) as T
    }

    companion object {
        private const val TAG = "NativeOnboarding"
        private const val TICK_MS = 350L
        const val KEY_WELCOME_COMPLETED = "flutter.welcome_completed"
        private const val KEY_FLOW = "flow"
        private const val KEY_DISTRIBUTION = "distribution"
        private const val KEY_PRESET = "preset"
        private const val KEY_TOOLS = "tools"
        private const val KEY_ENV_READY = "environmentReady"
        private const val KEY_OPTION = "option"
        private const val KEY_NAME = "name"
        private const val KEY_BASE_URL = "baseUrl"
    }
}
