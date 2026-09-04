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
    /**
     * Capabilities known from the official Harness composition, before an
     * ACP initialize handshake has happened. These are intentionally kept
     * separate from the negotiated ACP capabilities returned by initialize.
     * A health probe must remain read-only, but the UI still needs to explain
     * what an installed Harness can do.
     */
    val declaredCapabilities: Map<String, Any?> = emptyMap(),
)

private val DEEPSEEK_HARNESS_DECLARED_CAPABILITIES: Map<String, Any?> = mapOf(
    "plugin" to mapOf(
        "supported" to true,
        "authoring" to true,
        "installViaHarness" to true,
        "hostInstallApi" to false,
        "source" to "DeepSeek Harness Cordis profile",
    ),
    "tools" to mapOf(
        "fileRead" to true,
        "fileWrite" to true,
        "shell" to true,
        "plan" to true,
        "subagents" to true,
        "skills" to true,
    ),
    "mcp" to mapOf(
        "sessionServers" to true,
        "source" to "Harness-owned MCP composition",
    ),
)

internal const val DEEPSEEK_HARNESS_NPM_CHANNEL = "next"
internal const val DEEPSEEK_HARNESS_PNPM_VERSION = "11.22.0"
internal const val DEEPSEEK_HARNESS_NODE_ENTRYPOINT =
    "/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
internal const val DEEPSEEK_HARNESS_PREPARATION_REVISION =
    "deepseek-dsh-profile-reset-v13"
private const val DEEPSEEK_HARNESS_NPM_PRIMARY_REGISTRY =
    "https://registry.npmmirror.com"
private const val DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY =
    "https://registry.npmjs.org"
internal val DEEPSEEK_HARNESS_NPM_PACKAGE_NAMES = listOf(
    // The adapter is installed into DSH's official `acp` profile. DSH owns
    // the profile plugin graph, tools, commands, skills, and MCP composition.
    "@deepseek-ai/dsh",
    "@openma/deepseek-harness-acp",
)
internal val DEEPSEEK_HARNESS_NPM_PACKAGE_SPECS = listOf(
    "@deepseek-ai/dsh@$DEEPSEEK_HARNESS_NPM_CHANNEL",
    "@openma/deepseek-harness-acp@latest",
)
internal const val DEEPSEEK_HARNESS_INSTALL_SCRIPT_PATH =
    "/root/.dsh/omnibot-acp/install-dsh-runtime.sh"
internal const val DEEPSEEK_HARNESS_ACP_PATCH_PATH =
    "/root/.dsh/omnibot-acp/omnibot-acp-headless.patch.yml"
internal const val DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND =
        "export DSH_HOME=/root/.dsh/omnibot-acp; " +
        "export PATH=/root/.npm-global/bin:${'$'}PATH; " +
        "command -v dsh >/dev/null 2>&1 && " +
        "command -v dsh-acp-android >/dev/null 2>&1 && " +
        "command -v pnpm >/dev/null 2>&1 && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/package.json && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/package.json && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/dist/plugin.js && " +
        "test ! -L /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/dist/plugin.js && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/dist/stdio.js && " +
        "test ! -L /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/dist/stdio.js && " +
        "grep -Eq '^[[:space:]]*nodeLinker:[[:space:]]*hoisted[[:space:]]*$' " +
        "/root/.dsh/omnibot-acp/profiles/acp/pnpm-workspace.yaml && " +
        "grep -Eq '^[[:space:]]*packageImportMethod:[[:space:]]*copy[[:space:]]*$' " +
        "/root/.dsh/omnibot-acp/profiles/acp/pnpm-workspace.yaml && " +
        "node -e \"require('/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty')\" >/dev/null 2>&1 && " +
        // This read-only probe verifies the installed profile shape. Importing
        // the adapter directly is invalid because its modules deliberately
        // resolve DSH packages supplied by the Harness host. The installer
        // performs the stronger host-level `dsh --dump-config` validation.
        "cd /root/.dsh/omnibot-acp/profiles/acp && " +
        "node --input-type=module -e \"import fs from 'node:fs'; const profile=JSON.parse(fs.readFileSync('package.json','utf8')); const bundles=profile?.dsh?.profile?.bundles; if (!Array.isArray(bundles) || !bundles.includes('@openma/deepseek-harness-acp')) process.exit(1);\" >/dev/null 2>&1"
