package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * The app has one provider configuration. Official ACP runtimes each expose
 * their own deployment configuration, so this layer only remaps the shared
 * values into the official runtime surface. It does not define another
 * protocol or another agent implementation.
 */
internal data class AgentProviderCredentials(
    val baseUrl: String,
    val apiKey: String,
    val wireApi: String = "chat_completions",
    val customHeaders: Map<String, String> = emptyMap(),
    val protocolType: String = "openai_compatible"
)

internal data class AgentProviderMappingInput(
    val agentId: String,
    val provider: AgentProviderCredentials?,
    val model: String?,
    val harnessAdapter: AcpHarnessAdapter = AcpHarnessAdapters.standard,
    val deepSeekConfig: DeepSeekHarnessConfig = DeepSeekHarnessConfig(),
)

internal data class AgentProviderMapping(
    val environment: Map<String, String> = emptyMap(),
    val deepSeekConfig: DeepSeekHarnessConfig? = null,
    val codexModel: String? = null,
    val codexWireApi: String? = null,
    val codexBaseUrl: String? = null,
    /** Official OpenCode model reference (provider/model), written to opencode.json. */
    val openCodeModel: String? = null,
    val openCodeBaseUrl: String? = null
)

internal interface AgentConfigAdapter {
    fun map(input: AgentProviderMappingInput): AgentProviderMapping
}

internal object AgentConfigAdapterRegistry {
    private val adapters: List<AgentConfigAdapter> = listOf(
        DeepSeekHarnessConfigAdapter,
        CodexConfigAdapter,
        ClaudeCodeConfigAdapter,
        OpenCodeConfigAdapter
    )

    fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        return adapters.firstOrNull { it.supports(input) }
            ?.map(input)
            ?: AgentProviderMapping()
    }

    private fun AgentConfigAdapter.supports(input: AgentProviderMappingInput): Boolean {
        return when (this) {
            DeepSeekHarnessConfigAdapter ->
                input.harnessAdapter === AcpHarnessAdapters.deepSeekHarness
            CodexConfigAdapter ->
                input.agentId == AcpAgentProfileStore.CODEX_AGENT_ID
            ClaudeCodeConfigAdapter -> input.agentId == CLAUDE_CODE_AGENT_ID
            OpenCodeConfigAdapter -> input.agentId == OPENCODE_AGENT_ID
            else -> false
        }
    }
}

private object DeepSeekHarnessConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val config = syncAgentProviderCredentials(
            config = input.deepSeekConfig,
            sharedProvider = input.provider,
            sharedModel = input.model
        )
        return AgentProviderMapping(
            environment = config.toEnvironment(),
            deepSeekConfig = config
        )
    }
}

private object CodexConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider
        val environment = if (provider == null) {
            mapOf("CODEX_HOME" to AgentRuntimeDefaults.CODEX_HOME)
        } else {
            mapOf(
                "CODEX_HOME" to AgentRuntimeDefaults.CODEX_HOME,
                "OPENAI_BASE_URL" to provider.baseUrl,
                "OPENAI_API_KEY" to provider.apiKey
            )
        }
        return AgentProviderMapping(
            environment = environment,
            codexModel = input.model?.trim()?.takeIf { it.isNotEmpty() },
            // Current Codex ACP (1.1.x) removed the legacy Chat Completions
            // wire and rejects `wire_api = "chat"` during every request.
            // The shared Provider may still use Chat Completions for the app
            // and DSH, but Codex must receive its own official Responses
            // transport setting.
            codexWireApi = provider?.let { OpenAiWireApi.RESPONSES },
            codexBaseUrl = provider?.baseUrl?.let(::normalizeCodexBaseUrl)
        )
    }
}

private object ClaudeCodeConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider ?: return AgentProviderMapping()
        val model = input.model?.trim()?.takeIf { it.isNotEmpty() }
        return AgentProviderMapping(
            environment = buildMap {
                put("ANTHROPIC_BASE_URL", normalizeClaudeCodeBaseUrl(provider.baseUrl))
                put("ANTHROPIC_API_KEY", provider.apiKey)
                put("ANTHROPIC_AUTH_TOKEN", provider.apiKey)
                if (model != null) {
                    // Official Claude Code model override. Without this the
                    // CLI falls back to claude-opus and a shared gateway can
                    // reject the request before it reaches the model.
                    put("ANTHROPIC_MODEL", model)
                    put("ANTHROPIC_SMALL_FAST_MODEL", model)
                }
            }
        )
    }
}

