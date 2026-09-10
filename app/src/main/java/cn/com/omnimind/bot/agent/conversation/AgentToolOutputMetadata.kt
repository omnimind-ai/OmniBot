package cn.com.omnimind.bot.agent

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.StringReader

/** Bounded metadata for the current offloaded result, not a summary of its body.
 * Keep exact small values (including provider-owned cursors) without tool-name rules.
 * The original output remains in the offload; never truncate a cursor into a new value.
 */
internal fun toolOutputMetadata(text: String): String? {
    if (text.length > 512 * 1024) return null
    var remaining = 4094 // Reserve the outer object braces.
    fun readObject(reader: JsonReader, depth: Int): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        reader.beginObject()
        var count = 0
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (++count > 64 || key.length > 64 || remaining < key.length + 16) {
                reader.skipValue()
                continue
            }
            val value = when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> if (depth < 3) readObject(reader, depth + 1) else {
                    reader.skipValue(); null
                }
                JsonToken.STRING -> {
                    val raw = reader.nextString()
                    // These are the existing AgentEventAdapter envelope fields.
                    if (key in setOf("rawResultJson", "previewJson") && depth < 3 && raw.startsWith("{")) {
                        runCatching { JsonReader(StringReader(raw)).use { readObject(it, depth + 1) } }.getOrNull()
                    } else raw.takeIf { it.length <= 512 }?.let(::JsonPrimitive)
                }
                JsonToken.NUMBER -> reader.nextString().takeIf { it.length <= 64 }
                    ?.let { Json.parseToJsonElement(it) }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull }
                else -> { reader.skipValue(); null }
            }
            if (value != null && (value !is JsonObject || value.isNotEmpty())) {
                val cost = JsonPrimitive(key).toString().length + value.toString().length + 2
                if (cost <= remaining) { fields[key] = value; remaining -= cost }
            }
        }
        reader.endObject()
        return JsonObject(fields)
    }
    return runCatching {
        JsonReader(StringReader(text)).use { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) null
            else readObject(reader, 0).takeIf { it.isNotEmpty() }?.toString()
        }
    }.getOrNull()
}
