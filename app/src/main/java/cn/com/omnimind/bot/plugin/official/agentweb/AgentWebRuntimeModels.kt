package cn.com.omnimind.bot.plugin.official.agentweb

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.bot.agent.runtime.AgentProviderCredentials
import cn.com.omnimind.bot.agent.runtime.AcpHarnessAdapters
import cn.com.omnimind.bot.agent.runtime.AgentConfigAdapterRegistry
import cn.com.omnimind.bot.agent.runtime.AgentProviderMappingInput
import cn.com.omnimind.bot.agent.runtime.buildKimiCodeEnvironment
import cn.com.omnimind.bot.agent.runtime.buildKimiCodeManagedFiles
import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val OPENCODE_WEB_CONFIG_PATH = "/root/.config/opencode/omnibot-web.json"

internal enum class AgentWebUrlKind {
    KIMI,
    OPENCODE,
}

internal enum class AgentWebService(
    val id: String,
    val displayName: String,
    val packageId: String,
    val commandName: String,
    val command: String,
    val sessionId: String,
    val urlKind: AgentWebUrlKind,
    val readinessTimeoutMs: Long,
    val minimumMajorVersion: Int? = null,
) {
    KIMI(
        id = "kimi",
        displayName = "Kimi Code Web",
        packageId = "kimi",
        commandName = "kimi",
        command = "kimi web --no-open --host 127.0.0.1",
        // Retain the historical managed id so an app update can take
        // ownership of, validate, and restart an older Web process.
        sessionId = "omnibot-web-kimi",
        urlKind = AgentWebUrlKind.KIMI,
        readinessTimeoutMs = 60_000L,
    ),
    OPENCODE(
        id = "opencode",
        displayName = "OpenCode Web",
        packageId = "opencode",
        commandName = "opencode",
        // A dedicated foreground server stays under the existing ReTerminal
        // owner. Do not mutate or start OpenCode's shared background service.
        // Unset inherited passwords so the official startup output supplies
        // this process's fresh credentials for /api/pair.
        command = "env -u OPENCODE_PASSWORD -u OPENCODE_SERVER_PASSWORD " +
            "opencode serve --hostname 127.0.0.1 --port 0",
        sessionId = "omnibot-web-opencode",
        urlKind = AgentWebUrlKind.OPENCODE,
        readinessTimeoutMs = 90_000L,
        minimumMajorVersion = 2,
    );
}

internal enum class AgentWebResultCode {
    OPENED,
    RUNNING,
    STARTING,
    STOPPED,
    NOT_RUNNING,
    RUNTIME_MISSING,
    PROVIDER_REQUIRED,
    MODEL_REQUIRED,
    UNSUPPORTED_PROVIDER,
    START_FAILED,
    STOP_FAILED,
    URL_TIMEOUT,
    BROWSER_UNAVAILABLE,
    PAIRING_FAILED,
}

internal data class AgentWebOperationResult(
    val success: Boolean,
    val code: AgentWebResultCode,
    val service: AgentWebService,
    val running: Boolean,
    val reused: Boolean = false,
    val error: String? = null,
) {
    /** The browser URL and its credentials must never cross this boundary. */
    fun toJson(): JsonObject = buildJsonObject {
        put("success", success)
        put("code", code.name)
        put("serviceId", service.id)
        put("packageId", service.packageId)
        put("running", running)
        put("reused", reused)
        error?.takeIf(String::isNotBlank)?.let { put("error", it) }
    }
}

internal data class AgentWebRuntimeSnapshot(
    val running: Boolean,
    val transcript: String,
)

internal data class AgentWebRuntimeLaunch(
    val started: Boolean,
    val alreadyRunning: Boolean,
)

internal interface AgentWebRuntimeGateway {
    suspend fun isRuntimeAvailable(service: AgentWebService): Boolean

    suspend fun snapshot(sessionId: String): AgentWebRuntimeSnapshot?

    suspend fun launch(
        sessionId: String,
        command: String,
        environment: Map<String, String>,
    ): AgentWebRuntimeLaunch

    suspend fun stop(sessionId: String): Boolean

