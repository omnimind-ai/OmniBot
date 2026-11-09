package cn.com.omnimind.baselib.llm

/** Exact model declarations shared by discovery, session configuration and requests. */
object GeminiProvider {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1"
    private val levels = mapOf(
        "gemini-4-argon" to listOf("low", "medium", "high"),
        "gemini-3.8-flash" to listOf("low", "medium", "high"),
        "gemini-3.6-flash" to listOf("minimal", "low", "medium", "high"),
        "gemini-3.5-flash-lite" to listOf("minimal", "low", "medium", "high"),
        "gemini-3.1-pro-preview" to listOf("low", "medium", "high"),
        "gemini-3-flash-preview" to listOf("minimal", "low", "medium", "high"),
        "gemini-3-pro-preview" to listOf("low", "high"),
    )

    fun modelId(value: String): String = value.trim().removePrefix("models/")

    fun enrich(model: ProviderModelOption): ProviderModelOption {
        val supported = levels[modelId(model.id)] ?: return model
        return ProviderModelCapabilities.normalize(model.copy(
            reasoning = model.reasoning ?: true,
            supportedReasoningLevels = model.supportedReasoningLevels.ifEmpty { supported },
            reasoningCanDisable = model.reasoningCanDisable ?: false,
            reasoningDefaultEnabled = model.reasoningDefaultEnabled ?: true,
            reasoningWireFormat = "gemini_interactions",
            reasoningCapabilitySource = model.reasoningCapabilitySource ?: "bundled_catalog",
            inputModalities = model.inputModalities.ifEmpty { listOf("text", "image") },
            outputModalities = model.outputModalities.ifEmpty { listOf("text") },
            toolCall = model.toolCall ?: true,
            temperature = false,
        ))
    }

    /** A profile and its endpoint together scope opaque replay data; never include credentials. */
    fun replaySource(profileId: String?, baseUrl: String): String =
        "${profileId.orEmpty()}@${baseUrl.trim().removeSuffix("#").trimEnd('/')}"
}
