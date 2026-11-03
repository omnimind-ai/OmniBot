package cn.com.omnimind.bot.agent.runtime

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import java.io.IOException

private val naTerminalStates = setOf("completed", "cancelled", "failed")
private const val MAX_EVENT_BYTES = 8L * 1024 * 1024

/** Reads existing Na SSE frames. A cursor is acknowledged only after projection;
 * comments/heartbeats never create messages or complete the prompt. */
internal fun readNaEvents(source: BufferedSource, onEvent: (Map<String, Any?>) -> Boolean) {
    val gson = Gson()
    val type = object : TypeToken<Map<String, Any?>>() {}.type
    var eventType = ""
    val data = StringBuilder()
    var bytes = 0L
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(MAX_EVENT_BYTES)
        bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > MAX_EVENT_BYTES) throw IOException("Na event exceeds the size limit")
        if (line.isEmpty()) {
            if (eventType == "na" && data.isNotEmpty()) {
                val value = try { gson.fromJson<Map<String, Any?>>(data.toString(), type) }
                    catch (error: Exception) { throw IOException("Invalid Na event", error) }
                if (value == null) throw IOException("Empty Na event")
                if (!onEvent(value)) return
            }
            eventType = ""; data.setLength(0); bytes = 0
        } else if (!line.startsWith(":")) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> eventType = value
                "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
            }
        }
    }
    throw IOException("Na event connection closed")
}

/** Observe one already-admitted run. Recovery only reads snapshots and events;
 * it has no way to submit a prompt or manufacture a terminal lifecycle. */
internal suspend fun awaitNaRun(
    runId: String,
    snapshot: suspend () -> Map<String, Any?>,
    subscribe: suspend (Long, (Map<String, Any?>) -> Boolean) -> Unit,
    checkOwner: () -> Unit,
    onItem: (Map<String, Any?>) -> Unit,
): Map<String, Any?> {
    var failures = 0
    fun project(run: Map<String, Any?>) {
        checkOwner()
        (run["items"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach {
            onItem(it.entries.associate { entry -> entry.key.toString() to entry.value })
        }
    }
    while (true) {
        currentCoroutineContext().ensureActive(); checkOwner()
        val response = snapshot()
        @Suppress("UNCHECKED_CAST")
        val run = response["run"] as? Map<String, Any?> ?: response
        project(run)
        if (run["status"] in naTerminalStates) return run
        val snapshotCursor = (response["eventCursor"] as? Number)?.toLong()
        // Compatibility with a server that predates atomic snapshot cursors.
        if (snapshotCursor == null) { delay(600); continue }
        var cursor: Long = snapshotCursor
        var result: Map<String, Any?>? = null
        val context = currentCoroutineContext()
        try {
            subscribe(cursor) { event ->
                context.ensureActive(); checkOwner()
                val next = (event["id"] as? Number)?.toLong() ?: throw IOException("Missing Na cursor")
                if (next <= cursor) return@subscribe true
                if (event["runId"] == runId) {
                    @Suppress("UNCHECKED_CAST")
                    val data = event["data"] as? Map<String, Any?> ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val item = data["item"] as? Map<String, Any?>
                    if (event["type"] == "item.updated" && item != null) onItem(item)
                    @Suppress("UNCHECKED_CAST")
                    val updatedRun = data["run"] as? Map<String, Any?>
                    if (updatedRun != null && updatedRun["status"] in naTerminalStates) {
                        project(updatedRun); result = updatedRun
                    }
                }
                cursor = next; failures = 0
                result == null
            }
            result?.let { return it }
            throw IOException("Na event connection ended before completion")
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (++failures >= 3) throw error
            delay(300L * failures)
            // A fresh atomic snapshot also handles compacted SSE history (409).
        }
    }
}
