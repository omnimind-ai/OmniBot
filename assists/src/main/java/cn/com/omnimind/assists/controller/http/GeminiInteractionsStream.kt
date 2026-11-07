package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/** Per-request wire decoder. Its terminal marker is never an ACP turn completion. */
class GeminiInteractionsStream(private val sourceProvider: String) {
    private data class Step(val fields: MutableMap<String, JsonElement>, val arguments: StringBuilder = StringBuilder(), var stopped: Boolean = false) {
        val type get() = (fields["type"] as? JsonPrimitive)?.content.orEmpty()
    }
    private val steps = sortedMapOf<Int, Step>()
    private val callIds = mutableSetOf<String>()
    private var usage: JsonObject = JsonObject(emptyMap())
    private var received = 0L
    var terminal = false
        private set

    fun accept(eventName: String?, data: String): List<String> {
        if (terminal) return emptyList()
        require(data.trim() != "[DONE]") { "Gemini stream closed before completion signal" }
        received += data.length
        require(data.length <= 8 * 1024 * 1024 && received <= 32 * 1024 * 1024) { "Gemini stream exceeds protocol size limit" }
        val event = Json.parseToJsonElement(data).jsonObject
        val type = event.text("event_type")
        require(type.isNotEmpty() && (eventName.isNullOrBlank() || eventName == type)) { "Invalid Gemini event type" }
        val emitted = mutableListOf<String>()
        (event["metadata"] as? JsonObject)?.get("total_usage")?.let { mergeUsage(it.jsonObject) }
        when (type) {
            "interaction.created" -> (event["interaction"] as? JsonObject)?.get("usage")?.let { mergeUsage(it.jsonObject) }
            "interaction.status_update" -> (event["usage"] as? JsonObject)?.let(::mergeUsage)
            "step.start" -> {
                val index = index(event)
                require(index !in steps && steps.size < 4096) { "Duplicate or excessive Gemini step" }
                val fields = event.getValue("step").jsonObject.toMutableMap()
                val state = Step(fields)
                require(state.type in setOf("model_output", "thought", "function_call")) { "Unsupported Gemini output step" }
                steps[index] = state
                when (state.type) {
                    "model_output", "thought" -> {
                        val key = if (state.type == "thought") "summary" else "content"
                        val parts = fields[key] as? JsonArray ?: JsonArray(emptyList())
                        val initial = text(parts)
                        if (initial.isNotEmpty()) emitted += chunk(if (key == "summary") "reasoning_content" else "content", initial)
                    }
                    "function_call" -> {
                        val call = JsonObject(fields)
                        require(call.text("id").isNotBlank() && call.text("name").isNotBlank() && callIds.add(call.text("id"))) { "Invalid Gemini tool identity" }
                        require(fields["arguments"] == null || fields["arguments"] is JsonObject) { "Invalid Gemini tool arguments" }
                        emitted += toolChunk(index, call.text("id"), call.text("name"), "")
                    }
                }
            }
            "step.delta" -> {
                val index = index(event)
                val state = steps[index]?.takeUnless { it.stopped } ?: error("Gemini delta has no active step")
                val delta = event.getValue("delta").jsonObject
                when (delta.text("type")) {
                    "text" -> {
                        require(state.type == "model_output")
                        val value = delta.text("text")
                        appendContent(state, "content", buildJsonObject { put("type", "text"); put("text", value) })
                        emitted += chunk("content", value)
                    }
                    "thought_summary" -> {
                        require(state.type == "thought")
                        val part = delta.getValue("content").jsonObject
                        val value = text(JsonArray(listOf(part)))
                        appendContent(state, "summary", part)
                        emitted += chunk("reasoning_content", value)
                    }
                    "thought_signature" -> {
                        require(state.type == "thought" && (delta["signature"] as? JsonPrimitive)?.isString == true)
                        // The signature delta is a complete opaque replacement, never a text fragment.
                        state.fields["signature"] = delta.getValue("signature")
                    }
                    "arguments_delta" -> {
                        require(state.type == "function_call")
                        val value = delta.text("arguments")
                        state.arguments.append(value)
                        require(state.arguments.length <= 1024 * 1024) { "Gemini tool arguments exceed size limit" }
                        emitted += toolChunk(index, null, null, value)
                    }
                    "text_annotation_delta" -> {
                        require(state.type == "model_output")
                        val content = (state.fields["content"] as? JsonArray).orEmpty().toMutableList()
                        require(content.isNotEmpty()) { "Gemini annotation without text" }
                        val last = content.last().jsonObject
                        content[content.lastIndex] = JsonObject(last + ("annotations" to JsonArray(
                            (last["annotations"] as? JsonArray).orEmpty() + delta.getValue("annotations").jsonArray
                        )))
                        state.fields["content"] = JsonArray(content)
                    }
                    else -> error("Unsupported Gemini output delta")
                }
            }
            "step.stop" -> {
                val index = index(event)
                val state = steps[index]?.takeUnless { it.stopped } ?: error("Gemini stop has no active step")
                if (state.type == "function_call") {
                    val arguments = if (state.arguments.isNotEmpty()) Json.parseToJsonElement(state.arguments.toString()).jsonObject
                        else state.fields["arguments"] as? JsonObject ?: error("Missing Gemini tool arguments")
                    if (state.arguments.isEmpty()) emitted += toolChunk(index, null, null, arguments.toString())
                    state.fields["arguments"] = arguments
                }
                state.stopped = true
                (event["usage"] as? JsonObject)?.let(::mergeUsage)
            }
            "interaction.completed" -> {
                val interaction = event.getValue("interaction").jsonObject
                (interaction["usage"] as? JsonObject)?.let(::mergeUsage)
                val status = interaction.text("status")
                require(status in setOf("completed", "requires_action")) { "Gemini interaction ended with status: $status" }
                require(steps.isNotEmpty() && steps.values.all { it.stopped }) { "Gemini stream closed before completion signal: unfinished steps" }
                require((status == "requires_action") == steps.values.any { it.type == "function_call" }) { "Gemini tool calls require requires_action status" }
                val protocolState = GeminiMessageProtocolState(sourceProvider = sourceProvider, steps = JsonArray(steps.values.map { JsonObject(it.fields) }))
                emitted += buildJsonObject {
                    put(ChatCompletionProtocolMetadata.GEMINI_STREAM_STATE_FIELD, Json.encodeToJsonElement(protocolState))
                    put("usage", normalizedUsage())
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("index", 0)
                        put("delta", JsonObject(emptyMap()))
                        put("finish_reason", if (status == "requires_action") "tool_calls" else "stop")
                    }) })
                }.toString()
                emitted += "[DONE]"
                terminal = true
            }
            "error" -> error("Gemini stream error: ${(event["error"] as? JsonObject)?.text("message").orEmpty()}")
        }
        return emitted
    }

    /** Non-streaming uses the same validation and projection as streaming. */
    fun complete(interaction: JsonObject): List<String> = buildList {
        (interaction["steps"] as? JsonArray).orEmpty().forEachIndexed { index, step ->
            addAll(accept(null, buildJsonObject { put("event_type", "step.start"); put("index", index); put("step", step) }.toString()))
            addAll(accept(null, buildJsonObject { put("event_type", "step.stop"); put("index", index) }.toString()))
        }
        addAll(accept(null, buildJsonObject { put("event_type", "interaction.completed"); put("interaction", interaction) }.toString()))
    }

    private fun index(event: JsonObject) = event["index"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: error("Invalid Gemini step index")
    private fun text(parts: JsonArray): String = parts.joinToString("") {
        val part = it.jsonObject
        require(part.text("type") == "text") { "Unsupported Gemini output content" }
        part.text("text")
    }
    private fun appendContent(step: Step, field: String, part: JsonObject) {
        val parts = (step.fields[field] as? JsonArray).orEmpty().toMutableList()
        // Text deltas extend the current part; summary entries remain distinct as returned.
        if (field == "content" && parts.lastOrNull()?.jsonObject?.text("type") == "text") {
            val previous = parts.last().jsonObject
            parts[parts.lastIndex] = JsonObject(previous + ("text" to JsonPrimitive(previous.text("text") + part.text("text"))))
        } else parts += part
        step.fields[field] = JsonArray(parts)
    }
    private fun mergeUsage(incoming: JsonObject) { usage = JsonObject(usage + incoming) }
    private fun normalizedUsage(): JsonObject {
        fun count(key: String): Long {
            val value = usage[key] ?: return 0L
            return value.jsonPrimitive.longOrNull?.takeIf { it >= 0 } ?: error("Invalid Gemini usage counter")
        }
        val input = count("total_input_tokens")
        val thought = count("total_thought_tokens")
        val output = Math.addExact(count("total_output_tokens"), thought)
        return buildJsonObject {
            put("prompt_tokens", input)
            put("completion_tokens", output)
            put("total_tokens", if ("total_tokens" in usage) count("total_tokens") else Math.addExact(input, output))
            put("prompt_tokens_details", buildJsonObject { put("cached_tokens", count("total_cached_tokens")) })
            put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", thought) })
        }
    }
    private fun chunk(field: String, value: String) = deltaChunk(buildJsonObject { put(field, value) })
    private fun toolChunk(index: Int, id: String?, name: String?, arguments: String) = deltaChunk(buildJsonObject {
        put("tool_calls", buildJsonArray { add(buildJsonObject {
            put("index", index)
            id?.let { put("id", it); put("type", "function") }
            put("function", buildJsonObject { name?.let { put("name", it) }; put("arguments", arguments) })
        }) })
    })
    private fun deltaChunk(delta: JsonObject) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject { put("index", 0); put("delta", delta) }) })
    }.toString()

    companion object {
        fun listener(sourceProvider: String, outer: EventSourceListener): EventSourceListener = object : EventSourceListener() {
            private val decoder = GeminiInteractionsStream(sourceProvider)
            private var failed = false
            override fun onOpen(eventSource: EventSource, response: Response) = outer.onOpen(eventSource, response)
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (failed) return
                try { decoder.accept(type, data).forEach { outer.onEvent(eventSource, id, null, it) } }
                catch (error: Exception) {
                    failed = true
                    outer.onFailure(eventSource, if (error is kotlinx.serialization.SerializationException) IllegalStateException("Invalid Gemini JSON payload") else error, null)
                    eventSource.cancel()
                }
            }
            override fun onClosed(eventSource: EventSource) {
                if (failed) return
                if (decoder.terminal) outer.onClosed(eventSource)
                else outer.onFailure(eventSource, IllegalStateException("Gemini stream closed before completion signal"), null)
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!failed) { failed = true; outer.onFailure(eventSource, t, response) }
            }
        }
    }
}
