package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.util.AppSecretStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

internal data class AcpAgentProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtIn: Boolean = false
) {
    fun toPayload(
        selected: Boolean = false,
        health: AcpAgentHealth = AcpAgentHealth()
    ): Map<String, Any?> {
        val runtime = AcpAgentProfileStore.officialRuntime(this)
        return linkedMapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "command" to command,
            "arguments" to arguments,
            "environment" to environment.keys.associateWith { "" },
            "environmentSecretKeys" to environment.keys.sorted(),
            "enabled" to enabled,
            "builtIn" to builtIn,
            "source" to if (builtIn) "official" else "custom",
            "selected" to selected,
            "installed" to health.installed,
            "status" to health.status,
            "lastCheckError" to health.error,
            "lastCheckLatencyMs" to health.latencyMs,
            "lastCheckAt" to health.checkedAt,
            "capabilities" to health.capabilities,
            "discoveryCommand" to runtime?.discoveryCommand,
            "managedAdapter" to (runtime?.managedAdapterPackage != null)
        )
    }
}

internal data class AcpAgentHealth(
    val status: String = STATUS_UNCHECKED,
    val installed: Boolean? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long? = null,
    val capabilities: Map<String, Any?> = emptyMap()
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
    val managedAdapterPackage: String? = null
)

/**
 * ACP Agent registry inspired by AionUi's managed-agent catalog:
 * official definitions always remain visible, while user overrides and
 * custom ACP commands are persisted separately from API credentials.
 */
