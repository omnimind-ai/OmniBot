package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfig
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugModelProviderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val operation = intent.stringExtra("operation").ifBlank { OPERATION_CONFIGURE }

        scope.launch {
            try {
                val result = runCatching {
                    when (operation) {
                        OPERATION_QUERY -> queryState()
                        OPERATION_CONFIGURE -> configure(appContext, intent)
                        OPERATION_BIND_EXISTING -> bindExisting(intent)
                        else -> error("unsupported operation: $operation")
                    }
                }.getOrElse { error ->
                    linkedMapOf<String, Any?>(
                        "success" to false,
                        "phase" to "exception",
                        "error_message" to error.message.orEmpty(),
                        "error_type" to error.javaClass.name,
                    )
                }
                val json = gson.toJson(result)
                File(appContext.filesDir, RESULT_FILE).writeText(json)
                OmniLog.i(TAG, json)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun configure(context: Context, intent: Intent?): Map<String, Any?> {
        val baseUrl = intent.stringExtra("baseUrl", "base_url")
        val apiKey = intent.stringExtra("apiKey", "api_key")
        val modelId = intent.stringExtra("modelId", "model_id")
        val profileId = intent.stringExtra("profileId", "profile_id")
            .ifBlank { DEFAULT_PROFILE_ID }
        val name = intent.stringExtra("name").ifBlank { DEFAULT_PROFILE_NAME }
        val protocolType = intent.stringExtra("protocolType", "protocol_type")
            .ifBlank { "openai_compatible" }
        val wireApi = intent.stringExtra("wireApi", "wire_api")
            .ifBlank { OpenAiWireApi.CHAT_COMPLETIONS }
        val sceneIds = parseSceneIds(intent.stringExtra("sceneIds", "scene_ids"))

        require(baseUrl.isNotBlank()) { "baseUrl is empty" }
        require(apiKey.isNotBlank()) { "apiKey is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        require(sceneIds.isNotEmpty()) { "sceneIds is empty" }
        require(protocolType == "openai_compatible") {
            "debug provider must use openai_compatible"
        }
        require(wireApi in setOf(OpenAiWireApi.CHAT_COMPLETIONS, OpenAiWireApi.RESPONSES)) {
            "debug provider must use chat_completions or responses"
        }

        val profile = ModelProviderConfigStore.saveProfile(
            id = profileId,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            sourceType = "custom",
            protocolType = protocolType,
            wireApi = wireApi,
        )
        ModelProviderConfigStore.setEditingProfile(profile.id)
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId,
            )
        }
        if (SceneOperationConfigStore.SCENE_ID in sceneIds) {
            SceneOperationConfigStore.saveConfig(
                SceneOperationConfig(useOfficialService = false)
            )
        }
        seedFlutterManualModelId(context, profile.id, modelId)

        return queryState() + mapOf(
            "configuredProfileId" to profile.id,
            "configuredModelId" to modelId,
            "configuredSceneIds" to sceneIds,
        )
    }

    /**
     * Debug-only device verification helper. It changes only the scene binding
     * and never reads or accepts a credential, so a real Provider already saved
     * on the device can be used without copying its API key into adb history.
     */
    private fun bindExisting(intent: Intent?): Map<String, Any?> {
        val profileId = intent.stringExtra("profileId", "profile_id")
        val modelId = intent.stringExtra("modelId", "model_id")
        val sceneIds = parseSceneIds(intent.stringExtra("sceneIds", "scene_ids"))
        require(profileId.isNotBlank()) { "profileId is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        val profile = ModelProviderConfigStore.getProfile(profileId)
            ?: error("provider is not configured: $profileId")
        require(profile.isConfigured()) { "provider is not configured: $profileId" }
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profileId,
                modelId = modelId,
            )
        }
        return queryState() + mapOf(
            "boundExistingProfileId" to profileId,
            "boundExistingModelId" to modelId,
            "boundExistingSceneIds" to sceneIds,
        )
    }

    private fun queryState(): Map<String, Any?> = linkedMapOf(
        "success" to true,
        "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
        "profiles" to ModelProviderConfigStore.listProfiles().map { it.toSafePayload() },
        "sceneBindings" to SceneModelBindingStore.getBindingEntries().map { it.toPayload() },
    )

    private fun seedFlutterManualModelId(context: Context, profileId: String, modelId: String) {
        val preferences = context.getSharedPreferences(
            FLUTTER_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val current = runCatching {
            JSONObject(preferences.getString(FLUTTER_MANUAL_MODEL_IDS_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val modelIds = current.optJSONArray(profileId) ?: JSONArray()
        val exists = (0 until modelIds.length()).any { index ->
            modelIds.optString(index).trim() == modelId
        }
        if (!exists) modelIds.put(modelId)
        current.put(profileId, modelIds)
        preferences.edit().putString(FLUTTER_MANUAL_MODEL_IDS_KEY, current.toString()).apply()
    }

    private fun Intent?.stringExtra(vararg names: String): String {
        if (this == null) return ""
        names.forEach { name ->
            getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun parseSceneIds(raw: String): List<String> = raw
        .split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty { DEFAULT_SCENE_IDS }

    private fun ModelProviderProfile.toSafePayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "baseUrl" to baseUrl,
        "sourceType" to sourceType,
        "protocolType" to protocolType,
        "wireApi" to wireApi,
        "apiKeyConfigured" to apiKey.isNotBlank(),
        "configured" to isConfigured(),
    )

    private fun SceneModelBindingEntry.toPayload(): Map<String, Any?> = linkedMapOf(
        "sceneId" to sceneId,
        "providerProfileId" to providerProfileId,
        "modelId" to modelId,
    )

    companion object {
        private const val TAG = "DebugModelProviderConfigReceiver"
        private const val RESULT_FILE = "debug-model-provider-config-result.json"
        private const val OPERATION_CONFIGURE = "configure"
        private const val OPERATION_BIND_EXISTING = "bind_existing"
        private const val OPERATION_QUERY = "query"
        private const val DEFAULT_PROFILE_ID = "debug-runtime-provider"
        private const val DEFAULT_PROFILE_NAME = "OmniMind GPT 5.6 (Debug)"
        private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
        private const val FLUTTER_MANUAL_MODEL_IDS_KEY = "flutter.manual_provider_model_ids_v2"
        private val DEFAULT_SCENE_IDS = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context.chat",
        )
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
