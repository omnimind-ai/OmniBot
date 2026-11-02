package cn.com.omnimind.baselib.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.net.URI

/** Provider declarations are normalized before either UI or an Agent consumes the catalog. */
object ProviderModelCapabilities {
    private val disabledLevels = setOf("none", "off")
    private val anthropicEffortLevels = listOf("low", "medium", "high", "xhigh", "max")
    private val reasoningParameters = setOf(
        "reasoning", "reasoning_effort", "include_reasoning", "thinking",
        "enable_thinking", "thinking_budget", "reasoning_budget",
    )

    fun applyDeclared(
        model: ProviderModelOption,
        metadata: JsonObject,
        apiBase: String? = null,
    ): ProviderModelOption {
        val capabilityObjects = listOf("capabilities", "capability", "features", "feature")
            .mapNotNull { metadata[it] as? JsonObject }
        val reasoning = metadata["reasoning"] as? JsonObject
        val effort = metadata["effort"] as? JsonObject
        val thinking = capabilityObjects.firstNotNullOfOrNull { it["thinking"] as? JsonObject }
        val thinkingTypes = thinking?.get("types") as? JsonObject
        val anthropicEffort = capabilityObjects.firstNotNullOfOrNull { it["effort"] as? JsonObject }
        val parameters = metadata.stringArray("supported_parameters", "supportedParameters").orEmpty()

        val explicitReasoning = metadata.boolean("reasoning", "thinking", "supports_reasoning", "supportsReasoning")
            ?: capabilityObjects.firstNotNullOfOrNull {
                it.boolean("reasoning", "thinking", "supports_reasoning", "supportsReasoning")
            }
            ?: reasoning?.boolean("supported")
            ?: thinking?.boolean("supported")
        val levels = metadata.levelArray("supported_reasoning_levels", "supportedReasoningLevels")
            ?: effort?.levelArray("supported_levels", "supportedLevels")
            ?: reasoning?.levelArray("supported_efforts", "supportedEfforts")
            ?: capabilityObjects.firstNotNullOfOrNull {
                it.levelArray("reasoning_effort", "reasoningEffort")
            }
            ?: anthropicEffort?.let { declared ->
                if (declared.boolean("supported") == false) emptyList() else {
                    anthropicEffortLevels.filter { declared.capabilitySupported(it) == true }
                }
            }
            ?: model.supportedReasoningLevels.orEmpty()
        val mandatory = reasoning?.boolean("mandatory")
        val reasoningSupported = explicitReasoning ?: when {
            levels.any { it !in disabledLevels } -> true
            thinkingTypes?.let {
                it.capabilitySupported("adaptive") == true || it.capabilitySupported("enabled") == true
            } == true -> true
            reasoning != null && listOf(
                "mandatory", "supported_efforts", "supportedEfforts", "default_enabled", "defaultEnabled",
                "default_effort", "defaultEffort", "supports_max_tokens", "supportsMaxTokens",
            ).any(reasoning::containsKey) -> true
            parameters.any(reasoningParameters::contains) -> true
            else -> model.reasoning
        }
        val declaredCanDisable = metadata.boolean("reasoningCanDisable", "reasoning_can_disable")
            ?: reasoning?.boolean("can_disable", "canDisable")
            ?: thinkingTypes?.capabilitySupported("disabled")
            ?: model.reasoningCanDisable
        val deepSeekRoute = if (reasoningSupported == true &&
            effort?.levelArray("supported_levels", "supportedLevels") != null) {
            resolveDeepSeekRoute(apiBase)
        } else null
        val canDisable = when {
            mandatory == true -> false
            declaredCanDisable != null -> declaredCanDisable
            levels.any(disabledLevels::contains) -> true
            mandatory == false -> true
            "enable_thinking" in parameters -> true
            // DeepSeek's list describes enabled efforts; its documented `none`
            // request value disables thinking and is deliberately absent there.
            deepSeekRoute != null -> true
            else -> model.reasoningCanDisable
        }
        val defaultLevel = metadata.text("default_reasoning_level", "defaultReasoningLevel")
            ?: effort?.text("default_level", "defaultLevel")
            ?: reasoning?.text("default_effort", "defaultEffort")
            ?: capabilityObjects.firstNotNullOfOrNull {
                it.text("default_reasoning_effort", "defaultReasoningEffort")
            }
            ?: model.defaultReasoningLevel
        val defaultEnabled = metadata.boolean("reasoningDefaultEnabled", "reasoning_default_enabled")
            ?: reasoning?.boolean("default_enabled", "defaultEnabled")
            ?: if (mandatory == true) true else model.reasoningDefaultEnabled
        val supportsAdaptive = metadata.boolean("reasoningSupportsAdaptive", "reasoning_supports_adaptive")
            ?: thinkingTypes?.capabilitySupported("adaptive")
            ?: model.reasoningSupportsAdaptive
            // This endpoint accepts adaptive as enabled and ignores token budgets;
            // selecting a declared effort therefore uses output_config.effort.
            ?: true.takeIf { deepSeekRoute == DeepSeekRoute.ANTHROPIC }
        val wireFormat = metadata.text("reasoningWireFormat", "reasoning_wire_format")
            ?: model.reasoningWireFormat
        val effortSupported = metadata.boolean("reasoningEffortSupported", "reasoning_effort_supported")
            ?: true.takeIf { "reasoning_effort" in parameters }
            ?: model.reasoningEffortSupported
        val contentRequired = metadata.boolean("reasoningContentRequired", "reasoning_content_required")
            ?: model.reasoningContentRequired
        val hasDeclaration = explicitReasoning != null || reasoningSupported != model.reasoning ||
            levels != model.supportedReasoningLevels.orEmpty() || canDisable != model.reasoningCanDisable ||
            defaultLevel != model.defaultReasoningLevel || defaultEnabled != model.reasoningDefaultEnabled ||
            supportsAdaptive != model.reasoningSupportsAdaptive ||
            wireFormat != model.reasoningWireFormat || effortSupported != model.reasoningEffortSupported ||
            contentRequired != model.reasoningContentRequired ||
            effort != null || thinking != null || anthropicEffort != null || reasoning != null
        return normalize(model.copy(
            reasoning = reasoningSupported,
            supportedReasoningLevels = levels,
            defaultReasoningLevel = defaultLevel,
            reasoningCanDisable = canDisable,
            reasoningDefaultEnabled = defaultEnabled,
            reasoningSupportsAdaptive = supportsAdaptive,
            reasoningWireFormat = wireFormat,
            reasoningEffortSupported = effortSupported,
            reasoningContentRequired = contentRequired,
            reasoningCapabilitySource = if (hasDeclaration) "provider" else model.reasoningCapabilitySource,
        ))
    }

