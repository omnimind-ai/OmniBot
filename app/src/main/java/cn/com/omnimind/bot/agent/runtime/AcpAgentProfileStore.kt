package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

internal data class AcpAgentConfigAuditEntry(
    val revision: Long,
    val operation: String,
    val paths: List<String>,
    val createdAt: Long,
)

internal data class AcpAgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    @Transient val officialRuntime: AcpOfficialRuntime? = null,
) {
    fun toPayload(
        selected: Boolean = false,
        health: AcpAgentHealth = AcpAgentHealth()
    ): Map<String, Any?> {
        val runtime = officialRuntime
        return linkedMapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "command" to command,
            "arguments" to arguments,
            "environment" to environment,
            "enabled" to enabled,
            "builtIn" to builtIn,
            "source" to if (builtIn) "official" else "custom",
            "selected" to selected,
            "installed" to health.installed,
            "status" to health.status,
            "lastCheckError" to health.error,
            "lastCheckLatencyMs" to health.latencyMs,
            "lastCheckAt" to health.checkedAt,
            // Keep the read-only health result useful before a live ACP
            // handshake. Negotiated values always win; declared values are
            // only the official Harness composition contract and are shown
            // under the same generic capabilities map for all profiles.
            "capabilities" to mergeCapabilities(
                declared = runtime?.declaredCapabilities.orEmpty(),
                negotiated = health.capabilities,
            ),
            "discoveryCommand" to runtime?.discoveryCommand,
            "managedAdapter" to (runtime?.managedAdapterPackage != null)
        )
    }

    private fun mergeCapabilities(
        declared: Map<String, Any?>,
        negotiated: Map<String, Any?>,
    ): Map<String, Any?> {
        if (declared.isEmpty()) return negotiated
        if (negotiated.isEmpty()) return declared
        val merged = LinkedHashMap<String, Any?>(declared)
        negotiated.forEach { (key, value) ->
            val declaredValue = merged[key]
            if (declaredValue is Map<*, *> && value is Map<*, *>) {
                val nested = LinkedHashMap<String, Any?>()
                declaredValue.forEach { (nestedKey, nestedValue) ->
                    nested[nestedKey.toString()] = nestedValue
                }
                value.forEach { (nestedKey, nestedValue) ->
                    nested[nestedKey.toString()] = nestedValue
                }
                merged[key] = nested
            } else {
                merged[key] = value
            }
        }
        return merged
    }
}

internal data class AcpAgentHealth(
    val status: String = STATUS_UNCHECKED,
    val installed: Boolean? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val capabilities: Map<String, Any?> = emptyMap(),
    val preparationRevision: String? = null,
) {
    companion object {
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        const val STATUS_MISSING = "missing"
        const val STATUS_UNCHECKED = "unchecked"
    }
}

internal data class AcpOfficialRuntime(
    val discoveryCommand: String,
    val managedAdapterPackage: String? = null,
    val managedAdapterPackages: List<String> = managedAdapterPackage
        ?.let { listOf(it) }
        .orEmpty(),
    val requiresNativeBuildTools: Boolean = false,
    val managedAdapterHealthCommand: String? = null,
    val harnessAdapter: AcpHarnessAdapter = AcpHarnessAdapters.standard,
    val usesSharedProvider: Boolean = false,
    val terminalPackageId: String? = null,
    val managedInstallScriptPath: String? = null,
    val managedInstallCommand: String? = null,
    val preparationRevision: String? = null,
    val embedded: Boolean = false,
    /**
     * Capabilities known from the official Harness composition, before an
     * ACP initialize handshake has happened. These are intentionally kept
     * separate from the negotiated ACP capabilities returned by initialize.
     * A health probe must remain read-only, but the UI still needs to explain
     * what an installed Harness can do.
     */
    val declaredCapabilities: Map<String, Any?> = emptyMap(),
)

/**
 * ACP Agent registry inspired by AionUi's managed-agent catalog:
 * official definitions always remain visible, while user overrides and
 * custom ACP commands are persisted separately from API credentials.
 */
