package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
    val protocolType: String = "openai_compatible",
    /** First-party Responses endpoints understand Codex's namespace tools. */
    val supportsNamespaceTools: Boolean = false,
)

/**
 * Provider settings are shared by every Harness. Normalize them once at the
 * adapter boundary so environment variables, config files, and ACP launch
 * payloads cannot disagree about whitespace or wire API spelling.
 */
internal fun AgentProviderCredentials.normalized(): AgentProviderCredentials {
    val normalizedBaseUrl = baseUrl.trim()
    require(normalizedBaseUrl.isNotEmpty()) { "Provider base URL is empty." }
    require(!normalizedBaseUrl.any(Char::isWhitespace)) {
        "Provider base URL contains whitespace."
    }
    val normalizedHeaders = ProviderCustomHeaderUtils
        .sanitizeCustomHeaders(customHeaders)
        .mapValues { (_, value) -> value.trim() }
    return copy(
        baseUrl = normalizedBaseUrl,
        apiKey = apiKey.trim(),
        wireApi = OpenAiWireApi.normalize(wireApi),
        customHeaders = normalizedHeaders,
        protocolType = protocolType.trim().lowercase().ifEmpty { "openai_compatible" },
    )
}

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
    val openCodeBaseUrl: String? = null,
    /** Optional Harness-owned config file read before launch. */
    val launchConfigPath: String? = null,
    val launchConfigExecutorKey: String? = null,
    /** Official Codex env_http_headers bindings for Provider custom headers. */
    val codexEnvHttpHeaders: Map<String, String> = emptyMap(),
)

internal data class AgentConfigWrite(
    val path: String,
    val content: String,
    val executorKey: String,
)

/** File access owned by the runtime transport, not by a Harness converter. */
internal interface AgentConfigFileAccess {
    suspend fun read(path: String, executorKey: String): String
    suspend fun write(path: String, content: String, executorKey: String)
}

/**
 * Merge user launch options with the canonical Provider mapping.
 *
 * Only profiles using the shared Provider reserve its credential/model keys.
 * Custom ACP agents own their configuration, including those environment keys.
 */
internal fun mergeAcpLaunchEnvironment(
    profile: AcpAgentProfile,
    providerEnvironment: Map<String, String>,
): Map<String, String> = buildMap {
    val usesSharedProvider = AcpAgentProfileStore.usesSharedProvider(profile)
    profile.environment.forEach { (key, value) ->
        if (!usesSharedProvider ||
            (key !in PROVIDER_OWNED_ENVIRONMENT_KEYS &&
                !key.startsWith(PROVIDER_HEADER_ENV_PREFIX))
        ) {
            put(key, value)
        }
    }
    putAll(providerEnvironment)
}

/**
 * For shared-Provider profiles, remove stale keys even when that Provider is
 * absent so an old route or credential cannot silently become active again.
 */
private val PROVIDER_OWNED_ENVIRONMENT_KEYS = setOf(
    "OPENAI_API_KEY",
    "OPENAI_BASE_URL",
    "ANTHROPIC_API_KEY",
    "ANTHROPIC_AUTH_TOKEN",
    "ANTHROPIC_BASE_URL",
    "ANTHROPIC_MODEL",
    "ANTHROPIC_SMALL_FAST_MODEL",
    "DEEPSEEK_API_KEY",
    "DEEPSEEK_BASE_URL",
    "DSH_MODEL",
    "KIMI_CODE_HOME",
    "KIMI_MODEL_NAME",
    "KIMI_MODEL_API_KEY",
    "KIMI_MODEL_BASE_URL",
    "KIMI_MODEL_PROVIDER_TYPE",
    "KIMI_MODEL_CAPABILITIES",
    "KIMI_MODEL_THINKING_EFFORT",
    "KIMI_CODE_CUSTOM_HEADERS",
)
private const val PROVIDER_HEADER_ENV_PREFIX = "OMNIBOT_PROVIDER_HEADER_"

internal interface AgentConfigAdapter {
    fun map(input: AgentProviderMappingInput): AgentProviderMapping

    suspend fun readConfig(
        input: AgentProviderMappingInput,
        access: AgentConfigFileAccess,
    ): Map<String, Any?>? = null

    fun directConfigWrites(
        input: AgentProviderMappingInput,
        args: Map<String, Any?>,
        providerModels: List<ProviderModelOption> = emptyList(),
    ): List<AgentConfigWrite> = emptyList()

    fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> = emptyList()
}

