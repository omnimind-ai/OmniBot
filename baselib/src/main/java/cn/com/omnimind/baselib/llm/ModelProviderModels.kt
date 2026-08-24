package cn.com.omnimind.baselib.llm

import com.google.gson.annotations.SerializedName

data class ModelProviderConfig(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val source: String = "none",
    val providerType: String = "custom",
    val readOnly: Boolean = false,
    val ready: Boolean = true,
    val statusText: String? = null,
    val wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank()
}

data class ModelProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val customHeaders: Map<String, String> = emptyMap(),
    val sourceType: String = "custom",
    val readOnly: Boolean = false,
    val ready: Boolean = true,
    val statusText: String? = null,
    val protocolType: String = "openai_compatible",
    val wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS,
    val revision: Long = 0L,
) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank()
}

data class ProviderModelOption(
    val id: String,
    val displayName: String = id,
    val ownedBy: String? = null,
    val contextLimit: Int? = null,
    val inputLimit: Int? = null,
    val outputLimit: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val modelsDevProviderId: String? = null,
    val modelsDevProviderName: String? = null,
    val providerLogoUrl: String? = null,
    val family: String? = null,
    val group: String? = null,
    val attachment: Boolean? = null,
    val reasoning: Boolean? = null,
    val toolCall: Boolean? = null,
    val structuredOutput: Boolean? = null,
    val temperature: Boolean? = null
)

data class SceneCatalogItem(
    val sceneId: String,
    val description: String? = null,
    val defaultModel: String,
    val effectiveModel: String,
    val effectiveProviderProfileId: String? = null,
    val effectiveProviderProfileName: String? = null,
    val boundProviderProfileId: String? = null,
    val boundProviderProfileName: String? = null,
    val transport: String,
    val configSource: String,
    val overrideApplied: Boolean,
    val overrideModel: String? = null,
    val providerConfigured: Boolean = false,
    val bindingExists: Boolean = false,
    val bindingProfileMissing: Boolean = false
)

data class SceneModelOverrideEntry(
    val sceneId: String,
    val model: String
)

data class SceneModelBindingEntry(
    @field:SerializedName(value = "sceneId", alternate = ["a"])
    val sceneId: String,
    @field:SerializedName(value = "providerProfileId", alternate = ["b"])
    val providerProfileId: String,
    @field:SerializedName(value = "modelId", alternate = ["c"])
    val modelId: String
)

data class SceneOperationConfig(
    val useOfficialService: Boolean = false
)

data class OfficialVlmOperationConfig(
    val enabled: Boolean = false,
    val apiBase: String = "",
    val model: String = "",
    val wireApi: String = OpenAiWireApi.CHAT_COMPLETIONS
) {
    fun isConfigured(): Boolean {
        return enabled &&
            apiBase.trim().isNotEmpty() &&
            model.trim().isNotEmpty()
    }
}
