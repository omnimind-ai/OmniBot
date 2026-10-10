package cn.com.omnimind.bot.ui.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.nativeui.onboarding.EnvironmentSetup
import cn.com.omnimind.nativeui.onboarding.EnvironmentStep
import cn.com.omnimind.nativeui.onboarding.ONBOARDING_SCENES
import cn.com.omnimind.nativeui.onboarding.OnboardingActions
import cn.com.omnimind.nativeui.onboarding.OnboardingFlow
import cn.com.omnimind.nativeui.onboarding.OnboardingNotice
import cn.com.omnimind.nativeui.onboarding.OnboardingNotice.Kind
import cn.com.omnimind.nativeui.onboarding.OnboardingPage
import cn.com.omnimind.nativeui.onboarding.OnboardingState
import cn.com.omnimind.nativeui.onboarding.PROVIDER_OPTIONS
import cn.com.omnimind.nativeui.onboarding.ProviderFormError
import cn.com.omnimind.nativeui.onboarding.ProviderSetup
import cn.com.omnimind.nativeui.onboarding.decodeOnboardingFlow
import cn.com.omnimind.nativeui.onboarding.encode
import cn.com.omnimind.nativeui.onboarding.environmentPhase
import cn.com.omnimind.nativeui.onboarding.nextEnvironmentProgress
import cn.com.omnimind.nativeui.onboarding.onboardingPackageIds
import cn.com.omnimind.nativeui.onboarding.providerOptionFor
import cn.com.omnimind.nativeui.onboarding.rebalancedSceneSelections
import cn.com.omnimind.nativeui.onboarding.resumableOnboardingProfile
import cn.com.omnimind.nativeui.onboarding.validateProviderForm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native first-use onboarding (batch 5f-1b). Holds the page state; every
 * platform call goes through [OnboardingRepository]. The page, its back
 * stack and the choices survive process death in [SavedStateHandle]; the
 * API key never does, because saved state is written to disk.
 */
