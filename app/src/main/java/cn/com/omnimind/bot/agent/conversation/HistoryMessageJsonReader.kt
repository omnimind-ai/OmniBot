package cn.com.omnimind.bot.agent

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.PushbackReader
import java.io.Reader
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

internal class HistoryDisplayMetadataTooLarge : IllegalStateException("History display identity budget exceeded")

/**
 * Reads a display envelope without materializing large JSON strings or object trees.
 * Only the envelope, content, card and stream metadata are descended into. Other
 * values are framed with bounded storage before Gson sees them. The original JSON
 * remains in Room and in the complete-record attachment.
 */
internal class HistoryMessageJsonReader(source: Reader, private val context: CoroutineContext) {
    private val reader = PushbackReader(source.buffered(), 1)
    private var scanned = 0
    private var retained = 0
    private var identityBytes = 0

    fun read(): JsonObject {
        val value = readObject("", 0)
        check(nextNonWhitespace() == -1) { "Unexpected content after history JSON" }
        return value
    }

    private fun readObject(path: String, depth: Int): JsonObject {
        check(nextNonWhitespace() == '{'.code) { "History message must be an object" }
        val result = JsonObject()
        var next = nextNonWhitespace()
        while (next != '}'.code) {
            check(next == '"'.code) { "Invalid history JSON key" }
            val key = readString(256, preview = false)
            check(nextNonWhitespace() == ':'.code) { "Invalid history JSON field" }
            val first = nextNonWhitespace()
            check(first >= 0) { "Incomplete history JSON" }
            reader.unread(first)
            val identity = key in IDENTITY_FIELDS
            val limit = if (identity) VALUE_CHARS else minOf(VALUE_CHARS, (PAYLOAD_BYTES - retained).coerceAtLeast(0))
            val nestedPath = when {
                path.isEmpty() && key == "content" -> "content"
                path == "content" && key == "cardData" -> "cardData"
                key == "streamMeta" && depth < 3 -> "streamMeta"
                else -> null
            }
            val value: JsonElement? = when {
                nestedPath != null && first == '{'.code -> readObject(nestedPath, depth + 1)
                key in TEXT_FIELDS && first == '"'.code -> {
                    nextChar() // opening quote
                    readString(limit, preview = true)?.let(::JsonPrimitive)
                }
                else -> frame(if (key == null) 0 else limit)?.let(JsonParser::parseString)
            }
            if (identity && value == null) throw HistoryDisplayMetadataTooLarge()
            if (key != null && value != null) {
                val cost = JsonPrimitive(key).toString().toByteArray(Charsets.UTF_8).size + 16 +
                    if (nestedPath == null) value.toString().toByteArray(Charsets.UTF_8).size else 0
                // Nested fields have already paid for their own contents. Identities
                // have a separate, bounded allowance so a large body cannot hide a
                // later session/turn/item id or a historical card's state.
                var keep = true
                if (identity) {
                    identityBytes += cost
                    if (identityBytes > IDENTITY_BYTES) throw HistoryDisplayMetadataTooLarge()
                } else if (nestedPath == null && retained + cost > PAYLOAD_BYTES) {
                    keep = false
                } else {
                    retained += cost
                }
                if (keep) result.add(key, value)
            }
            next = nextNonWhitespace()
            check(next == ','.code || next == '}'.code) { "Invalid history JSON separator" }
            if (next == ','.code) {
                next = nextNonWhitespace()
                check(next != '}'.code) { "Invalid history JSON trailing comma" }
            }
        }
        return result
    }

    /** Opening quote has been consumed. Decode incrementally, including escapes. */
    private fun readString(limit: Int, preview: Boolean): String? {
        val text = StringBuilder(minOf(limit, 1024))
        var oversized = false
        while (true) {
            val c = nextChar()
            check(c >= 0) { "Incomplete history JSON string" }
            if (c == '"'.code) break
            val decoded = if (c == '\\'.code) {
                when (val escape = nextChar()) {
                    '"'.code, '\\'.code, '/'.code -> escape.toChar()
                    'b'.code -> '\b'
                    'f'.code -> '\u000c'
                    'n'.code -> '\n'
                    'r'.code -> '\r'
                    't'.code -> '\t'
                    'u'.code -> {
                        var code = 0
                        repeat(4) {
                            val digit = nextChar().takeIf { it >= 0 }?.toChar()?.digitToIntOrNull(16)
                            check(digit != null) { "Invalid history JSON escape" }
                            code = (code shl 4) or digit
                        }
                        code.toChar()
                    }
                    else -> error("Invalid history JSON escape")
                }
            } else {
                check(c >= 0x20) { "Invalid history JSON string" }
                c.toChar()
            }
            if (text.length < limit) text.append(decoded) else oversized = true
        }
        if (!oversized) return text.toString()
        if (!preview || limit < NOTICE.length) return null
        val prefix = text.substring(0, limit - NOTICE.length)
        return AgentTextSanitizer.sanitizeUtf16(prefix) + NOTICE
    }

    private fun frame(limit: Int): String? {
        val text = StringBuilder(minOf(limit, 1024))
        var oversized = false
        var quoted = false
        var escaped = false
        var depth = 0
        while (true) {
            val c = nextChar()
            check(c >= 0) { "Incomplete history JSON" }
            val char = c.toChar()
            if (!quoted && depth == 0 && (char == ',' || char == '}')) {
                reader.unread(c)
                break
            }
            if (text.length < limit) text.append(char) else oversized = true
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 32) oversized = true }
                    '}', ']' -> { depth--; check(depth >= 0) { "Invalid history JSON" } }
                }
            }
        }
        return text.toString().takeUnless { oversized }
    }

    private fun nextChar(): Int {
        if (++scanned % 8192 == 0) context.ensureActive()
        return reader.read()
    }

    private fun nextNonWhitespace(): Int {
        var c = nextChar()
        while (c >= 0 && c.toChar().isWhitespace()) c = nextChar()
        return c
    }

    companion object {
        private const val VALUE_CHARS = 8192
        private const val PAYLOAD_BYTES = 64 * 1024
        private const val IDENTITY_BYTES = 32 * 1024
        private const val NOTICE = "\n…（完整内容见附件）"
        private val TEXT_FIELDS = setOf("text", "thinkingContent", "reasoning_content", "reasoningContent", "summary")
        private val IDENTITY_FIELDS = setOf(
            "id", "dbId", "type", "user", "agentId", "toolCallId", "sessionId", "turnId", "taskId", "taskID",
            "cardId", "entryId", "parentTaskId", "kind", "seq", "entrySeq", "roundIndex", "status", "stage",
            "isLoading", "isError", "isSummarizing", "interruptedTurn", "createAt", "startTime", "endTime",
        )
    }
}
