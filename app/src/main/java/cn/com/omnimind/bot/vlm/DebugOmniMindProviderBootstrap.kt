package cn.com.omnimind.bot.vlm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig

object DebugOmniMindProviderBootstrap {
    internal const val LEGACY_OMNIMIND_PROFILE_ID = "debug-omnimind-chatgpt-luna"
    internal const val LLMTHU_PROFILE_ID = "debug-llmthu-glm"
    internal const val LLMTHU_PROFILE_NAME = "LLMTHU GLM-5.1 (Debug)"
    private const val TAG = "DebugOmniMindProvider"
    private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
    private const val FLUTTER_MANUAL_MODEL_IDS_KEY = "flutter.manual_provider_model_ids_v2"
    private val DEFAULT_LLMTHU_SCENES = listOf(
        "scene.dispatch.model",
        SceneOperationConfigStore.SCENE_ID,
        "scene.compactor.context.chat",
    )

    fun install(context: Context) {
        if (!BuildConfig.ENABLE_LLMTHU_BOOTSTRAP) return
        val llmThuPlan = createLlmThuPlan(
            apiBase = BuildConfig.DEBUG_LLMTHU_API_BASE,
            apiKey = BuildConfig.DEBUG_LLMTHU_API_KEY,
            model = BuildConfig.DEBUG_LLMTHU_MODEL,
        )
        if (llmThuPlan == null) {
            OmniLog.w(TAG, "debug LLMTHU provider is not configured")
            return
        }

        val previousEditingProfile = ModelProviderConfigStore.getEditingProfile()
        val savedLlmThuProfile = ModelProviderConfigStore.saveProfile(
            id = llmThuPlan.profile.id,
            name = llmThuPlan.profile.name,
            baseUrl = llmThuPlan.profile.baseUrl,
            apiKey = llmThuPlan.profile.apiKey,
            sourceType = llmThuPlan.profile.sourceType,
            protocolType = llmThuPlan.profile.protocolType,
            wireApi = llmThuPlan.profile.wireApi,
        )
        val selectedProfileId = if (
            previousEditingProfile.isConfigured() &&
            previousEditingProfile.id !in DEBUG_MANAGED_PROFILE_IDS
        ) {
            previousEditingProfile.id
        } else {
            savedLlmThuProfile.id
        }
        runCatching {
            ModelProviderConfigStore.setEditingProfile(selectedProfileId)
        }.onFailure {
            OmniLog.w(TAG, "select debug LLM provider failed: ${it.message}")
        }

        DEFAULT_LLMTHU_SCENES.forEach { sceneId ->
            val existingBinding = SceneModelBindingStore.getBinding(sceneId)
            val existingBoundProfile = existingBinding
                ?.providerProfileId
                ?.let(ModelProviderConfigStore::getProfile)
            if (shouldReplaceDefaultBinding(
                existingProfileId = existingBinding?.providerProfileId,
                existingProfileConfigured = existingBoundProfile?.isConfigured() == true,
            )) {
                SceneModelBindingStore.saveBinding(
                    sceneId = sceneId,
                    providerProfileId = savedLlmThuProfile.id,
                    modelId = llmThuPlan.model,
                )
            }
        }
        seedFlutterManualModelId(
            context = context,
            profileId = savedLlmThuProfile.id,
            modelId = llmThuPlan.model,
        )
        OmniLog.i(
            TAG,
            "debug LLMTHU provider configured; defaultScenes=${DEFAULT_LLMTHU_SCENES.size}",
        )
    }

    /**
     * Keep the configured default visible to Flutter before a remote
     * /models request succeeds. This matters on a fresh or temporarily
     * offline install, where the scene binding already knows the model but
     * the Flutter model cache is still empty.
     */
    private fun seedFlutterManualModelId(
        context: Context,
        profileId: String,
        modelId: String,
    ) {
        val preferences = context.applicationContext.getSharedPreferences(
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

    private val DEBUG_MANAGED_PROFILE_IDS = setOf(
        LEGACY_OMNIMIND_PROFILE_ID,
        LLMTHU_PROFILE_ID,
    )

    internal fun shouldReplaceDefaultBinding(
        existingProfileId: String?,
        existingProfileConfigured: Boolean,
    ): Boolean = existingProfileId == null ||
        !existingProfileConfigured ||
        existingProfileId in DEBUG_MANAGED_PROFILE_IDS

    internal fun createLlmThuPlan(
        apiBase: String,
        apiKey: String,
        model: String,
    ): DebugOmniMindProviderPlan? = createProviderPlan(
        profileId = LLMTHU_PROFILE_ID,
        profileName = LLMTHU_PROFILE_NAME,
        apiBase = apiBase,
        apiKey = apiKey,
        model = model,
        wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
    )

    private fun createProviderPlan(
        profileId: String,
        profileName: String,
        apiBase: String,
        apiKey: String,
        model: String,
        wireApi: String,
    ): DebugOmniMindProviderPlan? {
        val normalizedBase = ModelProviderConfigStore.normalizeBaseUrl(apiBase) ?: return null
        val normalizedKey = apiKey.trim().takeIf(String::isNotEmpty) ?: return null
        val normalizedModel = model.trim().takeIf(String::isNotEmpty) ?: return null
        return DebugOmniMindProviderPlan(
            profile = ModelProviderProfile(
                id = profileId,
                name = profileName,
                baseUrl = normalizedBase,
                apiKey = normalizedKey,
                sourceType = "custom",
                protocolType = "openai_compatible",
                wireApi = wireApi,
            ),
            model = normalizedModel,
        )
    }
}

internal data class DebugOmniMindProviderPlan(
    val profile: ModelProviderProfile,
    val model: String,
)
