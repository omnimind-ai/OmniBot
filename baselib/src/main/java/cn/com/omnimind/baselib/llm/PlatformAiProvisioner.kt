package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiSettings
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModel
import cn.com.omnimind.baselib.account.PlatformModelsUnavailableException
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlatformAiProvisioningStatus(
    val ready: Boolean = false,
    val statusText: String = "正在同步官方文本模型",
    val defaultModelId: String? = null,
    val models: List<ProviderModelOption> = emptyList(),
    val catalogVersion: String? = null,
    val defaultVisionModelId: String? = null,
    val defaultImageModelId: String? = null,
    val defaultTtsModelId: String? = null,
    val defaultSttModelId: String? = null,
    val visionModels: List<ProviderModelOption> = emptyList(),
    val imageModels: List<ProviderModelOption> = emptyList(),
    val ttsModels: List<ProviderModelOption> = emptyList(),
    val sttModels: List<ProviderModelOption> = emptyList(),
    val ttsVoiceAliases: List<String> = emptyList(),
    val defaultTtsVoiceAlias: String? = null,
)

internal fun PlatformAiProvisioningStatus.routingUnavailableReasonOrNull(): String? {
    if (ready) {
        return null
    }
    return statusText.takeIf { it.isNotBlank() }
        ?: "官方文本模型暂时不可用，请稍后重试"
}

/**
 * Keeps the platform-only provider separate from device BYOK configuration.
 * The user's previous dispatch-scene binding is restored when platform mode
 * is left, so local provider keys and choices are never overwritten.
 */
object PlatformAiProvisioner {
    private const val DISPATCH_SCENE_ID = "scene.dispatch.model"
    private const val KEY_ACTIVE = "platform_ai_official_binding_active_v1"
    private const val KEY_BYOK_HAD_BINDING = "platform_ai_byok_had_binding_v1"
    private const val KEY_BYOK_PROFILE_ID = "platform_ai_byok_profile_id_v1"
    private const val KEY_BYOK_MODEL_ID = "platform_ai_byok_model_id_v1"
    private const val KEY_PLATFORM_MODEL_ID = "platform_ai_model_id_v1"

    private val mutex = Mutex()

    @Volatile
    private var currentStatus = PlatformAiProvisioningStatus()

    fun status(): PlatformAiProvisioningStatus = currentStatus

    fun officialProfileOrNull(): ModelProviderProfile? =
        OmniOfficialProvider.profileOrNull(currentStatus)

    fun routingUnavailableReason(): String? {
        if (!OmniOfficialProvider.shouldExpose()) {
            return null
        }
        return currentStatus.routingUnavailableReasonOrNull()
    }

