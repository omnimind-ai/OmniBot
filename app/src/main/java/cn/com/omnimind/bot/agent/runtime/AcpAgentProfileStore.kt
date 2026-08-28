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
internal const val DEEPSEEK_HARNESS_PREPARATION_REVISION =
    "deepseek-dsh-pnpm-copy-v7"
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
internal const val DEEPSEEK_HARNESS_NATIVE_HEALTH_COMMAND =
        "DSH_HOME=/root/.dsh/omnibot-acp " +
        "PATH=/root/.npm-global/bin:${'$'}PATH " +
        "command -v dsh >/dev/null 2>&1 && " +
        "command -v dsh-acp-android >/dev/null 2>&1 && " +
        "command -v pnpm >/dev/null 2>&1 && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/package.json && " +
        "test -f /root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/package.json && " +
        "test -f /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/lib/utils.js && " +
        // PRoot's link2symlink emulation can leave package files pointing at
        // pnpm's extensionless content-addressed store. A direct adapter
        // import may still pass while DSH's loader rejects that target during
        // initialize, so fail health before the user attempts a prompt.
        "if find /root/.dsh/omnibot-acp/profiles/acp/node_modules " +
        "/root/.dsh/omnibot-acp/profiles/node_modules " +
        "/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules " +
        "/root/.npm-global/lib/node_modules/@openma " +
        "-type l -print 2>/dev/null | while IFS= read -r link; do " +
        "target=\$(readlink \"\$link\"); case \"\$target\" in " +
        "*/.local/share/pnpm/store/*|*/workspace/*|/workspace/*) exit 1;; esac; done; then :; else exit 1; fi && " +
        // Do not invoke `dsh --help`/`--dump-config` from a health probe:
        // both bootstrap the vendor ACP process under Android proot and can
        // fail merely because the hidden probe has no /proc/self/fd handles.
        // The profile graph and adapter package are the non-invasive health
        // boundary; the real dsh ACP launch is validated by initialize.
        "cd /root/.dsh/omnibot-acp/profiles/acp && " +
        "node --input-type=module -e \"import fs from 'node:fs'; JSON.parse(fs.readFileSync('package.json')); await import('@openma/deepseek-harness-acp/plugin'); await import('@openma/deepseek-harness-acp/stdio');\" >/dev/null 2>&1"
