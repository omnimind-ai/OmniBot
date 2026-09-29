package cn.com.omnimind.bot.model

import android.content.Context
import cn.com.omnimind.baselib.llm.*
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager

/** Existing stores own configuration; both settings surfaces share mutation side effects. */
internal class SceneModelSettingsRepository(private val context: Context) {
    fun profiles(): List<ModelProviderProfile> = ModelProviderConfigStore.listProfiles()
        .filterNot { OmniOfficialProvider.isOfficialProfile(it.id) } +
        listOfNotNull(PlatformAiProvisioner.officialProfileOrNull())

    suspend fun saveBinding(sceneId: String, providerProfileId: String, modelId: String): List<SceneModelBindingEntry> {
        val previousProviderId = SceneModelBindingStore.getBinding(sceneId)?.providerProfileId
        SceneModelBindingStore.saveBinding(sceneId, providerProfileId, modelId)
        if (sceneId == SceneOperationConfigStore.SCENE_ID) {
            SceneOperationConfigStore.saveConfig(SceneOperationConfig(useOfficialService = false))
        }
        // Same-provider model changes retain the live ACP session, as in the existing channel.
        if (sceneId == "scene.dispatch.model" && previousProviderId != providerProfileId) {
            AgentRuntimeManager.getIfInitialized()?.invalidateSharedProviderRuntime()
        }
        return SceneModelBindingStore.getBindingEntries()
    }

    suspend fun clearBinding(sceneId: String): List<SceneModelBindingEntry> {
        SceneModelBindingStore.clearBinding(sceneId)
        if (sceneId == "scene.dispatch.model") {
            AgentRuntimeManager.getIfInitialized()?.invalidateSharedProviderRuntime()
        }
        return SceneModelBindingStore.getBindingEntries()
    }

    fun setAutoPlay(enabled: Boolean): SceneVoiceConfig = SceneVoiceConfigStore.saveConfig(
        SceneVoiceConfigStore.getConfig().copy(autoPlay = enabled),
        replaceCustomCurlCommand = false,
    )

    /** Read compatibility data without adding a second writer for the Provider editor. */
    fun manualModelIds(profileId: String): List<String> = ProviderEditorRepository(context).manualIds(profileId)
}
