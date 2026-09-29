package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager

/**
 * Native settings adapter over the existing `AgentRuntimeManager.handleMethod`
 * boundary. Profile storage, health probes, managed installs and the ACP
 * lifecycle stay in the runtime; this class only adapts payloads. Commands,
 * arguments and environment values are launch configuration and must never be
 * logged from here.
 */
internal class NativeAgentsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private fun runtime() = AgentRuntimeManager.getInstance(appContext)

    /** Cached-health listing when [refresh] is false; the full probe when true. */
    suspend fun listAgents(refresh: Boolean): NativeAgentCatalog = parseCatalog(
        runtime().handleMethod(if (refresh) "agent/refresh" else "agent/list", emptyMap())
    )

    suspend fun saveAgent(draft: NativeCustomAgentDraft): NativeAgentCatalog {
        val response = runtime().handleMethod(
            "agent/save",
            mapOf(
                "agent" to mapOf(
                    "id" to "",
                    "name" to draft.name,
                    "command" to draft.command,
                    "arguments" to draft.arguments,
                    "environment" to draft.environment,
                    "enabled" to draft.enabled,
                ),
            ),
        )
        val catalog = (response as? Map<*, *>)?.get("catalog")
        return parseCatalog(catalog ?: response)
    }

    suspend fun deleteAgent(agentId: String): NativeAgentCatalog = parseCatalog(
        runtime().handleMethod("agent/delete", mapOf("agentId" to agentId.trim()))
    )

    suspend fun testAgent(agentId: String): NativeAgentActionResult = parseActionResult(
        runtime().handleMethod("agent/test", mapOf("agentId" to agentId.trim()))
    )

    /**
     * Explicit install/reinstall. The runtime's managed-preparation gate is the
     * single-flight owner; callers trigger once and refresh from `agent/list`.
     */
    suspend fun prepareAgent(agentId: String): NativeAgentActionResult = parseActionResult(
        runtime().handleMethod("agent/prepare", mapOf("agentId" to agentId.trim(), "force" to true))
    )

    fun cachedRemoteBridgeEnabled(): Boolean =
        preferences.getBoolean(KEY_REMOTE_BRIDGE_ENABLED, false)

    /** Reads only the enabled flag; bridge URLs and tokens stay inside the runtime store. */
    suspend fun readRemoteBridgeEnabled(): Boolean {
        val payload = runtime().handleMethod("config/remote/read", emptyMap()) as? Map<*, *>
            ?: return cachedRemoteBridgeEnabled()
        val enabled = payload["remoteEnabled"] == true
        preferences.edit().putBoolean(KEY_REMOTE_BRIDGE_ENABLED, enabled).apply()
        return enabled
    }

    private fun parseCatalog(payload: Any?): NativeAgentCatalog {
        val root = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        val agents = (root["agents"] as? List<*>).orEmpty().mapNotNull { raw ->
            (raw as? Map<*, *>)?.let(::parseProfile)
        }
        return NativeAgentCatalog(
            selectedAgentId = (root["selectedAgentId"] as? String).orEmpty(),
            agents = agents,
        )
    }

    private fun parseProfile(raw: Map<*, *>): NativeAgentProfile? {
        val id = (raw["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return null
        val plugin = (raw["capabilities"] as? Map<*, *>)?.get("plugin") as? Map<*, *>
        val pluginSupported = plugin?.get("supported") == true
        return NativeAgentProfile(
            id = id,
            name = (raw["name"] as? String).orEmpty(),
            description = (raw["description"] as? String).orEmpty(),
            command = (raw["command"] as? String).orEmpty(),
            arguments = (raw["arguments"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim() }
                ?.filter(String::isNotEmpty)
                .orEmpty(),
            enabled = raw["enabled"] != false,
            builtIn = raw["builtIn"] == true,
            installed = raw["installed"] as? Boolean,
            status = (raw["status"] as? String).orEmpty().ifEmpty { STATUS_UNCHECKED },
            lastCheckError = (raw["lastCheckError"] as? String)?.takeIf(String::isNotBlank),
            managedAdapter = raw["managedAdapter"] == true,
            pluginAuthoring = pluginSupported && plugin?.get("authoring") == true,
            pluginInstallViaHarness = pluginSupported && plugin?.get("installViaHarness") == true,
        )
    }

    private fun parseActionResult(payload: Any?): NativeAgentActionResult {
        val root = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        val agent = root["agent"] as? Map<*, *>
        val errorText = (root["error"] as? String)?.takeIf(String::isNotBlank)
        return NativeAgentActionResult(
            ok = root["ok"] == true,
            installed = agent?.get("installed") as? Boolean,
            errorKind = errorText?.let(::classifyAgentErrorText),
        )
    }

    private companion object {
        const val STATUS_UNCHECKED = "unchecked"

        // The Flutter page writes `remote_bridge_enabled`; SharedPreferences stores it prefixed.
        const val KEY_REMOTE_BRIDGE_ENABLED = "flutter.remote_bridge_enabled"
    }
}

internal data class NativeAgentProfile(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val arguments: List<String>,
    val enabled: Boolean,
    val builtIn: Boolean,
    val installed: Boolean?,
    val status: String,
    val lastCheckError: String?,
    val managedAdapter: Boolean,
    val pluginAuthoring: Boolean,
    val pluginInstallViaHarness: Boolean,
) {
    override fun toString(): String = "NativeAgentProfile(id=$id, name=$name, status=$status)"
}

internal data class NativeAgentCatalog(
    val selectedAgentId: String,
    val agents: List<NativeAgentProfile>,
)

internal data class NativeAgentActionResult(
    val ok: Boolean,
    val installed: Boolean?,
    val errorKind: String?,
)

internal data class NativeCustomAgentDraft(
    val name: String,
    val command: String,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val enabled: Boolean,
) {
    override fun toString(): String = "NativeCustomAgentDraft(name=$name, enabled=$enabled)"
}

/** Reuses the runtime's failure classification; raw payload text is never rendered. */
internal fun classifyAgentErrorText(raw: String): String? =
    AgentRuntimeErrorSupport.failureKind(Exception(raw))

internal fun classifyAgentError(error: Throwable): String? =
    AgentRuntimeErrorSupport.failureKind(error)
