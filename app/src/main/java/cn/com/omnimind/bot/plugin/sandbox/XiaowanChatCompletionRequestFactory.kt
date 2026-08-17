package cn.com.omnimind.bot.plugin.sandbox

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
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
    const val MIN_MAX_TOKENS = 32
    const val DEFAULT_MAX_TOKENS = 800
    const val MAX_MAX_TOKENS = 4_096
    const val DEFAULT_TEMPERATURE = 0.4
    const val DEFAULT_REASONING_EFFORT = "none"
    const val MAX_PROMPT_CHARS = 32_000
    const val MAX_SYSTEM_CHARS = 8_000

    private val reasoningEfforts = setOf("none", "low", "medium", "high")

    fun create(
        prompt: String,
        system: String = "",
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): ChatCompletionRequest {
        require(prompt.length <= MAX_PROMPT_CHARS) {
            "prompt exceeds the $MAX_PROMPT_CHARS character limit"
        }
        require(system.length <= MAX_SYSTEM_CHARS) {
            "system exceeds the $MAX_SYSTEM_CHARS character limit"
        }
        val normalizedEffort = reasoningEffort.trim().lowercase()
            .takeIf { it in reasoningEfforts }
            ?: throw IllegalArgumentException(
                "reasoning_effort must be one of ${reasoningEfforts.joinToString()}",
            )
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
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    ): ChatCompletionRequest {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        require(messages.sumOf { it.content?.toString()?.length ?: 0 } <= MAX_PROMPT_CHARS) {
            "messages exceed the $MAX_PROMPT_CHARS character limit"
        }
        val normalizedEffort = reasoningEffort.trim().lowercase()
            .takeIf { it in reasoningEfforts }
            ?: throw IllegalArgumentException(
                "reasoning_effort must be one of ${reasoningEfforts.joinToString()}",
            )
        return ChatCompletionRequest(
            messages = messages,
            model = AgentConversationContextCompactor.DEFAULT_AGENT_MODEL_SCENE,
            maxCompletionTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS),
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
}
