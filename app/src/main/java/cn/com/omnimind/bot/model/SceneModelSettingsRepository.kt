package cn.com.omnimind.bot.model

import android.content.Context
import cn.com.omnimind.baselib.llm.*
import cn.com.omnimind.baselib.util.LegacyFlutterPreferences
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import org.json.JSONObject

/** Existing stores own configuration; both settings surfaces share mutation side effects. */
internal class SceneModelSettingsRepository(private val context: Context) {
    fun profiles(): List<ModelProviderProfile> = ModelProviderConfigStore.listProfiles()
        .filterNot { OmniOfficialProvider.isOfficialProfile(it.id) } +
        listOfNotNull(PlatformAiProvisioner.officialProfileOrNull())

    fun saveBinding(sceneId: String, providerProfileId: String, modelId: String): List<SceneModelBindingEntry> {
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

    fun clearBinding(sceneId: String): List<SceneModelBindingEntry> {
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
    fun manualModelIds(profileId: String, firstEditableProfileId: String?): List<String> {
        val preferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val map = runCatching { JSONObject(preferences.getString("flutter.manual_provider_model_ids_v2", "{}")!!) }
            .getOrElse { JSONObject() }
        val array = map.optJSONArray(profileId)
        val ids = if (array != null) List(array.length()) { array.optString(it) }
            else if (!map.has(profileId) && profileId == firstEditableProfileId)
                LegacyFlutterPreferences.readStringList(preferences, "flutter.manual_provider_model_ids_v1")
            else emptyList()
        return ids.map(String::trim).filter(SceneModelBindingStore::isValidModelName).distinct()
    }
}
