package cn.com.omnimind.bot.agent.runtime

import android.content.Context
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
)

internal const val DEEPSEEK_HARNESS_NPM_CHANNEL = "next"
internal val DEEPSEEK_HARNESS_NPM_PACKAGE_NAMES = listOf(
    // Install the official Harness product and the existing ACP adapter. The
    // adapter composes DSH in-process and projects assistant/chunk into ACP
    // text and thought chunks; the app does not maintain another agent loop.
    "@deepseek-ai/dsh",
    "@openma/deepseek-harness-acp",
)
internal val DEEPSEEK_HARNESS_NPM_PACKAGE_SPECS = listOf(
    "@deepseek-ai/dsh@$DEEPSEEK_HARNESS_NPM_CHANNEL",
    "@openma/deepseek-harness-acp@latest",
)
internal const val DEEPSEEK_HARNESS_INSTALL_SCRIPT_PATH =
    "/root/.dsh/omnibot-acp/install-dsh-runtime.sh"
internal const val DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND =
    "node -e 'const fs = require(\"node:fs\"); " +
        "const { createRequire } = require(\"node:module\"); " +
        "const adapterPackageJson = \"/root/.npm-global/lib/node_modules/" +
        "@openma/deepseek-harness-acp/package.json\"; " +
        "const dshPackageJson = \"/root/.npm-global/lib/node_modules/" +
        "@deepseek-ai/dsh/package.json\"; " +
        "const adapter = JSON.parse(fs.readFileSync(adapterPackageJson)); " +
        "const dsh = JSON.parse(fs.readFileSync(dshPackageJson)); " +
        "if (adapter.name !== \"@openma/deepseek-harness-acp\" || " +
        "dsh.name !== \"@deepseek-ai/dsh\" || " +
        "!fs.existsSync(\"/root/.npm-global/lib/node_modules/@openma/" +
        "deepseek-harness-acp/node_modules/@deepseek-ai/dsh-credentials\")) " +
        "process.exit(1); " +
        "createRequire(\"/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/" +
        "node_modules/@deepseek-ai/dsh-subprocess-local/package.json\")(\"node-pty\");'"