private object OpenCodeConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider ?: return AgentProviderMapping()
        val model = input.model?.trim()?.takeIf { it.isNotEmpty() }
        return AgentProviderMapping(
            environment = mapOf(
                "OPENAI_BASE_URL" to provider.baseUrl,
                "OPENAI_API_KEY" to provider.apiKey
            ),
            openCodeModel = model?.let { "$OPEN_CODE_PROVIDER_ID/$it" },
            openCodeBaseUrl = normalizeOpenCodeBaseUrl(provider.baseUrl)
        )
    }
}

internal fun syncAgentProviderCredentials(
    config: DeepSeekHarnessConfig,
    sharedProvider: AgentProviderCredentials?,
    sharedModel: String? = null
): DeepSeekHarnessConfig {
    return config.copy(
        baseUrl = sharedProvider?.baseUrl ?: config.baseUrl,
        apiKey = sharedProvider?.apiKey ?: config.apiKey,
        model = when {
            sharedModel != null -> sharedModel.trim()
            sharedProvider != null -> ""
            else -> config.model
        }
    )
}

/**
 * Selects a model without inventing a deployment-specific default.
 *
 * The Provider list is authoritative. A missing or empty list means the
 * Provider could not verify a usable model, so an old adapter model must not
 * be resurrected for an ACP launch.
 */
internal fun resolveAdapterModel(
    providerModelIds: List<String>?,
    boundModel: String?
): String? {
    val normalizedBoundModel = boundModel.normalizedModelId()
    val normalizedProviderModels = providerModelIds
        ?.mapNotNull { it.normalizedModelId() }
        ?.distinctBy(String::lowercase)
        .orEmpty()
    if (normalizedProviderModels.isEmpty()) return null
    return normalizedBoundModel.findMatchingModel(normalizedProviderModels)
}

internal fun resolveAcpLaunchModel(
    providerModelIds: List<String>?,
    boundModel: String?
): String? {
    return resolveAdapterModel(
        providerModelIds = providerModelIds,
        boundModel = boundModel
    )
}

/**
 * Resolves an ACP launch model while preserving an explicit scene binding
 * when the Provider model catalog is temporarily unavailable. A non-empty
 * Provider catalog remains authoritative: a bound model that is absent from
 * that catalog must still fail instead of silently launching an old model.
 */
internal fun resolveAcpLaunchModelWithBindingFallback(
    providerModelIds: List<String>?,
    boundModel: String?
): String? {
    val normalizedBoundModel = boundModel.normalizedModelId() ?: return null
    val normalizedProviderModels = providerModelIds
        ?.mapNotNull { it.normalizedModelId() }
        ?.distinctBy(String::lowercase)
        .orEmpty()
    if (normalizedProviderModels.isEmpty()) {
        return normalizedBoundModel
    }
    return normalizedBoundModel.findMatchingModel(normalizedProviderModels)
}

