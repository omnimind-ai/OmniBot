package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import kotlinx.serialization.json.*

/** Kotlin port of Pi estimateContextTokens/findCutPoint (MIT).
 * Upstream: badlogic/pi-mono@7d8ab31a477ecc07b36f56ffcae58c79307a68be,
 * packages/coding-agent/src/core/compaction/compaction.ts. See docs/third-party/compaction.md and its license files.
 * Message types and system-message preservation are Android host adaptations.
 * These are estimates, never provider-reported usage or an exact tokenizer.
 */
internal object AgentContextBudget {
    // Gemini CLI tokenCalculation.ts (Apache-2.0): ASCII/4, non-ASCII*1.5.
    // Host adaptation: retain the multilingual estimate for large strings too.
    fun textTokens(text: String): Long {
        var quarters = 0L
        for (char in text) quarters += if (char.code <= 127) 1 else 6
        return (quarters + 3) / 4
    }

    fun messageTokens(message: ChatCompletionMessage): Long {
        fun contentTokens(content: JsonElement?): Long = when (content) {
            null, JsonNull -> 0
            is JsonPrimitive -> textTokens(content.content)
            is JsonArray -> content.sumOf { block ->
                val obj = block as? JsonObject
                when (obj?.get("type")?.jsonPrimitive?.contentOrNull) {
                    "image_url", "image" -> 1200L // Pi image estimate; do not count Base64 as text.
                    "text" -> textTokens(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    else -> textTokens(block.toString())
                }
            }
            else -> textTokens(content.toString())
        }
        return 4 + contentTokens(message.content) + textTokens(message.reasoningContent.orEmpty()) +
            message.toolCalls.orEmpty().sumOf { textTokens(it.function.name + it.function.arguments) }
    }

    fun estimate(messages: List<ChatCompletionMessage>, reportedTokens: Int? = null,
        reportedMessageCount: Int = 0, toolTokens: Int = 0): Int {
        val validUsage = reportedTokens != null && reportedMessageCount in 1..messages.size
        val tokens = if (validUsage) reportedTokens!!.toLong() +
            messages.drop(reportedMessageCount).sumOf(::messageTokens)
        else messages.sumOf(::messageTokens) + toolTokens
        return tokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Pi: walk backwards retaining recent tokens; cut at user/assistant, never tool results.
     * Additionally require all earlier calls to have results before discarding their group.
     */
    fun cutPoint(messages: List<ChatCompletionMessage>, keepRecentTokens: Int): Int? {
        val start = messages.takeWhile { it.role == "system" }.size
        val pending = linkedSetOf<String>()
        val valid = mutableListOf<Int>()
        for (i in start until messages.size) {
            val message = messages[i]
            if (i > start && pending.isEmpty() && message.role in setOf("user", "assistant")) valid += i
            message.toolCalls.orEmpty().forEach { pending += it.id }
            if (message.role == "tool") pending.remove(message.toolCallId)
        }
        // A fully completed final tool group may itself be summarized when no newer
        // assistant exists yet (our request boundary is before the next model call).
        if (pending.isEmpty() && messages.lastOrNull()?.role == "tool") valid += messages.size
        var tokens = 0L
        for (i in messages.indices.reversed()) {
            tokens += messageTokens(messages[i])
            if (tokens >= keepRecentTokens) return valid.firstOrNull { it >= i }
        }
        return valid.lastOrNull { messages.getOrNull(it)?.role == "user" } ?: valid.firstOrNull()
    }

    fun rebuild(messages: List<ChatCompletionMessage>, firstKept: Int, summary: String): List<ChatCompletionMessage> {
        val systems = messages.takeWhile { it.role == "system" }
            .filterNot(AgentConversationHistorySupport::isContextSummaryMessage)
        val latestUser = messages.indexOfLast { it.role == "user" }
        return buildList {
            addAll(systems)
            add(AgentConversationHistorySupport.buildContextSummaryAssistantMessage(summary))
            // The active request remains exact even when its early tool steps were summarized.
            if (latestUser >= 0 && latestUser < firstKept) add(messages[latestUser])
            addAll(messages.drop(firstKept))
        }
    }
}
