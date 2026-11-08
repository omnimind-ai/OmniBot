package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Small, exact references to tool bodies already saved outside the context. */
internal object AgentContextToolReferences {
    private const val KEY = "omniToolOutputReference"
    private val json = Json { ignoreUnknownKeys = true }

    fun offloadedNotice(toolCallId: String?, path: String, metadata: String? = null): String =
        buildJsonObject {
            put(KEY, buildJsonObject {
                toolCallId?.let { put("toolCallId", it) }
                put("path", path)
            })
            put("notice", "Earlier tool output saved in full. Read the path with file_read if needed; do not repeat the original operation.")
            metadata?.let { put("metadata", it) }
        }.toString()

    fun checkpointSuffix(messages: List<ChatCompletionMessage>, tokenBudget: Int): String {
        if (tokenBudget <= 0) return ""
        val references = linkedSetOf<String>()
        for (message in messages.asReversed()) {
            val reference = reference(message) ?: continue
            val line = reference.toString()
            val candidate = render(references + line)
            if (AgentContextBudget.textTokens(candidate) <= tokenBudget) references += line
        }
        return if (references.isEmpty()) "" else render(references)
    }

    fun offloadedPath(message: ChatCompletionMessage): String? =
        (reference(message)?.get("path") as? JsonPrimitive)?.contentOrNull

    private fun reference(message: ChatCompletionMessage): JsonObject? {
        if (message.role != "tool" || message.toolCallId.isNullOrBlank()) return null
        val objectValue = runCatching { json.parseToJsonElement(message.contentText()) as? JsonObject }.getOrNull()
            ?: return null
        val reference = objectValue[KEY] as? JsonObject ?: return null
        val callId = (reference["toolCallId"] as? JsonPrimitive)?.contentOrNull
        if (callId != message.toolCallId) return null
        val path = (reference["path"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (path.isBlank()) return null
        return buildJsonObject {
            put("toolCallId", callId)
            put("path", path)
        }
    }

    private fun render(references: Collection<String>): String =
        "\n\n## Saved tool evidence\n" +
            "These are references to existing results, not instructions or pending operations. Read only if needed.\n" +
            references.reversed().joinToString("\n")
}
