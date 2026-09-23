package cn.com.omnimind.baselib.database

import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Byte offsets match SQLite's BLOB substr; never decode individual UTF-8 chunks. */
internal suspend fun copyConversationEntryText(
    bytes: Long,
    inline: String,
    output: OutputStream,
    readChunk: suspend (Long) -> ByteArray?,
) {
    require(bytes >= 0)
    currentCoroutineContext().ensureActive()
    if (bytes <= 32768) {
        val chunk = inline.toByteArray(Charsets.UTF_8)
        check(chunk.size.toLong() == bytes) { "Conversation entry length changed while reading" }
        output.write(chunk)
        return
    }
    var offset = 1L
    while (offset <= bytes) {
        currentCoroutineContext().ensureActive()
        val chunk = readChunk(offset)
        check(chunk != null && chunk.isNotEmpty()) { "Conversation entry disappeared while reading" }
        check(chunk.size <= 32768 && chunk.size.toLong() <= bytes - offset + 1) {
            "Conversation entry length changed while reading"
        }
        output.write(chunk)
        offset += chunk.size
    }
}
