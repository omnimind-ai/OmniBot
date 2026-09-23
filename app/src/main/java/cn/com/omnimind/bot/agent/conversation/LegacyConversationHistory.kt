package cn.com.omnimind.bot.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One-way import into the existing conversation repository, on its IO dispatcher.
 * No legacy payload is sent through Flutter, and native entries remain authoritative.
 */
internal class LegacyConversationHistory(
    private val read: (String) -> String?,
    private val remove: (Map<String, String>) -> Unit,
) {
    constructor(context: Context) : this(
        read = { key -> preferences(context).getString(key, null) },
        remove = { snapshots ->
            val prefs = preferences(context)
            val editor = prefs.edit()
            snapshots.forEach { (key, value) ->
                if (prefs.getString(key, null) == value) editor.remove(key)
            }
            check(editor.commit()) { "Could not retire imported conversation history" }
        },
    )

    data class Snapshot(val sources: Map<String, String>, val messages: List<Map<String, Any?>>)

    fun read(conversationId: Long, mode: String): Snapshot {
        val sources = linkedMapOf<String, String>()
        val messages = linkedMapOf<String, Map<String, Any?>>()
        for (key in keys(conversationId, mode)) {
            val raw = read(key)?.takeIf { it.isNotBlank() } ?: continue
            // An invalid source is left intact; valid buckets can still recover.
            val rows = runCatching {
                gson.fromJson<List<Any?>>(raw, messageListType)
            }.getOrNull() ?: continue
            val normalized = rows.filterIsInstance<Map<*, *>>().mapNotNull { row ->
                normalize(row.entries.associate { it.key.toString() to it.value }, mode in agentModes)
            }
            sources[key] = raw
            normalized.forEach { message ->
                messages.putIfAbsent(message.getValue("id").toString(), message)
            }
        }
        return Snapshot(sources, messages.values.toList())
    }

    fun retire(snapshot: Snapshot) {
        if (snapshot.sources.isNotEmpty()) remove(snapshot.sources)
    }

    suspend fun import(conversationId: Long, mode: String, store: suspend (List<Map<String, Any?>>) -> Unit) = mutationLock.withLock {
        val snapshot = read(conversationId, mode)
        if (snapshot.sources.isEmpty()) return@withLock
        store(snapshot.messages)
        // Never retire a source before the canonical transaction commits.
        retire(snapshot)
    }

    suspend fun clear(conversationId: Long, modes: List<String>, clearStored: suspend () -> Unit) = mutationLock.withLock {
        val sources = modes.flatMap { keys(conversationId, it) }.mapNotNull { key ->
            read(key)?.let { key to it }
        }.toMap()
        clearStored()
        if (sources.isNotEmpty()) remove(sources)
    }

    companion object {
        private val gson = Gson()
        private val agentModes = setOf("agent", "normal", "codex", "acp", "coding")
        // Repository instances are shared by several Flutter engines/WebChat.
        // Serialize import with explicit clears, including the database commit.
        private val mutationLock = Mutex()
        private val messageListType = object : TypeToken<List<Any?>>() {}.type

        private fun preferences(context: Context) =
            context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

        internal fun keys(conversationId: Long, mode: String): List<String> {
            val suffixes = if (mode in agentModes) {
                listOf("agent_$conversationId", "normal_$conversationId", "$conversationId", "codex_$conversationId")
            } else listOf("${mode}_$conversationId")
            return suffixes.map { "flutter.conversation_messages_$it" }
        }

        private fun number(value: Any?, default: Int): Int =
            (value as? Number)?.toInt() ?: value?.toString()?.toDoubleOrNull()?.toInt() ?: default

        internal fun normalize(raw: Map<String, Any?>, canonicalAgent: Boolean): Map<String, Any?>? {
            val type = number(raw["type"], 1)
            val user = number(raw["user"], 1)
            val content = (raw["content"] as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
            if (type == 1 && user == 2) {
                val text = LegacyAssistantText.sanitize(content?.get("text")?.toString().orEmpty())
                if (content?.containsKey("text") == true) content["text"] = text
                if (text.isBlank() && raw["isError"] != true && raw["isLoading"] != true &&
                    raw["isSummarizing"] != true && (content?.get("attachments") as? List<*>).isNullOrEmpty()
                ) return null
            }
            val card = (content?.get("cardData") as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to it.value }?.toMutableMap()
            if (card != null && canonicalAgent) {
                if (card["type"]?.toString()?.trim() in setOf("codex_request", "agent_request")) {
                    card["type"] = "agent_request"
                }
                if (card["uiStyle"]?.toString()?.trim() in setOf("codex_tool", "agent_tool")) {
                    card["uiStyle"] = "agent_tool"
                }
                card["toolName"]?.toString()?.trim()?.let { name ->
                    card["toolName"] = when {
                        name.isEmpty() -> null
                        name.startsWith("codex.") -> "agent." + name.removePrefix("codex.")
                        name.startsWith("codex/") -> "agent/" + name.removePrefix("codex/")
                        else -> name
                    }
                }
                content?.set("cardData", card)
            }
            val id = raw["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: content?.get("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: content?.get("dbId")?.let { "legacy-db-${number(it, 0)}" }
                // Stable identity makes imports idempotent even after a crash
                // between the Room commit and removal of SharedPreferences.
                ?: "legacy-" + MessageDigest.getInstance("SHA-256")
                    .digest(gson.toJson(raw).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            return raw + mapOf(
                "id" to id,
                "type" to type,
                "user" to user,
                "content" to content,
            )
        }
    }
}

/** Compatibility for old persisted HTTP frames; never used to interpret live ACP updates. */
internal object LegacyAssistantText {
    private val gson = Gson()

    fun sanitize(raw: String): String {
        val first = raw.indexOfFirst { !it.isWhitespace() }
        if (first < 0 || raw[first] != '{') return raw
        var cursor = first
        var stripped = false
        val text = StringBuilder()
        while (cursor < raw.length) {
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (cursor == raw.length || raw[cursor] != '{') break
            val end = objectEnd(raw, cursor) ?: break
            val frame = runCatching { gson.fromJson(raw.substring(cursor, end + 1), Map::class.java) }.getOrNull()
            val extracted = frame?.let(::frameText) ?: break
            stripped = true
            text.append(extracted)
            cursor = end + 1
        }
        return if (stripped) (raw.substring(0, first) + text + raw.substring(cursor)).trim() else raw
    }

    private fun objectEnd(raw: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            when {
                escaped -> escaped = false
                inString && char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun frameText(frame: Map<*, *>): String? {
        val choices = frame["choices"] as? List<*>
        if (choices != null) {
            if (choices.isEmpty()) return ""
            val first = choices.first() as? Map<*, *>
            if (first != null) {
                (first["delta"] as? Map<*, *>)?.let { return payloadText(it["content"]) }
                (first["message"] as? Map<*, *>)?.let { return payloadText(it["content"]) }
                val text = payloadText(first["text"] ?: first["content"])
                if (text.isNotEmpty()) return text
                if (listOf("finish_reason", "delta", "message").any(first::containsKey)) return ""
            }
        }
        val output = frame["output"] as? List<*> ?: return null
        val transport = output.any { item ->
            item is Map<*, *> && (item.containsKey("content") || item.containsKey("text") ||
                item["type"]?.toString()?.trim()?.lowercase() in setOf("message", "output_text", "reasoning", "reasoning_text"))
        }
        return if (transport) output.joinToString("") { payloadText(it) } else null
    }

    private fun payloadText(value: Any?): String = when (value) {
        is String -> value
        is List<*> -> value.joinToString("") { payloadText(it) }
        is Map<*, *> -> when {
            value["type"]?.toString()?.trim()?.lowercase() in setOf("text", "output_text") -> payloadText(value["text"])
            value.containsKey("text") -> payloadText(value["text"])
            else -> payloadText(value["content"])
        }
        else -> ""
    }
}