internal object AgentConfigAdapterRegistry {
    private val adaptersById: Map<String, AgentConfigAdapter> = mapOf(
        "deepseek-harness" to DeepSeekHarnessConfigAdapter,
        "codex" to CodexConfigAdapter,
        "claude-code" to ClaudeCodeConfigAdapter,
        "kimi-code" to KimiCodeConfigAdapter,
        "open-code" to OpenCodeConfigAdapter,
    )

    fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val normalizedInput = input.normalized()
        return adapterFor(normalizedInput)
            ?.map(normalizedInput)
            ?: AgentProviderMapping()
    }

    suspend fun readConfig(
        input: AgentProviderMappingInput,
        access: AgentConfigFileAccess,
    ): Map<String, Any?>? {
        val normalizedInput = input.normalized()
        return adapterFor(normalizedInput)?.readConfig(normalizedInput, access)
    }

    fun directConfigWrites(
        input: AgentProviderMappingInput,
        args: Map<String, Any?>,
        providerModels: List<ProviderModelOption>,
    ): List<AgentConfigWrite> {
        val normalizedInput = input.normalized()
        return adapterFor(normalizedInput)
            ?.directConfigWrites(
                input = normalizedInput,
                args = args,
                providerModels = providerModels,
            )
            .orEmpty()
    }

    fun launchConfigWrites(
        input: AgentProviderMappingInput,
        mapping: AgentProviderMapping,
        providerModels: List<ProviderModelOption>,
        existingConfig: String,
    ): List<AgentConfigWrite> {
        val normalizedInput = input.normalized()
        return adapterFor(normalizedInput)
        ?.launchConfigWrites(
            input = normalizedInput,
            mapping = mapping,
            providerModels = providerModels,
            existingConfig = existingConfig,
        )
        .orEmpty()
    }

    private fun adapterFor(input: AgentProviderMappingInput): AgentConfigAdapter? {
        return input.harnessAdapter.configAdapterId
            ?.let(adaptersById::get)
    }
}

private fun AgentProviderMappingInput.normalized(): AgentProviderMappingInput = copy(
    provider = provider?.normalized(),
    model = model?.trim()?.takeIf { it.isNotEmpty() },
)