internal class NativeOnboardingViewModel(
    replay: Boolean,
    private val savedState: SavedStateHandle,
    private val repository: OnboardingRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(restore(replay))
    val state = mutableState.asStateFlow()

    /** The installer's own progress; the shown progress eases towards it. */
    private var installerProgress = 0f
    private var tickerJob: Job? = null
    /** Set by Cancel so the end of the install reads as paused, not failed. */
    private var cancelRequested = false
    private var providerLoaded = false
    private val installListener: (Map<String, Any?>) -> Unit = { followInstaller() }

    val actions = OnboardingActions(
        goTo = ::goTo,
        next = { state.value.nextPage?.let(::goTo) },
        back = { state.value.flow.goBack()?.let(::setFlow) },
        jumpTo = { page -> setFlow(state.value.flow.jumpToVisited(page)) },
        selectDistribution = { id -> editEnvironment { it.copy(distribution = id) } },
        selectPreset = { id -> editEnvironment { it.copy(presetId = id) } },
        toggleTool = { id -> editEnvironment { it.copy(toolIds = if (id in it.toolIds) it.toolIds - id else it.toolIds + id) } },
        startEnvironment = ::startInstall,
        cancelEnvironment = ::cancelInstall,
        // The host replaces this with the Flutter account hand-off.
        openAccount = {},
        chooseProvider = ::chooseProvider,
        updateName = { value -> editForm { it.copy(name = value) } },
        updateBaseUrl = { value -> editForm { it.copy(baseUrl = value) } },
        updateApiKey = { value -> editForm { it.copy(apiKey = value) } },
        connect = ::connect,
        addModel = ::addModel,
        selectSceneModel = ::selectSceneModel,
        saveScenes = ::saveScenes,
        complete = ::complete,
    )

    init {
        repository.addInstallListener(installListener)
        viewModelScope.launch {
            val stored = runCatching { repository.selectedDistribution() }.getOrNull()
            updateEnvironment { current ->
                // A choice restored from saved state wins over the stored system.
                current.copy(distributionLoading = false,
                    distribution = savedState.get<String>(KEY_DISTRIBUTION) ?: stored ?: current.distribution)
            }
        }
        // An install still running when the page comes back is followed, not restarted.
        if (repository.installSnapshot().running) {
            updateEnvironment { it.copy(busy = true, progress = INITIAL_PROGRESS) }
            followInstaller()
            startTicker()
            viewModelScope.launch { awaitRunningInstall() }
        }
        if (state.value.flow.page.needsProvider) loadProvider()
    }

    override fun onCleared() {
        repository.removeInstallListener(installListener)
    }

    private fun restore(replay: Boolean): OnboardingState {
        val option = PROVIDER_OPTIONS.firstOrNull { it.id == savedState.get<String>(KEY_OPTION) } ?: PROVIDER_OPTIONS.first()
        val installed = savedState.get<Boolean>(KEY_INSTALLED) == true
        val defaults = OnboardingState()
        return defaults.copy(
            flow = decodeOnboardingFlow(savedState.get<String>(KEY_FLOW)),
            replay = replay,
            accountAvailable = BuildConfig.BASE_URL.isNotBlank(),
            environment = defaults.environment.copy(
                presetId = savedState.get<String>(KEY_PRESET) ?: defaults.environment.presetId,
                toolIds = savedState.get<ArrayList<String>>(KEY_TOOLS)?.toSet().orEmpty(),
                ready = installed,
                progress = if (installed) 1f else 0f,
            ),
            provider = defaults.provider.startedOver(option).copy(
                name = savedState.get<String>(KEY_NAME) ?: defaults.provider.startedOver(option).name,
                baseUrl = savedState.get<String>(KEY_BASE_URL) ?: option.baseUrl,
            ),
        )
    }

    private fun updateEnvironment(change: (EnvironmentSetup) -> EnvironmentSetup) =
        mutableState.update { it.copy(environment = change(it.environment)) }

    private fun updateProvider(change: (ProviderSetup) -> ProviderSetup) =
        mutableState.update { it.copy(provider = change(it.provider)) }

    private fun notify(kind: Kind, detail: String = "") = updateProvider { it.copy(notice = OnboardingNotice(kind, detail)) }

    // ---- Navigation -------------------------------------------------------

    private val OnboardingPage.needsProvider: Boolean
        get() = ordinal >= OnboardingPage.Provider.ordinal && this != OnboardingPage.Completion

    private fun setFlow(flow: OnboardingFlow) {
        if (flow == state.value.flow || state.value.locked) return
        savedState[KEY_FLOW] = flow.encode()
        // A notice belongs to the page that raised it.
        mutableState.update { it.copy(flow = flow, provider = it.provider.copy(notice = null)) }
        if (flow.page.needsProvider) loadProvider()
    }

    private fun goTo(page: OnboardingPage) = setFlow(state.value.flow.goTo(page))

    // ---- Environment ------------------------------------------------------

    private fun editEnvironment(change: (EnvironmentSetup) -> EnvironmentSetup) {
        if (state.value.environment.busy) return
        val next = change(state.value.environment).choiceChanged()
        savedState[KEY_DISTRIBUTION] = next.distribution
        savedState[KEY_PRESET] = next.presetId
        savedState[KEY_TOOLS] = ArrayList(next.toolIds)
        savedState[KEY_INSTALLED] = false
        updateEnvironment { next }
    }

    private fun startInstall() {
        val environment = state.value.environment
        if (environment.busy || environment.distributionLoading) return
        goTo(OnboardingPage.EnvironmentProgress)
        cancelRequested = false
        installerProgress = INITIAL_PROGRESS
        updateEnvironment {
            it.copy(busy = true, ready = false, failed = false, progress = INITIAL_PROGRESS, stage = "",
                step = EnvironmentStep.SavingChoices)
        }
        startTicker()
        viewModelScope.launch {
            try {
                val system = repository.selectDistribution(environment.distribution)
                updateEnvironment { it.copy(distribution = system.id, step = EnvironmentStep.PreparingSystem) }
                val result = repository.install(onboardingPackageIds(environment.presetId, environment.toolIds))
                finishInstall(result.success, result.message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e(TAG, "Environment setup failed", error)
                finishInstall(false, error.message.orEmpty())
            }
        }
    }

    private fun cancelInstall() {
        if (!state.value.environment.busy) return
        cancelRequested = true
        repository.cancelInstall()
    }

    private suspend fun awaitRunningInstall() {
        while (viewModelScope.isActive) {
            val snapshot = repository.installSnapshot()
            if (!snapshot.running) {
                finishInstall(snapshot.success, snapshot.stage)
                return
            }
            delay(TICK_MS)
        }
    }

    /** Reads the installer's progress and stage while an install runs. */
    private fun followInstaller() {
        if (!state.value.environment.busy) return
        val snapshot = repository.installSnapshot()
        if (!snapshot.running) return
        installerProgress = snapshot.progress
        if (snapshot.stage.isNotEmpty()) updateEnvironment { it.copy(stage = snapshot.stage) }
    }

    /** Eases the shown progress every [TICK_MS] (Dart `_startProgressTracking`). */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive && state.value.environment.busy) {
                delay(TICK_MS)
                followInstaller()
                updateEnvironment { environment ->
                    val phase = environmentPhase(environment.stage, maxOf(installerProgress, environment.progress), ready = false)
                    environment.copy(progress = nextEnvironmentProgress(environment.progress, installerProgress, phase))
                }
            }
        }
    }

    private suspend fun finishInstall(success: Boolean, message: String) {
        tickerJob?.cancel()
        if (success) easeToComplete()
        savedState[KEY_INSTALLED] = success
        // A failure shows the installer's last message; a cancel or a silent failure shows onboarding's own line.
        val outcome = when {
            success -> null
            cancelRequested -> EnvironmentStep.Cancelled
            message.isBlank() -> EnvironmentStep.Failed
            else -> null
        }
        updateEnvironment {
            it.copy(
                busy = false, ready = success, failed = !success,
                progress = if (success) 1f else it.progress,
                stage = when {
                    success -> it.stage
                    outcome != null -> ""
                    else -> message.trim()
                },
                step = outcome,
            )
        }
    }

    /** A short ease-out run to 100% instead of a jump (Dart `_completeProgressAnimation`). */
    private suspend fun easeToComplete() {
        val start = state.value.environment.progress
        for (frame in 1..COMPLETE_FRAMES) {
            delay(COMPLETE_FRAME_MS)
            val t = frame / COMPLETE_FRAMES.toFloat()
            val eased = 1 - (1 - t) * (1 - t)
            updateEnvironment { it.copy(progress = start + (1 - start) * eased) }
        }
    }

    // ---- Provider ---------------------------------------------------------

    /** Resumes with a Provider the user can already call; runs once. */
    private fun loadProvider() {
        if (providerLoaded) return
        providerLoaded = true
        updateProvider { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (profiles, editingId) = repository.profiles()
                val resumable = resumableOnboardingProfile(profiles.map(OnboardingRepository::onboardingProfile), editingId)
                val profile = profiles.firstOrNull { it.id == resumable?.id }
                if (profile == null || state.value.provider.connected) {
                    updateProvider { it.copy(loading = false) }
                    return@launch
                }
                // Same one-shot discovery as Dart when nothing is cached yet.
                val models = repository.storedModels(profile).ifEmpty {
                    runCatching { repository.fetchModels(profile, refresh = false) }.getOrDefault(emptyList())
                }
                val bindings = repository.bindingsFor(profile.id)
                val option = providerOptionFor(OnboardingRepository.onboardingProfile(profile),
                    ModelProviderConfigStore::sameCanonicalEndpoint)
                updateProvider {
                    it.copy(loading = false, optionId = option.id, name = profile.name, baseUrl = profile.baseUrl,
                        apiKey = profile.apiKey).connected(profile.id, profile.name, models, bindings)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to read Provider settings: ${error.message}")
                updateProvider { it.copy(loading = false, notice = OnboardingNotice(Kind.LoadFailed)) }
            }
        }
    }

    /** Edits the connection form; the name and URL are saved, the key is not. */
    private fun editForm(change: (ProviderSetup) -> ProviderSetup) {
        if (state.value.provider.busy) return
        updateProvider { change(it).copy(notice = null) }
        savedState[KEY_NAME] = state.value.provider.name
        savedState[KEY_BASE_URL] = state.value.provider.baseUrl
    }

    /** Opens the connection form; choosing the connected option again keeps it. */
    private fun chooseProvider(optionId: String) {
        val provider = state.value.provider
        if (provider.busy || provider.loading) return
        if (!provider.connected || provider.optionId != optionId) {
            val option = PROVIDER_OPTIONS.first { it.id == optionId }
            updateProvider { it.startedOver(option) }
            savedState[KEY_OPTION] = option.id
            savedState[KEY_NAME] = state.value.provider.name
            savedState[KEY_BASE_URL] = option.baseUrl
        }
        goTo(OnboardingPage.ProviderConnection)
    }

    /**
     * Saves the connection, then reads its models. A save failure stays on
     * the form; a fetch failure still continues, because the user can add
     * model IDs by hand on the next page.
     */
    private fun connect() {
        val form = state.value.provider
        if (form.busy) return
        validateProviderForm(form.option, form.name, form.baseUrl, form.apiKey, ModelProviderConfigStore::isValidBaseUrl)?.let {
            notify(it.notice)
            return
        }
        updateProvider { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val profile = try {
                repository.saveConnection(form.option, form.name.trim(), form.baseUrl.trim(), form.apiKey.trim())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to save Provider: ${error.message}")
                updateProvider { it.copy(busy = false) }
                notify(Kind.SaveFailed, error.message.orEmpty())
                return@launch
            }
            val (models, notice) = try {
                repository.fetchModels(profile, refresh = true).let { it to if (it.isEmpty()) Kind.NoModels else null }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to fetch Provider models: ${error.message}")
                repository.manualModels(profile.id) to Kind.FetchFailed
            }
            updateProvider { it.copy(busy = false).connected(profile.id, profile.name, models) }
            goTo(OnboardingPage.ModelInventory)
            // After the page change, which clears notices: the outcome belongs to the model page.
            notice?.let { notify(it) }
        }
    }

    private fun addModel(raw: String) {
        val provider = state.value.provider
        val profileId = provider.profileId ?: return
        val modelId = raw.trim()
        if (modelId.isEmpty() || modelId in provider.models) return
        if (!SceneModelBindingStore.isValidModelName(modelId) || modelId.any(Char::isWhitespace)) {
            notify(Kind.InvalidModelId)
            return
        }
        viewModelScope.launch {
            try {
                repository.addManualModel(profileId, modelId)
                updateProvider {
                    val models = it.models + modelId
                    it.copy(models = models, notice = null,
                        sceneSelections = rebalancedSceneSelections(models, it.sceneSelections, it.pickedScenes))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notify(Kind.AddModelFailed, error.message.orEmpty())
            }
        }
    }

    private fun selectSceneModel(sceneId: String, modelId: String) = updateProvider {
        it.copy(sceneSelections = it.sceneSelections + (sceneId to modelId), pickedScenes = it.pickedScenes + sceneId)
    }

    private fun saveScenes() {
        val provider = state.value.provider
        val profileId = provider.profileId ?: return
        if (provider.savingScenes || provider.models.isEmpty()) return
        val selections = ONBOARDING_SCENES.associate { it.id to provider.sceneSelections[it.id].orEmpty() }
        if (selections.values.any { it.isBlank() }) {
            notify(Kind.MissingScene)
            return
        }
        updateProvider { it.copy(savingScenes = true, notice = null) }
        viewModelScope.launch {
            try {
                repository.saveBindings(profileId, selections)
                updateProvider { it.copy(savingScenes = false) }
                goTo(OnboardingPage.Completion)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to save scene models: ${error.message}")
                updateProvider { it.copy(savingScenes = false) }
                notify(Kind.SceneSaveFailed, error.message.orEmpty())
            }
        }
    }

    // ---- Completion -------------------------------------------------------

    private fun complete() {
        if (state.value.completing) return
        mutableState.update { it.copy(completing = true) }
        viewModelScope.launch {
            try {
                repository.markCompleted()
                mutableState.update { it.copy(finished = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e(TAG, "Unable to finish onboarding", error)
                mutableState.update { it.copy(completing = false) }
            }
        }
    }

    class Factory(context: Context, private val replay: Boolean) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            NativeOnboardingViewModel(replay, extras.createSavedStateHandle(), OnboardingRepository(appContext)) as T
    }

    private companion object {
        const val TAG = "NativeOnboarding"
        const val TICK_MS = 350L
        const val INITIAL_PROGRESS = .02f
        const val COMPLETE_FRAMES = 12
        const val COMPLETE_FRAME_MS = 45L
        const val KEY_FLOW = "flow"
        const val KEY_DISTRIBUTION = "distribution"
        const val KEY_PRESET = "preset"
        const val KEY_TOOLS = "tools"
        const val KEY_INSTALLED = "installed"
        const val KEY_OPTION = "option"
        const val KEY_NAME = "name"
        const val KEY_BASE_URL = "baseUrl"
    }
}

private val ProviderFormError.notice: Kind
    get() = when (this) {
        ProviderFormError.MissingName -> Kind.MissingName
        ProviderFormError.InvalidBaseUrl -> Kind.InvalidBaseUrl
        ProviderFormError.MissingApiKey -> Kind.MissingApiKey
    }