    /** Preserve unknown capability data and keep explicit rejection stronger than inferred support. */
    fun normalize(model: ProviderModelOption): ProviderModelOption {
        if (model.reasoning == false) {
            return model.copy(
                supportedReasoningLevels = emptyList(),
                defaultReasoningLevel = null,
                reasoningCanDisable = null,
                reasoningDefaultEnabled = false,
            )
        }
        val declaredLevels = model.supportedReasoningLevels.orEmpty()
            .map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
        val canDisable = model.reasoningCanDisable
            ?: true.takeIf { declaredLevels.any(disabledLevels::contains) }
        val levels = if (canDisable == false) declaredLevels.filterNot(disabledLevels::contains) else declaredLevels
        val defaultLevel = model.defaultReasoningLevel?.trim()?.lowercase()?.takeIf {
            it in levels || (it in disabledLevels && canDisable == true)
        }
        return model.copy(
            reasoning = model.reasoning ?: true.takeIf { levels.any { it !in disabledLevels } },
            supportedReasoningLevels = levels,
            defaultReasoningLevel = defaultLevel,
            reasoningCanDisable = canDisable,
            reasoningDefaultEnabled = model.reasoningDefaultEnabled
                ?: false.takeIf { defaultLevel in disabledLevels },
        )
    }

    private fun JsonObject.levelArray(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonArray)?.mapNotNull { entry ->
                val primitive = when (entry) {
                    is JsonPrimitive -> entry
                    is JsonObject -> (entry["effort"] ?: entry["value"]) as? JsonPrimitive
                    else -> null
                }
                primitive?.takeIf { it.isString }?.contentOrNull?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
            }?.distinct()
        }

    private fun JsonObject.stringArray(vararg names: String): List<String>? =
        names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            }
        }

    private fun JsonObject.text(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
                ?.trim()?.takeIf(String::isNotEmpty)
        }

    private fun JsonObject.capabilitySupported(name: String): Boolean? =
        (this[name] as? JsonObject)?.boolean("supported")

    private fun JsonObject.boolean(vararg names: String): Boolean? =
        names.firstNotNullOfOrNull { parseBoolean(this[it]) }

    private fun parseBoolean(value: JsonElement?): Boolean? {
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1", "yes", "supported", "enabled" -> true
            "false", "0", "no", "unsupported", "disabled" -> false
            else -> null
        }
    }

    private enum class DeepSeekRoute { OPENAI, ANTHROPIC }

    private fun resolveDeepSeekRoute(apiBase: String?): DeepSeekRoute? {
        val uri = runCatching { URI(apiBase?.trim().orEmpty()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("api.deepseek.com", ignoreCase = true) ||
            uri.port !in setOf(-1, 443) || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        return when (uri.rawPath.orEmpty().trimEnd('/')) {
            "", "/v1" -> DeepSeekRoute.OPENAI
            "/anthropic", "/anthropic/v1" -> DeepSeekRoute.ANTHROPIC
            else -> null
        }
    }
}
