package cn.com.omnimind.bot.agent

import kotlinx.coroutines.CancellationException

/** User history-management commands, using the existing ACP owner for session operations. */
internal class ConversationUiActions(
    private val preferences: ChatConversationPreferences,
    private val acp: suspend (String, Map<String, Any?>) -> Unit,
    private val get: suspend (Long) -> Map<String, Any?>?,
    private val archive: suspend (Long, Boolean) -> Unit,
    private val delete: suspend (Long) -> Unit,
    private val rename: suspend (Long, String) -> Unit,
) {
    private suspend fun attempt(operation: suspend () -> Unit): Boolean = try {
        operation()
        true
    } catch (error: CancellationException) { throw error
    } catch (_: Exception) { false }

    suspend fun execute(action: String, id: Long, mode: String?, title: String = ""): Boolean {
        require(id != 0L)
        val current = get(id) ?: return false
        val storedMode = current["mode"]?.toString()?.trim()?.lowercase() ?: mode
        val agent = storedMode in setOf("agent", "normal", "codex", "acp", "coding")
        val owner = current["agentId"]?.toString()?.trim()?.takeIf(String::isNotEmpty)
        // History can be managed while its runtime is unavailable or its
        // legacy owner is unknown. Never send that operation to a global Agent.
        val arguments = mapOf("conversationId" to id, "agentId" to owner)
        val remote = if (agent && owner != null) when (action) {
            "delete", "archive" -> attempt { acp("session/archive", arguments) }
            "unarchive" -> attempt { acp("session/unarchive", arguments) }
            "rename" -> attempt { acp("session/name/set", arguments + ("name" to title)) }
            else -> false
        } else false
        val local = when (action) {
            "delete" -> if (agent) attempt {
                if (current["isArchived"] != true) archive(id, true)
            } else attempt { delete(id) }
            "archive" -> attempt { archive(id, true) }
            "unarchive" -> attempt { archive(id, false) }
            "rename" -> attempt { rename(id, title) }
            else -> throw IllegalArgumentException("Unknown conversation action: $action")
        }
        if (!local && !remote) return false
        if (action == "delete" && agent) preferences.hide(id)
        if (action == "delete" || action == "archive" && agent) preferences.clearReferences(id, if (agent) "agent" else storedMode)
        return true
    }
}

/** Filter hidden compatibility rows before applying visible offsets; hydrate bindings only for the result page. */
internal suspend fun <T> visibleConversationPage(
    offset: Int,
    limit: Int,
    hasHiddenRows: Boolean,
    visible: (T) -> Boolean,
    readPage: suspend (Int, Int) -> List<T>,
): List<T> {
    if (limit <= 0) return emptyList()
    val start = offset.coerceAtLeast(0)
    if (!hasHiddenRows) return readPage(start, limit)
    val rows = mutableListOf<T>()
    var consumed = 0
    var skipped = 0
    val batch = limit.coerceIn(64, 512)
    while (rows.size < limit) {
        val page = readPage(consumed, batch)
        for (row in page) {
            if (!visible(row)) continue
            if (skipped < start) { skipped++; continue }
            rows.add(row)
            if (rows.size == limit) break
        }
        consumed += page.size
        if (page.size < batch) break
    }
    return rows
}