internal fun buildCodexModelCatalogJson(
    providerModels: List<ProviderModelOption>
): String {
    val models = JsonArray()
    providerModels
        .asSequence()
        .mapNotNull { providerModel ->
            val modelId = providerModel.id.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            modelId to providerModel
        }
        .distinctBy { it.first.lowercase() }
        .forEach { (modelId, providerModel) ->
            val contextWindow = providerModel.contextLimit
                ?.takeIf { it > 0 }
                ?: CODEX_DEFAULT_CONTEXT_WINDOW
            // The Provider /models response does not expose Codex's concrete
            // effort list. Codex 1.1.x otherwise falls back to `none`, which
            // the shared gateway rejects. Keep the adapter's default explicit
            // and conservative; it does not change the Provider model ID.
            val reasoningLevels = listOf("medium")
            val inputModalities = providerModel.inputModalities
                .map { it.trim().lowercase() }
                .filter { it in CODEX_SUPPORTED_INPUT_MODALITIES }
                .distinct()
                .toMutableList()
            if ("text" !in inputModalities) inputModalities += "text"

            val model = JsonObject().apply {
                addProperty("slug", modelId)
                addProperty("display_name", providerModel.displayName.trim().ifEmpty { modelId })
                add("description", JsonNull.INSTANCE)
                addProperty("base_instructions", CODEX_PROVIDER_BASE_INSTRUCTIONS)
                if (reasoningLevels.isEmpty()) {
                    add("default_reasoning_level", JsonNull.INSTANCE)
                    add("supported_reasoning_levels", JsonArray())
                } else {
                    addProperty("default_reasoning_level", "medium")
                    add("supported_reasoning_levels", JsonArray().apply {
                        reasoningLevels.forEach { effort ->
                            add(JsonObject().apply {
                                addProperty("effort", effort)
                                addProperty("description", effort)
                            })
                        }
                    })
                }
                addProperty("shell_type", "default")
                addProperty("visibility", "list")
                addProperty("supported_in_api", true)
                addProperty("priority", 99)
                add("additional_speed_tiers", JsonArray())
                add("service_tiers", JsonArray())
                add("default_service_tier", JsonNull.INSTANCE)
                add("availability_nux", JsonNull.INSTANCE)
                add("upgrade", JsonNull.INSTANCE)
                add("model_messages", JsonNull.INSTANCE)
                addProperty("include_skills_usage_instructions", false)
                addProperty("include_plugin_usage_instructions", false)
                addProperty("include_apps_usage_instructions", false)
                addProperty("supports_reasoning_summary_parameter", false)
                add("default_reasoning_summary", JsonNull.INSTANCE)
                addProperty("support_verbosity", false)
                add("default_verbosity", JsonNull.INSTANCE)
                add("apply_patch_tool_type", JsonNull.INSTANCE)
                addProperty("web_search_tool_type", "text")
                add("truncation_policy", JsonObject().apply {
                    addProperty("mode", "bytes")
                    addProperty("limit", 10000)
                })
                addProperty("supports_image_detail_original", false)
                addProperty("context_window", contextWindow)
                addProperty("max_context_window", contextWindow)
                add("auto_compact_token_limit", JsonNull.INSTANCE)
                add("comp_hash", JsonNull.INSTANCE)
                addProperty("effective_context_window_percent", 95)
                add("experimental_supported_tools", JsonArray())
                add("input_modalities", GsonBuilder().create().toJsonTree(inputModalities))
                addProperty("supports_search_tool", false)
                // Codex 1.1.x requires this capability bit when loading the
                // provider model catalog.  Only advertise parallel calls
                // when the Provider's /models metadata explicitly says so.
                addProperty("supports_parallel_tool_calls", providerModel.toolCall == true)
                addProperty("use_responses_lite", false)
                addProperty("node_repl_auto_review_required", false)
                addProperty("node_repl_disabled", false)
                add("auto_review_model_override", JsonNull.INSTANCE)
                add("model_specialty", JsonNull.INSTANCE)
                add("tool_mode", JsonNull.INSTANCE)
                add("multi_agent_version", JsonNull.INSTANCE)
            }
            models.add(model)
        }

    return GsonBuilder()
        .setPrettyPrinting()
        .create()
        .toJson(JsonObject().apply { add("models", models) }) + "\n"
}

private const val CODEX_DEFAULT_CONTEXT_WINDOW = 272000
private val CODEX_SUPPORTED_INPUT_MODALITIES = setOf("text", "image", "audio")
private const val CODEX_PROVIDER_BASE_INSTRUCTIONS =
    "You are a coding agent. Follow the user's instructions, inspect the workspace, and make safe, precise changes."

