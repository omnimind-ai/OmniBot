package cn.com.omnimind.bot.agent

import android.content.Context
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.Base64

/** Durable selection/visibility policy, shared by all engines without Dart preference caches. */
internal class ChatConversationPreferences(
    private val read: (String) -> Any?,
    private val write: (Map<String, Any?>) -> Unit,
) {
    constructor(context: Context) : this(
        read = { preferences(context).all["flutter.$it"] },
        write = { values ->
            val editor = preferences(context).edit()
            values.forEach { (key, value) ->
                when (value) {
                    null -> editor.remove("flutter.$key")
                    is Number -> editor.putLong("flutter.$key", value.toLong())
                    else -> editor.putString("flutter.$key", value.toString())
                }
            }
            check(editor.commit()) { "Unable to persist conversation selection" }
        },
    )

    companion object {
        private val lock = Any()
        private val gson = Gson()
        private val modes = listOf("normal", "chat_only", "openclaw", "subagent", "agent")
        private const val LAST = "last_visible_conversation_target"
        private const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        private fun preferences(context: Context) = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        private fun id(value: Any?) = ((value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull())?.takeUnless { it == 0L }
        private fun mode(raw: Any?): String = when (val value = raw?.toString()?.trim()?.lowercase()) {
            "chat", "chatonly", "chat-only" -> "chat_only"
            "chat_only", "openclaw", "subagent" -> value
            else -> "agent"
        }

        private fun sanitize(raw: Map<*, *>, selectedMode: String? = null): Map<String, Any?> = linkedMapOf<String, Any?>(
            "conversationId" to id(raw["conversationId"]),
            "mode" to mode(selectedMode ?: raw["mode"]),
            "isNewConversation" to (raw["isNewConversation"] == true),
            "fromNativeRoute" to false,
        ).apply {
            for ((key, oldKey) in listOf("agentId" to "agentId", "agentSessionId" to "codexThreadId", "agentRuntime" to "codexRuntime")) {
                (raw[key] ?: raw[oldKey])?.toString()?.takeIf(String::isNotEmpty)?.let { put(key, it) }
            }
            val active = raw["agentSessionActive"] ?: raw["codexThreadActive"]
            when (active?.toString()?.trim()?.lowercase()) {
                "true", "1" -> put("agentSessionActive", true)
                "false", "0" -> put("agentSessionActive", false)
            }
        }

        internal fun decodeStringList(raw: Any?): List<String> = runCatching {
            when (raw) {
                is Collection<*> -> raw.filterIsInstance<String>()
                is String -> when {
                    raw.startsWith("$LIST_PREFIX!") -> gson.fromJson(raw.removePrefix("$LIST_PREFIX!"), List::class.java).filterIsInstance<String>()
                    raw.startsWith(LIST_PREFIX) -> {
                        // Older shared_preferences versions serialized ArrayList<String>.
                        val bytes = Base64.getMimeDecoder().decode(raw.removePrefix(LIST_PREFIX))
                        object : ObjectInputStream(ByteArrayInputStream(bytes)) {
                            override fun resolveClass(desc: ObjectStreamClass): Class<*> {
                                require(desc.name in setOf("java.util.ArrayList", "java.lang.String", "[Ljava.lang.String;"))
                                return super.resolveClass(desc)
                            }
                        }.use { (it.readObject() as? List<*>)?.filterIsInstance<String>().orEmpty() }
                    }
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun decode(raw: Any?, selectedMode: String? = null): Map<String, Any?>? = runCatching {
        val value = gson.fromJson(raw as? String ?: return null, Map::class.java) ?: return null
        sanitize(value, selectedMode)
    }.getOrNull()

    fun currentId(mode: String): Long? = synchronized(lock) {
        id(read("current_conversation_id_$mode")) ?: if (mode == "normal") id(read("current_conversation_id")) else null
    }

    fun currentTarget(mode: String): Map<String, Any?>? = synchronized(lock) {
        decode(read("current_conversation_target_$mode"), mode)
            ?: currentId(mode)?.let { sanitize(mapOf("conversationId" to it), mode) }
    }

    fun lastTarget(): Map<String, Any?>? = synchronized(lock) {
        val raw = read(LAST) as? String
        if (raw.isNullOrBlank()) modes.firstNotNullOfOrNull(::currentTarget) else decode(raw)
    }

    private fun idChanges(id: Long?, mode: String): Map<String, Any?> = buildMap {
        put("current_conversation_id_$mode", id)
        if (id == null && mode == "normal") put("current_conversation_id", null)
    }

    fun saveId(value: Long?, mode: String) = synchronized(lock) { write(idChanges(value, mode)) }

    fun saveTarget(target: Map<*, *>?, mode: String) = synchronized(lock) {
        val sanitized = target?.let { sanitize(it, mode) }
        write(idChanges(id(sanitized?.get("conversationId")), mode) + mapOf(
            "current_conversation_target_$mode" to sanitized?.let(gson::toJson),
        ))
    }

    fun saveLast(target: Map<*, *>?) = synchronized(lock) {
        write(mapOf(LAST to target?.let { gson.toJson(sanitize(it)) }))
    }

    fun clearReferences(conversationId: Long, selectedMode: String? = null) = synchronized(lock) {
        val changes = mutableMapOf<String, Any?>()
        for (entryMode in selectedMode?.let(::listOf) ?: modes) {
            if (id(currentTarget(entryMode)?.get("conversationId")) == conversationId) {
                changes.putAll(idChanges(null, entryMode))
                changes["current_conversation_target_$entryMode"] = null
            }
        }
        val last = lastTarget()
        if (id(last?.get("conversationId")) == conversationId &&
            (selectedMode == null || mode(last?.get("mode")) == mode(selectedMode))) changes[LAST] = null
        if (changes.isNotEmpty()) write(changes)
    }

    fun hiddenIds(): Set<Long> = synchronized(lock) {
        (decodeStringList(read("hidden_agent_conversation_ids")) + decodeStringList(read("hidden_codex_conversation_ids")))
            .mapNotNull(String::toLongOrNull).toSet()
    }

    fun hide(conversationId: Long) = synchronized(lock) {
        write(mapOf("hidden_agent_conversation_ids" to "$LIST_PREFIX!" + gson.toJson((hiddenIds() + conversationId).map(Long::toString).sorted())))
    }

    fun handle(operation: String, arguments: Map<*, *>): Any? {
        val mode = arguments["mode"]?.toString() ?: "agent"
        return when (operation) {
            "getId" -> currentId(mode)
            "getTarget" -> currentTarget(mode)
            "getLast" -> lastTarget()
            "saveId" -> { saveId(id(arguments["conversationId"]), mode); null }
            "saveTarget" -> { saveTarget(arguments["target"] as? Map<*, *>, mode); null }
            "saveLast" -> { saveLast(arguments["target"] as? Map<*, *>); null }
            "clearReferences" -> { clearReferences(requireNotNull(id(arguments["conversationId"])), arguments["mode"]?.toString()); null }
            else -> throw IllegalArgumentException("Unknown conversation preference operation: $operation")
        }
    }
}
