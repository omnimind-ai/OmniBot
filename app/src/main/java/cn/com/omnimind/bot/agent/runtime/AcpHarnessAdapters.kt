package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Transport differences between official ACP Harness implementations.
 *
 * The Agent runtime must depend on this capability surface rather than on a
 * concrete Harness id. A Harness adapter owns its protocol quirks here; the
 * session, turn, and Provider paths remain shared.
 */
internal interface AcpHarnessAdapter {
    /** Declarative key for the optional Harness config converter. */
    val configAdapterId: String?
        get() = null
    val launchConfigPath: String?
        get() = null
    val launchConfigExecutorKey: String?
        get() = null
    /** Returns the adapter-owned config surface, or null when it has none. */
    fun readConfigPayload(
        profileId: String,
        rawConfig: String,
        provider: AgentProviderCredentials?,
        model: String?,
    ): Map<String, Any?>? = null

    /** Serializes an adapter-owned config surface, or null when it has none. */
    fun writeConfigPayload(
        args: Map<String, Any?>,
        rawConfig: String,
        provider: AgentProviderCredentials?,
        model: String?,
    ): String? = null

    /** Builds launch-only environment values for a Harness-owned transport. */
    fun launchEnvironment(
        provider: AgentProviderCredentials?,
        model: String?,
        rawConfig: String,
    ): Map<String, String>? = null

    fun normalizeStdioLine(line: String): String = line

    /**
     * Whether this Harness may receive the host MCP declaration for the
     * selected Provider. This is a capability negotiation boundary: ACP and
     * MCP stay enabled, but a Provider that cannot deserialize Codex's
     * namespace tool container must not be sent that declaration.
     */
    fun supportsSessionMcp(provider: AgentProviderCredentials?): Boolean = true
}

internal object AcpHarnessAdapters {
    val standard: AcpHarnessAdapter = object : AcpHarnessAdapter {}

    // Keep these capability identities separate even when their transport is
    // currently identical. Provider/config mapping belongs to the selected
    // Harness adapter, not to a vendor branch in the shared runtime.
    val codex: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "codex"

        override fun supportsSessionMcp(provider: AgentProviderCredentials?): Boolean {
            // Codex currently groups MCP tools into the Responses-only
            // `type=namespace` container. Custom Responses-compatible
            // gateways generally accept only function/web_search/mcp tool
            // types, so sending our MCP declaration makes every request fail
            // before the model sees the prompt. First-party OpenAI providers
            // keep the full MCP surface.
            return provider?.supportsNamespaceTools == true ||
                !OpenAiWireApi.isResponses(provider?.wireApi)
        }
    }

    val claudeCode: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "claude-code"
    }

    val kimiCode: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "kimi-code"
    }

    val openCode: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "open-code"
    }

    val deepSeekHarness: AcpHarnessAdapter = object : AcpHarnessAdapter {
        // DSH ACP 0.4.x documents per-session `mcpServers` (stdio and
        // streamable HTTP). The shared runtime keeps that declaration on the
        // official session/new request, where DSH owns discovery and tool
        // namespacing.
        override val configAdapterId = "deepseek-harness"
        override val launchConfigPath = DEEPSEEK_HARNESS_CONFIG_PATH
        override val launchConfigExecutorKey = "harness-launch-config-read"

        override fun readConfigPayload(
            profileId: String,
            rawConfig: String,
            provider: AgentProviderCredentials?,
            model: String?,
        ): Map<String, Any?> {
            val config = parseDeepSeekHarnessConfig(rawConfig)
            return linkedMapOf(
                "agentId" to profileId,
                "kind" to "deepseek-harness",
                "configPath" to DEEPSEEK_HARNESS_CONFIG_DISPLAY_PATH,
                "baseUrl" to (provider?.baseUrl ?: config.baseUrl),
                "model" to model.orEmpty(),
                "apiKey" to config.apiKey,
                "reasoningEffort" to config.reasoningEffort,
                "permissionMode" to config.permissionMode,
            )
        }

        override fun writeConfigPayload(
            args: Map<String, Any?>,
            rawConfig: String,
            provider: AgentProviderCredentials?,
            model: String?,
        ): String {
            val config = deepSeekHarnessConfigFromArgs(
                args = args,
                current = parseDeepSeekHarnessConfig(rawConfig),
                sharedProvider = provider,
                sharedModel = model,
            )
            return buildDeepSeekHarnessConfigJson(config)
        }

        override fun launchEnvironment(
            provider: AgentProviderCredentials?,
            model: String?,
            rawConfig: String,
        ): Map<String, String> {
            val config = syncAgentProviderCredentials(
                config = parseDeepSeekHarnessConfig(rawConfig),
                sharedProvider = provider,
                sharedModel = model,
            )
            require(config.model.isNotBlank()) {
                "Harness 没有可用模型，拒绝使用默认模型启动。"
            }
            require(config.apiKey.isNotBlank()) {
                "Configure an API key in Model Provider settings before starting an ACP Agent."
            }
            return config.toEnvironment()
        }

        override fun normalizeStdioLine(line: String): String =
            normalizeAcpModeNames(line)
    }

    /** Resolve a declarative catalog converter without coupling the runtime
     * lifecycle to a concrete Agent id. Unknown entries use the standard ACP
     * transport and can still participate in session/new and session/prompt. */
    fun forConfigAdapterId(id: String?): AcpHarnessAdapter = when (id) {
        "codex" -> codex
        "claude-code" -> claudeCode
        "kimi-code" -> kimiCode
        "open-code" -> openCode
        "deepseek-harness" -> deepSeekHarness
        else -> standard
    }

    fun forProfile(profile: AcpAgentProfile): AcpHarnessAdapter =
        AcpAgentProfileStore.officialRuntime(profile)?.harnessAdapter ?: standard
}

