package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.util.ImageCompressor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

interface OmniFlowModelClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
    ): ChatCompletionTurn
}

class OmniFlowModelHost(
    private val modelClient: OmniFlowModelClient,
    private val imageCompressor: (String) -> String = ::compressVlmImage,
    private val onReasoningUpdate: suspend (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun modelTurn(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedModel = firstText(payload["model"])
        require(requestedModel.isNotEmpty()) { "model_turn_model_required" }
        val request = json.decodeFromJsonElement<ChatCompletionRequest>(
            jsonValue(mapValue(payload["request"])),
        )
        require(request.model == requestedModel) { "model_turn_request_model_mismatch" }
        val rejectedAction = stalledPreviousAction(payload)
        var activeRequest = request.copy(
            messages = request.messages.map { message ->
                message.copy(content = compressImages(message.content))
            },
        )
        val turns = mutableListOf<ChatCompletionTurn>()
        var rejectedAttempts = 0
        lateinit var turn: ChatCompletionTurn
        while (true) {
            val candidate = modelClient.streamTurn(
                request = activeRequest,
                onReasoningUpdate = { thinking ->
                    thinking.trim().takeIf(String::isNotEmpty)?.let {
                        onReasoningUpdate(it)
                    }
                },
            )
            turns += candidate
            if (rejectedAction == null || !repeatsRejectedAction(candidate, rejectedAction)) {
                turn = candidate
                break
            }
            rejectedAttempts += 1
            check(rejectedAttempts <= MAX_REJECTED_ACTION_RETRIES) {
                "model_repeated_explicitly_rejected_action:${rejectedAction.tool}"
            }
            activeRequest = activeRequest.copy(
                messages = activeRequest.messages + ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(rejectedAction.reflectionPrompt()),
                ),
            )
        }
        val resolvedModel = turn.resolvedModel?.trim().orEmpty().ifBlank { requestedModel }
        return linkedMapOf<String, Any?>(
            "requested_model" to requestedModel,
            "resolved_model" to resolvedModel,
            "tool_calls" to turn.message.toolCalls.orEmpty().map { toolCall ->
                linkedMapOf(
                    "id" to toolCall.id,
                    "type" to toolCall.type,
                    "function" to linkedMapOf(
                        "name" to toolCall.function.name,
                        "arguments" to toolCall.function.arguments,
                    ),
                )
            },
            "reasoning" to turn.reasoning.trim().takeIf(String::isNotEmpty),
            "finish_reason" to turn.finishReason,
            "usage" to aggregateUsage(turns),
            "rejected_stalled_actions" to rejectedAttempts.takeIf { it > 0 },
        ).filterValues { it != null }
    }

    suspend fun completeJson(
        payload: Map<String, Any?>,
        modelOverride: String? = null,
    ): Map<String, Any?> {
        val request = jsonCompletionRequest(
            payload = payload,
            model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
        )
        // The provider/ACP stream owns its lifetime.  Do not synthesize a
        // failure merely because a model is slow before its next chunk.
        val turn = modelClient.streamTurn(request)
        val content = submitJsonArguments(turn)
        return mapOf("content" to content)
    }

    private fun usage(turn: ChatCompletionTurn): Map<String, Any?>? {
        val usage = turn.usage ?: return null
        val promptDetails = usage.promptTokensDetails as? JsonObject
        val completionDetails = usage.completionTokensDetails as? JsonObject
        return linkedMapOf<String, Any?>(
            "prompt_tokens" to usage.promptTokens,
            "completion_tokens" to usage.completionTokens,
            "total_tokens" to usage.totalTokens,
            "reasoning_tokens" to completionDetails.intValue("reasoning_tokens"),
            "text_tokens" to completionDetails.intValue("text_tokens"),
            "image_tokens" to promptDetails.intValue("image_tokens"),
            "cached_tokens" to promptDetails.intValue("cached_tokens"),
            "prefill_tokens_per_second" to usage.prefillTokensPerSecond,
            "decode_tokens_per_second" to usage.decodeTokensPerSecond,
        ).filterValues { it != null }.takeIf(Map<String, Any?>::isNotEmpty)
    }

    private fun aggregateUsage(turns: List<ChatCompletionTurn>): Map<String, Any?>? {
        if (turns.isEmpty()) return null
        val perTurn = turns.mapNotNull(::usage)
        return linkedMapOf<String, Any?>(
            "model_calls" to turns.size,
            "responses_with_usage" to perTurn.size,
            "responses_without_usage" to (turns.size - perTurn.size),
            "prompt_tokens" to sumUsage(perTurn, "prompt_tokens"),
            "completion_tokens" to sumUsage(perTurn, "completion_tokens"),
            "total_tokens" to sumUsage(perTurn, "total_tokens"),
            "reasoning_tokens" to sumUsage(perTurn, "reasoning_tokens"),
            "text_tokens" to sumUsage(perTurn, "text_tokens"),
            "image_tokens" to sumUsage(perTurn, "image_tokens"),
            "cached_tokens" to sumUsage(perTurn, "cached_tokens"),
        ).filterValues { value -> value !is Number || value.toLong() != 0L }
            .takeIf(Map<String, Any?>::isNotEmpty)
    }

    private fun sumUsage(values: List<Map<String, Any?>>, key: String): Int =
        values.sumOf { (it[key] as? Number)?.toLong() ?: 0L }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun stalledPreviousAction(payload: Map<String, Any?>): RejectedAction? {
        val state = mapValue(payload["state"])
        val extra = mapValue(state["extra"])
        val error = firstText(extra["previous_action_error"])
        if (error !in STALLED_ACTION_ERRORS) return null
        val previous = mapValue(extra["previous_action"])
        val tool = firstText(previous["tool"])
        if (tool.isEmpty()) return null
        val display = mapValue(state["display"])
        val width = (display["width"] as? Number)?.toDouble() ?: 0.0
        val height = (display["height"] as? Number)?.toDouble() ?: 0.0
        val rawArgs = mapValue(previous["args"]).mapValues { (key, value) ->
            when (key) {
                "x", "x1", "x2" -> canonicalCoordinateToPixels(value, width)
                "y", "y1", "y2" -> canonicalCoordinateToPixels(value, height)
                else -> value
            }
        }
        return RejectedAction(tool = tool, arguments = rawArgs, error = error)
    }

    private fun canonicalCoordinateToPixels(value: Any?, extent: Double): Any? =
        if (value is Number && extent > 0.0) value.toDouble() / 1000.0 * extent else value

    private fun repeatsRejectedAction(
        turn: ChatCompletionTurn,
        rejected: RejectedAction,
    ): Boolean {
        val call = turn.message.toolCalls.orEmpty().singleOrNull() ?: return false
        if (call.function.name.trim() != rejected.tool) return false
        val arguments = runCatching {
            json.parseToJsonElement(call.function.arguments) as? JsonObject
        }.getOrNull() ?: return false
        return rejected.arguments.all { (key, expected) ->
            equivalentArgument(expected, arguments[key])
        }
    }

    private fun equivalentArgument(expected: Any?, actual: JsonElement?): Boolean {
        val primitive = actual as? JsonPrimitive ?: return false
        return when (expected) {
            is Number -> primitive.doubleOrNull?.let {
                abs(it - expected.toDouble()) <= COORDINATE_MATCH_TOLERANCE_PX
            } == true
            is Boolean -> primitive.contentOrNull?.toBooleanStrictOrNull() == expected
            null -> primitive.contentOrNull == null
            else -> primitive.contentOrNull == expected.toString()
        }
    }

    private data class RejectedAction(
        val tool: String,
        val arguments: Map<String, Any?>,
        val error: String,
    ) {
        fun reflectionPrompt(): String =
            "REFLECTION REQUIRED. Your proposed native tool_call was explicitly " +
                "rejected because it repeats the previous action after `$error`: " +
                "$tool $arguments. Do not return this same control or coordinate " +
                "again. Explain the failure to yourself, inspect the current screenshot " +
                "and execution history, then return exactly one DIFFERENT native " +
                "tool_call that makes progress. If a primary button is gray or disabled, " +
                "select the required visible option, radio item, or choice card first."
    }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun compressImages(content: JsonElement?): JsonElement? {
        val blocks = content as? JsonArray ?: return content
        return JsonArray(
            blocks.map { item ->
                val block = item as? JsonObject ?: return@map item
                if (block["type"]?.jsonPrimitive?.contentOrNull != "image_url") {
                    return@map item
                }
                val imageUrl = block["image_url"] as? JsonObject ?: return@map item
                val url = imageUrl["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!url.startsWith("data:image/")) return@map item
                JsonObject(
                    block + (
                        "image_url" to JsonObject(
                            imageUrl + ("url" to JsonPrimitive(imageCompressor(url))),
                        )
                    ),
                )
            },
        )
    }

    companion object {
        private const val MAX_REJECTED_ACTION_RETRIES = 3
        private const val COORDINATE_MATCH_TOLERANCE_PX = 2.0
        private val STALLED_ACTION_ERRORS = setOf(
            "action_completed_without_state_change",
            "action_already_succeeded_on_current_state",
            "repeated_action_without_progress",
        )

        private fun compressVlmImage(value: String): String {
            val compressed = ImageCompressor.compressBase64Image(
                base64String = value,
                scale = 0.3f,
                quality = 70,
                bypassThreshold = 0L,
            ).base64
            val payload = compressed.substringAfter(",", "")
            return if (payload.isBlank()) value else "data:image/jpeg;base64,$payload"
        }

        suspend fun completeJson(
            payload: Map<String, Any?>,
            modelOverride: String? = null,
        ): Map<String, Any?> {
            val request = jsonCompletionRequest(
                payload = payload,
                model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
            )
            // JSON completion is still cancelled by the owning coroutine or
            // process shutdown, but has no host-imposed wall-clock deadline.
            val content = OmniFlowPythonRuntime.completeJson(request)
            check(content.isNotBlank()) { "model_completion_empty" }
            return mapOf("content" to content)
        }

        private fun jsonCompletionRequest(
            payload: Map<String, Any?>,
            model: String,
        ): ChatCompletionRequest =
            ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive(firstText(payload["prompt"])),
                    ),
                ),
                maxCompletionTokens = intValue(payload["max_tokens"], defaultValue = 1800),
                temperature = (payload["temperature"] as? Number)?.toDouble() ?: 0.1,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(),
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(
                            name = "submit_json",
                            description = "Submit the requested JSON object.",
                            parameters = buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put("additionalProperties", JsonPrimitive(true))
                            },
                        ),
                    ),
                ),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
            )

        private fun submitJsonArguments(turn: ChatCompletionTurn): String {
            val toolCall = turn.message.toolCalls.orEmpty().singleOrNull {
                it.function.name == "submit_json"
            }
            if (toolCall != null) {
                return toolCall.function.arguments.trim().ifBlank {
                    error("model_completion_submit_json_empty")
                }
            }
            // Some OpenAI-compatible providers ignore tool_choice=required and
            // return the requested object as ordinary assistant content. Keep
            // the structured tool path preferred, but accept a JSON object so
            // offline Function enhancement remains usable with those providers.
            val content = turn.message.contentText().trim()
            val candidate = content
                .removePrefix("```")
                .removePrefix("json")
                .removeSuffix("```")
                .trim()
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                return candidate
            }
            error("model_completion_submit_json_required")
        }
    }
}
