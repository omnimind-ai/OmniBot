package cn.com.omnimind.bot.agent

import java.io.File
import java.io.PushbackReader
import java.io.Reader

/** File tool text pages; file size and the original artifact remain unrestricted. */
internal object AgentFileReadSupport {
    const val PAGE_CHARS = 64 * 1024

    data class TextPage(
        val content: String,
        val offset: Long,
        val nextOffset: Long?,
        val outputTruncated: Boolean,
    ) {
        fun toPayload(): Map<String, Any?> = linkedMapOf(
            "kind" to "text", "content" to content, "offset" to offset,
            "returnedChars" to content.length, "hasMore" to (nextOffset != null),
            "nextOffset" to nextOffset, "outputTruncated" to outputTruncated,
        )
    }

    fun isBinary(file: File, mimeType: String): Boolean {
        val mime = mimeType.substringBefore(';').lowercase()
        if (mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime == "application/pdf" || mime == "application/zip" ||
            mime.contains("officedocument") || mime.startsWith("application/vnd.ms-") ||
            mime in setOf("application/msword", "application/gzip", "application/x-7z-compressed",
                "application/x-rar-compressed", "application/x-tar")) return true
        val prefix = file.inputStream().use { input ->
            val bytes = ByteArray(8192)
            val count = input.read(bytes)
            bytes.copyOf(count.coerceAtLeast(0))
        }
        if (hasUtf16Bom(prefix)) return false
        return prefix.any { byte ->
            val value = byte.toInt() and 255
            value == 0 || value in 1..8 || value in 14..31
        }
    }

    fun read(file: File, offset: Long = 0, lineStart: Int? = null, lineCount: Int? = null,
        maxChars: Int = PAGE_CHARS): TextPage {
        require(maxChars in 2..PAGE_CHARS) { "maxChars must be between 2 and $PAGE_CHARS" }
        val input = file.inputStream().buffered()
        input.mark(2)
        val first = input.read()
        val second = input.read()
        val charset = when {
            first == 0xff && second == 0xfe -> Charsets.UTF_16LE
            first == 0xfe && second == 0xff -> Charsets.UTF_16BE
            else -> { input.reset(); Charsets.UTF_8 }
        }
        return input.reader(charset).use { readPage(it, offset, lineStart, lineCount, maxChars) }
    }

    private fun hasUtf16Bom(bytes: ByteArray): Boolean = bytes.size >= 2 &&
        ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
            (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))

    internal fun readPage(
        source: Reader, offset: Long = 0, lineStart: Int? = null,
        lineCount: Int? = null, maxChars: Int = PAGE_CHARS,
    ): TextPage {
        require(maxChars >= 2)
        val reader = PushbackReader(source.buffered(), 2)
        var position = 0L
        fun next(): Int = reader.read().also { if (it >= 0) position++ }
        fun unread(value: Int) { if (value >= 0) { reader.unread(value); position-- } }
        fun peek(): Int = next().also(::unread)
        if (lineStart != null) {
            var line = 1L
            while (line < lineStart.coerceAtLeast(1).toLong()) {
                when (next()) {
                    -1 -> break
                    '\n'.code -> line++
                    '\r'.code -> { if (peek() == '\n'.code) next(); line++ }
                }
            }
        } else {
            var remaining = offset.coerceAtLeast(0)
            while (remaining > 0) {
                val skipped = reader.skip(remaining)
                position += skipped
                remaining -= skipped
                if (skipped == 0L) {
                    if (next() < 0) break
                    remaining--
                }
            }
        }
        val start = position
        val text = StringBuilder(maxChars)
        var lines = 0
        var previousWasCr = false
        while (text.length < maxChars) {
            val value = next()
            if (value < 0) break
            val character = value.toChar()
            text.append(character)
            if (character == '\r' || (character == '\n' && !previousWasCr)) lines++
            previousWasCr = character == '\r'
            if (lineCount != null && lines >= lineCount.coerceAtLeast(1)) {
                if (character == '\r' && text.length < maxChars && peek() == '\n'.code) {
                    text.append(next().toChar())
                }
                break
            }
        }
        // Never split a supplementary Unicode character between continuation pages.
        if (text.isNotEmpty() && text.last().isHighSurrogate() && peek().toChar().isLowSurrogate()) {
            unread(text.last().code)
            text.setLength(text.length - 1)
        }
        val hasMore = peek() >= 0
        return TextPage(text.toString(), start, position.takeIf { hasMore },
            hasMore && (lineCount == null || lines < lineCount.coerceAtLeast(1)))
    }
}