/**
 * Compatibility entrypoint retained for protocol tests and old callers. New
 * runtime code uses [AcpHarnessAdapters.forProfile] instead of an enabled
 * boolean or a concrete Harness id check.
 */
internal fun normalizeDeepSeekHarnessAcpModeNames(
    line: String,
    enabled: Boolean,
): String = if (enabled) AcpHarnessAdapters.deepSeekHarness.normalizeStdioLine(line) else line

private fun normalizeAcpModeNames(line: String): String {
    val root = runCatching { Json.parseToJsonElement(line) }.getOrNull() ?: return line
    return normalizeAcpModeJson(root).toString()
}

private fun normalizeAcpModeJson(
    element: JsonElement,
    parentKey: String? = null,
): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            put(key, normalizeAcpModeJson(value, key))
        }
        if (element["name"] == null &&
            (parentKey == "availableModes" || parentKey == "options")
        ) {
            // Some official ACP Harness registries omit the display name while
            // still returning a stable protocol id/value.
            val displayName = sequenceOf("label", "title", "value", "id")
                .mapNotNull { key ->
                    (element[key] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                }
                .firstOrNull()
            displayName?.let { put("name", JsonPrimitive(it)) }
        }
    }
    is JsonArray -> buildJsonArray {
        element.forEach { add(normalizeAcpModeJson(it, parentKey)) }
    }
    else -> element
}

internal const val DEEPSEEK_HARNESS_CONFIG_HOME = "/root/.dsh/omnibot-acp"
internal const val DEEPSEEK_HARNESS_CONFIG_PATH =
    "$DEEPSEEK_HARNESS_CONFIG_HOME/config.json"
internal const val DEEPSEEK_HARNESS_SETTINGS_PATH =
    "$DEEPSEEK_HARNESS_CONFIG_HOME/settings.yaml"
internal const val DEEPSEEK_HARNESS_CONFIG_DISPLAY_PATH =
    "~/.dsh/omnibot-acp/config.json"
internal const val ACP_FILESYSTEM_COMPAT_PATH =
    "/root/.omnibot/acp-fs-compat.cjs"
internal val ACP_FILESYSTEM_COMPAT_SCRIPT = """
    // Android app sandboxes reject hard-link creation. Official ACP runtimes
    // use fs.promises.link as an atomic publish primitive, so copy the fully
    // written temporary file only for that specific denied operation.
    const fs = require('node:fs');
    const promises = fs.promises;
    const originalLink = promises.link.bind(promises);
    promises.link = async (existingPath, newPath, ...args) => {
      try {
        return await originalLink(existingPath, newPath, ...args);
      } catch (error) {
        if (error && (error.code === 'EACCES' || error.code === 'EPERM')) {
          await promises.copyFile(
            existingPath,
            newPath,
            fs.constants.COPYFILE_EXCL
          );
          return;
        }
        throw error;
      }
    };
""".trimIndent() + "\n"
private val DEEPSEEK_HARNESS_REASONING_EFFORTS = setOf("off", "high", "max")
private val DEEPSEEK_HARNESS_PERMISSION_MODES = setOf(
    "read-only",
    "workspace-write",
    "danger-full-access"
)
private const val DEEPSEEK_HARNESS_PERSISTENCE_HOME = "/root/.dsh/omnibot-acp-clean"

