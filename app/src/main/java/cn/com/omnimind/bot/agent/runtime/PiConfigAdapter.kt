package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ProviderModelCapabilities
import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.GsonBuilder

internal const val PI_AGENT_HOME = "/root/.pi/omnibot-acp"
internal const val PI_MODELS_PATH = "$PI_AGENT_HOME/models.json"
internal const val PI_RUNTIME_HEALTH_COMMAND =
    "PATH=/root/.npm-global/bin:\$PATH; export PATH; omnibot-pi-acp --health"

/** Translate shared Provider configuration; the Pi process owns SDK/MCP execution. */
internal object PiConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = requireNotNull(input.provider) { "Pi requires a shared Provider." }
        val model = requireNotNull(input.model) { "Select a shared Provider model before starting Pi." }
        return AgentProviderMapping(
            launchConfigPath = PI_MODELS_PATH,
            launchConfigExecutorKey = "pi-model-catalog-read",
            environment = buildMap {
                putAll(mapOf(
                    "PI_CODING_AGENT_DIR" to PI_AGENT_HOME,
                    "OMNIBOT_PI_MODELS_PATH" to PI_MODELS_PATH,
                    "OMNIBOT_PI_MODEL" to model,
                    "OMNIBOT_PI_API_KEY" to provider.apiKey,
                    "OMNIBOT_PI_HEADERS" to GsonBuilder().disableHtmlEscaping().create().toJson(provider.customHeaders),
                    "OMNIBOT_PI_SKILL_PATHS" to "/workspace/.omnibot/skills",
                ))
                input.providerProfile?.let { profile ->
                    put("OMNIBOT_PI_PROVIDER_ID", profile.id)
                    put("OMNIBOT_PI_PROVIDER_REVISION", profile.revision.toString())
                    put("OMNIBOT_PI_PROVIDER_API", piApi(provider))
                    put("OMNIBOT_PI_PROVIDER_BASE_URL", piBaseUrl(provider))
                }
            },
        )
    }

    override fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> = listOf(AgentConfigWrite(
        path = PI_MODELS_PATH,
        content = buildPiModelsJson(
            requireNotNull(input.provider), requireNotNull(input.model), providerModels, input.providerProfile,
        ),
        executorKey = "pi-model-catalog-write",
    ))
}

internal fun buildPiModelsJson(
    provider: AgentProviderCredentials,
    selectedModel: String,
    models: List<ProviderModelOption>,
    profile: ModelProviderProfile? = null,
): String {
    val api = piApi(provider)
    val catalog = (models + ProviderModelOption(id = selectedModel)).distinctBy { it.id }
        .map(ProviderModelCapabilities::normalize)
    val config = mapOf("providers" to mapOf("omnibot" to mapOf(
        "api" to api,
        "baseUrl" to piBaseUrl(provider),
        "models" to catalog.map { model -> buildMap<String, Any> {
            val contextWindow = model.contextLimit?.takeIf { it > 0 } ?: 128_000
            put("id", model.id)
            put("name", model.displayName)
            model.reasoning?.let { put("reasoning", it) }
            put("input", buildList {
                add("text")
                if (model.inputModalities.any { it.equals("image", ignoreCase = true) } || model.attachment == true) add("image")
            })
            put("contextWindow", contextWindow)
            put("maxTokens", (model.outputLimit?.takeIf { it > 0 } ?: 4096).coerceAtMost(contextWindow))
            piThinkingLevelMap(model, api)?.let { put("thinkingLevelMap", it) }
            val compatibility = buildMap<String, Any> {
                if (api == "anthropic-messages") {
                    model.reasoningSupportsAdaptive?.let { put("forceAdaptiveThinking", it) }
                }
                if (api == "openai-completions") {
                    model.reasoningWireFormat?.let { put("thinkingFormat", it) }
                    model.reasoningEffortSupported?.let { put("supportsReasoningEffort", it) }
                    model.reasoningContentRequired?.let { put("requiresReasoningContentOnAssistantMessages", it) }
                }
            }
            if (compatibility.isNotEmpty()) put("compat", compatibility)
            put("cost", mapOf("input" to 0, "output" to 0, "cacheRead" to 0, "cacheWrite" to 0))
        } },
    )), "_meta" to mapOf("omnibot" to buildMap<String, Any> {
        profile?.let {
            put("providerProfileId", it.id)
            put("providerRevision", it.revision)
        }
        put("modelThinkingDefaults", catalog.mapNotNull { model ->
            piDefaultThinkingLevel(model)?.let { model.id to it }
        }.toMap())
    }))
    // Credentials and custom header values belong to the process environment,
    // including host-managed local grants. They never enter models.json.
    return GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(config) + "\n"
}

private fun piApi(provider: AgentProviderCredentials): String = when {
    provider.protocolType == "anthropic" -> "anthropic-messages"
    OpenAiWireApi.isResponses(provider.wireApi) -> "openai-responses"
    else -> "openai-completions"
}

private fun piBaseUrl(provider: AgentProviderCredentials): String =
    if (provider.protocolType == "anthropic") normalizeAnthropicServiceRoot(provider.baseUrl)
    else normalizeOpenCodeBaseUrl(provider.baseUrl)

private val piEnabledThinkingLevels = listOf("minimal", "low", "medium", "high", "xhigh", "max")

/** The enabled effort allowlist and the ability to disable thinking are independent facts. */
private fun piThinkingLevelMap(model: ProviderModelOption, api: String): Map<String, String?>? {
    if (model.reasoning != true) return null
    val levels = model.supportedReasoningLevels.toSet()
    val enabled = levels - setOf("off", "none")
    return buildMap {
        if (enabled.isNotEmpty()) {
            for (level in piEnabledThinkingLevels) put(level, level.takeIf(enabled::contains))
        }
        when {
            model.reasoningCanDisable == false -> put("off", null)
            // OpenAI's disabled wire value is `none`. Other SDK API adapters
            // own their thinking switch, including DeepSeek's thinking.type.
            api == "openai-completions" && "none" in levels -> put("off", "none")
            api == "openai-completions" && "off" in levels -> put("off", "off")
            api == "openai-completions" && model.reasoningCanDisable == true -> put("off", "none")
        }
    }.takeIf { it.isNotEmpty() }
}

private fun piDefaultThinkingLevel(model: ProviderModelOption): String? {
    if (model.reasoning != true) return null
    if (model.reasoningDefaultEnabled == false && model.reasoningCanDisable != false) return "off"
    return when (val level = model.defaultReasoningLevel) {
        "none", "off" -> "off".takeUnless { model.reasoningCanDisable == false }
        in piEnabledThinkingLevels -> level
        else -> null
    }
}
