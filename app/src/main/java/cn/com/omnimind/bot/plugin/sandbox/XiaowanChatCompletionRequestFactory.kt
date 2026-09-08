package cn.com.omnimind.bot.plugin.sandbox

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ReasoningEffort
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import kotlinx.serialization.json.JsonPrimitive

/**
 * The single request boundary for Xiaowan's one-shot generation capability.
 *
 * Xiaowan is an app capability, not a second wire protocol. Keep its request
 * in the official OpenAI-compatible Chat Completions model and let
 * [HttpAgentLlmClient] resolve the configured provider and route.
 */
internal object XiaowanChatCompletionRequestFactory {
    const val DEFAULT_TEMPERATURE = 0.4
    const val DEFAULT_REASONING_EFFORT = "none"

    fun create(
        prompt: String,
        system: String = "",
        maxTokens: Int? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): ChatCompletionRequest {
        val messages = buildList {
            if (system.isNotEmpty()) {
                add(ChatCompletionMessage(role = "system", content = JsonPrimitive(system)))
            }
            add(ChatCompletionMessage(role = "user", content = JsonPrimitive(prompt)))
        }
        return create(
            messages = messages,
            maxTokens = maxTokens,
            temperature = temperature,
            reasoningEffort = reasoningEffort,
        )
    }

    fun create(
        messages: List<ChatCompletionMessage>,
        maxTokens: Int? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): ChatCompletionRequest {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val normalizedEffort = normalizeReasoningEffort(reasoningEffort)
        return ChatCompletionRequest(
            messages = messages,
            model = AgentConversationContextCompactor.DEFAULT_AGENT_MODEL_SCENE,
            maxCompletionTokens = maxTokens,
            temperature = temperature.coerceIn(0.0, 2.0),
            stream = true,
            streamOptions = ChatCompletionStreamOptions(),
            reasoningEffort = normalizedEffort,
            enableThinking = if (normalizedEffort == "none") false else null,
            thinking = if (normalizedEffort == "none") {
                ChatCompletionThinking(type = "disabled")
            } else {
                null
            },
        )
    }

    /** Xiaowan's configured Provider supports four levels; higher host values
     * are intentionally reduced at this adapter boundary. */
    private fun normalizeReasoningEffort(value: String): String {
        return when (ReasoningEffort.normalize(value)) {
            ReasoningEffort.NONE -> ReasoningEffort.NONE
            ReasoningEffort.LOW -> ReasoningEffort.LOW
            ReasoningEffort.MEDIUM -> ReasoningEffort.MEDIUM
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
            ReasoningEffort.MAX -> ReasoningEffort.HIGH
            null -> throw IllegalArgumentException(
                "reasoning_effort must be one of ${ReasoningEffort.supported.joinToString()}",
            )
            else -> throw IllegalArgumentException(
                "reasoning_effort must be one of ${ReasoningEffort.supported.joinToString()}",
            )
        }
    }
}
