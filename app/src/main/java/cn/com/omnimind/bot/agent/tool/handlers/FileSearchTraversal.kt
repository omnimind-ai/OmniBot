package cn.com.omnimind.bot.agent.tool.handlers

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Stop traversal itself at the requested limit, and retain the owning turn's cancellation. */
internal suspend fun <T : Any> collectFileSearchMatches(
    files: Sequence<File>,
    maxResults: Int?,
    match: suspend (File) -> T?,
): List<T> {
    val results = mutableListOf<T>()
    val iterator = files.iterator()
    while (maxResults == null || results.size < maxResults) {
        currentCoroutineContext().ensureActive()
        if (!iterator.hasNext()) break
        val file = iterator.next()
        currentCoroutineContext().ensureActive()
        match(file)?.let(results::add)
    }
    currentCoroutineContext().ensureActive()
    return results
}

internal suspend fun listWorkspaceFiles(
    directory: File, recursive: Boolean, maxDepth: Int?, limit: Int?,
): List<File> {
    currentCoroutineContext().ensureActive()
    val entries = if (recursive) {
        val walked = directory.walkTopDown()
        val depthLimited = if (maxDepth != null) walked.maxDepth(maxDepth) else walked
        // The requested limit counts returned entries, not the traversal root.
        depthLimited.drop(1)
    } else {
        (directory.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()).asSequence()
    }
    return collectFileSearchMatches(entries, limit) { it }
}
