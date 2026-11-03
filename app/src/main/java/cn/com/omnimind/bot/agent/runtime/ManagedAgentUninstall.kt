package cn.com.omnimind.bot.agent.runtime

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Package maintenance only; the existing ACP runtime still owns all sessions and turns. */
internal enum class ManagedAgentUninstallStrategy(val id: String) {
    CLAUDE_CODE("claude-code"), CODEX("codex"), OPENCODE("opencode"),
    // Kept only for removal of installations created before this Agent was retired.
    DEEPSEEK_HARNESS("deepseek-harness"), PI("pi"), KIMI_CODE("kimi-code");

    companion object {
        fun fromId(id: String): ManagedAgentUninstallStrategy = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported managed uninstall strategy: $id")
    }
}

internal class ManagedAgentUninstallException(
    val failureKind: String,
    message: String,
) : IllegalStateException(message)

internal data class ManagedAgentUninstallReport(
    val ok: Boolean,
    val managedFilesRemain: Boolean,
    val commandPresent: Boolean,
    val externalCommands: List<String> = emptyList(),
    val error: String? = null,
) {
    val removed: Boolean get() = !managedFilesRemain && !commandPresent && externalCommands.isEmpty()
}

internal data class ManagedAgentUninstallOutcome(
    val profile: AcpAgentProfile,
    val report: ManagedAgentUninstallReport?,
    val error: String?,
    val failureKind: String?,
) {
    val ok: Boolean get() = report?.removed == true && report.ok && error == null
}

internal fun managedAgentUninstallCommand(
    source: String,
    strategy: ManagedAgentUninstallStrategy,
    probe: Boolean = false,
): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return "export PATH=/root/.npm-global/bin:${'$'}PATH\nnode -e ${quote(source)} -- ${quote(strategy.id)}" +
        if (probe) " --probe" else ""
}

internal fun parseManagedAgentUninstallReport(output: String): ManagedAgentUninstallReport {
    val marker = "__OMNI_AGENT_UNINSTALL__"
    val row = output.lineSequence().filter { it.startsWith(marker) }.singleOrNull()
        ?: error("Managed uninstall did not report its final disk state.")
    val json = JsonParser.parseString(row.removePrefix(marker)).asJsonObject
    fun boolean(name: String): Boolean {
        val value = json.get(name)
        require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isBoolean) {
            "Invalid managed uninstall result: $name"
        }
        return value.asBoolean
    }
    val external = json.get("externalCommands")
    require(external?.isJsonArray == true) { "Invalid managed uninstall command inventory." }
    val error = json.get("error")?.takeUnless { it.isJsonNull }
    require(error == null || (error.isJsonPrimitive && error.asJsonPrimitive.isString)) {
        "Invalid managed uninstall error."
    }
    return ManagedAgentUninstallReport(
        ok = boolean("ok"),
        managedFilesRemain = boolean("managedFilesRemain"),
        commandPresent = boolean("commandPresent"),
        externalCommands = external.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        },
        error = error?.asString?.takeIf(String::isNotBlank),
    )
}

/** Uses the existing shared installation gate, including the final inventory refresh. */
internal class ManagedAgentUninstaller(
    private val gate: ManagedAcpPreparationGate,
    private val profiles: () -> List<AcpAgentProfile>,
    private val currentDistribution: () -> String,
    private val disconnect: suspend (AcpAgentProfile) -> Unit,
    private val execute: suspend (AcpAgentProfile, String, Boolean) -> ManagedAgentUninstallReport,
    private val refresh: suspend (AcpAgentProfile, String, ManagedAgentUninstallReport?) -> Unit,
) {
    suspend fun uninstall(agentId: String, distributionId: String): ManagedAgentUninstallOutcome {
        val profile = profiles().firstOrNull { it.id == agentId.trim() }
            ?.takeIf { it.builtIn && it.officialRuntime?.managedUninstallStrategy != null }
            ?: throw ManagedAgentUninstallException(
                "agent_uninstall_invalid_request", "This Agent does not support managed uninstallation.",
            )
        requireDistribution(distributionId)
        return gate.run(profile.id) {
            // A queued operation must not target an environment selected after admission.
            requireDistribution(distributionId)
            disconnect(profile)
            var report: ManagedAgentUninstallReport? = null
            var failure: Exception? = null
            try {
                requireDistribution(distributionId)
                report = execute(profile, distributionId, false)
            } catch (error: Exception) {
                failure = error
            } finally {
                // A timed-out command can have removed some files. Probe only; never
                // retry the destructive operation or retain an optimistic installed version.
                withContext(NonCancellable) {
                    if (report == null) {
                        try {
                            report = execute(profile, distributionId, true)
                        } catch (error: Exception) {
                            if (failure == null) failure = error
                        }
                    }
                    try {
                        refresh(profile, distributionId, report)
                    } catch (error: Exception) {
                        if (failure == null) failure = error
                    }
                }
            }
            (failure as? CancellationException)?.let { throw it }
            val changed = currentDistribution() != distributionId
            val error = when {
                changed -> "Terminal environment changed. Refresh Agent settings before retrying."
                failure != null -> failure?.message ?: "Managed uninstall failed."
                report?.error != null -> report?.error
                report?.ok != true || report?.removed != true -> "Managed installation files or commands remain. Retry uninstallation."
                else -> null
            }
            ManagedAgentUninstallOutcome(
                profile = profile,
                report = report,
                error = error,
                failureKind = when {
                    changed -> "agent_uninstall_distribution_changed"
                    failure is ManagedAgentUninstallException -> (failure as ManagedAgentUninstallException).failureKind
                    report?.externalCommands?.isNotEmpty() == true -> "agent_uninstall_external_installation"
                    error != null -> "agent_uninstall_failed"
                    else -> null
                },
            )
        }
    }

    private fun requireDistribution(distributionId: String) {
        if (distributionId.isBlank() || distributionId != currentDistribution()) {
            throw ManagedAgentUninstallException(
                "agent_uninstall_distribution_changed", "Terminal environment changed. Refresh Agent settings before retrying.",
            )
        }
    }
}

internal fun managedAgentHealthAfterUninstall(
    report: ManagedAgentUninstallReport?,
    checkedAt: Long = System.currentTimeMillis(),
): AcpAgentHealth = AcpAgentHealth(
    status = when {
        report == null -> AcpAgentHealth.STATUS_UNCHECKED
        report.removed -> AcpAgentHealth.STATUS_MISSING
        else -> AcpAgentHealth.STATUS_OFFLINE
    },
    installed = report?.let { !it.removed },
    error = report?.error,
    checkedAt = checkedAt,
)

internal fun managedAgentVersionAfterUninstall(
    previous: ManagedAgentVersion?,
    report: ManagedAgentUninstallReport?,
): ManagedAgentVersion = (previous ?: ManagedAgentVersion()).copy(
    installedVersion = null,
    error = if (report?.removed == true) null else "local_probe_failed",
)
