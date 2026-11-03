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
    /** Runtime-only account version; excluded from persisted provider metadata. */
    val credentialRevision: Long = 0L,
) {
    fun isConfigured(): Boolean = baseUrl.isNotBlank()
}

/**
 * Wire capabilities owned by the resolved Provider route. These are not ACP
 * session capabilities: they describe the request/response contract between
 * the shared Agent client and one upstream model endpoint.
 */
data class ProviderRequestCapabilities(
    val supportsChatPromptCacheKey: Boolean = false,
    val supportsExplicitAutoToolChoice: Boolean = true,
    val requiresReasoningContentForToolCalls: Boolean = false,
    val requiresAnthropicThinkingReplay: Boolean = false,
    val supportsResponsesPromptCacheKey: Boolean = true,
    val supportsResponsesParallelToolCalls: Boolean = true,
    /**
     * Whether this resolved route accepts image input.
     *
     * Null means the provider has not declared this fact. It is intentionally
     * tri-state so unknown BYOK routes keep their existing behavior while a
     * provider can explicitly reject image continuation before a request is
     * built.
     */
    val supportsVisionInput: Boolean? = null,
)

/** Cached by native code and consumed by Flutter; JSON keys are an explicit storage contract. */
data class ProviderModelOption(
    @field:SerializedName("id")
    val id: String,
    @field:SerializedName("displayName")
    val displayName: String = id,
    @field:SerializedName("ownedBy")
    val ownedBy: String? = null,
    @field:SerializedName("contextLimit")
    val contextLimit: Int? = null,
    @field:SerializedName("inputLimit")
    val inputLimit: Int? = null,
    @field:SerializedName("outputLimit")
    val outputLimit: Int? = null,
    @field:SerializedName("inputModalities")
    val inputModalities: List<String> = emptyList(),
    @field:SerializedName("outputModalities")
    val outputModalities: List<String> = emptyList(),
    @field:SerializedName("modelsDevProviderId")
    val modelsDevProviderId: String? = null,
    @field:SerializedName("modelsDevProviderName")
    val modelsDevProviderName: String? = null,
    @field:SerializedName("providerLogoUrl")
    val providerLogoUrl: String? = null,
    @field:SerializedName("family")
    val family: String? = null,
    @field:SerializedName("group")
    val group: String? = null,
    @field:SerializedName("attachment")
    val attachment: Boolean? = null,
    @field:SerializedName("reasoning")
    val reasoning: Boolean? = null,
    @field:SerializedName("supportedReasoningLevels")
    val supportedReasoningLevels: List<String> = emptyList(),
    @field:SerializedName("defaultReasoningLevel")
    val defaultReasoningLevel: String? = null,
    /** Independent of enabled effort levels; null means the Provider has not declared it. */
    @field:SerializedName("reasoningCanDisable")
    val reasoningCanDisable: Boolean? = null,
    /** A default effort alone does not declare whether thinking starts enabled. */
    @field:SerializedName("reasoningDefaultEnabled")
    val reasoningDefaultEnabled: Boolean? = null,
    @field:SerializedName("reasoningSupportsAdaptive")
    val reasoningSupportsAdaptive: Boolean? = null,
    @field:SerializedName("reasoningWireFormat")
    val reasoningWireFormat: String? = null,
    @field:SerializedName("reasoningEffortSupported")
    val reasoningEffortSupported: Boolean? = null,
    @field:SerializedName("reasoningContentRequired")
    val reasoningContentRequired: Boolean? = null,
    @field:SerializedName("reasoningCapabilitySource")
    val reasoningCapabilitySource: String? = null,
    @field:SerializedName("toolCall")
    val toolCall: Boolean? = null,
    @field:SerializedName("structuredOutput")
    val structuredOutput: Boolean? = null,
    @field:SerializedName("temperature")
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

/** Persisted voice-scene settings shared by native playback and Flutter UI. */
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
    val customCurlCommand: String = "",
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