internal class AcpAgentProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val catalog = AcpAgentCatalog.load(appContext)
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()
    private val configHistoryPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            CONFIG_HISTORY_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    fun list(): List<AcpAgentProfile> {
        migrateLegacyXiaowanAliases()
        val stored = readStoredProfiles()
            .mapNotNull(::normalize)
            .filterNot { it.id in catalog.retiredAgentIds }
        val storedById = stored.associateBy { it.id }
        val official = catalog.agents.map { definition ->
            val override = storedById[definition.id] ?: return@map definition
            val migratedOfficialCommand =
                definition.id == DEEPSEEK_HARNESS_AGENT_ID &&
                    override.command == "dsh-acp"
            definition.copy(
                command = if (migratedOfficialCommand) definition.command else override.command,
                arguments = if (migratedOfficialCommand) definition.arguments else override.arguments,
                environment = override.environment,
                enabled = override.enabled,
                officialRuntime = if (
                    migratedOfficialCommand ||
                    (override.command == definition.command &&
                        override.arguments == definition.arguments)
                ) {
                    definition.officialRuntime
                } else {
                    null
                },
            )
        }
        val custom = stored
            .filterNot { it.id in catalog.officialIds }
            .map { it.copy(builtIn = false) }
        return official + custom
    }

    fun selected(): AcpAgentProfile {
        val profiles = list()
        val selectedId = preferences.getString(KEY_SELECTED_PROFILE_ID, null)
        return profiles.firstOrNull { it.id == selectedId && it.enabled }
            ?: profiles.firstOrNull { it.enabled }
            ?: profiles.first()
    }

    @Synchronized
    fun bindSession(sessionId: String, agentId: String) {
        val normalizedSessionId = sessionId.trim()
        val normalizedAgentId = agentId.trim()
        if (normalizedSessionId.isEmpty() || normalizedAgentId.isEmpty()) return
        val bindings = sessionBindings().toMutableMap()
        bindings[normalizedSessionId] = normalizedAgentId
        preferences.edit().putString(KEY_SESSION_BINDINGS, gson.toJson(bindings)).apply()
    }

    fun agentIdForSession(sessionId: String): String? {
        migrateLegacyXiaowanAliases()
        return sessionBindings()[sessionId.trim()]
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it in catalog.retiredAgentIds }
    }

    /** Embedded Agent settings share the existing session owner and deletion path. */
    fun sessionConfiguration(sessionId: String): Map<String, String> {
        val raw = preferences.getString("session_config:$sessionId", null) ?: return emptyMap()
        return gson.fromJson(raw, object : TypeToken<Map<String, String>>() {}.type)
    }

    fun saveSessionConfiguration(sessionId: String, values: Map<String, String>) {
        check(preferences.edit().putString("session_config:$sessionId", gson.toJson(values)).commit()) {
            "Failed to persist ACP session configuration."
        }
    }

    /**
     * Remove the durable owner of an ACP session after `session/delete`.
     *
     * Session ownership is separate from the Room conversation binding: the
     * conversation is intentionally preserved by the host, while a deleted
     * ACP session must not be resurrected as belonging to the old Harness on
     * the next load/switch.
     */
    @Synchronized
    fun unbindSession(sessionId: String) {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty()) return
        val bindings = sessionBindings().toMutableMap()
        preferences.edit().remove("session_config:$normalizedSessionId").apply()
        if (bindings.remove(normalizedSessionId) != null) {
            preferences.edit()
                .putString(KEY_SESSION_BINDINGS, gson.toJson(bindings))
                .apply()
        }
    }

    @Synchronized
    fun bindConversation(conversationId: Long, agentId: String) {
        if (conversationId <= 0L) return
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isEmpty()) return
        val bindings = conversationBindings().toMutableMap()
        val key = conversationId.toString()
        val currentAgentId = bindings[key]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it in catalog.retiredAgentIds }
        // Existing ownership is immutable. Harness switching creates a new
        // conversation; only retired aliases may be replaced by migration.
        if (currentAgentId != null) return
        bindings[key] = normalizedAgentId
        preferences.edit().putString(KEY_CONVERSATION_BINDINGS, gson.toJson(bindings)).apply()
    }

    @Synchronized
    fun repairConversationBinding(conversationId: Long, agentId: String) {
        if (conversationId <= 0L) return
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isEmpty()) return
        val bindings = conversationBindings().toMutableMap()
        bindings[conversationId.toString()] = normalizedAgentId
        preferences.edit().putString(KEY_CONVERSATION_BINDINGS, gson.toJson(bindings)).apply()
    }

    fun agentIdForConversation(conversationId: Long): String? {
        if (conversationId <= 0L) return null
        migrateLegacyXiaowanAliases()
        return conversationBindings()[conversationId.toString()]
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it in catalog.retiredAgentIds }
    }

    @Synchronized
    fun unbindConversation(conversationId: Long) {
        if (conversationId <= 0L) return
        val bindings = conversationBindings().toMutableMap()
        if (bindings.remove(conversationId.toString()) != null) {
            preferences.edit()
                .putString(KEY_CONVERSATION_BINDINGS, gson.toJson(bindings))
                .apply()
        }
    }

    @Synchronized
    fun select(id: String): AcpAgentProfile {
        val selected = list().firstOrNull { it.id == id.trim() }
            ?: throw IllegalArgumentException("Unknown ACP agent: $id")
        require(selected.enabled) { "ACP agent ${selected.name} is disabled." }
        preferences.edit().putString(KEY_SELECTED_PROFILE_ID, selected.id).apply()
        return selected
    }

    @Synchronized
    fun save(raw: AcpAgentProfile): AcpAgentProfile {
        val current = list()
        val selectedIdBeforeSave = preferences
            .getString(KEY_SELECTED_PROFILE_ID, null)
            ?: selected().id
        val requestedId = raw.id.trim()
        val targetId = requestedId.ifBlank { UUID.randomUUID().toString() }
        val officialDefinition = catalog.definition(targetId)
        val candidate = if (officialDefinition != null) {
            officialDefinition.copy(
                command = raw.command,
                arguments = raw.arguments,
                environment = raw.environment,
                enabled = raw.enabled
            )
        } else {
            raw.copy(id = targetId, builtIn = false)
        }
        val profile = normalize(candidate)
            ?: throw IllegalArgumentException("Agent name and command are required.")
        val stored = current
            .filterNot { it.id == profile.id }
            .toMutableList()
            .apply { add(profile) }
        writeProfiles(stored)
        clearHealth(profile.id)
        if (!profile.enabled && selectedIdBeforeSave == profile.id) {
            val fallback = list().firstOrNull { it.enabled && it.id != profile.id }
            if (fallback != null) {
                preferences.edit().putString(KEY_SELECTED_PROFILE_ID, fallback.id).apply()
            }
        }
        return list().first { it.id == profile.id }
    }

    @Synchronized
    fun delete(id: String) {
        val normalizedId = id.trim()
        require(normalizedId.isNotEmpty()) { "Agent id is required." }
        require(normalizedId !in catalog.officialIds) {
            "Official ACP agents cannot be deleted."
        }
        val remaining = list().filterNot { it.builtIn || it.id == normalizedId }
        val officialOverrides = readStoredProfiles().filter { it.id in catalog.officialIds }
        writeProfiles(officialOverrides + remaining)
        val remainingBindings = sessionBindings().filterValues { it != normalizedId }
        val remainingConversationBindings =
            conversationBindings().filterValues { it != normalizedId }
        preferences.edit()
            .putString(KEY_SESSION_BINDINGS, gson.toJson(remainingBindings))
            .putString(KEY_CONVERSATION_BINDINGS, gson.toJson(remainingConversationBindings))
            .apply()
        clearHealth(normalizedId)
        if (preferences.getString(KEY_SELECTED_PROFILE_ID, null) == normalizedId) {
            // Xiaowan is the single built-in default entry.  Deleting a
            // custom profile must not silently switch the user to Codex.
            preferences.edit().putString(KEY_SELECTED_PROFILE_ID, XIAOWAN_AGENT_ID).apply()
        }
    }

    fun health(agentId: String): AcpAgentHealth {
        return readHealth()[agentId] ?: AcpAgentHealth()
    }

    @Synchronized
    fun saveHealth(agentId: String, health: AcpAgentHealth) {
        val current = readHealth().toMutableMap()
        current[agentId] = health
        preferences.edit().putString(KEY_HEALTH, gson.toJson(current)).apply()
    }

    @Synchronized
    fun clearHealth(agentId: String) {
        val current = readHealth().toMutableMap()
        if (current.remove(agentId) != null) {
            preferences.edit().putString(KEY_HEALTH, gson.toJson(current)).apply()
        }
    }

    /**
     * Agent configuration is versioned at the existing profile store
     * boundary. Snapshots are encrypted; the regular audit payload contains
     * paths and operation names only, never credentials or file contents.
     */
    @Synchronized
    fun recordConfigRevision(
        agentId: String,
        operation: String,
        files: Map<String, String>,
    ): AcpAgentConfigAuditEntry {
        require(agentId.isNotBlank()) { "Agent id is required." }
        require(files.isNotEmpty()) { "At least one config file is required." }
        val history = readConfigAudit(agentId).toMutableList()
        val revision = (history.maxOfOrNull { it.revision } ?: 0L) + 1L
        val entry = AcpAgentConfigAuditEntry(
            revision = revision,
            operation = operation.trim().ifEmpty { "write" },
            paths = files.keys.map(String::trim).filter(String::isNotEmpty),
            createdAt = System.currentTimeMillis(),
        )
        val snapshots = readConfigSnapshots(agentId).toMutableMap()
        snapshots[revision.toString()] = files
        configHistoryPreferences.edit()
            .putString(configAuditKey(agentId), gson.toJson(history + entry))
            .putString(configSnapshotsKey(agentId), gson.toJson(snapshots))
            .commit()
            .also { check(it) { "Failed to persist Agent config revision." } }
        return entry
    }

    fun configRevision(agentId: String): Long =
        readConfigAudit(agentId).maxOfOrNull { it.revision } ?: 0L

    fun configAudit(agentId: String): List<AcpAgentConfigAuditEntry> =
        readConfigAudit(agentId)

    fun configSnapshot(agentId: String, revision: Long): Map<String, String> {
        require(revision > 0L) { "Config revision must be positive." }
        return readConfigSnapshots(agentId)[revision.toString()]
            ?: throw IllegalArgumentException(
                "Unknown Agent config revision $revision for $agentId."
            )
    }

    private fun readConfigAudit(agentId: String): List<AcpAgentConfigAuditEntry> = runCatching {
        val raw = configHistoryPreferences.getString(configAuditKey(agentId), null)
            ?: return@runCatching emptyList()
        gson.fromJson<List<AcpAgentConfigAuditEntry>>(
            raw,
            object : TypeToken<List<AcpAgentConfigAuditEntry>>() {}.type,
        )
    }.getOrNull().orEmpty()

    private fun readConfigSnapshots(agentId: String): Map<String, Map<String, String>> = runCatching {
        val raw = configHistoryPreferences.getString(configSnapshotsKey(agentId), null)
            ?: return@runCatching emptyMap()
        gson.fromJson<Map<String, Map<String, String>>>(
            raw,
            object : TypeToken<Map<String, Map<String, String>>>() {}.type,
        )
    }.getOrNull().orEmpty()

    private fun configAuditKey(agentId: String) = "audit:$agentId"
    private fun configSnapshotsKey(agentId: String) = "snapshots:$agentId"

    private fun readStoredProfiles(): List<AcpAgentProfile> = runCatching {
        val json = preferences.getString(KEY_PROFILES, null)
            ?: return@runCatching emptyList()
        gson.fromJson<List<AcpAgentProfile>>(
            json,
            object : TypeToken<List<AcpAgentProfile>>() {}.type
        )
    }.getOrNull().orEmpty()

    /**
     * Older builds could persist the built-in Xiaowan command as a custom
     * profile with the legacy id. Migrate that known identity and its stored
     * references, without guessing from user-editable names or commands.
     */
    @Synchronized
    private fun migrateLegacyXiaowanAliases() {
        val stored = readStoredProfiles()
        val aliases = stored.filter(::isLegacyXiaowanAlias)
        if (aliases.isEmpty()) return
        val aliasIds = aliases.mapTo(linkedSetOf()) { it.id }
        writeProfiles(stored.filterNot { it.id in aliasIds })

        val selectedId = preferences.getString(KEY_SELECTED_PROFILE_ID, null)
        val sessionBindings = sessionBindings().mapValues { (_, agentId) ->
            if (agentId in aliasIds) XIAOWAN_AGENT_ID else agentId
        }
        val conversationBindings = conversationBindings().mapValues { (_, agentId) ->
            if (agentId in aliasIds) XIAOWAN_AGENT_ID else agentId
        }
        val health = readHealth().filterKeys { it !in aliasIds }
        preferences.edit().apply {
            if (selectedId in aliasIds) {
                putString(KEY_SELECTED_PROFILE_ID, XIAOWAN_AGENT_ID)
            }
            putString(KEY_SESSION_BINDINGS, gson.toJson(sessionBindings))
            putString(KEY_CONVERSATION_BINDINGS, gson.toJson(conversationBindings))
            putString(KEY_HEALTH, gson.toJson(health))
            apply()
        }
    }

    private fun writeProfiles(profiles: List<AcpAgentProfile>) {
        val persistable = profiles.filter { !it.builtIn || hasOfficialOverride(it) }
        preferences.edit().putString(KEY_PROFILES, gson.toJson(persistable)).apply()
    }

    private fun hasOfficialOverride(profile: AcpAgentProfile): Boolean {
        val definition = catalog.definition(profile.id) ?: return true
        return profile.command != definition.command ||
            profile.arguments != definition.arguments ||
            profile.environment.isNotEmpty() ||
            profile.enabled != definition.enabled
    }

    private fun sessionBindings(): Map<String, String> = runCatching {
        val json = preferences.getString(KEY_SESSION_BINDINGS, null)
            ?: return@runCatching emptyMap()
        gson.fromJson<Map<String, String>>(
            json,
            object : TypeToken<Map<String, String>>() {}.type
        )
    }.getOrNull().orEmpty()

    private fun conversationBindings(): Map<String, String> = runCatching {
        val json = preferences.getString(KEY_CONVERSATION_BINDINGS, null)
            ?: return@runCatching emptyMap()
        gson.fromJson<Map<String, String>>(
            json,
            object : TypeToken<Map<String, String>>() {}.type
        )
    }.getOrNull().orEmpty()

    private fun readHealth(): Map<String, AcpAgentHealth> = runCatching {
        val json = preferences.getString(KEY_HEALTH, null)
            ?: return@runCatching emptyMap()
        gson.fromJson<Map<String, AcpAgentHealth>>(
            json,
            object : TypeToken<Map<String, AcpAgentHealth>>() {}.type
        )
    }.getOrNull().orEmpty()

    private fun normalize(profile: AcpAgentProfile): AcpAgentProfile? {
        val id = profile.id.trim()
        val name = profile.name.trim()
        val command = profile.command.trim()
        if (id.isEmpty() || name.isEmpty() || command.isEmpty()) {
            return null
        }
        return profile.copy(
            id = id,
            name = name,
            description = profile.description.trim(),
            command = command,
            arguments = profile.arguments.map(String::trim).filter(String::isNotEmpty),
            environment = profile.environment.entries
                .mapNotNull { (key, value) ->
                    key.trim()
                        .takeIf(ENVIRONMENT_NAME::matches)
                        ?.let { it to value }
                }
                .toMap(),
            builtIn = id in catalog.officialIds,
            officialRuntime = catalog.definition(id)?.officialRuntime,
        )
    }

    companion object {
        const val CODEX_AGENT_ID = "codex-acp"
        const val DEEPSEEK_HARNESS_AGENT_ID = "deepseek-harness-acp"
        const val KIMI_CODE_AGENT_ID = "kimi-code-acp"
        const val XIAOWAN_AGENT_ID = "xiaowan-acp"
        const val DEFAULT_AGENT_ID = XIAOWAN_AGENT_ID

        /**
         * Compatibility accessor for callers that already have a resolved
         * profile. The catalog owns the descriptor; this method does not
         * contain an Agent list or a vendor lookup.
         */
        fun officialRuntime(profile: AcpAgentProfile): AcpOfficialRuntime? =
            profile.officialRuntime

        fun usesSharedProvider(profile: AcpAgentProfile): Boolean =
            profile.officialRuntime?.usesSharedProvider == true

        internal fun isLegacyXiaowanAlias(profile: AcpAgentProfile): Boolean {
            // Names and commands are user configuration, not identity.
            // Only the known persisted legacy id authorizes migration.
            return profile.id.equals("legacy-xiaowan-bot", ignoreCase = true) ||
                profile.id.equals("legacy-xiaowan-command", ignoreCase = true)
        }

        private const val PREFERENCES_NAME = "acp_agent_profiles"
        private const val CONFIG_HISTORY_PREFERENCES_NAME = "acp_agent_config_history"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val KEY_SESSION_BINDINGS = "session_bindings"
        private const val KEY_CONVERSATION_BINDINGS = "conversation_bindings"
        private const val KEY_HEALTH = "health"
        private const val DEEPSEEK_HARNESS_CORDIS_PATH =
            "/root/.dsh/omnibot-acp/cordis.yml"
        private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
