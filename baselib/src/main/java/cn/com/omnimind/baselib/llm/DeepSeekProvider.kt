package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object DeepSeekProvider {
    const val OFFICIAL_PROFILE_ID = "deepseek-official"
    const val OFFICIAL_PROFILE_NAME = "DeepSeek"
    const val OFFICIAL_BASE_URL = "https://api.deepseek.com"
    const val PROTOCOL_TYPE = "deepseek"

    fun officialProfile(): ModelProviderProfile {
        return ModelProviderProfile(
            id = OFFICIAL_PROFILE_ID,
            name = OFFICIAL_PROFILE_NAME,
            baseUrl = OFFICIAL_BASE_URL,
            sourceType = PROTOCOL_TYPE,
            protocolType = PROTOCOL_TYPE
        )
    }

    fun isOfficialBaseUrl(value: String?): Boolean {
        return OfficialProviderUrlMatcher.matchesHttpsHostWithOptionalV1(
            value = value,
            expectedHost = "api.deepseek.com"
        )
    }

    fun shouldUseOfficialAdapter(protocolType: String?, apiBase: String?): Boolean {
        return normalizeProtocolType(protocolType) != "anthropic" && isOfficialBaseUrl(apiBase)
    }

    /**
     * The resolved route is the single source of upstream wire capabilities.
     * Keep these facts here so the HTTP adapter and Agent request policy cannot
     * drift into different DeepSeek/Anthropic detection rules.
     */
    fun requestCapabilities(
        protocolType: String?,
        apiBase: String?,
        model: String?,
    ): ProviderRequestCapabilities {
        val normalizedProtocol = normalizeProtocolType(protocolType)
        return ProviderRequestCapabilities(
            supportsExplicitAutoToolChoice = !(
                normalizedProtocol != "anthropic" &&
                    isOfficialBaseUrl(apiBase) &&
                    isV4Model(model)
                ),
            requiresReasoningContentForToolCalls = shouldUseOfficialAdapter(protocolType, apiBase),
            requiresAnthropicThinkingReplay = normalizedProtocol == "anthropic",
            supportsResponsesPromptCacheKey = !shouldUseOfficialAdapter(protocolType, apiBase),
            supportsResponsesParallelToolCalls = !shouldUseOfficialAdapter(protocolType, apiBase),
            supportsVisionInput = visionInputSupport(protocolType, apiBase, model),
        )
    }

    /**
     * DeepSeek documents V4-Flash as text-only and exposes image input through
     * the separate `deepseek-v4-flash-vision-exp` model. Only make this
     * decision for the official host; a proxy may expose a different contract.
     */
    private fun visionInputSupport(
        protocolType: String?,
        apiBase: String?,
        model: String?,
    ): Boolean? {
        if (normalizeProtocolType(protocolType) == "anthropic" || !isOfficialBaseUrl(apiBase)) {
            return null
        }
        val normalizedModel = model
            ?.trim()
            ?.lowercase()
            ?.substringAfterLast('/')
            .orEmpty()
        return normalizedModel == "deepseek-v4-flash-vision-exp"
    }

    /**
     * DeepSeek V4 thinking already defaults to automatic tool selection. Its
     * official Agent integration documents that an explicit `tool_choice`
     * field is not accepted on this route, so remove only the redundant auto
     * form at the provider boundary. Required or named choices retain their
     * semantics and are never silently rewritten.
     */
    fun shouldOmitExplicitAutoToolChoice(
        protocolType: String?,
        apiBase: String?,
        model: String?,
        thinkingDisabled: Boolean,
        toolChoice: JsonElement?
    ): Boolean {
        if (thinkingDisabled) return false
        if (!requestCapabilities(protocolType, apiBase, model).supportsExplicitAutoToolChoice) {
            return when (toolChoice) {
                is JsonPrimitive -> toolChoice.contentOrNull?.equals("auto", ignoreCase = true) == true
                is JsonObject -> {
                    val type = (toolChoice["type"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?.trim()
                    type.equals("auto", ignoreCase = true)
                }
                else -> false
            }
        }
        return false
    }

    fun normalizeProtocolType(value: String?): String {
        return when (value?.trim()?.lowercase().orEmpty()) {
            PROTOCOL_TYPE -> PROTOCOL_TYPE
            "anthropic" -> "anthropic"
            else -> "openai_compatible"
        }
    }

    private fun isV4Model(value: String?): Boolean =
        value?.trim()?.lowercase()?.startsWith("deepseek-v4") == true

    fun mapReasoningEffortForOfficialApi(value: String?): String? {
        return when (ReasoningEffort.normalize(value)) {
            ReasoningEffort.LOW -> ReasoningEffort.LOW
            ReasoningEffort.HIGH,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.XHIGH -> ReasoningEffort.HIGH
            ReasoningEffort.MAX -> ReasoningEffort.MAX
            else -> null
        }
    }
}