internal data class DeepSeekHarnessConfig(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val reasoningEffort: String = "",
    val permissionMode: String = ""
) {
    fun toEnvironment(): Map<String, String> = buildMap {
        if (baseUrl.isNotBlank()) put("DEEPSEEK_BASE_URL", baseUrl)
        if (apiKey.isNotBlank()) put("DEEPSEEK_API_KEY", apiKey)
        if (model.isNotBlank()) put("DSH_MODEL", model)
        if (reasoningEffort.isNotBlank()) {
            put("DSH_REASONING_EFFORT", reasoningEffort)
            put("DSH_PI_AI_REASONING_EFFORT", reasoningEffort)
            put("DSH_THINKING", if (reasoningEffort == "off") "disabled" else "enabled")
        }
        if (permissionMode.isNotBlank()) put("DSH_PERMISSION_MODE", permissionMode)
        put("DSH_ACP_HOME", DEEPSEEK_HARNESS_PERSISTENCE_HOME)
        put("DSH_SESSION_ROOT", "$DEEPSEEK_HARNESS_PERSISTENCE_HOME/sessions")
        put("DSH_HOME", DEEPSEEK_HARNESS_CONFIG_HOME)
        put("DSH_PROVIDER", "deepseek-official")
        put("NODE_NO_WARNINGS", "1")
    }
}

internal fun parseDeepSeekHarnessConfig(source: String): DeepSeekHarnessConfig {
    val json = runCatching {
        JsonParser.parseString(source)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
    }.getOrNull() ?: return DeepSeekHarnessConfig()
    fun stringValue(key: String): String? = json.get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asString
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val reasoningEffort = stringValue("reasoningEffort").orEmpty()
    require(reasoningEffort.isEmpty() || reasoningEffort in DEEPSEEK_HARNESS_REASONING_EFFORTS) {
        "DeepSeek Harness reasoning effort is invalid."
    }
    val permissionMode = stringValue("permissionMode").orEmpty()
    require(permissionMode.isEmpty() || permissionMode in DEEPSEEK_HARNESS_PERMISSION_MODES) {
        "DeepSeek Harness permission mode is invalid."
    }
    return DeepSeekHarnessConfig(
        baseUrl = stringValue("baseUrl").orEmpty(),
        model = stringValue("model").orEmpty(),
        apiKey = stringValue("apiKey").orEmpty(),
        reasoningEffort = reasoningEffort,
        permissionMode = permissionMode
    )
}

internal fun deepSeekHarnessConfigFromArgs(
    args: Map<String, Any?>,
    current: DeepSeekHarnessConfig = DeepSeekHarnessConfig(),
    sharedProvider: AgentProviderCredentials? = null,
    sharedModel: String? = null
): DeepSeekHarnessConfig {
    val baseUrl = sharedProvider?.baseUrl.orEmpty()
    val model = sharedModel.orEmpty()
    val apiKey = sharedProvider?.apiKey.orEmpty()
    val reasoningEffort = args.agentConfigStringValue("reasoningEffort")
        ?: current.reasoningEffort
    require(baseUrl.isNotBlank()) { "DeepSeek Base URL is required." }
    require(model.isNotBlank()) {
        "DeepSeek model ID is required. Select a model from the active Provider first."
    }
    require(apiKey.isNotBlank()) { "DeepSeek API key is required." }
    require(reasoningEffort.isEmpty() || reasoningEffort in DEEPSEEK_HARNESS_REASONING_EFFORTS) {
        "DeepSeek Harness reasoning effort is invalid."
    }
    val permissionMode = args.agentConfigStringValue("permissionMode")
        ?: current.permissionMode
    require(permissionMode.isEmpty() || permissionMode in DEEPSEEK_HARNESS_PERMISSION_MODES) {
        "DeepSeek Harness permission mode is invalid."
    }
    return DeepSeekHarnessConfig(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        reasoningEffort = reasoningEffort,
        permissionMode = permissionMode
    )
}

internal fun buildDeepSeekHarnessConfigJson(config: DeepSeekHarnessConfig): String =
    GsonBuilder()
        .setPrettyPrinting()
        .create()
        .toJson(
            linkedMapOf(
                "baseUrl" to config.baseUrl,
                "model" to config.model,
                "apiKey" to config.apiKey,
                "reasoningEffort" to config.reasoningEffort,
                "permissionMode" to config.permissionMode
            )
        ) + "\n"

private fun Map<String, Any?>.agentConfigStringValue(key: String): String? =
    this[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