internal val DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND = """
    set -eu
    export PATH="/root/.npm-global/bin:${'$'}PATH"
    export DSH_HOME="/root/.dsh/omnibot-acp"
    # These are pnpm settings, so use pnpm's documented environment prefix.
    # Copying package files avoids PRoot's hard-link emulation while hoisting
    # retains the layout expected by the official DSH profile.
    export PNPM_CONFIG_NODE_LINKER=hoisted
    export PNPM_CONFIG_PACKAGE_IMPORT_METHOD=copy
    DSH_PACKAGE_ROOT="/root/.npm-global/lib/node_modules/@deepseek-ai/dsh"
    NPM_PRIMARY_REGISTRY="${'$'}{OMNIBOT_NPM_REGISTRY:-$DEEPSEEK_HARNESS_NPM_PRIMARY_REGISTRY}"
    mkdir -p "${'$'}DSH_HOME"
    npm config set prefix /root/.npm-global
    if ! command -v pnpm >/dev/null 2>&1; then
      if ! npm install -g --no-audit --no-fund \
          --registry="${'$'}NPM_PRIMARY_REGISTRY" \
          pnpm@$DEEPSEEK_HARNESS_PNPM_VERSION; then
        npm install -g --no-audit --no-fund \
          --registry="$DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY" \
          pnpm@$DEEPSEEK_HARNESS_PNPM_VERSION
      fi
    fi
    # A previous Android npm run may leave a seemingly installed package with
    # an incomplete dependency tree. Keep this preflight structural and let
    # the authoritative native/import checks run later in this script.
    if [ ! -f "${'$'}DSH_PACKAGE_ROOT/package.json" ] || \
        [ ! -f "${'$'}DSH_PACKAGE_ROOT/lib/bin.js" ] || \
        { [ ! -f "${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty/prebuilds/linux-arm64/pty.node" ] && \
          [ ! -f "${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node" ]; } || \
        [ ! -d "${'$'}DSH_PACKAGE_ROOT/node_modules" ]; then
      npm cache clean --force >/dev/null 2>&1 || true
      rm -rf "${'$'}DSH_PACKAGE_ROOT" \
        /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-* 2>/dev/null || true
      install_dsh_runtime() {
        registry="${'$'}1"
        npm install -g --no-audit --no-fund --prefer-offline \
          --fetch-retries=5 --fetch-retry-factor=2 \
          --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=15000 \
          --fetch-timeout=120000 --loglevel=notice \
          --registry="${'$'}registry" \
          @deepseek-ai/dsh@$DEEPSEEK_HARNESS_NPM_CHANNEL
      }
      if ! install_dsh_runtime "${'$'}NPM_PRIMARY_REGISTRY"; then
        rm -rf "${'$'}DSH_PACKAGE_ROOT" \
          /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-* 2>/dev/null || true
        install_dsh_runtime "$DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY"
      fi
    fi
    # Some Android npm builds install the package but skip creating its bin
    # shim. Recreate the vendor-declared executable from the installed package
    # before invoking the official DSH plugin workflow; this is still the
    # upstream CLI entrypoint, not a private ACP replacement.
    if [ ! -x /root/.npm-global/bin/dsh ] && \
        [ -f /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js ]; then
      ln -sf /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js \
        /root/.npm-global/bin/dsh
    fi
    test -x /root/.npm-global/bin/dsh
    # DSH's HMR plugin requires a Node internal flag. NODE_OPTIONS rejects
    # this flag, so publish a tiny launcher that passes it as a CLI argument
    # while still executing the vendor's official lib/bin.js entrypoint.
    # The ACP transport is headless. Keep the Web-only plugins installed in
    # the shared DSH profile, but do not activate them in this process: they
    # wait for webServer/webRuntime and make the ACP tree fail after a slow
    # initialize. This overlay is launch-scoped and never deletes user data.
    printf '%s\n' '# OmniBot ACP headless overlay' \
      '- id: dsh-plugin-mgr' \
      '  disabled: true' \
      '- id: dsh-plugin-studio' \
      '  disabled: true' \
      '- id: uisfx' \
      '  disabled: true' \
      > "$DEEPSEEK_HARNESS_ACP_PATCH_PATH"
    printf '%s\n' '#!/bin/sh' \
      'exec node --expose-internals $DEEPSEEK_HARNESS_NODE_ENTRYPOINT --patch "$DEEPSEEK_HARNESS_ACP_PATCH_PATH" "${'$'}@"' \
      > /root/.npm-global/bin/dsh-acp-android
    chmod 755 /root/.npm-global/bin/dsh-acp-android
    test -x /root/.npm-global/bin/dsh-acp-android
    # The official node-pty package ships a glibc linux-arm64 prebuild. Alpine
    # can use gcompat; Ubuntu must not be failed by an unavailable apk command.
    if command -v apk >/dev/null 2>&1; then
      apk add --no-cache gcompat >/dev/null 2>&1 || true
    fi
    if ! node -e "require('${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty')" >/dev/null 2>&1; then
      PTY_ROOT="${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty"
      PTY_VENDOR="${'$'}PTY_ROOT/prebuilds/linux-arm64/pty.node"
      PTY_VENDOR_COPY="${'$'}DSH_HOME/node-pty-linux-arm64.vendor.node"
      if [ -f "${'$'}PTY_VENDOR" ]; then
        cp -f "${'$'}PTY_VENDOR" "${'$'}PTY_VENDOR_COPY"
      fi
      if command -v apk >/dev/null 2>&1; then
        apk add --no-cache build-base python3 linux-headers util-linux-dev >/dev/null
      elif command -v apt-get >/dev/null 2>&1; then
        DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
          build-essential python3 >/dev/null
      else
        printf '%s\n' 'DeepSeek Harness: no supported native build package manager found' >&2
        exit 1
      fi
      npm_config_build_from_source=true npm_config_nodedir= npm rebuild --prefix "${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty" --build-from-source
      # node-gyp may leave an absolute Android data-path symlink. Proot sees
      # the Alpine root instead, so materialize the compiled addon as a regular
      # file before the runtime loads it.
      PTY_BUILD="${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node"
      if [ -L "${'$'}PTY_BUILD" ]; then
        cp -Lf "${'$'}PTY_BUILD" "${'$'}PTY_BUILD.materialized"
        mv -f "${'$'}PTY_BUILD.materialized" "${'$'}PTY_BUILD"
      fi
      if [ ! -f "${'$'}PTY_BUILD" ] && [ -f "${'$'}PTY_VENDOR_COPY" ]; then
        mkdir -p "${'$'}PTY_ROOT/prebuilds/linux-arm64"
        cp -f "${'$'}PTY_VENDOR_COPY" "${'$'}PTY_ROOT/prebuilds/linux-arm64/pty.node"
      fi
    fi
    node -e "require('${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty')" >/dev/null 2>&1
    PROFILE_LAYOUT_MARKER="${'$'}DSH_HOME/.omnibot-profile-reset-v13"
    profile_was_reset=0
    # Profiles created before copy imports were enabled contain PRoot's
    # `.l2s...0001 -> ...0002` hard-link emulation. pnpm reports those files
    # as already installed and will not rewrite them. This DSH_HOME is owned by
    # OmniBot, so rebuild its profile once for this layout revision; the marker
    # preserves later user-installed plugins and commands on normal retries.
    if [ ! -f "${'$'}PROFILE_LAYOUT_MARKER" ]; then
      rm -rf "${'$'}DSH_HOME/profiles"
      profile_was_reset=1
    fi
    configure_dsh_profile_pnpm() {
      profile_root="${'$'}DSH_HOME/profiles/acp"
      [ -f "${'$'}profile_root/pnpm-workspace.yaml" ] || return 0
      (
        cd "${'$'}profile_root"
        pnpm config set --location=project nodeLinker hoisted
        pnpm config set --location=project packageImportMethod copy
      )
    }
    dsh_acp_profile_is_healthy() {
      $DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND &&
        timeout 30 dsh-acp-android --profile acp --dump-config >/dev/null 2>&1
    }
    install_dsh_acp_adapter() {
      registry="${'$'}1"
      export npm_config_registry="${'$'}registry"
      plugin_status=0
      config_status=0
      # DSH forwards plugin arguments to pnpm. Explicitly select the workspace
      # root so this remains valid across supported pnpm versions.
      dsh plugin --profile acp add -w @openma/deepseek-harness-acp@latest || plugin_status="${'$'}?"
      configure_dsh_profile_pnpm || config_status="${'$'}?"
      if [ "${'$'}config_status" -eq 0 ] && dsh_acp_profile_is_healthy; then
        if [ "${'$'}plugin_status" -ne 0 ]; then
          printf '%s\n' \
            "DeepSeek Harness: plugin command exited ${'$'}plugin_status, but the ACP profile passed health checks" >&2
        fi
        return 0
      fi
      return 1
    }
    # Follow the vendor workflow: DSH creates/updates the ACP profile and
    # owns its plugin dependency graph, patch layers, tools, and commands.
    # Preserve the persistent profile and its user plugins. A failed mirror
    # attempt retries only the adapter operation against the official registry.
    configure_dsh_profile_pnpm || true
    if ! install_dsh_acp_adapter "${'$'}NPM_PRIMARY_REGISTRY"; then
      install_dsh_acp_adapter "$DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY"
    fi
    # The ACP profile is persistent Harness state, not session state. Never
    # remove dependencies from it during a reconnect or a normal Agent switch:
    # user-installed DSH plugins, skills and commands must remain available to
    # every later ACP session that uses this same profile. A broken or
    # incompatible plugin must be reported by ACP initialize/health instead of
    # being silently destroyed by the host.
    test -f "${'$'}DSH_HOME/profiles/acp/package.json"
    dsh_acp_profile_is_healthy
    if [ "${'$'}profile_was_reset" -eq 1 ]; then
      : > "${'$'}PROFILE_LAYOUT_MARKER"
    fi
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
            val migratedOfficialCommand =
                definition.id == DEEPSEEK_HARNESS_AGENT_ID &&
                    override.command == "dsh-acp"
            definition.copy(
                command = if (migratedOfficialCommand) definition.command else override.command,
                arguments = if (migratedOfficialCommand) definition.arguments else override.arguments,
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
            ?.takeUnless { it in RETIRED_AGENT_IDS }
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
        const val KIMI_CODE_AGENT_ID = "kimi-code-acp"
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
                id = KIMI_CODE_AGENT_ID,
                name = "Kimi Code",
                description = "Kimi Code through its official ACP interface",
                command = "kimi",
                arguments = listOf("acp"),
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
                id = CODEX_AGENT_ID,
                name = "Codex",
                description = "OpenAI Codex through its managed ACP adapter",
                command = "codex-acp",
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
                description = "DeepSeek Harness official ACP profile",
                command = "dsh-acp-android",
                arguments = listOf("--profile", "acp"),
                builtIn = true
            )
        )
        val CODEX_AGENT = OFFICIAL_AGENTS.first { it.id == CODEX_AGENT_ID }
        private val OFFICIAL_AGENT_IDS = OFFICIAL_AGENTS.mapTo(linkedSetOf()) { it.id }
        private val RETIRED_AGENT_IDS = setOf("gemini-cli-acp")
        private val OFFICIAL_RUNTIMES = mapOf(
            KIMI_CODE_AGENT_ID to AcpOfficialRuntime(
                discoveryCommand = "kimi",
                managedAdapterPackage = KIMI_CODE_NPM_PACKAGE_SPEC,
                managedAdapterHealthCommand = KIMI_CODE_NATIVE_HEALTH_COMMAND,
                harnessAdapter = AcpHarnessAdapters.kimiCode,
                usesSharedProvider = true,
                terminalPackageId = "kimi",
                managedInstallCommand = KIMI_CODE_NPM_INSTALL_COMMAND,
                preparationRevision = "kimi-code-acp-v1",
            ),
            CODEX_AGENT_ID to AcpOfficialRuntime(
                discoveryCommand = "codex",
                managedAdapterPackage = "@openai/codex@latest",
                managedAdapterPackages = listOf(
                    "@openai/codex@latest",
                    "@agentclientprotocol/codex-acp@1.1.7"
                ),
                terminalPackageId = "codex",
                harnessAdapter = AcpHarnessAdapters.codex,
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
                harnessAdapter = AcpHarnessAdapters.claudeCode,
                usesSharedProvider = true,
            ),
            "opencode-acp" to AcpOfficialRuntime(
                discoveryCommand = "opencode",
                managedAdapterPackage = "opencode-ai@latest",
                terminalPackageId = "opencode",
                managedInstallCommand =
                    "npm install -g --no-audit --no-fund opencode-ai@latest && " +
                        "if [ ! -x /root/.npm-global/lib/node_modules/opencode-linux-arm64-musl/bin/opencode ]; then " +
                        "rm -rf /root/.npm-global/lib/node_modules/opencode-linux-arm64-musl && " +
                        "npm install -g --force --no-audit --no-fund --prefer-online " +
                        "opencode-linux-arm64-musl@latest; fi && " +
                        "ln -sf /root/.npm-global/lib/node_modules/opencode-linux-arm64-musl/bin/opencode " +
                        "/root/.npm-global/bin/opencode && " +
                        "test -x /root/.npm-global/bin/opencode",
                harnessAdapter = AcpHarnessAdapters.openCode,
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
                preparationRevision = DEEPSEEK_HARNESS_PREPARATION_REVISION,
                declaredCapabilities = DEEPSEEK_HARNESS_DECLARED_CAPABILITIES,
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
