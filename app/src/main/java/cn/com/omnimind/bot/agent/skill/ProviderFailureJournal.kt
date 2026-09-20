package cn.com.omnimind.bot.agent

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Diagnostic evidence only: never owns, retries, or completes an ACP turn. */
internal object ProviderFailureJournal {
    private val lock = Any()
    private val gson = Gson()

    fun record(context: Context, error: Throwable) {
        if (error is CancellationException) return
        runCatching { record(AgentWorkspaceManager(context).skillsRoot(), error) }
    }

    fun record(skillsRoot: File, error: Throwable) {
        if (error is CancellationException) return
        val kind = AgentRuntimeErrorSupport.failureKind(error) ?: return
        if (!kind.startsWith("provider_")) return
        // Store only an allowlisted category and remediation. Provider bodies,
        // prompts, URLs and credentials must never enter a durable lesson.
        synchronized(lock) {
            val directory = File(skillsRoot, "self-improving-agent/data").apply { mkdirs() }
            val file = File(directory, "provider-diagnostic.json")
            val value = linkedMapOf(
                "schemaVersion" to 1,
                "observedAtMs" to System.currentTimeMillis(),
                "failureKind" to kind,
                "status" to "needs_verification",
                "guidance" to AgentRuntimeErrorSupport.userFacingMessage(error),
            )
            // Keep a bounded evidence trail; a later outage must not erase the
            // earlier diagnosis. This does not change any Provider or ACP state.
            val history = File(directory, "provider-diagnostics").apply { mkdirs() }
            val entry = File(history, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
            entry.writeText(gson.toJson(value))
            history.listFiles().orEmpty()
                .filter { it.isFile && it.name.matches(Regex("[0-9]+-[0-9a-f-]{36}\\.json")) }
                .sortedByDescending { it.name }
                .drop(20)
                .forEach { it.delete() }
            val temporary = File(directory, "provider-diagnostic.json.tmp")
            temporary.writeText(gson.toJson(value))
            check(temporary.renameTo(file)) { "Cannot persist provider diagnostic" }
        }
    }
}