internal fun syncAgentProviderCredentials(
    config: DeepSeekHarnessConfig,
    sharedProvider: AgentProviderCredentials?,
    sharedModel: String? = null
): DeepSeekHarnessConfig {
    val normalizedProvider = sharedProvider?.normalized()
    return config.copy(
        baseUrl = normalizedProvider?.baseUrl ?: config.baseUrl,
        apiKey = normalizedProvider?.apiKey ?: config.apiKey,
        model = when {
            sharedModel != null -> sharedModel.trim()
            normalizedProvider != null -> ""
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

/**
 * Resolves the model used by an ACP adapter from the Dispatch Model source.
 *
 * A persisted scene binding is an optional user preference. When it is absent,
 * the first verified model in the current Dispatch Provider catalog is the
 * default, so Harness installation and startup do not depend on a binding
 * record existing in MMKV.
 */
internal fun resolveAcpLaunchModelForDispatch(
    providerModelIds: List<String>?,
    dispatchModel: String?,
): String? {
    return resolveAcpLaunchModelWithBindingFallback(
        providerModelIds = providerModelIds,
        boundModel = dispatchModel,
    ) ?: providerModelIds
        ?.mapNotNull { it.normalizedModelId() }
        ?.distinctBy(String::lowercase)
        ?.firstOrNull()
}

internal fun buildCodexModelCatalogJson(
    providerModels: List<ProviderModelOption>,
    provider: AgentProviderCredentials? = null,
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
            val inputModalities = resolveCodexInputModalities(
                providerModel = providerModel,
                provider = provider,
            )

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
                    // Required official Codex model metadata. Use the configured
                    // context capacity instead of a separate 10 KB host ceiling.
                    // Codex's tool_output_token_limit remains an explicit override.
                    addProperty("mode", "tokens")
                    addProperty("limit", contextWindow)
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

/**
 * Resolve the image capability written to Codex's model catalog.
 *
 * An omitted `input_modalities` field means "unknown" in a Provider
 * `/models` response. Writing `text` for that case is not neutral: Codex
 * treats the catalog value as an explicit capability and rejects image input
 * before the request reaches the upstream model. Keep explicit model
 * metadata authoritative, then use the resolved Provider route capability,
 * and finally preserve Codex's text-plus-image default for unknown routes.
 */
internal fun resolveCodexInputModalities(
    providerModel: ProviderModelOption,
    provider: AgentProviderCredentials? = null,
): List<String> {
    val declaredModalities = providerModel.inputModalities
        .map { it.trim().lowercase() }
        .filter { it in CODEX_SUPPORTED_INPUT_MODALITIES }
        .distinct()
    if (declaredModalities.isNotEmpty()) {
        return declaredModalities.toMutableList().apply {
            if ("text" !in this) add("text")
        }
    }

    // Some compatible /models endpoints expose this as `attachment` or
    // `vision` instead of an input modality list. Treat an explicit boolean
    // as a model declaration, but do not let it override a modality list.
    providerModel.attachment?.let { supportsImage ->
        return if (supportsImage) {
            CODEX_DEFAULT_INPUT_MODALITIES
        } else {
            listOf("text")
        }
    }

    val routeSupportsImage = provider?.let {
        DeepSeekProvider.requestCapabilities(
            protocolType = it.protocolType,
            apiBase = it.baseUrl,
            model = providerModel.id,
        ).supportsVisionInput
    }
    return when (routeSupportsImage) {
        false -> listOf("text")
        true, null -> CODEX_DEFAULT_INPUT_MODALITIES
    }
}

private const val CODEX_DEFAULT_CONTEXT_WINDOW = 272000
private val CODEX_SUPPORTED_INPUT_MODALITIES = setOf("text", "image", "audio")
private val CODEX_DEFAULT_INPUT_MODALITIES = listOf("text", "image")
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

/**
 * Exposes editable Provider headers to official Harnesses without copying
 * secret values into their durable config files. The generated variable names
 * are stable for one ordered header map and are consumed only by official
 * configuration fields such as Codex `env_http_headers` and OpenCode's
 * `{env:...}` header references.
 */
internal data class AcpHeaderBindings(
    val environment: Map<String, String>,
    val envHttpHeaders: Map<String, String>,
)

internal fun buildAcpHeaderBindings(
    headers: Map<String, String>,
): AcpHeaderBindings {
    val sanitized = ProviderCustomHeaderUtils.sanitizeCustomHeaders(headers)
    val environment = linkedMapOf<String, String>()
    val envHttpHeaders = linkedMapOf<String, String>()
    sanitized.entries.forEachIndexed { index, (name, value) ->
        val envName = "$PROVIDER_HEADER_ENV_PREFIX$index"
        environment[envName] = value
        envHttpHeaders[name] = envName
    }
    return AcpHeaderBindings(
        environment = environment,
        envHttpHeaders = envHttpHeaders,
    )
}

internal fun normalizeCodexBaseUrl(baseUrl: String): String {
    var normalized = baseUrl.trim().trimEnd('/')
    listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/responses",
        "/responses",
    ).firstOrNull { normalized.endsWith(it, ignoreCase = true) }?.let {
        normalized = normalized.dropLast(it.length).trimEnd('/')
    }
    if (normalized.isEmpty() || normalized.contains('#')) return normalized
    if (normalized.endsWith("/v1") || normalized.endsWith("/compatible-mode/v1")) {
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
 * Claude Code speaks Anthropic Messages over ACP. Some Providers publish a
 * separate Anthropic-compatible path while their normal Provider URL is the
 * OpenAI-compatible path used by Xiaowan/Codex/OpenCode. Remap only known
 * official endpoints; a generic proxy URL must remain untouched because the
 * host cannot infer its protocol contract.
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

    // DeepSeek publishes separate OpenAI and Anthropic roots on the same
    // official host. Claude Code must use the documented Anthropic root;
    // passing the shared OpenAI root makes the model appear unavailable even
    // though the same Provider/model works through Xiaowan.
    if (DeepSeekProvider.isOfficialBaseUrl(normalized)) {
        val uri = java.net.URI(normalized)
        return "${uri.scheme}://${uri.rawAuthority}/anthropic"
    }

    val host = runCatching {
        java.net.URI(normalized).host?.lowercase()
    }.getOrNull().orEmpty()
    val isMiniMax = host == "api.minimaxi.com" || host == "api.minimax.io"
    val isAlibabaModelStudio = host == "dashscope.aliyuncs.com" ||
        host == "dashscope-us.aliyuncs.com" ||
        host.endsWith(".dashscope.aliyuncs.com") ||
        host.endsWith(".maas.aliyuncs.com")
    if (isMiniMax) {
        return when {
            normalized.endsWith("/anthropic/v1", ignoreCase = true) ->
                normalized.dropLast("/v1".length)
            normalized.endsWith("/anthropic", ignoreCase = true) -> normalized
            else -> {
                val uri = java.net.URI(normalized)
                "${uri.scheme}://${uri.rawAuthority}/anthropic"
            }
        }
    }
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
