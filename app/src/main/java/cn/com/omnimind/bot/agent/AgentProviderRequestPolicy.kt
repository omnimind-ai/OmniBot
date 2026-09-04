package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ProviderRequestCapabilities
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the runtime compatibility contract between the shared Agent request
 * model and a configured Provider route.
 *
 * The request model is deliberately provider-neutral. Optional fields are
 * removed here only after the route has proved that it does not understand
 * them. The result is shared by all ACP sessions using the same route, so a
 * second Conversation does not repeat the same invalid request.
 */
internal object AgentProviderRequestPolicy {
    private const val ROUTE_KEY_SEPARATOR = "\u001f"

    private val routesWithoutThinkingParameters =
        ConcurrentHashMap.newKeySet<String>()

    /**
     * Provider settings are mutable. A route can keep the same id and URL
     * while its headers or gateway behavior changes, so learned capabilities
     * must not outlive an explicit Provider invalidation.
     */
    fun invalidate() {
        routesWithoutThinkingParameters.clear()
    }

    fun prepare(
        routeInfo: HttpController.ChatCompletionRouteInfo,
        request: ChatCompletionRequest,
    ): ChatCompletionRequest {
        val thinkingDisabled = request.enableThinking == false ||
            request.thinking?.type.equals("disabled", ignoreCase = true)
        val providerCompatibleRequest = if (
            shouldOmitUnsupportedExplicitAutoToolChoice(
                capabilities = routeInfo.providerCapabilities,
                thinkingDisabled = thinkingDisabled,
                toolChoice = request.toolChoice,
            )
        ) {
            request.copy(toolChoice = null)
        } else {
            request
        }
        val compatibleRequest = if (routeKey(routeInfo) in routesWithoutThinkingParameters) {
            withoutThinkingParameters(providerCompatibleRequest)
        } else {
            providerCompatibleRequest
        }
        return normalizeToolCallIds(compatibleRequest)
    }

    /**
     * Records a route capability learned from a definitive 400 response and
     * returns the one compatible request to retry. A null result means this
     * error is not a capability rejection or there is nothing to change.
     */
    fun requestAfterFailure(
        routeInfo: HttpController.ChatCompletionRouteInfo,
        request: ChatCompletionRequest,
        error: AgentStreamRequestException,
    ): ChatCompletionRequest? {
        if (!isBadRequest(error) || !isThinkingParameterUnsupported(error)) {
            return null
        }
        routesWithoutThinkingParameters += routeKey(routeInfo)
        return withoutThinkingParameters(request).takeIf { it != request }
    }

    private fun withoutThinkingParameters(
        request: ChatCompletionRequest,
    ): ChatCompletionRequest = request.copy(
        enableThinking = null,
        thinking = null,
    )

    private fun shouldOmitUnsupportedExplicitAutoToolChoice(
        capabilities: ProviderRequestCapabilities,
        thinkingDisabled: Boolean,
        toolChoice: JsonElement?,
    ): Boolean {
        if (thinkingDisabled || capabilities.supportsExplicitAutoToolChoice) {
            return false
        }
        return when (toolChoice) {
            is JsonPrimitive ->
                toolChoice.contentOrNull?.equals("auto", ignoreCase = true) == true
            is JsonObject ->
                (toolChoice["type"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.equals("auto", ignoreCase = true) == true
            else -> false
        }
    }

    /**
     * Tool-call ids are internal identities, but several OpenAI-compatible
     * routes validate their wire value as a maximum-64-character call_id.
     * Keep the original id in the Agent/tool lifecycle and shorten only the
     * outbound request. One map covers the whole request so the assistant
     * tool call and its following tool result remain referentially equal.
     */
    private fun normalizeToolCallIds(
        request: ChatCompletionRequest,
    ): ChatCompletionRequest {
        val replacements = mutableMapOf<String, String>()
        fun normalize(id: String?): String? {
            val value = id?.takeIf { it.isNotBlank() } ?: return id
            if (value.length <= MAX_TOOL_CALL_ID_LENGTH) return value
            return replacements.getOrPut(value) { compactToolCallId(value) }
        }

        val messages = request.messages.map { message ->
            val toolCalls = message.toolCalls?.map { call: AssistantToolCall ->
                val normalizedId = normalize(call.id) ?: call.id
                if (normalizedId == call.id) call else call.copy(id = normalizedId)
            }
            val normalizedToolCallId = normalize(message.toolCallId)
            if (toolCalls == message.toolCalls &&
                normalizedToolCallId == message.toolCallId
            ) {
                message
            } else {
                message.copy(
                    toolCalls = toolCalls,
                    toolCallId = normalizedToolCallId,
                )
            }
        }
        return if (messages == request.messages) request else request.copy(messages = messages)
    }

    private fun compactToolCallId(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "call_${digest.take(MAX_TOOL_CALL_ID_LENGTH - TOOL_CALL_PREFIX.length)}"
    }

    private const val MAX_TOOL_CALL_ID_LENGTH = 64
    private const val TOOL_CALL_PREFIX = "call_"

    private fun routeKey(
        routeInfo: HttpController.ChatCompletionRouteInfo,
    ): String = listOf(
        routeInfo.providerProfileId.orEmpty(),
        routeInfo.apiBase.orEmpty(),
        routeInfo.resolvedModel,
        routeInfo.routeTag.orEmpty(),
        routeInfo.protocolType,
        routeInfo.wireApi,
    ).joinToString(separator = ROUTE_KEY_SEPARATOR)

    private fun isBadRequest(error: AgentStreamRequestException): Boolean {
        if (error.statusCode == 400) return true
        val text = (error.reason + " " + error.responseBody.orEmpty()).lowercase()
        return text.contains("status_code=400") ||
            text.contains("status code: 400") ||
            text.contains("bad request")
    }

    private fun isThinkingParameterUnsupported(error: AgentStreamRequestException): Boolean {
        val text = (error.reason + " " + error.responseBody.orEmpty()).lowercase()
        val mentionsThinkingParameter = text.contains("enable_thinking") ||
            text.contains("enable thinking")
        val rejectsParameter = text.contains("unsupported parameter") ||
            text.contains("unsupported field") ||
            text.contains("unknown parameter") ||
            text.contains("unknown field") ||
            text.contains("invalid parameter") ||
            text.contains("not supported") ||
            text.contains("不支持")
        return mentionsThinkingParameter && rejectsParameter
    }
}