    suspend fun synchronize(
        settings: AiSettings? = null,
        forceRefresh: Boolean = settings != null,
    ): PlatformAiProvisioningStatus =
        mutex.withLock {
            val platformMode = settings?.effectiveMode == AiAccessMode.PLATFORM ||
                (settings == null && OmniOfficialProvider.shouldExpose())
            if (!platformMode) {
                deactivateLocked()
                return@withLock currentStatus
            }

            val access = OmniAccount.currentAiRequestAccess()
            if (!access.usesPlatform) {
                currentStatus = PlatformAiProvisioningStatus(
                    statusText = access.unavailableReason
                        ?: "平台 AI 登录状态尚未就绪，请重新登录",
                )
                return@withLock currentStatus
            }

            val existingBinding = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
            if (!forceRefresh &&
                currentStatus.ready &&
                currentStatus.models.isNotEmpty() &&
                existingBinding != null &&
                OmniOfficialProvider.isOfficialProfile(existingBinding.providerProfileId) &&
                currentStatus.models.any { it.id == existingBinding.modelId }
            ) {
                return@withLock currentStatus
            }

            currentStatus = PlatformAiProvisioningStatus(
                statusText = "正在同步官方文本模型",
            )
            try {
                val catalog = OmniAccount.repository().getPlatformModelCatalog()
                val mmkv = MMKV.defaultMMKV()
                val currentBinding = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
                val rememberedModelId = currentBinding
                    ?.takeIf {
                        OmniOfficialProvider.isOfficialProfile(it.providerProfileId)
                    }
                    ?.modelId
                    ?: mmkv.decodeString(KEY_PLATFORM_MODEL_ID)
                val selection = OmniOfficialProvider.selectModels(
                    catalog = catalog,
                    rememberedTextModelId = rememberedModelId,
                )
                val selected = selection.defaultTextModel
                if (selected == null) {
                    currentStatus = PlatformAiProvisioningStatus(
                        statusText = "官方服务当前没有可用的已验证文本模型",
                    )
                    return@withLock currentStatus
                }

                activateBinding(mmkv, selected.id)
                currentStatus = PlatformAiProvisioningStatus(
                    ready = true,
                    statusText = "官方文本模型已就绪",
                    defaultModelId = selected.id,
                    models = selection.textModels.toOptions(),
                    catalogVersion = catalog.version,
                    defaultVisionModelId = selection.defaultVisionModel?.id,
                    defaultImageModelId = selection.defaultImageModel?.id,
                    defaultTtsModelId = selection.defaultTtsModel?.id,
                    defaultSttModelId = selection.defaultSttModel?.id,
                    visionModels = selection.visionModels.toOptions(),
                    imageModels = selection.imageModels.toOptions(),
                    ttsModels = selection.ttsModels.toOptions(),
                    sttModels = selection.sttModels.toOptions(),
                    ttsVoiceAliases = selection.ttsVoiceAliases,
                    defaultTtsVoiceAlias = selection.defaultTtsVoiceAlias,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                currentStatus = PlatformAiProvisioningStatus(
                    statusText = "获取官方模型失败，请检查网络后重试",
                )
            }
            currentStatus
        }

    suspend fun ensureReadyStatus(): PlatformAiProvisioningStatus {
        val existing = currentStatus
        if (existing.ready && existing.models.isNotEmpty()) {
            return existing
        }
        val synchronized = synchronize()
        if (!synchronized.ready || synchronized.models.isEmpty()) {
            throw PlatformModelsUnavailableException(
                synchronized.statusText.ifBlank { "官方文本模型暂时不可用" }
            )
        }
        return synchronized
    }

    suspend fun ensureReadyAndGetModels(): List<ProviderModelOption> =
        ensureReadyStatus().models

    suspend fun deactivate() {
        mutex.withLock { deactivateLocked() }
    }

    private fun activateBinding(mmkv: MMKV, modelId: String) {
        val wasActive = mmkv.decodeBool(KEY_ACTIVE, false)
        if (!wasActive) {
            val current = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
            val byokBinding = current?.takeUnless {
                OmniOfficialProvider.isOfficialProfile(it.providerProfileId)
            }
            mmkv.encode(KEY_BYOK_HAD_BINDING, byokBinding != null)
            if (byokBinding == null) {
                mmkv.removeValuesForKeys(arrayOf(KEY_BYOK_PROFILE_ID, KEY_BYOK_MODEL_ID))
            } else {
                mmkv.encode(KEY_BYOK_PROFILE_ID, byokBinding.providerProfileId)
                mmkv.encode(KEY_BYOK_MODEL_ID, byokBinding.modelId)
            }
            mmkv.encode(KEY_ACTIVE, true)
        }
        mmkv.encode(KEY_PLATFORM_MODEL_ID, modelId)
        SceneModelBindingStore.saveBinding(
            sceneId = DISPATCH_SCENE_ID,
            providerProfileId = OmniOfficialProvider.PROFILE_ID,
            modelId = modelId,
        )
    }

    private fun deactivateLocked() {
        val mmkv = MMKV.defaultMMKV()
        if (mmkv.decodeBool(KEY_ACTIVE, false)) {
            val current = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
            if (current != null &&
                OmniOfficialProvider.isOfficialProfile(current.providerProfileId)
            ) {
                mmkv.encode(KEY_PLATFORM_MODEL_ID, current.modelId)
            }

            val byokProfileId = mmkv.decodeString(KEY_BYOK_PROFILE_ID)?.trim().orEmpty()
            val byokModelId = mmkv.decodeString(KEY_BYOK_MODEL_ID)?.trim().orEmpty()
            val canRestore = mmkv.decodeBool(KEY_BYOK_HAD_BINDING, false) &&
                byokProfileId.isNotEmpty() &&
                byokModelId.isNotEmpty() &&
                ModelProviderConfigStore.getProfile(byokProfileId)?.isConfigured() == true
            if (canRestore) {
                SceneModelBindingStore.saveBinding(
                    sceneId = DISPATCH_SCENE_ID,
                    providerProfileId = byokProfileId,
                    modelId = byokModelId,
                )
            } else {
                SceneModelBindingStore.clearBinding(DISPATCH_SCENE_ID)
            }
            mmkv.encode(KEY_ACTIVE, false)
        }
        currentStatus = PlatformAiProvisioningStatus(
            statusText = "平台模式未启用",
        )
    }

    private fun List<PlatformModel>.toOptions(): List<ProviderModelOption> =
        map { model ->
            ProviderModelOption(
                id = model.id,
                displayName = model.id,
                ownedBy = model.ownedBy,
            )
        }
}