internal val DEEPSEEK_HARNESS_ACP_DEPENDENCY_MOUNT_COMMAND = """
    set -eu
    source_root=/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai
    target_root=/root/.npm-global/lib/node_modules/@openma/deepseek-harness-acp/node_modules/@deepseek-ai
    mkdir -p "${'$'}target_root"
    [ -e "${'$'}target_root/dsh" ] || ln -s /root/.npm-global/lib/node_modules/@deepseek-ai/dsh "${'$'}target_root/dsh"
    for source in "${'$'}source_root"/*; do
      [ -d "${'$'}source" ] || continue
      package_name="${'$'}(basename "${'$'}source")"
      target="${'$'}target_root/${'$'}package_name"
      [ -e "${'$'}target" ] || ln -s "${'$'}source" "${'$'}target"
    done
""".trimIndent()
internal val DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND = """
    cleanup_deepseek_harness_npm_staging() {
      # npm uses hidden sibling directories while publishing scoped packages.
      # A cancelled/failed Android install can leave one behind; npm then
      # fails its next rename with ENOTEMPTY before it can repair the package.
      for staging_dir in /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-*; do
        [ -e "${'$'}staging_dir" ] || continue
        # A recursive delete through Android's proot can block the foreground
        # ACP switch for minutes. Rename within the same filesystem first,
        # then reclaim the explicit temporary directory asynchronously.
        stale_dir="/tmp/omnibot-dsh-stale-${'$'}{staging_dir##*/}-${'$'}${'$'}"
        if mv "${'$'}staging_dir" "${'$'}stale_dir" 2>/dev/null; then
          (rm -rf "${'$'}stale_dir" >/dev/null 2>&1 || true) &
        else
          rm -rf "${'$'}staging_dir"
        fi
      done
    }
    find_deepseek_harness_node_pty_dir() {
      for candidate in \
        /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty \
        /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-subprocess-local/node_modules/node-pty; do
        if [ -f "${'$'}candidate/package.json" ]; then
          printf '%s\n' "${'$'}candidate"
          return 0
        fi
      done
      return 1
    }
    repair_deepseek_harness_node_pty() {
      node_pty_dir="${'$'}(find_deepseek_harness_node_pty_dir || true)"
      [ -n "${'$'}node_pty_dir" ] || return 0
      if [ -f "${'$'}node_pty_dir/package.json" ] &&
         ! $DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND >/dev/null 2>&1; then
        (
          cd "${'$'}node_pty_dir"
          node-gyp configure
          sed -i 's|^cmd_copy = .*|cmd_copy = rm -rf "${'$'}@" \&\& cp -af "${'$'}<" "${'$'}@"|' build/Makefile
          node-gyp build
        )
      fi
    }
    install_deepseek_harness_packages() {
      # Once published, keep the package directory stable: Android's proot
      # can leave a populated scoped directory that npm cannot rename over.
      # The health check below still verifies the official DSH + ACP graph;
      # a missing package is the only case that needs a fresh npm install.
      if [ -f /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/package.json ] &&
         [ -f /root/.npm-global/lib/node_modules/@openma/deepseek-harness-acp/package.json ]; then
        return 0
      fi
      cleanup_deepseek_harness_npm_staging
      hardlink_helper='/tmp/omnibot-node-gyp-copy'
      rm -rf "${'$'}hardlink_helper"
      mkdir -p "${'$'}hardlink_helper"
      printf '%s\n' \
        '#!/bin/sh' \
        'if [ "${'$'}1" = "-f" ]; then' \
        '  shift' \
        '  src="${'$'}1"' \
        '  dst="${'$'}2"' \
        '  exec cp -af "${'$'}src" "${'$'}dst"' \
        'fi' \
        'exec /bin/ln "${'$'}@"' > "${'$'}hardlink_helper/ln"
      chmod 755 "${'$'}hardlink_helper/ln"
      if PATH="${'$'}hardlink_helper:${'$'}PATH" npm install -g --prefix /root/.npm-global \
          --include=peer --no-audit --no-fund ${DEEPSEEK_HARNESS_NPM_PACKAGE_SPECS.joinToString(" ")}; then
        install_status=0
      else
        install_status=${'$'}?
      fi
      rm -rf "${'$'}hardlink_helper"
      return "${'$'}install_status"
    }
    install_deepseek_harness_packages
    $DEEPSEEK_HARNESS_ACP_DEPENDENCY_MOUNT_COMMAND
    # npm may replace node-pty during the install above, so the native repair
    # must run after package publication as well as on later health checks.
    repair_deepseek_harness_node_pty
    $DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND
""".trimIndent()

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

    @Synchronized
    fun list(): List<AcpAgentProfile> {
        migrateLegacyXiaowanAliases()
        val stored = readStoredProfiles()
            .mapNotNull(::normalize)
            .filterNot { it.id in RETIRED_AGENT_IDS }
        val storedById = stored.associateBy { it.id }
        val official = OFFICIAL_AGENTS.map { definition ->
            val override = storedById[definition.id] ?: return@map definition
            definition.copy(
                command = override.command,
                arguments = override.arguments,
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
        migrateLegacyXiaowanAliases()
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
        migrateLegacyXiaowanAliases()
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
     * profile named "小万 Bot". Keep the official id as the only identity and
     * migrate all persisted references to it during the first catalog read.
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
        const val CODEX_AGENT_ID = "codex-acp"
        const val DEEPSEEK_HARNESS_AGENT_ID = "deepseek-harness-acp"
        const val XIAOWAN_AGENT_ID = "xiaowan-acp"
        const val DEFAULT_AGENT_ID = XIAOWAN_AGENT_ID

        val OFFICIAL_AGENTS = listOf(
            AcpAgentProfile(
                id = XIAOWAN_AGENT_ID,
                name = "小万",
                description = "小万内置能力通过官方 ACP Agent 接口提供",
                command = "omnibot-xiaowan-acp",
                builtIn = true
            ),
            AcpAgentProfile(
                id = CODEX_AGENT_ID,
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
            ),
            AcpAgentProfile(
                id = DEEPSEEK_HARNESS_AGENT_ID,
                name = "DeepSeek Harness",
                description = "DeepSeek Harness through the existing streaming ACP adapter",
                command = "dsh-acp",
                builtIn = true
            )
        )
        val CODEX_AGENT = OFFICIAL_AGENTS.first { it.id == CODEX_AGENT_ID }
        private val OFFICIAL_AGENT_IDS = OFFICIAL_AGENTS.mapTo(linkedSetOf()) { it.id }
        private val RETIRED_AGENT_IDS = setOf("gemini-cli-acp")
        private val OFFICIAL_RUNTIMES = mapOf(
            CODEX_AGENT_ID to AcpOfficialRuntime(
                discoveryCommand = "codex",
                managedAdapterPackage = "@openai/codex@latest",
                managedAdapterPackages = listOf(
                    "@openai/codex@latest",
                    "@agentclientprotocol/codex-acp@1.1.7"
                ),
                terminalPackageId = "codex",
                usesSharedProvider = true,
            ),
            "claude-code-acp" to AcpOfficialRuntime(
                discoveryCommand = "claude",
                managedAdapterPackage = "@anthropic-ai/claude-code@latest",
                managedAdapterPackages = listOf(
                    "@anthropic-ai/claude-code@latest",
                    "@agentclientprotocol/claude-agent-acp@0.61.0"
                ),
                terminalPackageId = "claude_code",
                usesSharedProvider = true,
            ),
            "opencode-acp" to AcpOfficialRuntime(
                discoveryCommand = "opencode",
                managedAdapterPackage = "opencode-ai@latest",
                terminalPackageId = "opencode",
                usesSharedProvider = true,
            ),
            DEEPSEEK_HARNESS_AGENT_ID to AcpOfficialRuntime(
                // The existing adapter composes the official `dsh` package
                // in-process and exposes text plus reasoning deltas.
                discoveryCommand = "dsh",
                managedAdapterPackage = DEEPSEEK_HARNESS_NPM_PACKAGE_SPECS.last(),
                managedAdapterPackages = DEEPSEEK_HARNESS_NPM_PACKAGE_SPECS,
                requiresNativeBuildTools = true,
                managedAdapterHealthCommand = DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND,
                harnessAdapter = AcpHarnessAdapters.deepSeekHarness,
                terminalPackageId = "deepseek_harness",
                managedInstallScriptPath = DEEPSEEK_HARNESS_INSTALL_SCRIPT_PATH,
                managedInstallCommand = DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND,
                usesSharedProvider = true,
            ),
            XIAOWAN_AGENT_ID to AcpOfficialRuntime(
                discoveryCommand = "omnibot-xiaowan-acp",
                usesSharedProvider = true,
            )
        )

        fun officialRuntime(profile: AcpAgentProfile): AcpOfficialRuntime? {
            val definition = OFFICIAL_AGENTS.firstOrNull { it.id == profile.id }
                ?: return null
            if (
                profile.command != definition.command ||
                profile.arguments != definition.arguments
            ) {
                return null
            }
            return OFFICIAL_RUNTIMES[profile.id]
        }

        fun usesSharedProvider(profile: AcpAgentProfile): Boolean =
            officialRuntime(profile)?.usesSharedProvider == true

        internal fun isLegacyXiaowanAlias(profile: AcpAgentProfile): Boolean {
            if (profile.id == XIAOWAN_AGENT_ID) return false
            val normalizedName = profile.name
                .trim()
                .lowercase()
                .replace(Regex("[\\s_-]+"), "")
            return profile.id.equals("legacy-xiaowan-bot", ignoreCase = true) ||
                profile.command.equals("omnibot-xiaowan-acp", ignoreCase = true) ||
                profile.command.contains("xiaowan", ignoreCase = true) ||
                normalizedName == "小万" ||
                normalizedName == "小万bot" ||
                normalizedName == "xiaowanbot"
        }

        private const val PREFERENCES_NAME = "acp_agent_profiles"
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
