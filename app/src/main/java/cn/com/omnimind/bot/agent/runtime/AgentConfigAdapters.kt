package cn.com.omnimind.bot.agent.runtime

/**
 * The app has one provider configuration. Official ACP runtimes each expose
 * their own deployment configuration, so this layer only remaps the shared
 * values into the official runtime surface. It does not define another
 * protocol or another agent implementation.
 */
internal data class AgentProviderCredentials(
    val baseUrl: String,
    val apiKey: String,
    val wireApi: String = "chat_completions"
)

internal data class AgentProviderMappingInput(
    val agentId: String,
    val provider: AgentProviderCredentials?,
    val model: String?,
    val deepSeekConfig: DeepSeekHarnessConfig = DeepSeekHarnessConfig(),
    val existingCodexModel: String? = null
)

internal data class AgentProviderMapping(
    val environment: Map<String, String> = emptyMap(),
    val deepSeekConfig: DeepSeekHarnessConfig? = null,
    val codexModel: String? = null,
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
        return adapters.firstOrNull { it.supports(input.agentId) }
            ?.map(input)
            ?: AgentProviderMapping()
    }

    private fun AgentConfigAdapter.supports(agentId: String): Boolean {
        return when (this) {
            DeepSeekHarnessConfigAdapter ->
                agentId == AcpAgentProfileStore.DEEPSEEK_HARNESS_AGENT_ID
            CodexConfigAdapter ->
                agentId == AcpAgentProfileStore.DEFAULT_CODEX_AGENT_ID
            ClaudeCodeConfigAdapter -> agentId == CLAUDE_CODE_AGENT_ID
            OpenCodeConfigAdapter -> agentId == OPENCODE_AGENT_ID
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
            codexModel = input.model
                ?.takeIf { it.isNotBlank() }
                ?: input.existingCodexModel
                    ?.takeIf { it.isNotBlank() }
                ?: "gpt-5-codex",
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
        model = sharedModel?.takeIf { it.isNotBlank() } ?: config.model
    )
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
