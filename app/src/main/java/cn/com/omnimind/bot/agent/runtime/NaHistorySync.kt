package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.io.File
import java.util.UUID

private val naInstallationLock = Any()

/** Device migration/backup must not clone the message namespace onto a second
 * phone. The caller supplies Android's noBackupFilesDir, not preferences. */
internal fun naInstallationOrigin(directory: File): String = synchronized(naInstallationLock) {
    check(directory.isDirectory || directory.mkdirs())
    val file = File(directory, "na-history-client-origin")
    if (file.isFile) return@synchronized UUID.fromString(file.readText().trim()).toString()
    val origin = UUID.randomUUID().toString()
    val temporary = File(directory, ".na-history-origin-$origin")
    temporary.writeText(origin)
    check(temporary.renameTo(file))
    origin
}

private val naHistoryEventTypes = setOf(
    "conversation.updated", "conversation.deleted", "message.created", "item.updated",
    "run.updated", "run.started", "run.completed", "run.failed", "run.cancelled", "files.updated",
)

/** Cache invalidation only. The existing ACP snapshot mapper/reducer owns all
 * messages and turn state, including runs admitted by another phone. Never POSTs.
 * Each connection starts from a fresh atomic list snapshot, so disconnects and
 * compacted delivery logs cannot strand the observer on an obsolete cursor. */
internal suspend fun observeNaHistoryChanges(
    snapshot: suspend () -> Map<String, Any?>,
    subscribe: suspend (Long, (Map<String, Any?>) -> Boolean) -> Unit,
    checkOwner: () -> Unit,
    changed: () -> Unit,
    coalesceMillis: Long = 250,
    retryMillis: Long = 1000,
) {
    var failures = 0
    while (true) {
        currentCoroutineContext().ensureActive(); checkOwner()
        try {
            val initial = snapshot()
            checkOwner(); changed()
            val cursor = (initial["eventCursor"] as? Number)?.toLong()
            // Old servers still synchronize when foregrounded and by bounded
            // observation; they never need a second message/admission protocol.
            if (cursor == null) { delay(3000); continue }
            coroutineScope {
                val ownerContext = currentCoroutineContext()
                val invalidations = Channel<Unit>(Channel.CONFLATED)
                val refresh = launch {
                    for (ignored in invalidations) {
                        delay(coalesceMillis)
                        checkOwner(); snapshot(); checkOwner(); changed()
                    }
                }
                var after: Long = checkNotNull(cursor)
                try {
                    subscribe(after) { event ->
                        ownerContext.ensureActive(); checkOwner()
                        val next = (event["id"] as? Number)?.toLong()
                            ?: throw IOException("Missing Na history cursor")
                        if (next > after) {
                            if (event["type"] in naHistoryEventTypes) invalidations.trySend(Unit)
                            after = next
                            failures = 0
                        }
                        true
                    }
                    throw IOException("Na history connection closed")
                } finally {
                    refresh.cancel()
                    invalidations.close()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive(); checkOwner()
            failures = (failures + 1).coerceAtMost(4)
            delay(retryMillis * failures)
        }
    }
}

/** Device-local ACP ids stay unchanged; server admission keys are namespaced
 * by a random installation id so two phones can send in the same millisecond. */
internal fun naCloudMessageId(origin: String, localId: String): String = "$origin:$localId".also {
    require(it.length <= 128) { "Na message identity is too long" }
}

internal fun naSnapshotTurnId(run: Map<*, *>, origin: String?): String {
    val raw = (run["clientMessageId"] ?: run["id"]).toString()
    return if (naRunIsLocal(run, origin) && raw.startsWith("$origin:"))
        raw.removePrefix("$origin:") else raw
}

internal fun naRunIsLocal(run: Map<*, *>, origin: String?): Boolean = origin != null &&
    (run["clientOriginId"] == origin || (run["clientOriginId"] == null &&
        (run["clientMessageId"] as? String)?.startsWith("$origin:") == true))

internal data class NaConversationListMetadata(val updatedAt: Long, val messageCount: Int, val preview: String?)

internal fun naConversationListMetadata(value: Map<*, *>): NaConversationListMetadata? {
    val at = runCatching { java.time.Instant.parse(value["updatedAt"] as? String).toEpochMilli() }.getOrNull() ?: return null
    val count = (value["messageCount"] as? Number)?.toLong()?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
    return NaConversationListMetadata(at, count.toInt(), (value["lastMessage"] as? String)?.take(1000))
}
