package cn.com.omnimind.bot.agent.tool.handlers

import java.io.Reader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Scan every character as needed, retaining only match overlap and the returned excerpt. */
internal suspend fun findFileContentSnippet(reader: Reader, query: String, caseSensitive: Boolean): String? {
    require(query.isNotEmpty())
    // Search is line-local, like the original file_search contract.
    if ('\n' in query || '\r' in query) return null
    val context = currentCoroutineContext()
    val buffer = CharArray(8192)
    val window = StringBuilder()
    var match = -1
    fun excerpt(): String = window.substring(
        (match - 40).coerceAtLeast(0),
        (match + query.length + 120).coerceAtMost(window.length),
    )
    while (true) {
        context.ensureActive()
        val count = reader.read(buffer)
        context.ensureActive()
        if (count < 0) return if (match >= 0) excerpt() else null
        var start = 0
        for (end in 0..count) {
            val lineEnd = end < count && (buffer[end] == '\n' || buffer[end] == '\r')
            if (end != count && !lineEnd) continue
            window.append(buffer, start, end - start)
            if (match < 0) match = window.indexOf(query, ignoreCase = !caseSensitive)
            if (match >= 0 && (lineEnd || window.length - match - query.length >= 120)) {
                return excerpt()
            }
            if (lineEnd) {
                window.setLength(0)
                match = -1
            } else if (match < 0) {
                // Enough preceding context for a match that straddles the next read.
                val keep = query.length - 1 + 40
                if (window.length > keep) window.delete(0, window.length - keep)
            }
            start = end + 1
        }
    }
}
