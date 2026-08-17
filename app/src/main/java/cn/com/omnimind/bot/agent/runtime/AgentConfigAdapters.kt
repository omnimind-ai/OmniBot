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
    val codexBaseUrl: String? = null
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
        return AgentProviderMapping(
            environment = mapOf(
                "ANTHROPIC_BASE_URL" to provider.baseUrl,
                "ANTHROPIC_API_KEY" to provider.apiKey,
                "ANTHROPIC_AUTH_TOKEN" to provider.apiKey
            )
        )
    }
}

private object OpenCodeConfigAdapter : AgentConfigAdapter {
    override fun map(input: AgentProviderMappingInput): AgentProviderMapping {
        val provider = input.provider ?: return AgentProviderMapping()
        return AgentProviderMapping(
            environment = mapOf(
                "OPENAI_BASE_URL" to provider.baseUrl,
                "OPENAI_API_KEY" to provider.apiKey
            )
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
