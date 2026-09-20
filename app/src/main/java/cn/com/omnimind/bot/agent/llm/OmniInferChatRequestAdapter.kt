package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import java.net.URI
import kotlinx.serialization.json.JsonPrimitive

/** Request-only adaptation for the official local GGUF templates; history is never changed. */
internal object OmniInferChatRequestAdapter {
    fun prepare(
        request: ChatCompletionRequest,
        apiBase: String?,
        resolvedModel: String,
        wireApi: String,
    ): ChatCompletionRequest {
        val endpoint = runCatching { URI(apiBase) }.getOrNull() ?: return request
        if (wireApi != OpenAiWireApi.CHAT_COMPLETIONS ||
            endpoint.host !in setOf("127.0.0.1", "localhost", "[::1]", "::1") ||
            endpoint.port != 9099 || !resolvedModel.endsWith(".gguf", ignoreCase = true)
        ) return request

        val leading = request.messages.takeWhile { it.role == "system" }
        require(request.messages.drop(leading.size).none { it.role == "system" }) {
            "OmniInfer requires system instructions before conversation messages"
        }
        if (leading.size < 2) return request
        require(leading.all {
            (it.content as? JsonPrimitive)?.isString == true && it.name == null &&
                it.toolCalls.isNullOrEmpty() && it.toolCallId == null &&
                it.reasoningContent == null && it.protocolState == null
        }) { "OmniInfer requires plain text leading system instructions" }
        val combined = leading.first().copy(content = JsonPrimitive(
            leading.joinToString("\n\n") { (it.content as JsonPrimitive).content }
        ))
        return request.copy(messages = listOf(combined) + request.messages.drop(leading.size))
    }
}
