package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.OpenAiWireApi
import kotlinx.serialization.json.Json
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
    /** Official config accepts a local HTTP Responses endpoint and bearer grant. */
    val supportsHostManagedResponses: Boolean
        get() = false
    /** Official adapter metadata extensions; no additional session lifecycle. */
    fun clientCapabilityMeta(base: JsonObject): JsonObject = base

    /** Failure declared by the adapter on this owning ACP PromptResponse. */
    fun promptFailure(meta: JsonObject?): String? = null

    fun normalizeStdioLine(line: String): String = line

    /** Resolve a Provider model only against values advertised by this session. */
    fun resolveModelValue(model: String, available: List<String>): String? =
        model.takeIf { it in available }

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
        override val supportsHostManagedResponses = true

        // codex-acp 1.10.0's unstable plan extension calls the identity
        // planId; acp-kotlin 0.30.1 requires id. Normalize only this wire
        // boundary, before SDK decoding; retain the original plan identity.
        override fun normalizeStdioLine(line: String): String = normalizeCodexPlanIdentity(line)

        // codex-acp 1.10.0 AirExtension / CodexEventHandler: without this
        // negotiated capability it emits transport errors as assistant text.
        override fun clientCapabilityMeta(base: JsonObject): JsonObject = buildJsonObject {
            base.forEach { (key, value) -> put(key, value) }
            put("jetbrains", buildJsonObject {
                put("air", buildJsonObject {
                    put("version", 1)
                    put("capabilities", buildJsonArray { add(JsonPrimitive("sessionFailure")) })
                })
            })
        }

        override fun promptFailure(meta: JsonObject?): String? {
            val jetbrains = meta?.get("jetbrains") as? JsonObject ?: return null
            val air = jetbrains["air"] as? JsonObject ?: return null
            val version = (air["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: return null
            if (version < 1) return null
            val failure = air["sessionFailure"] as? JsonObject ?: return null
            if ((failure["severity"] as? JsonPrimitive)?.contentOrNull != "error") return null
            return (failure["title"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "Assistant request failed."
        }

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

        // Claude's terminal_output extension sends Bash stdout in _meta, not
        // standard tool content. We do not render that extension. Let the
        // official adapter emit its standard text fallback instead; the ACP
        // terminal capability and host terminal execution remain available.
        override fun clientCapabilityMeta(base: JsonObject): JsonObject =
            JsonObject(base - "terminal_output")
    }

    val kimiCode: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "kimi-code"
        override val supportsHostManagedResponses = true
    }

    val pi: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "pi"
        override val supportsHostManagedResponses = true
    }

    val openCode: AcpHarnessAdapter = object : AcpHarnessAdapter {
        override val configAdapterId = "open-code"
        override val supportsHostManagedResponses = true
        override fun resolveModelValue(model: String, available: List<String>): String? =
            super.resolveModelValue(model, available)
                ?: "omnibot/$model".takeIf { it in available }
    }

    /** Resolve a declarative catalog converter without coupling the runtime
     * lifecycle to a concrete Agent id. Unknown entries use the standard ACP
     * transport and can still participate in session/new and session/prompt. */
    fun forConfigAdapterId(id: String?): AcpHarnessAdapter = when (id) {
        "codex" -> codex
        "claude-code" -> claudeCode
        "kimi-code" -> kimiCode
        "pi" -> pi
        "open-code" -> openCode
        else -> standard
    }

    fun forProfile(profile: AcpAgentProfile): AcpHarnessAdapter =
        AcpAgentProfileStore.officialRuntime(profile)?.harnessAdapter ?: standard
}

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

private fun normalizeCodexPlanIdentity(line: String): String {
    if (!line.contains("planId")) return line
    val root = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        ?: return line
    if ((root["method"] as? JsonPrimitive)?.contentOrNull != "session/update") return line
    val params = root["params"] as? JsonObject ?: return line
    val update = params["update"] as? JsonObject ?: return line
    val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNull
    val target = when (kind) {
        "plan_update" -> update["plan"] as? JsonObject ?: return line
        "plan_removed" -> update
        else -> return line
    }
    if (target["id"] != null) return line
    val id = target["planId"] as? JsonPrimitive ?: return line
    if (!id.isString) return line
    val normalized = JsonObject(target + ("id" to id))
    val normalizedUpdate = if (kind == "plan_update") {
        JsonObject(update + ("plan" to normalized))
    } else normalized
    return JsonObject(root + ("params" to JsonObject(params + ("update" to normalizedUpdate)))).toString()
}
