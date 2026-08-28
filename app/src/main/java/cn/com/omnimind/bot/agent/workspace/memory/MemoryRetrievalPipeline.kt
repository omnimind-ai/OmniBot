package cn.com.omnimind.bot.agent.workspace.memory

import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.WorkspaceMemorySearchHit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks which long-term memory ids/slugs the agent has already loaded in the
 * current turn, so we don't re-attach the same content twice (saving tokens
 * and keeping the LLM focused).
 *
 * One instance per agent run.
 */
class TurnMemoryLoadTracker {
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    fun markLoaded(ids: Collection<String>) {
        ids.forEach { id ->
            if (id.isNotBlank()) loaded.add(id)
        }
    }

    fun markLoaded(id: String) {
        if (id.isNotBlank()) loaded.add(id)
    }

    /** Returns true only for the first successful mark of this id. */
    fun markLoadedIfAbsent(id: String): Boolean {
        return id.isNotBlank() && loaded.add(id)
    }

    fun isLoaded(id: String): Boolean = id in loaded

    fun loadedIds(): Set<String> = loaded.toSet()

    fun reset() {
        loaded.clear()
    }
}

class MemoryRetrievalPipeline(
    private val memoryService: WorkspaceMemoryService,
    private val ltmIndex: LongTermMemoryIndex
) {
    /**
     * Fetch top-K relevant workspace memory hits for the user's message.
     * Hard-capped at 1.5 s so it never blocks the LLM stream — on timeout
     * or any failure we silently return an empty list.
     */
    suspend fun prefetchRelevant(
        userMessage: String,
        topK: Int = 4,
        timeoutMillis: Long = 1500
    ): List<WorkspaceMemorySearchHit> {
        val query = userMessage.trim()
        if (query.isEmpty()) return emptyList()
        return try {
            withTimeoutOrNull(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        memoryService.searchMemory(query, topK).hits
                    }.getOrDefault(emptyList())
                }
            } ?: emptyList()
        } catch (_: TimeoutCancellationException) {
            emptyList()
        }
    }

    fun indexSummary(maxEntries: Int = 80, maxCharsPerEntry: Int = 120): String {
        return runCatching {
            ltmIndex.summaryForPrompt(maxEntries, maxCharsPerEntry)
        }.getOrDefault("")
    }
}
