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
    val destinationConsentValid: Boolean = false,
) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && (readOnly || destinationConsentValid)
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
    val consentVersion: Int = 0,
    val consentOrigin: String = "",
    val consentRevision: Long = 0L,
    val destinationConsentValid: Boolean = false,
) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && (readOnly || destinationConsentValid)
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

data class SceneVoiceConfig(
    @field:SerializedName(value = "autoPlay", alternate = ["a"])
    val autoPlay: Boolean = false,
    @field:SerializedName(value = "voiceId", alternate = ["b"])
    val voiceId: String = "default_zh",
    @field:SerializedName(value = "stylePreset", alternate = ["c"])
    val stylePreset: String = "默认",
    @field:SerializedName(value = "customStyle", alternate = ["d"])
    val customStyle: String = "",
    @field:SerializedName(value = "ttsMode", alternate = ["e"])
    val ttsMode: String = "builtin",
    @field:SerializedName(value = "customCurlCommand", alternate = ["f"])
    val customCurlCommand: String = ""
)