    suspend fun openBrowser(url: String): Boolean

    /** May mint a single-use login link; never cache it as process readiness. */
    suspend fun prepareBrowserUrl(service: AgentWebService, snapshot: AgentWebRuntimeSnapshot): String?
}

internal interface AgentWebConfigurationProvider {
    fun providerCredentials(): AgentProviderCredentials?

    fun modelId(): String?

    fun providerModels(): List<ProviderModelOption> = emptyList()
}

internal data class AgentWebLaunchConfiguration(
    val environment: Map<String, String>,
    val managedFiles: Map<String, String> = emptyMap(),
    val fingerprintSource: String,
)

internal fun buildAgentWebLaunchConfiguration(
    service: AgentWebService,
    provider: AgentProviderCredentials,
    model: String,
    reasoningEffort: String? = null,
    providerModels: List<ProviderModelOption> = emptyList(),
): AgentWebLaunchConfiguration {
    val credentials = provider.normalizedForAgentWeb()
    val modelId = model.trim().also {
        require(it.isNotEmpty()) { "Dispatch Model is required." }
        require(it.none(Char::isISOControl)) { "Dispatch Model contains control characters." }
    }
    val environment: Map<String, String>
    val managedFiles: Map<String, String>
    when (service) {
        AgentWebService.KIMI -> {
            environment = buildKimiCodeEnvironment(
                provider = credentials,
                model = modelId,
                reasoningEffort = reasoningEffort,
            )
            managedFiles = buildKimiCodeManagedFiles(credentials, modelId, reasoningEffort)
        }
        AgentWebService.OPENCODE -> {
            require(reasoningEffort.isNullOrBlank()) {
                "Choose OpenCode reasoning options in its Web interface."
            }
            // Use the same configuration owner as ACP, with a dedicated Web
            // overlay instead of overwriting the user's/ACP global config.
            val input = AgentProviderMappingInput(
                agentId = "opencode-acp",
                provider = credentials,
                model = modelId,
                harnessAdapter = AcpHarnessAdapters.openCode,
            )
            val mapping = AgentConfigAdapterRegistry.map(input)
            val write = AgentConfigAdapterRegistry.launchConfigWrites(
                input, mapping, providerModels, "",
            ).single()
            environment = mapping.environment + mapOf("OPENCODE_CONFIG" to OPENCODE_WEB_CONFIG_PATH)
            managedFiles = mapOf(OPENCODE_WEB_CONFIG_PATH to write.content)
        }
    }
    val fingerprintSource = buildString {
        environment.entries
            .sortedBy { it.key }
            .forEach { (key, value) -> appendLine("env:$key=$value") }
        managedFiles.entries
            .sortedBy { it.key }
            .forEach { (path, content) -> appendLine("file:$path=$content") }
    }
    return AgentWebLaunchConfiguration(
        environment = environment,
        managedFiles = managedFiles,
        fingerprintSource = fingerprintSource,
    )
}

private fun AgentProviderCredentials.normalizedForAgentWeb(): AgentProviderCredentials {
    val baseUrl = baseUrl.trim()
    val apiKey = apiKey.trim()
    require(baseUrl.isNotEmpty()) { "Dispatch Provider URL is required." }
    require(apiKey.isNotEmpty()) { "Dispatch Provider API key is required." }
    require(apiKey.none { it == '\r' || it == '\n' }) {
        "Dispatch Provider API key contains line breaks."
    }
    val uri = runCatching { URI(baseUrl) }.getOrNull()
    require(
        uri != null &&
            uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    ) {
        "Dispatch Provider must use an HTTP(S) base URL without user info, query, or fragment."
    }
    return copy(
        baseUrl = baseUrl,
        apiKey = apiKey,
        wireApi = OpenAiWireApi.normalize(wireApi),
        protocolType = protocolType.trim().lowercase(),
        customHeaders = ProviderCustomHeaderUtils.sanitizeCustomHeaders(customHeaders)
            .mapValues { (_, value) -> value.trim() }
            .filter { (key, value) -> key.isNotEmpty() && value.isNotEmpty() },
    )
}
