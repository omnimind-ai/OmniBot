package cn.com.omnimind.bot.ui.onboarding

import android.content.Context
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OfficialProviderRegistry
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.model.ProviderEditorRepository
import cn.com.omnimind.bot.model.ProviderModelCatalogService
import cn.com.omnimind.bot.model.SceneModelSettingsRepository
import cn.com.omnimind.bot.terminal.EmbeddedTerminalInitCoordinator
import cn.com.omnimind.bot.termux.TermuxLiveEnvironmentResult
import cn.com.omnimind.nativeui.onboarding.OnboardingProfile
import cn.com.omnimind.nativeui.onboarding.ProviderOption
import cn.com.omnimind.nativeui.onboarding.profileToOverwrite
import com.ai.assistance.operit.terminal.TerminalManager
import com.rk.settings.Settings
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the install reports between ticks. */
internal data class InstallSnapshot(val running: Boolean, val success: Boolean, val progress: Float, val stage: String)

/**
 * Every platform call onboarding makes, on the owners the Flutter
 * controllers reached through channels. Blocking work runs on IO; the
 * ViewModel only holds state.
 */
internal class OnboardingRepository(context: Context) {
    private val appContext = context.applicationContext
    private val providers = ProviderEditorRepository(appContext)
    private val catalog = ProviderModelCatalogService(appContext)
    private val scenes = SceneModelSettingsRepository(appContext)
    private val preferences = appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    // ---- Environment ------------------------------------------------------

    suspend fun selectedDistribution(): String = withContext(Dispatchers.IO) { TerminalDistribution.selected().id }

    /**
     * Makes [distributionId] the terminal's system. Dart also closed every
     * session when the choice was unchanged; only a real switch does now.
     */
    suspend fun selectDistribution(distributionId: String): TerminalDistribution.Spec = withContext(Dispatchers.IO) {
        val target = TerminalDistribution.fromId(distributionId)
        if (TerminalDistribution.selected().id != target.id) {
            Settings.terminal_distribution = target.workingMode
            Settings.working_Mode = target.workingMode
            TerminalManager.getInstance(appContext).closeAllSessions()
        }
        target
    }

    /** Prepares the system and installs [packageIds]; suspends until the install ends. */
    suspend fun install(packageIds: List<String>): TermuxLiveEnvironmentResult =
        EmbeddedTerminalInitCoordinator.prepare(appContext, packageIds)

    fun cancelInstall() {
        EmbeddedTerminalInitCoordinator.cancelCurrent()
    }

    fun installSnapshot(): InstallSnapshot {
        val snapshot = EmbeddedTerminalInitCoordinator.buildSnapshot()
        return InstallSnapshot(
            running = snapshot["running"] == true,
            success = snapshot["success"] == true,
            progress = (snapshot["progress"] as? Number)?.toFloat() ?: 0f,
            stage = snapshot["stage"]?.toString()?.trim().orEmpty(),
        )
    }

    fun addInstallListener(listener: (Map<String, Any?>) -> Unit) = EmbeddedTerminalInitCoordinator.addListener(listener)
    fun removeInstallListener(listener: (Map<String, Any?>) -> Unit) = EmbeddedTerminalInitCoordinator.removeListener(listener)

    // ---- Provider ---------------------------------------------------------

    /** The user's profiles (the account's platform profile is not one) and the one being edited. */
    suspend fun profiles(): Pair<List<ModelProviderProfile>, String> = withContext(Dispatchers.IO) {
        providers.profiles().filterNot { OmniOfficialProvider.isOfficialProfile(it.id) } to providers.editingId()
    }

    /** Models already known for [profile]: the last fetch and the manual IDs. */
    suspend fun storedModels(profile: ModelProviderProfile): List<String> = withContext(Dispatchers.IO) {
        normalizedModelIds(ModelProviderConfigStore.cachedModels(appContext, profile).map { it.id } + providers.manualIds(profile.id))
    }

    /** Asks the Provider for its models; the manual IDs are always kept. */
    suspend fun fetchModels(profile: ModelProviderProfile, refresh: Boolean): List<String> = withContext(Dispatchers.IO) {
        val fetched = catalog.fetch(profile.id, capability = "text", forceRefresh = refresh,
            expectedRevision = profile.revision, expectedBaseUrl = profile.baseUrl)
        normalizedModelIds(fetched.map { it.id } + providers.manualIds(profile.id))
    }

    suspend fun manualModels(profileId: String): List<String> = withContext(Dispatchers.IO) { providers.manualIds(profileId) }

    suspend fun addManualModel(profileId: String, modelId: String) = withContext(Dispatchers.IO) {
        providers.saveManualIds(profileId, providers.manualIds(profileId) + modelId)
    }

    /** Saves the connection over the profile it belongs to (or a new one) and makes it the editing profile. */
    suspend fun saveConnection(option: ProviderOption, name: String, baseUrl: String, apiKey: String): ModelProviderProfile =
        withContext(Dispatchers.IO) {
            val existing = profileToOverwrite(option, baseUrl, providers.profiles().map(::onboardingProfile),
                ModelProviderConfigStore::sameCanonicalEndpoint)
            providers.save(existing?.id, name, baseUrl, apiKey, headers = null, option.sourceType, option.protocolType,
                wireApi = "chat_completions").also { providers.select(it.id) }
        }

    /** The scene bindings that already point at [profileId]. */
    suspend fun bindingsFor(profileId: String): Map<String, String> = withContext(Dispatchers.IO) {
        SceneModelBindingStore.getBindingEntries().filter { it.providerProfileId == profileId }.associate { it.sceneId to it.modelId }
    }

    suspend fun saveBindings(profileId: String, selections: Map<String, String>) = withContext(Dispatchers.IO) {
        selections.forEach { (sceneId, modelId) -> scenes.saveBinding(sceneId, profileId, modelId) }
    }

    // ---- Completion -------------------------------------------------------

    /** `commit()`: the launcher reads the flag on the next cold start. */
    suspend fun markCompleted() = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean(KEY_WELCOME_COMPLETED, true).commit()) { "Could not save onboarding state" }
    }

    companion object {
        const val KEY_WELCOME_COMPLETED = "flutter.welcome_completed"

        fun onboardingProfile(profile: ModelProviderProfile) = OnboardingProfile(
            id = profile.id, name = profile.name, baseUrl = profile.baseUrl, apiKey = profile.apiKey,
            sourceType = profile.sourceType, builtIn = OfficialProviderRegistry.findByProfileId(profile.id) != null,
        )

        private fun normalizedModelIds(ids: List<String>) = ids.map(String::trim).filter { it.isNotEmpty() }.distinct()
    }
}