internal fun buildAuthoritativeProviderModelPayload(
    providerModelIds: List<String>?,
    boundModel: String?
): Map<String, Any?> {
    val models = providerModelIds
        .orEmpty()
        .mapNotNull { it.normalizedModelId() }
        .distinctBy(String::lowercase)
        .map { modelId ->
            linkedMapOf<String, Any?>(
                "id" to modelId,
                "model" to modelId,
                "displayName" to modelId,
            )
        }
    val modelIds = models.mapNotNull { it["id"] as? String }
    return linkedMapOf(
        "models" to models,
        "modelConfigSupported" to modelIds.isNotEmpty(),
        "currentModelId" to resolveAdapterModel(modelIds, boundModel),
        "reasoningEfforts" to emptyList<String>(),
        "currentReasoningEffort" to null,
        "configOptions" to emptyList<Any?>(),
        "source" to "provider",
    )
}

private fun String?.normalizedModelId(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

private fun String?.findMatchingModel(candidates: List<String>): String? {
    val value = this ?: return null
    return candidates.firstOrNull { it == value || it.equals(value, ignoreCase = true) }
}

internal fun buildSharedAgentProviderEnvironment(
    agentId: String,
    credentials: AgentProviderCredentials?
): Map<String, String> {
    return AgentConfigAdapterRegistry.map(
        AgentProviderMappingInput(
            agentId = agentId,
            provider = credentials,
            model = null
        )
    ).environment
}

internal fun normalizeCodexBaseUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    if (normalized.isEmpty()) return normalized
    if (normalized.contains('#') ||
        normalized.endsWith("/v1") ||
        normalized.endsWith("/compatible-mode/v1") ||
        normalized.endsWith("/responses") ||
        normalized.endsWith("/v1/responses")
    ) {
        return normalized
    }
    return "$normalized/v1"
}

internal const val OPEN_CODE_PROVIDER_ID = "omnibot"

/** OpenCode's official OpenAI-compatible provider expects the API root, not a
 * chat-completions endpoint. The shared store normally already holds the root,
 * but accepting legacy endpoint values keeps old profiles usable. */
internal fun normalizeOpenCodeBaseUrl(baseUrl: String): String {
    var normalized = baseUrl.trim().trimEnd('/')
    listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/responses",
        "/responses"
    ).firstOrNull { normalized.endsWith(it, ignoreCase = true) }?.let {
        normalized = normalized.dropLast(it.length).trimEnd('/')
    }
    if (normalized.isEmpty() || normalized.endsWith("#")) return normalized
    if (normalized.endsWith("/v1") || normalized.endsWith("/compatible-mode/v1")) {
        return normalized
    }
    return "$normalized/v1"
}

/**
 * Claude Code speaks Anthropic Messages over ACP.  Alibaba Model Studio
 * publishes a separate official Anthropic-compatible endpoint; its normal
 * provider URL is the OpenAI-compatible endpoint used by Codex/OpenCode.
 * Remap only the documented Alibaba endpoints here.  Other providers keep
 * their configured URL untouched because the host cannot infer a compatible
 * protocol from a generic URL.
 */
internal fun normalizeClaudeCodeBaseUrl(baseUrl: String): String {
    var normalized = baseUrl.trim().trimEnd('/')
    listOf(
        "/chat/completions",
        "/v1/chat/completions",
        "/responses",
        "/v1/responses",
    ).firstOrNull { normalized.endsWith(it, ignoreCase = true) }?.let {
        normalized = normalized.dropLast(it.length).trimEnd('/')
    }
    if (normalized.endsWith("/apps/anthropic", ignoreCase = true)) {
        return normalized
    }

    val host = runCatching {
        java.net.URI(normalized).host?.lowercase()
    }.getOrNull().orEmpty()
    val isAlibabaModelStudio = host == "dashscope.aliyuncs.com" ||
        host == "dashscope-us.aliyuncs.com" ||
        host.endsWith(".dashscope.aliyuncs.com") ||
        host.endsWith(".maas.aliyuncs.com")
    if (!isAlibabaModelStudio) return normalized

    val openAiPath = when {
        normalized.endsWith("/compatible-mode/v1", ignoreCase = true) ->
            "/compatible-mode/v1"
        normalized.endsWith("/v1", ignoreCase = true) -> "/v1"
        else -> null
    }
    return if (openAiPath != null) {
        normalized.dropLast(openAiPath.length).trimEnd('/') + "/apps/anthropic"
    } else {
        normalized
    }
}
