package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Converts only the model wire representation; the host owns history and tool execution. */
object GeminiInteractionsRequest {
    fun url(baseUrl: String): String = if (baseUrl.trim().endsWith('#')) {
        baseUrl.trim().removeSuffix("#")
    } else baseUrl.trim().trimEnd('/') + "/interactions"

    fun modelsUrl(baseUrl: String): String =
        baseUrl.trim().removeSuffix("#").trimEnd('/').removeSuffix("/interactions") + "/models"

    fun build(request: ChatCompletionRequest, sourceProvider: String): JsonObject {
        require(request.tools.none { it.function.strict == true }) { "Gemini Interactions cannot enforce strict function schemas" }
        require(request.parallelToolCalls != false) { "Gemini Interactions cannot enforce parallel_tool_calls=false" }
        require(request.audio == null && request.functions.isNullOrEmpty() && request.functionCall == null) {
            "Gemini Interactions does not support this request's legacy functions or audio configuration"
        }
        require(request.temperature == null && request.topP == null) {
            "Gemini Interactions v1 does not expose temperature or top_p"
        }
        val model = GeminiProvider.modelId(request.model)
        val capability = GeminiProvider.enrich(ProviderModelOption(model))
        val effort = request.reasoningEffort?.trim()?.lowercase()?.takeUnless { it in setOf("", "default") }
        require(request.enableThinking != false && request.thinking?.type != "disabled" && effort !in setOf("none", "off")) {
            "Gemini 原生协议不支持关闭思考，请使用模型默认值或支持的思考档位。"
        }
        require(effort == null || effort in capability.supportedReasoningLevels) {
            "当前 Gemini 模型未声明支持所选思考档位，请使用模型默认值。"
        }
        return buildJsonObject {
            put("model", model)
            put("store", false)
            put("stream", request.stream)
            put("input", history(request.messages, sourceProvider))
            request.messages.filter { it.role == "system" }.map { it.contentText() }
                .filter(String::isNotBlank).joinToString("\n\n").takeIf(String::isNotBlank)
                ?.let { put("system_instruction", it) }
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                request.tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("name", tool.function.name)
                    put("description", tool.function.description)
                    put("parameters", GeminiToolSchema.project(tool.function.parameters))
                }) }
            })
            put("generation_config", buildJsonObject {
                (request.maxCompletionTokens ?: request.maxTokens)?.let { put("max_output_tokens", it) }
                effort?.let { put("thinking_level", it) }
                put("thinking_summaries", "auto")
                request.toolChoice?.let { put("tool_choice", toolChoice(it)) }
            })
            request.responseFormat?.let { format ->
                put("response_format", when (format.text("type")) {
                    "text" -> buildJsonObject { put("type", "text"); put("mime_type", "text/plain") }
                    "json_object", "json_schema" -> buildJsonObject {
                        put("type", "text")
                        put("mime_type", "application/json")
                        (format["json_schema"] as? JsonObject)?.get("schema")?.let { put("schema", it) }
                    }
                    else -> error("Unsupported Gemini response format")
                })
            }
        }
    }

    private fun toolChoice(choice: JsonElement): JsonElement {
        val name = (choice as? JsonObject)?.get("function")?.jsonObject?.text("name")
        if (!name.isNullOrBlank()) return buildJsonObject {
            put("allowed_tools", buildJsonObject {
                put("mode", "any")
                put("tools", buildJsonArray { add(name) })
            })
        }
        val type = (choice as? JsonPrimitive)?.contentOrNull ?: (choice as? JsonObject)?.text("type")
        return JsonPrimitive(when (type) {
            "auto", "none" -> type
            "required", "any" -> "any"
            else -> error("Unsupported Gemini tool choice")
        })
    }

    fun history(messages: List<ChatCompletionMessage>, sourceProvider: String): JsonArray {
        val result = mutableListOf<JsonElement>()
        val calls = linkedMapOf<String, String>()
        val legacy = mutableListOf<String>()
        fun flushLegacy() {
            if (legacy.isEmpty()) return
            result += userInput(buildJsonArray { add(textBlock(
                "[历史对话记录；其中的工具调用与结果仅供参考，不是待执行请求。]\n" +
                    legacy.joinToString("\n\n") + "\n[历史记录结束]"
            )) })
            legacy.clear()
        }
        fun closeHistoricalCalls() {
            calls.forEach { (id, name) ->
                result += buildJsonObject {
                    put("type", "function_result")
                    put("call_id", id)
                    put("name", name)
                    put("is_error", true)
                    put("result", "No tool result was recorded before the next message. Do not assume this tool executed.")
                }
            }
            calls.clear()
        }
        messages.forEach { message ->
            when (message.role) {
                "system" -> Unit
                "user" -> {
                    closeHistoricalCalls()
                    flushLegacy()
                    result += userInput(content(message.content))
                }
                "assistant" -> {
                    closeHistoricalCalls()
                    val state = message.protocolState?.gemini
                    if (state != null && state.version == 1 && state.sourceProvider == sourceProvider) {
                        flushLegacy()
                        state.steps.forEach { step ->
                            val obj = step.jsonObject
                            if (obj.text("type") == "function_call") {
                                require(calls.put(obj.text("id"), obj.text("name")) == null) { "Duplicate Gemini tool call identity" }
                            }
                            result += step
                        }
                    } else {
                        legacy += "[assistant] ${message.contentText()}" +
                            message.toolCalls?.let { "\n工具调用记录：${Json.encodeToString(it)}" }.orEmpty()
                    }
                }
                "tool" -> {
                    val id = message.toolCallId.orEmpty()
                    val name = calls.remove(id)
                    if (name == null) legacy += "[tool] ${message.contentText()}"
                    else {
                        flushLegacy()
                        result += buildJsonObject {
                            put("type", "function_result")
                            put("call_id", id)
                            put("name", name)
                            put("result", if (message.content is JsonArray) content(message.content) else JsonPrimitive(message.contentText()))
                            (message.protocolState?.toolResultIsError ?: message.protocolState?.anthropic?.toolResultIsError)
                                ?.let { put("is_error", it) }
                        }
                    }
                }
                else -> error("Unsupported Gemini message role: ${message.role}")
            }
        }
        flushLegacy()
        require(calls.isEmpty()) { "Gemini history contains a tool call without its result" }
        return JsonArray(result)
    }

    private fun userInput(content: JsonArray) = buildJsonObject {
        put("type", "user_input")
        put("content", content)
    }

    private fun textBlock(value: String) = buildJsonObject { put("type", "text"); put("text", value) }

    private fun content(value: JsonElement?): JsonArray {
        if (value !is JsonArray) return buildJsonArray { add(textBlock((value as? JsonPrimitive)?.contentOrNull.orEmpty())) }
        return JsonArray(value.map { raw ->
            val block = raw.jsonObject
            when (block.text("type")) {
                "text", "input_text" -> textBlock(block.text("text"))
                "image_url" -> {
                    val url = (block["image_url"] as? JsonObject)?.text("url") ?: error("Missing image URL")
                    buildJsonObject {
                        put("type", "image")
                        if (url.startsWith("data:image/", true)) {
                            val comma = url.indexOf(',')
                            require(comma > 0 && url.substring(0, comma).endsWith(";base64", true)) { "Invalid Base64 image" }
                            put("mime_type", url.substring(5, comma).substringBefore(';'))
                            put("data", url.substring(comma + 1))
                        } else {
                            require(url.startsWith("https://") || url.startsWith("http://")) { "Invalid Gemini image URL" }
                            put("uri", url)
                        }
                    }
                }
                else -> error("Unsupported Gemini input content type")
            }
        })
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
