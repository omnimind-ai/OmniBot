package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** Apply the existing Gemini recent-tool-output budget before materializing history.
 * This is a request projection only: never persist its replacement payload in Room.
 * Oversized fields are skipped by the streaming reader, not decoded then discarded.
 */
internal class AgentHistoryToolOutputProjection(
    private val offload: (AgentConversationEntry) -> String,
    private val budgetTokens: Long = 50_000,
) {
    private var retainedTokens = 0L
    private val gson = Gson()

    fun project(entry: AgentConversationEntry): AgentConversationEntry {
        if (entry.entryType != AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT) return entry
        val size = AgentContextBudget.textTokens(entry.payloadJson)
        if (size + retainedTokens <= budgetTokens) {
            retainedTokens += size
            return entry
        }
        // Persist successfully before replacing anything. An I/O failure propagates.
        val path = offload(entry)
        val payload = linkedMapOf<String, Any?>()
        JsonReader(StringReader(entry.payloadJson)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key in REPLAY_METADATA) payload[key] = JsonParser.parseReader(reader)
                else reader.skipValue()
            }
            reader.endObject()
        }
        val notice = "Earlier tool record saved in full to $path. Read it with file_read if needed."
        payload["rawResultJson"] = notice
        val callId = sequenceOf("modelToolCallId", "toolCallId").mapNotNull { key ->
            (payload[key] as? com.google.gson.JsonPrimitive)?.asString?.takeIf { it.isNotBlank() }
        }.firstOrNull()
        val assistant = (payload["modelAssistantMessageJson"] as? com.google.gson.JsonPrimitive)?.asString
        if (!assistant.isNullOrBlank() && callId != null) {
            payload["modelToolCallId"] = callId
            payload["modelToolResultMessageJson"] = gson.toJson(mapOf(
                "role" to "tool", "tool_call_id" to callId, "content" to notice,
            ))
        }
        return entry.copy(payloadJson = gson.toJson(payload))
    }

    private companion object {
        val REPLAY_METADATA = setOf(
            "toolName", "displayName", "toolTitle", "toolType", "argsJson",
            "status", "success", "historyOmitted", "taskId", "sessionId", "turnId",
            "toolCallId", "modelToolCallId", "modelAssistantMessageJson",
            "interruptedBy", "interruptionReason",
        )
    }
}
