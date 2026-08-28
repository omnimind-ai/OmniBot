package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.PlatformModel
import cn.com.omnimind.baselib.account.PlatformModelCatalog

data class OfficialModelSelection(
    val textModels: List<PlatformModel> = emptyList(),
    val visionModels: List<PlatformModel> = emptyList(),
    val imageModels: List<PlatformModel> = emptyList(),
    val embeddingModels: List<PlatformModel> = emptyList(),
    val ttsModels: List<PlatformModel> = emptyList(),
    val defaultTextModel: PlatformModel? = null,
    val defaultVisionModel: PlatformModel? = null,
    val defaultImageModel: PlatformModel? = null,
    val defaultEmbeddingModel: PlatformModel? = null,
    val defaultTtsModel: PlatformModel? = null,
    val ttsVoiceAliases: List<String> = emptyList(),
    val defaultTtsVoiceAlias: String? = null,
)

object OmniOfficialProvider {
    const val SOURCE_TYPE = "omnibot_official"
    const val PROFILE_ID = "omnibot-official-ai"
    const val PROFILE_NAME = "OmniBot 官方 AI"
    const val DEFAULT_TEXT_MODEL_ID = "Qwen3.5-Plus"
    val LEGACY_TTS_VOICE_ALIASES: List<String> = listOf(
        SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
        SceneVoiceConfigStore.VOICE_DEFAULT_EN,
        SceneVoiceConfigStore.VOICE_MIMO_DEFAULT,
    )
    fun isOfficialProfile(profileId: String?): Boolean =
        profileId?.trim() == PROFILE_ID

    fun shouldExpose(): Boolean =
        OmniAccount.currentAiRequestAccess().mode == AiAccessMode.PLATFORM

    fun profileOrNull(status: PlatformAiProvisioningStatus): ModelProviderProfile? {
        val access = OmniAccount.currentAiRequestAccess()
        if (!shouldExpose()) {
            return null
        }
        val ready = access.usesPlatform && status.ready
        return ModelProviderProfile(
            id = PROFILE_ID,
            name = PROFILE_NAME,
            baseUrl = access.platformGatewayUrl.orEmpty(),
            sourceType = SOURCE_TYPE,
            readOnly = true,
            ready = ready,
            statusText = when {
                ready -> "官方文本模型已就绪"
                !access.unavailableReason.isNullOrBlank() -> access.unavailableReason
                status.statusText.isNotBlank() -> status.statusText
                else -> "正在同步官方文本模型"
            },
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
        )
    }

    /**
     * Resolves only server-declared capabilities. For an older gateway without
     * official_catalog, the single locally verified text model is the only
     * allowed fallback; endpoint metadata is deliberately not guessed.
     */
    fun selectModels(
        catalog: PlatformModelCatalog,
        rememberedTextModelId: String? = null,
    ): OfficialModelSelection {
        val availableById = catalog.models.associateBy(PlatformModel::id)
        fun declared(ids: List<String>): List<PlatformModel> =
            ids.mapNotNull(availableById::get).distinctBy(PlatformModel::id)

        val textModels = if (catalog.hasOfficialCatalog) {
            declared(catalog.capabilities.text)
        } else {
            catalog.models.filter { it.id == DEFAULT_TEXT_MODEL_ID }
        }
        val visionModels = if (catalog.hasOfficialCatalog) {
            declared(catalog.capabilities.vision)
        } else {
            emptyList()
        }
        val imageModels = if (catalog.hasOfficialCatalog) {
            declared(catalog.capabilities.image)
        } else {
            emptyList()
        }
        val embeddingModels = if (catalog.hasOfficialCatalog) {
            declared(catalog.capabilities.embedding)
        } else {
            emptyList()
        }
        val ttsModels = if (catalog.hasOfficialCatalog) {
            declared(catalog.capabilities.tts)
        } else {
            emptyList()
        }
        fun declaredDefault(id: String?, models: List<PlatformModel>): PlatformModel? {
            val normalized = id?.trim().orEmpty()
            return models.firstOrNull { it.id == normalized }
        }

        val remembered = rememberedTextModelId?.trim().orEmpty()
        val defaultText = textModels.firstOrNull { it.id == remembered }
            ?: declaredDefault(catalog.defaults.text, textModels)
            ?: textModels.firstOrNull { it.id == DEFAULT_TEXT_MODEL_ID }
        val publishedVoiceAliases = catalog.capabilities.ttsVoices
        val publishedDefaultVoice = catalog.defaults.ttsVoice
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val ttsVoiceAliases = when {
            publishedVoiceAliases != null -> publishedVoiceAliases
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            publishedDefaultVoice != null -> listOf(publishedDefaultVoice)
            else -> LEGACY_TTS_VOICE_ALIASES
        }
        val defaultTtsVoiceAlias = when {
            publishedVoiceAliases != null -> publishedDefaultVoice
                ?.takeIf(ttsVoiceAliases::contains)
                ?: ttsVoiceAliases.firstOrNull()
            publishedDefaultVoice != null -> publishedDefaultVoice
            else -> SceneVoiceConfigStore.VOICE_DEFAULT_ZH
        }
        return OfficialModelSelection(
            textModels = textModels,
            visionModels = visionModels,
            imageModels = imageModels,
            embeddingModels = embeddingModels,
            ttsModels = ttsModels,
            defaultTextModel = defaultText,
            defaultVisionModel = declaredDefault(catalog.defaults.vision, visionModels),
            defaultImageModel = declaredDefault(catalog.defaults.image, imageModels),
            defaultEmbeddingModel = declaredDefault(
                catalog.defaults.embedding,
                embeddingModels,
            ),
            defaultTtsModel = declaredDefault(catalog.defaults.tts, ttsModels),
            ttsVoiceAliases = ttsVoiceAliases,
            defaultTtsVoiceAlias = defaultTtsVoiceAlias,
        )
    }

    fun textModels(models: List<PlatformModel>): List<PlatformModel> =
        selectModels(PlatformModelCatalog(models = models)).textModels

    fun chooseDefaultModel(
        models: List<PlatformModel>,
        rememberedModelId: String? = null,
    ): PlatformModel? = selectModels(
        catalog = PlatformModelCatalog(models = models),
        rememberedTextModelId = rememberedModelId,
    ).defaultTextModel
}