internal val DEEPSEEK_HARNESS_NPM_INSTALL_COMMAND = """
    set -eu
    export PATH="/root/.npm-global/bin:${'$'}PATH"
    export DSH_HOME="/root/.dsh/omnibot-acp"
    # Android proot turns pnpm hard-links into symlinks to extensionless store
    # blobs. Node then refuses the official ACP loader entries (for example
    # `.0002`) before the Harness can answer initialize. Keep the vendor DSH
    # workflow, but request copied, hoisted dependencies at install time.
    export npm_config_node_linker=hoisted
    export npm_config_package_import_method=copy
    DSH_PACKAGE_ROOT="/root/.npm-global/lib/node_modules/@deepseek-ai/dsh"
    mkdir -p "${'$'}DSH_HOME"
    npm config set prefix /root/.npm-global
    # Repair an already-installed Android pnpm tree before checking package
    # completeness. Otherwise `require.resolve()` follows the broken .0001
    # link chain, declares the whole DSH package incomplete, and starts a
    # needless network reinstall on every Harness switch.
    materialize_pnpm_link() {
      link="${'$'}1"
      target="${'$'}(readlink "${'$'}link" 2>/dev/null || true)"
      case "${'$'}target" in
        */.local/share/pnpm/store/*)
          target_name="${'$'}{target##*/}"
          target_dir="${'$'}{target%/*}"
          store_bucket="${'$'}{target_dir##*/}"
          store_prefix="/root/.local/share/pnpm/store/v11/files/${'$'}{store_bucket}/${'$'}target_name"
          # The suffix is not always `.0002`; Android's link2symlink layer
          # allocates `.0001`, `.0002`, `.0007`, etc. Pick the first regular
          # content file and never follow another `.l2s` symlink.
          for store_file in "${'$'}store_prefix".*; do
            if [ -f "${'$'}store_file" ] && [ ! -L "${'$'}store_file" ]; then
              cp -f "${'$'}store_file" "${'$'}link.materialized" 2>/dev/null || return 1
              rm -f "${'$'}link"
              mv -f "${'$'}link.materialized" "${'$'}link" 2>/dev/null || return 1
              break
            fi
          done
          ;;
        */workspace/*|/workspace/*)
          # Local plugins added with `dsh plugin ... add /workspace/<pkg>` are
          # represented by pnpm as links back to the mounted workspace. The
          # Cordis loader imports from the real plugin path, so Node walks out
          # of the profile tree and cannot see peer dependencies such as
          # @deepseek-ai/dsh-tools. Materialize the link inside the profile
          # tree while keeping the vendor DSH workflow intact.
          workspace_rel="${'$'}{target#*workspace/}"
          workspace_source="/workspace/${'$'}workspace_rel"
          if [ -e "${'$'}workspace_source" ]; then
            if [ -d "${'$'}workspace_source" ]; then
              cp -R "${'$'}workspace_source" "${'$'}link.materialized" 2>/dev/null || return 1
            else
              cp -f "${'$'}workspace_source" "${'$'}link.materialized" 2>/dev/null || return 1
            fi
            rm -f "${'$'}link"
            mv -f "${'$'}link.materialized" "${'$'}link" 2>/dev/null || return 1
          fi
          ;;
        *)
          return 0
          ;;
      esac
    }
    materialize_pnpm_tree() {
      node_root="${'$'}1"
      [ -d "${'$'}node_root" ] || return 0
      find "${'$'}node_root" -type l 2>/dev/null |
        while IFS= read -r link; do
          materialize_pnpm_link "${'$'}link" || exit 1
        done
    }
    materialize_pnpm_tree "${'$'}DSH_PACKAGE_ROOT/node_modules"
    materialize_pnpm_tree "/root/.npm-global/lib/node_modules/@openma"
    materialize_pnpm_tree "${'$'}DSH_HOME/profiles/node_modules"
    materialize_pnpm_tree "${'$'}DSH_HOME/profiles/acp/node_modules"
    if ! command -v pnpm >/dev/null 2>&1; then
      npm install -g --no-audit --no-fund pnpm@$DEEPSEEK_HARNESS_PNPM_VERSION
    fi
    # A previous Android npm run may leave a seemingly installed package with
    # an incomplete dependency tree. Do not use a deep `require.resolve()` here:
    # pnpm's Android link emulation makes that check follow the broken store
    # chain before our profile health/materialization pass can repair it.
    # The authoritative import check runs at the end of this script.
    if [ ! -f "${'$'}DSH_PACKAGE_ROOT/package.json" ] || \
        [ ! -f "${'$'}DSH_PACKAGE_ROOT/lib/bin.js" ] || \
        { [ ! -f "${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty/prebuilds/linux-arm64/pty.node" ] && \
          [ ! -f "${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty/build/Release/pty.node" ]; } || \
        [ ! -d "${'$'}DSH_PACKAGE_ROOT/node_modules" ]; then
      npm cache clean --force >/dev/null 2>&1 || true
      rm -rf "${'$'}DSH_PACKAGE_ROOT" \
        /root/.npm-global/lib/node_modules/@deepseek-ai/.dsh-* 2>/dev/null || true
      npm install -g --no-audit --no-fund --prefer-offline \
        --fetch-retries=5 --fetch-retry-factor=2 \
        --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=15000 \
        --fetch-timeout=120000 --loglevel=notice \
        --registry="${'$'}{OMNIBOT_NPM_REGISTRY:-$DEEPSEEK_HARNESS_NPM_PRIMARY_REGISTRY}" \
        @deepseek-ai/dsh@$DEEPSEEK_HARNESS_NPM_CHANNEL
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
    printf '%s\n' '#!/bin/sh' \
      'exec node --expose-internals /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js "${'$'}{@}"' \
      > /root/.npm-global/bin/dsh-acp-android
    chmod 755 /root/.npm-global/bin/dsh-acp-android
    test -x /root/.npm-global/bin/dsh-acp-android
    # The official node-pty package ships a glibc linux-arm64 prebuild. Try
    # Alpine's glibc compatibility layer first; if it is still not loadable,
    # preserve that vendor binary and rebuild the dependency from its upstream
    # sources inside the target runtime. This keeps DSH's official CLI/plugin
    # workflow while adapting only the platform-native PTY dependency.
    apk add --no-cache gcompat >/dev/null 2>&1 || true
    if ! node -e "require('${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty')" >/dev/null 2>&1; then
      PTY_ROOT="${'$'}DSH_PACKAGE_ROOT/node_modules/node-pty"
      PTY_VENDOR="${'$'}PTY_ROOT/prebuilds/linux-arm64/pty.node"
      PTY_VENDOR_COPY="${'$'}DSH_HOME/node-pty-linux-arm64.vendor.node"
      if [ -f "${'$'}PTY_VENDOR" ]; then
        cp -f "${'$'}PTY_VENDOR" "${'$'}PTY_VENDOR_COPY"
      fi
      apk add --no-cache build-base python3 linux-headers util-linux-dev >/dev/null
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
    # Follow the vendor workflow: DSH creates/updates the ACP profile and
    # owns its plugin dependency graph, patch layers, tools, and commands.
    export npm_config_registry="${'$'}{OMNIBOT_NPM_REGISTRY:-$DEEPSEEK_HARNESS_NPM_PRIMARY_REGISTRY}"
    if ! dsh plugin --profile acp add @openma/deepseek-harness-acp@latest; then
      export npm_config_registry="$DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY"
      npm install -g --no-audit --no-fund --prefer-offline \
        --fetch-retries=5 --fetch-retry-factor=2 \
        --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=15000 \
        --fetch-timeout=120000 --loglevel=notice \
        --registry="$DEEPSEEK_HARNESS_NPM_FALLBACK_REGISTRY" \
        @deepseek-ai/dsh@$DEEPSEEK_HARNESS_NPM_CHANNEL
      dsh plugin --profile acp add @openma/deepseek-harness-acp@latest
    fi
    # The ACP profile is a managed, headless composition. A previous Web UI
    # or developer session may have left web-only/plugin-demo bundles in this
    # same profile (for example studio, appshot, or a local hello plugin).
    # Those rows wait for services such as webServer and make ACP initialize
    # fail before it can answer session/new. Reconcile the profile through
    # DSH's official plugin command and retain only the ACP bundle; user
    # extensions can still live in their own explicitly selected DSH profile.
    prune_acp_profile_plugins() {
      cd "${'$'}DSH_HOME/profiles/acp"
      node --input-type=module -e '
        import fs from "node:fs";
        const packageJson = JSON.parse(fs.readFileSync("package.json", "utf8"));
        for (const name of Object.keys(packageJson.dependencies ?? {})) {
          if (name !== "@openma/deepseek-harness-acp") console.log(name);
        }
      ' | while IFS= read -r package_name; do
        [ -n "${'$'}package_name" ] || continue
        dsh plugin --profile acp remove "${'$'}package_name"
      done
    }
    prune_acp_profile_plugins
    # Android proot's link2symlink layer can turn pnpm's hard links inside the
    # official profile into a two-hop absolute link chain:
    # module.js -> .../.l2s.<hash>0001 -> .../.l2s.<hash>0001.0002
    # Resolve the final pnpm store file explicitly; cp -L is unreliable here
    # because the first hop points outside the mounted proot root.
    for node_root in \
      "${'$'}DSH_HOME/profiles/acp/node_modules" \
      "${'$'}DSH_HOME/profiles/node_modules" \
      "${'$'}DSH_PACKAGE_ROOT/node_modules" \
      "/root/.npm-global/lib/node_modules/@openma"; do
      [ -d "${'$'}node_root" ] || continue
      find "${'$'}node_root" -type l 2>/dev/null |
        while IFS= read -r link; do
          materialize_pnpm_link "${'$'}link" || exit 1
        done
    done
    test -f "${'$'}DSH_HOME/profiles/acp/package.json"
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
