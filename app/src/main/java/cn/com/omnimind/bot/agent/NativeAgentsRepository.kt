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
                    "id" to draft.id,
                    "name" to draft.name,
                    "description" to draft.description,
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
            environment = (raw["environment"] as? Map<*, *>)?.entries
                ?.mapNotNull { (key, value) ->
                    (key as? String)?.trim()?.takeIf(String::isNotEmpty)
                        ?.let { it to value?.toString().orEmpty() }
                }
                ?.toMap()
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

    /** Cached catalog read for one profile, including its launch environment for the editor. */
    suspend fun readProfile(agentId: String): NativeAgentProfile =
        listAgents(refresh = false).agents.firstOrNull { it.id == agentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $agentId")

    suspend fun readAgentConfig(agentId: String): NativeAgentConfig = parseConfig(
        runtime().handleMethod("agent/config/read", mapOf("agentId" to agentId.trim()))
    )

    /**
     * `agent/config/write` with the runtime's expectedRevision optimistic lock.
     * Null fields are omitted so the adapter keeps the stored value.
     */
    suspend fun writeAgentConfig(
        agentId: String,
        content: String? = null,
        reasoningEffort: String? = null,
        permissionMode: String? = null,
        expectedRevision: Long? = null,
    ): NativeAgentConfig {
        val args = buildMap<String, Any?> {
            put("agentId", agentId.trim())
            if (content != null) put("content", content)
            if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
            if (permissionMode != null) put("permissionMode", permissionMode)
            if (expectedRevision != null && expectedRevision > 0) {
                put("expectedRevision", expectedRevision)
            }
        }
        return parseConfig(runtime().handleMethod("agent/config/write", args))
    }

    /**
     * Selects the Harness new conversations use (`agent/select`). Selection
     * only changes the stored profile; running sessions keep their own
     * Harness (`LocalAcpRuntime.selectAgent`).
     */
    suspend fun selectAgent(agentId: String): NativeAgentCatalog =
        parseCatalog(runtime().handleMethod("agent/select", mapOf("agentId" to agentId.trim())))

    /** Existing runtime teardown owner; the next ACP start reconnects from the saved binding. */
    suspend fun disconnectRuntime() {
        runtime().disconnect()
    }

    private fun parseConfig(payload: Any?): NativeAgentConfig {
        val root = payload as? Map<*, *> ?: emptyMap<String, Any?>()
        return NativeAgentConfig(
            kind = (root["kind"] as? String).orEmpty(),
            revision = (root["revision"] as? Number)?.toLong() ?: 0L,
            configPath = (root["configPath"] as? String) ?: (root["path"] as? String).orEmpty(),
            authPath = (root["authPath"] as? String).orEmpty(),
            content = (root["content"] as? String).orEmpty(),
            reasoningEffort = (root["reasoningEffort"] as? String)?.trim()?.takeIf(String::isNotEmpty),
            permissionMode = (root["permissionMode"] as? String)?.trim()?.takeIf(String::isNotEmpty),
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
    val environment: Map<String, String>,
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
    val id: String = "",
    val description: String = "",
) {
    override fun toString(): String = "NativeCustomAgentDraft(name=$name, enabled=$enabled)"
}

/**
 * The adapter-owned config surface. `content` may embed provider credentials
 * written by the user, so it stays out of the string form. apiKey/baseUrl/model
 * from the read payload are dropped here because no native editor shows them.
 */
internal data class NativeAgentConfig(
    val kind: String,
    val revision: Long,
    val configPath: String,
    val authPath: String,
    val content: String,
    val reasoningEffort: String?,
    val permissionMode: String?,
) {
    override fun toString(): String = "NativeAgentConfig(kind=$kind, revision=$revision)"
}

/** The runtime's optimistic-lock rejection from `agent/config/write`. */
internal fun isAgentConfigRevisionConflict(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any {
        it.message?.contains("Agent config changed concurrently") == true
    }

/** Reuses the runtime's failure classification; raw payload text is never rendered. */
internal fun classifyAgentErrorText(raw: String): String? =
    AgentRuntimeErrorSupport.failureKind(Exception(raw))

internal fun classifyAgentError(error: Throwable): String? =
    AgentRuntimeErrorSupport.failureKind(error)
