package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import java.net.URI

internal const val KIMI_CODE_NPM_PACKAGE_SPEC = "@moonshot-ai/kimi-code@latest"
internal const val KIMI_CODE_NPM_INSTALL_COMMAND =
    "npm install -g --no-audit --no-fund --prefix /root/.npm-global " +
        "--registry=https://registry.npmmirror.com $KIMI_CODE_NPM_PACKAGE_SPEC || " +
        "npm install -g --no-audit --no-fund --prefix /root/.npm-global " +
        KIMI_CODE_NPM_PACKAGE_SPEC
internal const val KIMI_CODE_NATIVE_HEALTH_COMMAND =
    "PATH=/root/.npm-global/bin:\$PATH; export PATH; " +
        "command -v kimi >/dev/null 2>&1 && " +
        "node -e 'const [major, minor] = process.versions.node.split(\".\").map(Number); " +
        "if (major < 22 || (major === 22 && minor < 19)) process.exit(1)'"
internal const val KIMI_CODE_HOME = "/root/.kimi-code/omnibot"

/**
 * Build Kimi Code's documented in-memory Provider override. The same process
 * configuration is consumed by both `kimi acp` and `kimi web`, so the two
 * official surfaces cannot drift onto different Dispatch models.
 */
internal fun buildKimiCodeEnvironment(
    provider: AgentProviderCredentials,
    model: String,
    reasoningEffort: String? = null,
): Map<String, String> {
    require(!OpenAiWireApi.isResponses(provider.wireApi)) {
        "Kimi Code's KIMI_MODEL_* channel does not support the OpenAI Responses wire API."
    }
    val providerType = resolveKimiProviderType(provider)
    val effort = reasoningEffort?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    require(effort == null || effort in KIMI_REASONING_EFFORTS) {
        "Kimi Code reasoning effort must be low, medium, high, xhigh, or max."
    }
    return linkedMapOf<String, String>().apply {
        putAll(kimiCodeBaseEnvironment())
        put("KIMI_MODEL_NAME", model)
        put("KIMI_MODEL_API_KEY", provider.apiKey)
        put(
            "KIMI_MODEL_BASE_URL",
            if (providerType == "anthropic") {
                provider.baseUrl
            } else {
                normalizeKimiCodeBaseUrl(provider.baseUrl)
            },
        )
        put("KIMI_MODEL_PROVIDER_TYPE", providerType)
        put(
            "KIMI_MODEL_CAPABILITIES",
            buildList {
                if (kimiCodeVisionInputSupport(provider, model) != false) add("image_in")
                add("thinking")
            }.joinToString(","),
        )
        effort?.let { put("KIMI_MODEL_THINKING_EFFORT", it) }
        if (provider.customHeaders.isNotEmpty()) {
            put(
                "KIMI_CODE_CUSTOM_HEADERS",
                provider.customHeaders.entries.joinToString("\n") { (key, value) ->
                    "$key: $value"
                },
            )
        }
    }
}

internal fun kimiCodeBaseEnvironment(): Map<String, String> = linkedMapOf(
    "KIMI_CODE_HOME" to KIMI_CODE_HOME,
    "KIMI_CODE_NO_AUTO_UPDATE" to "1",
    "KIMI_DISABLE_TELEMETRY" to "1",
)

private fun resolveKimiProviderType(provider: AgentProviderCredentials): String {
    if (provider.protocolType == "anthropic") return "anthropic"
    val host = runCatching { URI(provider.baseUrl).host?.lowercase() }.getOrNull().orEmpty()
    return if (host in KIMI_API_HOSTS) "kimi" else "openai"
}

/** Kimi expects an API root, while Provider entries may store a full endpoint. */
internal fun normalizeKimiCodeBaseUrl(baseUrl: String): String {
    var normalized = baseUrl.trim().trimEnd('/')
    listOf(
        "/v1/chat/completions",
        "/chat/completions",
        "/v1/responses",
        "/responses",
    ).firstOrNull { normalized.endsWith(it, ignoreCase = true) }?.let { suffix ->
        normalized = normalized.dropLast(suffix.length).trimEnd('/')
    }
    if (normalized.isEmpty()) return normalized
    if (
        normalized.endsWith("/v1", ignoreCase = true) ||
        normalized.endsWith("/compatible-mode/v1", ignoreCase = true)
    ) {
        return normalized
    }
    return "$normalized/v1"
}

private fun kimiCodeVisionInputSupport(
    provider: AgentProviderCredentials,
    model: String,
): Boolean? = DeepSeekProvider.requestCapabilities(
    protocolType = provider.protocolType,
    apiBase = provider.baseUrl,
    model = model,
).supportsVisionInput

private val KIMI_REASONING_EFFORTS = setOf("low", "medium", "high", "xhigh", "max")
private val KIMI_API_HOSTS = setOf("api.kimi.com", "api.moonshot.ai", "api.moonshot.cn")
