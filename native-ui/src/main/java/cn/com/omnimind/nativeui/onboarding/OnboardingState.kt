package cn.com.omnimind.nativeui.onboarding

import androidx.compose.runtime.Immutable
import cn.com.omnimind.nativeui.settings.PermissionsState

/** A message the onboarding pages show; [detail] carries an error text where the cause matters. */
@Immutable
data class OnboardingNotice(val kind: Kind, val detail: String = "") {
    enum class Kind {
        LoadFailed, MissingName, InvalidBaseUrl, MissingApiKey, SaveFailed,
        NoModels, FetchFailed, InvalidModelId, AddModelFailed, MissingScene, SceneSaveFailed,
    }
}

/** Install steps onboarding itself reports, before or instead of the installer's own text. */
enum class EnvironmentStep { SavingChoices, PreparingSystem, Failed, Cancelled }

@Immutable
data class EnvironmentSetup(
    val distributionLoading: Boolean = true,
    /** `alpine` or `ubuntu`. */
    val distribution: String = "alpine",
    val presetId: String = ENVIRONMENT_PRESETS.first().id,
    val toolIds: Set<String> = emptySet(),
    val busy: Boolean = false,
    val ready: Boolean = false,
    val failed: Boolean = false,
    val progress: Float = 0f,
    /** The installer's latest stage text (Chinese; see [localizedEnvironmentStage]). */
    val stage: String = "",
    /** Shown while [stage] is empty: onboarding's own step. */
    val step: EnvironmentStep? = null,
) {
    /** The user stopped the install; it reads as paused, not as an error. */
    val cancelled: Boolean get() = failed && step == EnvironmentStep.Cancelled
    val preset: EnvironmentPreset get() = ENVIRONMENT_PRESETS.firstOrNull { it.id == presetId } ?: ENVIRONMENT_PRESETS.first()
    val distributionName: String get() = if (distribution == "ubuntu") "Ubuntu" else "Alpine"
    val phase: Int get() = environmentPhase(stage, progress, ready)

    /** A new choice invalidates the last install outcome. */
    fun choiceChanged(): EnvironmentSetup = copy(ready = false, failed = false, progress = 0f, stage = "", step = null)
}

@Immutable
data class ProviderSetup(
    val loading: Boolean = false,
    val busy: Boolean = false,
    val optionId: String = PROVIDER_OPTIONS.first().id,
    val name: String = PROVIDER_OPTIONS.first().label,
    val baseUrl: String = PROVIDER_OPTIONS.first().baseUrl,
    val apiKey: String = "",
    /** The saved profile the models and scenes belong to; null until connected. */
    val profileId: String? = null,
    val profileName: String = "",
    val models: List<String> = emptyList(),
    val sceneSelections: Map<String, String> = emptyMap(),
    /** Scenes the user chose by hand; a later model never replaces these picks. */
    val pickedScenes: Set<String> = emptySet(),
    val savingScenes: Boolean = false,
    val notice: OnboardingNotice? = null,
) {
    val option: ProviderOption get() = PROVIDER_OPTIONS.firstOrNull { it.id == optionId } ?: PROVIDER_OPTIONS.first()
    val connected: Boolean get() = profileId != null

    /** An empty form for [option], prefilled with its name and endpoint. */
    fun startedOver(option: ProviderOption): ProviderSetup = ProviderSetup(
        loading = loading, optionId = option.id, name = if (option.id == "custom") "" else option.label, baseUrl = option.baseUrl,
    )

    /** Connected to a saved profile with [models]; scene picks start from the defaults or [bindings]. */
    fun connected(profileId: String, profileName: String, models: List<String>, bindings: Map<String, String> = emptyMap()) = copy(
        profileId = profileId, profileName = profileName, models = models,
        sceneSelections = defaultSceneSelections(models, bindings), pickedScenes = emptySet(),
    )
}

@Immutable
data class OnboardingState(
    val flow: OnboardingFlow = OnboardingFlow(),
    /** Opened again from settings: the first page can go back and completion just closes. */
    val replay: Boolean = false,
    /** The build has an account backend, so sign-in is offered. */
    val accountAvailable: Boolean = false,
    val environment: EnvironmentSetup = EnvironmentSetup(),
    val provider: ProviderSetup = ProviderSetup(),
    val completing: Boolean = false,
    /** Completion was saved; the host leaves for Home. */
    val finished: Boolean = false,
) {
    /** Leaving a page is blocked while an install, a connection or a save is running. */
    val locked: Boolean get() = environment.busy || provider.busy || provider.savingScenes || completing
    val nextPage: OnboardingPage?
        get() = if (environment.distributionLoading && flow.page == OnboardingPage.System) null
            else onboardingNextPage(flow.page, provider.connected, provider.models.isNotEmpty())
    val canGoBack: Boolean get() = !locked && (flow.hasHistory || replay)
}

/** The three permissions onboarding treats as core (accessibility belongs to the GUI plugin). */
val PermissionsState.onboardingCoreReady: Int
    get() = listOf(backgroundAllowed, overlayAllowed, installedAppsAllowed).count { it }

const val ONBOARDING_CORE_PERMISSIONS = 3

data class OnboardingActions(
    val goTo: (OnboardingPage) -> Unit,
    val next: () -> Unit,
    val back: () -> Unit,
    val jumpTo: (OnboardingPage) -> Unit,
    val selectDistribution: (String) -> Unit,
    val selectPreset: (String) -> Unit,
    val toggleTool: (String) -> Unit,
    val startEnvironment: () -> Unit,
    val cancelEnvironment: () -> Unit,
    val openAccount: () -> Unit,
    val chooseProvider: (String) -> Unit,
    val updateName: (String) -> Unit,
    val updateBaseUrl: (String) -> Unit,
    val updateApiKey: (String) -> Unit,
    val connect: () -> Unit,
    val addModel: (String) -> Unit,
    val selectSceneModel: (sceneId: String, modelId: String) -> Unit,
    val saveScenes: () -> Unit,
    val complete: () -> Unit,
)
