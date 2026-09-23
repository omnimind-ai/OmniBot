package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File
import java.io.OutputStream
import java.io.PushbackReader
import java.io.Reader
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A disposable UI projection, never a replacement for the canonical Room row. */
internal data class AgentHistoryDisplayEntry(
    val entry: AgentConversationEntry,
    val fullPayloadFile: File? = null,
)

internal object AgentHistoryDisplayProjection {
    const val INLINE_TOOL_BYTES = 8192L
    private const val VALUE_CHARS = 8192
    private const val PAYLOAD_CHARS = 64 * 1024
    private const val OFFLOAD_NOTICE = "完整内容请查看「完整工具记录」附件。"
    private val gson = Gson()

    suspend fun offload(
        entry: AgentConversationEntry,
        directory: File,
        copyPayload: suspend (OutputStream) -> Unit,
    ): AgentHistoryDisplayEntry {
        val temporary = File.createTempFile("history-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(temporary.outputStream().buffered(), digest).use { output ->
                copyPayload(output)
            }
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val file = File(directory, "${entry.id}-$hash.json")
            if (!file.exists()) check(temporary.renameTo(file)) { "Could not save complete history record" }
            val payload = file.reader(Charsets.UTF_8).use { readPayload(it) }
            return AgentHistoryDisplayEntry(entry.copy(payloadJson = gson.toJson(payload)), file)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Frame top-level JSON values before handing them to Gson. JsonReader.nextString
     * alone materializes an entire tool-output string, even with a streaming Reader.
     * Oversized/unknown values are scanned with constant storage; full bytes remain
     * in the artifact. IDs after a large field are still read, as are small previews.
     */
    internal suspend fun readPayload(source: Reader): Map<String, JsonElement> {
        val reader = PushbackReader(source.buffered(), 1)
        val result = linkedMapOf<String, JsonElement>()
        var retained = 0
        fun nextNonWhitespace(): Int {
            var c = reader.read()
            while (c >= 0 && c.toChar().isWhitespace()) c = reader.read()
            return c
        }
        suspend fun value(limit: Int): String? {
            val text = StringBuilder(minOf(limit, 1024))
            var oversized = false
            var quoted = false
            var escaped = false
            var depth = 0
            var scanned = 0
            while (true) {
                val c = reader.read()
                check(c >= 0) { "Incomplete history JSON" }
                val char = c.toChar()
                if (!quoted && depth == 0 && (char == ',' || char == '}' || char == ':')) {
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
                if (++scanned % 8192 == 0) currentCoroutineContext().ensureActive()
            }
            return text.toString().takeUnless { oversized }
        }
        check(nextNonWhitespace() == '{'.code) { "History tool payload must be an object" }
        var next = nextNonWhitespace()
        while (next != '}'.code) {
            currentCoroutineContext().ensureActive()
            check(next == '"'.code) { "Invalid history JSON key" }
            reader.unread(next)
            val keyJson = value(256)
            check(nextNonWhitespace() == ':'.code) { "Invalid history JSON field" }
            val key = keyJson?.let { JsonParser.parseString(it).asString }
            val limit = when (key) {
                in IDENTITY_FIELDS -> VALUE_CHARS
                in DISPLAY_FIELDS -> minOf(VALUE_CHARS, PAYLOAD_CHARS - retained)
                else -> 0
            }
            val raw = value(limit)
            if (key in DISPLAY_FIELDS && raw != null) {
                result[key!!] = JsonParser.parseString(raw)
                if (key !in IDENTITY_FIELDS) retained += raw.length
            } else if (key in OUTPUT_FIELDS) {
                result[key!!] = gson.toJsonTree(OFFLOAD_NOTICE)
            }
            next = nextNonWhitespace()
            check(next == ','.code || next == '}'.code) { "Invalid history JSON separator" }
            if (next == ','.code) {
                next = nextNonWhitespace()
                check(next != '}'.code) { "Invalid history JSON trailing comma" }
            }
        }
        check(nextNonWhitespace() == -1) { "Unexpected content after history JSON" }
        return result
    }

    private val OUTPUT_FIELDS = setOf("argsJson", "resultPreviewJson", "rawResultJson", "terminalOutput")
    // Verbose previews must not consume the space needed by later ACP identities.
    private val IDENTITY_FIELDS = setOf(
        "agentId", "toolCallId", "sessionId", "turnId", "taskId", "cardId", "toolName", "toolType", "status",
    )
    private val DISPLAY_FIELDS = OUTPUT_FIELDS + setOf(
        "agentId", "agentName", "toolCallId", "sessionId", "turnId", "taskId", "cardId",
        "toolName", "displayName", "toolTitle", "toolType", "serverName", "status", "success",
        "summary", "question", "missingFields", "reasoning_content", "reasoningContent",
        "progress", "subagentStatusText", "subagentEvents", "terminalSessionId", "terminalStreamState",
        "interruptedBy", "interruptionReason", "timedOut", "workspaceId", "artifacts", "actions", "streamMeta",
    )
}