internal class AcpAgentProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val gson = Gson()

    init {
        migrateLegacyEnvironmentValues()
        migrateOfficialCommandOverrides()
    }

    @Synchronized
    fun list(): List<AcpAgentProfile> {
        val stored = readStoredProfiles()
            .mapNotNull(::normalize)
            .filterNot { it.id in RETIRED_AGENT_IDS }
        val storedById = stored.associateBy { it.id }
        val official = OFFICIAL_AGENTS.map { definition ->
            val override = storedById[definition.id] ?: return@map definition
            definition.copy(
                environment = override.environment,
                enabled = override.enabled
            )
        }
        val custom = stored
            .filterNot { it.id in OFFICIAL_AGENT_IDS }
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
        return sessionBindings()[sessionId.trim()]
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it in RETIRED_AGENT_IDS }
    }

    @Synchronized
    fun bindConversation(conversationId: Long, agentId: String) {
        if (conversationId <= 0L) return
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isEmpty()) return
        val bindings = conversationBindings().toMutableMap()
        bindings[conversationId.toString()] = normalizedAgentId
        preferences.edit().putString(KEY_CONVERSATION_BINDINGS, gson.toJson(bindings)).apply()
    }

    fun agentIdForConversation(conversationId: Long): String? {
        if (conversationId <= 0L) return null
        return conversationBindings()[conversationId.toString()]
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it in RETIRED_AGENT_IDS }
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
    fun clearConversationBindings(): Boolean {
        return preferences.edit().remove(KEY_CONVERSATION_BINDINGS).commit()
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
        val officialDefinition = OFFICIAL_AGENTS.firstOrNull { it.id == targetId }
        val candidate = if (officialDefinition != null) {
            officialDefinition.copy(
                environment = mergeEnvironmentForReplace(
                    raw.environment,
                    current.firstOrNull { it.id == targetId }?.environment.orEmpty(),
                ),
                enabled = raw.enabled
            )
        } else {
            raw.copy(
                id = targetId,
                builtIn = false,
                environment = mergeEnvironmentForReplace(
                    raw.environment,
                    current.firstOrNull { it.id == targetId }?.environment.orEmpty(),
                ),
            )
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
        require(normalizedId !in OFFICIAL_AGENT_IDS) {
            "Official ACP agents cannot be deleted."
        }
        val remaining = list().filterNot { it.builtIn || it.id == normalizedId }
        val officialOverrides = readStoredProfiles().filter { it.id in OFFICIAL_AGENT_IDS }
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
            preferences.edit().putString(KEY_SELECTED_PROFILE_ID, DEFAULT_CODEX_AGENT_ID).apply()
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

    private fun readStoredProfiles(): List<AcpAgentProfile> = readStoredProfilesRaw().map { profile ->
        profile.copy(
            environment = profile.environment.keys.associateWith { key ->
                AppSecretStore.read(environmentSecretKey(profile.id, key)).orEmpty()
            },
        )
    }

    private fun readStoredProfilesRaw(): List<AcpAgentProfile> = runCatching {
        val json = preferences.getString(KEY_PROFILES, null)
            ?: return@runCatching emptyList()
        gson.fromJson<List<AcpAgentProfile>>(
            json,
            object : TypeToken<List<AcpAgentProfile>>() {}.type
        )
    }.getOrNull().orEmpty()

    private fun writeProfiles(profiles: List<AcpAgentProfile>) {
        val persistable = profiles.filter { !it.builtIn || hasOfficialOverride(it) }
        val oldRawProfiles = readStoredProfilesRaw()
        val oldMetadataJson = preferences.getString(KEY_PROFILES, null)
        val desiredKeyNames = persistable.flatMap { profile ->
            profile.environment.keys.map { key -> environmentSecretKey(profile.id, key) }
        }.toSet()
        val oldKeyNames = oldRawProfiles.flatMap { profile ->
            profile.environment.keys.map { key -> environmentSecretKey(profile.id, key) }
        }.toSet()
        val snapshots = (desiredKeyNames + oldKeyNames).associateWith { key ->
            AppSecretStore.readWithStatus(key).also { result ->
                if (!result.succeeded) throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
            }.value
        }
        val desiredSecrets = linkedMapOf<String, String>()
        val safeProfiles = persistable.map { profile ->
            val safeEnvironment = linkedMapOf<String, String>()
            profile.environment.forEach { (key, value) ->
                val secretKey = environmentSecretKey(profile.id, key)
                val desiredValue = value.takeIf(String::isNotEmpty)
                    ?: snapshots[secretKey]
                    ?: throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
                desiredSecrets[secretKey] = desiredValue
                safeEnvironment[key] = ""
            }
            profile.copy(environment = safeEnvironment)
        }
        val desiredMetadataJson = gson.toJson(safeProfiles)
        try {
            desiredSecrets.forEach { (key, value) ->
                if (!AppSecretStore.write(key, value) || AppSecretStore.read(key) != value) {
                    throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
                }
            }
            val committed = preferences.edit()
                .putString(KEY_PROFILES, desiredMetadataJson)
                .commit()
            if (!committed || preferences.getString(KEY_PROFILES, null) != desiredMetadataJson) {
                throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
            }
            (oldKeyNames - desiredSecrets.keys).forEach { key ->
                if (!AppSecretStore.delete(key)) {
                    throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
                }
            }
        } catch (error: Exception) {
            var secretsRestored = true
            snapshots.forEach { (key, value) ->
                val restored = if (value == null) {
                    AppSecretStore.delete(key)
                } else {
                    AppSecretStore.write(key, value)
                }
                secretsRestored = restored && secretsRestored
            }
            val metadataRestored = if (oldMetadataJson == null) {
                preferences.edit().remove(KEY_PROFILES).commit() &&
                    preferences.getString(KEY_PROFILES, null) == null
            } else {
                preferences.edit().putString(KEY_PROFILES, oldMetadataJson).commit() &&
                    preferences.getString(KEY_PROFILES, null) == oldMetadataJson
            }
            if (!secretsRestored || !metadataRestored) {
                throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
            }
            throw IllegalStateException(ACP_AGENT_PROFILE_PERSIST_FAILED)
        }
    }

    private fun migrateLegacyEnvironmentValues() {
        val rawProfiles = readStoredProfilesRaw()
        if (rawProfiles.none { profile -> profile.environment.values.any(String::isNotEmpty) }) return
        val safeProfiles = rawProfiles.map { profile ->
            val safeEnvironment = linkedMapOf<String, String>()
            profile.environment.forEach { (key, legacyValue) ->
                if (legacyValue.isEmpty()) {
                    safeEnvironment[key] = ""
                } else {
                    val secretKey = environmentSecretKey(profile.id, key)
                    val migrated = AppSecretStore.write(secretKey, legacyValue) &&
                        AppSecretStore.read(secretKey) == legacyValue
                    if (migrated) safeEnvironment[key] = "" else AppSecretStore.delete(secretKey)
                }
            }
            profile.copy(environment = safeEnvironment)
        }
        val scrubbed = preferences.edit()
            .putString(KEY_PROFILES, gson.toJson(safeProfiles))
            .commit()
        if (!scrubbed) {
            // Removing the complete legacy record is safer than retaining credentials.
            preferences.edit().remove(KEY_PROFILES).commit()
            rawProfiles.forEach { AppSecretStore.deletePrefix(environmentSecretPrefix(it.id)) }
        }
    }

    private fun migrateOfficialCommandOverrides() {
        val stored = readStoredProfiles()
        if (stored.none { profile ->
                val definition = OFFICIAL_AGENTS.firstOrNull { it.id == profile.id }
                definition != null &&
                    (profile.command != definition.command || profile.arguments != definition.arguments)
            }
        ) {
            return
        }
        writeProfiles(
            stored.map { profile ->
                val definition = OFFICIAL_AGENTS.firstOrNull { it.id == profile.id }
                    ?: return@map profile
                definition.copy(
                    environment = profile.environment,
                    enabled = profile.enabled
                )
            }
        )
    }

    private fun mergeEnvironmentForReplace(
        requested: Map<String, String>,
        current: Map<String, String>,
    ): Map<String, String> = requested.mapValues { (key, value) ->
        if (value.isEmpty() && current.containsKey(key)) current.getValue(key) else value
    }

    private fun environmentSecretPrefix(profileId: String): String = "acp.env.$profileId."

    private fun environmentSecretKey(profileId: String, name: String): String =
        environmentSecretPrefix(profileId) + name

    private fun hasOfficialOverride(profile: AcpAgentProfile): Boolean {
        val definition = OFFICIAL_AGENTS.firstOrNull { it.id == profile.id } ?: return true
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
            builtIn = id in OFFICIAL_AGENT_IDS
        )
    }

    companion object {
        const val DEFAULT_CODEX_AGENT_ID = "codex-acp"

        val OFFICIAL_AGENTS = listOf(
            AcpAgentProfile(
                id = DEFAULT_CODEX_AGENT_ID,
                name = "Codex",
                description = "OpenAI Codex through its managed ACP adapter",
                command = "codex-acp",
                builtIn = true
            ),
            AcpAgentProfile(
                id = "claude-code-acp",
                name = "Claude Code",
                description = "Claude Code through the ACP adapter",
                command = "claude-agent-acp",
                builtIn = true
            ),
            AcpAgentProfile(
                id = "opencode-acp",
                name = "OpenCode",
                description = "OpenCode ACP server",
                command = "opencode",
                arguments = listOf("acp"),
                builtIn = true
            )
        )
        val DEFAULT_CODEX_AGENT = OFFICIAL_AGENTS.first()
        private val OFFICIAL_AGENT_IDS = OFFICIAL_AGENTS.mapTo(linkedSetOf()) { it.id }
        private val RETIRED_AGENT_IDS = setOf("gemini-cli-acp")
        private val OFFICIAL_RUNTIMES = mapOf(
            DEFAULT_CODEX_AGENT_ID to AcpOfficialRuntime(
                discoveryCommand = "codex",
                managedAdapterPackage = MANAGED_CODEX_ACP_PACKAGE_SPEC
            ),
            "claude-code-acp" to AcpOfficialRuntime(
                discoveryCommand = "claude",
                managedAdapterPackage = MANAGED_CLAUDE_ACP_PACKAGE_SPEC
            ),
            "opencode-acp" to AcpOfficialRuntime(discoveryCommand = "opencode")
        )

        fun officialRuntime(profile: AcpAgentProfile): AcpOfficialRuntime? {
            return OFFICIAL_RUNTIMES[profile.id]
        }

        private const val PREFERENCES_NAME = "acp_agent_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        private const val KEY_SESSION_BINDINGS = "session_bindings"
        private const val KEY_CONVERSATION_BINDINGS = "conversation_bindings"
        private const val KEY_HEALTH = "health"
        internal const val ACP_AGENT_PROFILE_PERSIST_FAILED =
            "ACP_AGENT_PROFILE_PERSIST_FAILED"
        private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
