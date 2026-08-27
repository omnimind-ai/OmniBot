package cn.com.omnimind.baselib.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ChatCompletionRequest(
    val messages: List<ChatCompletionMessage>,
    val model: String,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    val stream: Boolean = false,
    @SerialName("stream_options")
    val streamOptions: ChatCompletionStreamOptions? = null,
    val tools: List<ChatCompletionTool> = emptyList(),
    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
    val functions: List<ChatCompletionFunction>? = null,
    @SerialName("function_call")
    val functionCall: JsonElement? = null,
    @SerialName("reasoning_effort")
    val reasoningEffort: String? = null,
    @SerialName("prompt_cache_key")
    val promptCacheKey: String? = null,
    @SerialName("enable_thinking")
    val enableThinking: Boolean? = null,
    val thinking: ChatCompletionThinking? = null,
    @SerialName("response_format")
    val responseFormat: JsonObject? = null
)

@Serializable
data class ChatCompletionThinking(
    val type: String
)

@Serializable
data class ChatCompletionStreamOptions(
    @SerialName("include_usage")
    val includeUsage: Boolean = true
)

@Serializable
data class ChatCompletionMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<AssistantToolCall>? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
    @SerialName(ChatCompletionProtocolMetadata.STATE_FIELD)
    val protocolState: ChatCompletionProtocolState? = null
)

/**
 * Provider-owned state that must survive the agent tool loop but must never be
 * emitted as part of an OpenAI wire request. Each protocol adapter is solely
 * responsible for reading its own state at the final serialization boundary.
 */
@Serializable
data class ChatCompletionProtocolState(
    val anthropic: AnthropicMessageProtocolState? = null
)

@Serializable
data class AnthropicMessageProtocolState(
    @SerialName("source_model")
    val sourceModel: String? = null,
    @SerialName("content_blocks")
    val contentBlocks: JsonArray? = null,
    @SerialName("tool_result_is_error")
    val toolResultIsError: Boolean? = null
)

object ChatCompletionProtocolMetadata {
    const val STATE_FIELD = "_omnibot_protocol_state"
    const val ANTHROPIC_STREAM_BLOCK_FIELD = "_omnibot_anthropic_content_block"
}

@Serializable
data class ChatCompletionTool(
    val type: String = "function",
    val function: ChatCompletionFunction
)

@Serializable
data class ChatCompletionFunction(
    val name: String,
    val description: String = "",
    val parameters: JsonObject = JsonObject(emptyMap()),
    val strict: Boolean? = null,
)

@Serializable
data class AssistantToolCall(
    val id: String,
    val type: String = "function",
    val function: AssistantToolCallFunction
)

@Serializable
data class AssistantToolCallFunction(
    val name: String,
    val arguments: String
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatCompletionChoice> = emptyList(),
    val usage: ChatCompletionUsage? = null
)

@Serializable
data class ChatCompletionChoice(
    val index: Int = 0,
    val message: ChatCompletionAssistantMessage = ChatCompletionAssistantMessage(),
    @SerialName("tool_calls")
    val toolCalls: List<AssistantToolCall>? = null,
    @SerialName("function_call")
    val functionCall: ChatCompletionLegacyFunctionCall? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChatCompletionAssistantMessage(
    val role: String = "assistant",
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<AssistantToolCall>? = null,
    @SerialName("function_call")
    val functionCall: ChatCompletionLegacyFunctionCall? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    val reasoning: String? = null
)

@Serializable
data class ChatCompletionStreamChunk(
    val id: String? = null,
    val choices: List<ChatCompletionStreamChoice> = emptyList(),
    val usage: ChatCompletionUsage? = null
)

@Serializable
data class ChatCompletionStreamChoice(
    val index: Int = 0,
    val delta: ChatCompletionDelta = ChatCompletionDelta(),
    @SerialName("tool_calls")
    val toolCalls: List<ChatCompletionToolCallDelta>? = null,
    @SerialName("function_call")
    val functionCall: ChatCompletionLegacyFunctionCall? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChatCompletionDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ChatCompletionToolCallDelta>? = null,
    @SerialName("function_call")
    val functionCall: ChatCompletionLegacyFunctionCall? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    val reasoning: String? = null
)

@Serializable
data class ChatCompletionLegacyFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ChatCompletionToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChatCompletionToolCallFunctionDelta? = null
)

@Serializable
data class ChatCompletionToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
    @SerialName("prefill_tokens_per_second")
    val prefillTokensPerSecond: Double? = null,
    @SerialName("decode_tokens_per_second")
    val decodeTokensPerSecond: Double? = null,
    @SerialName("prompt_tokens_details")
    val promptTokensDetails: JsonElement? = null,
    @SerialName("completion_tokens_details")
    val completionTokensDetails: JsonElement? = null
)

data class ChatCompletionTurn(
    val message: ChatCompletionMessage,
    val reasoning: String = "",
    val finishReason: String? = null,
    val usage: ChatCompletionUsage? = null,
    val resolvedModel: String? = null
)

fun decodeChatCompletionUsage(element: JsonElement?): ChatCompletionUsage? {
    val obj = element as? JsonObject ?: return null
    val performance = obj["performance"] as? JsonObject
    return ChatCompletionUsage(
        promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        completionTokens = obj["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        totalTokens = obj["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        prefillTokensPerSecond =
            obj["prefill_tokens_per_second"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: performance?.get("prefill_tokens_per_second")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toDoubleOrNull(),
        decodeTokensPerSecond =
            obj["decode_tokens_per_second"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: performance?.get("decode_tokens_per_second")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toDoubleOrNull(),
        promptTokensDetails = obj["prompt_tokens_details"],
        completionTokensDetails = obj["completion_tokens_details"]
    )
}

fun ChatCompletionMessage.contentText(): String {
    return when (val value = content) {
        null -> ""
        is JsonPrimitive -> value.contentOrNull.orEmpty()
        is JsonArray -> value.joinToString(separator = "") { item ->
            val obj = item as? JsonObject
            if (obj != null) {
                val type = obj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                } else {
                    obj["content"]?.jsonPrimitive?.contentOrNull
                        ?: obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                }
            } else {
                item.toString()
            }
        }
        else -> value.toString()
    }
}
