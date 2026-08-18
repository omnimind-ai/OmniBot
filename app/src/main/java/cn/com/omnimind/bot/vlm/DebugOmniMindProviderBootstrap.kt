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
    private const val DEVICE_DEBUG_BUILD_TYPE = "deviceDebug"
    private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
    private const val FLUTTER_MANUAL_MODEL_IDS_KEY = "flutter.manual_provider_model_ids_v2"
    private val DEFAULT_LLMTHU_SCENES = listOf(
        "scene.dispatch.model",
        SceneOperationConfigStore.SCENE_ID,
        "scene.compactor.context.chat",
    )

    fun install(context: Context) {
        if (!shouldInstallDebugProvider(BuildConfig.BUILD_TYPE, BuildConfig.ENABLE_LLMTHU_BOOTSTRAP)) {
            if (BuildConfig.BUILD_TYPE == DEVICE_DEBUG_BUILD_TYPE) {
                removeDeviceDebugState(context)
            }
            return
        }
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
     * The normal device-validation APK is signed like debug so it can be
     * installed quickly, but it must not inherit debug-only provider
     * injection. Otherwise a stale bundled LLMTHU profile becomes the active
     * model and hides the user's shared Provider configuration.
     */
    internal fun shouldInstallDebugProvider(
        buildType: String,
        enabled: Boolean,
    ): Boolean = enabled && buildType != DEVICE_DEBUG_BUILD_TYPE

    private fun removeDeviceDebugState(context: Context) {
        val profiles = runCatching { ModelProviderConfigStore.listProfiles() }
            .getOrElse { return }
        val removedProfile = profiles.firstOrNull { it.id in DEBUG_MANAGED_PROFILE_IDS }
        val remainingProfiles = profiles.filterNot { it.id in DEBUG_MANAGED_PROFILE_IDS }
        val recoveredProfile = recoverSharedProvider(remainingProfiles, removedProfile)
        val recoveredTargetId = remainingProfiles.firstOrNull { profile ->
            profile.id == "profile-1" && !profile.isConfigured() && !profile.readOnly
        }?.id
        val recoveredProfileForStorage = recoveredProfile?.copy(
            id = recoveredTargetId ?: "shared-agent-provider",
        )
        val profilesToKeep = if (recoveredProfileForStorage == null) {
            remainingProfiles
        } else {
            val replaced = remainingProfiles.map { profile ->
                if (profile.id == recoveredTargetId) recoveredProfileForStorage else profile
            }
            if (recoveredTargetId == null) replaced + recoveredProfileForStorage else replaced
        }
        val currentEditingProfileId = runCatching {
            ModelProviderConfigStore.getEditingProfile().id
        }.getOrNull()
        val selectedProfileId = profilesToKeep.firstOrNull {
            it.id == currentEditingProfileId && canStartAcpAgent(it)
        }?.id ?: profilesToKeep.firstOrNull(::canStartAcpAgent)?.id
            ?: recoveredProfileForStorage?.id
            ?: profilesToKeep.firstOrNull {
                it.id == currentEditingProfileId && it.isConfigured()
            }?.id ?: profilesToKeep.firstOrNull(ModelProviderProfile::isConfigured)?.id
            ?: profilesToKeep.firstOrNull()?.id
        val shouldRewriteProfiles = remainingProfiles.size != profiles.size ||
            recoveredProfileForStorage != null ||
            selectedProfileId != currentEditingProfileId
        if (shouldRewriteProfiles && selectedProfileId != null) {
            runCatching {
                // replaceProfiles also removes the corresponding encrypted
                // secrets. If the debug profile was the only profile, the
                // store creates its normal empty profile-1 fallback. When a
                // debug profile was the only configured one, recover its
                // credentials under the normal shared-provider identity.
                ModelProviderConfigStore.replaceProfiles(
                    profilesToKeep,
                    editingProfileId = selectedProfileId,
                )
                if (recoveredProfileForStorage != null) {
                    val sharedProfileId = ModelProviderConfigStore.getEditingProfile().id
                    val recoveredModel = BuildConfig.DEBUG_LLMTHU_MODEL.trim()
                    if (recoveredModel.isNotEmpty()) {
                        DEFAULT_LLMTHU_SCENES.forEach { sceneId ->
                            SceneModelBindingStore.saveBinding(
                                sceneId = sceneId,
                                providerProfileId = sharedProfileId,
                                modelId = recoveredModel,
                            )
                        }
                        seedFlutterManualModelId(
                            context = context,
                            profileId = sharedProfileId,
                            modelId = recoveredModel,
                        )
                    }
                }
            }.onFailure {
                OmniLog.w(TAG, "remove device debug provider failed: ${it.message}")
            }
        }
        DEFAULT_LLMTHU_SCENES.forEach { sceneId ->
            runCatching {
                val binding = SceneModelBindingStore.getBinding(sceneId)
                if (binding?.providerProfileId in DEBUG_MANAGED_PROFILE_IDS) {
                    SceneModelBindingStore.clearBinding(sceneId)
                }
            }
        }
        removeFlutterDebugModelCache(context)
    }

    private fun recoverSharedProvider(
        remainingProfiles: List<ModelProviderProfile>,
        removedProfile: ModelProviderProfile?,
    ): ModelProviderProfile? {
        if (remainingProfiles.any(::canStartAcpAgent)) return null
        val source = removedProfile?.takeIf(::canStartAcpAgent)
            ?: createLlmThuPlan(
                apiBase = BuildConfig.DEBUG_LLMTHU_API_BASE,
                apiKey = BuildConfig.DEBUG_LLMTHU_API_KEY,
                model = BuildConfig.DEBUG_LLMTHU_MODEL,
            )?.profile
            ?: return null
        return source.copy(
            id = "profile-1",
            name = "共享 Agent Provider",
            sourceType = "custom",
            readOnly = false,
            ready = false,
            statusText = "",
        )
    }

    private fun removeFlutterDebugModelCache(context: Context) {
        val preferences = context.applicationContext.getSharedPreferences(
            FLUTTER_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val current = runCatching {
            JSONObject(preferences.getString(FLUTTER_MANUAL_MODEL_IDS_KEY, null).orEmpty())
        }.getOrElse { return }
        var changed = false
        DEBUG_MANAGED_PROFILE_IDS.forEach { profileId ->
            if (current.has(profileId)) {
                current.remove(profileId)
                changed = true
            }
        }
        if (changed) {
            preferences.edit().putString(FLUTTER_MANUAL_MODEL_IDS_KEY, current.toString()).apply()
        }
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

    internal fun canStartAcpAgent(profile: ModelProviderProfile): Boolean =
        profile.baseUrl.isNotBlank() && profile.apiKey.isNotBlank()

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
