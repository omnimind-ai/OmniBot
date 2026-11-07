package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiInteractionsTest {
    private val source = "provider@https://example.test/v1"
    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun request(vararg messages: ChatCompletionMessage) = ChatCompletionRequest(messages.toList(), "gemini-3.8-flash", stream = true)
    private val user = ChatCompletionMessage("user", JsonPrimitive("hello"))

    @Test fun `request uses native stateless contract and declared thinking`() {
        val body = GeminiInteractionsRequest.build(request(ChatCompletionMessage("system", JsonPrimitive("system")), user).copy(reasoningEffort = "high"), source)
        assertEquals(JsonPrimitive(false), body["store"])
        assertFalse(body.containsKey("previous_interaction_id"))
        assertEquals("system", body["system_instruction"]!!.jsonPrimitive.content)
        assertEquals("high", body["generation_config"]!!.jsonObject["thinking_level"]!!.jsonPrimitive.content)
        assertEquals("user_input", body["input"]!!.jsonArray.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/v1/interactions", GeminiInteractionsRequest.url("https://example.test/v1/"))
        assertEquals("https://example.test/custom", GeminiInteractionsRequest.url("https://example.test/custom#"))
        assertEquals("https://example.test/v1/models", GeminiInteractionsRequest.modelsUrl("https://example.test/v1/interactions#"))
    }

    @Test fun `unsupported explicit settings fail instead of changing semantics`() {
        listOf(request(user).copy(reasoningEffort = "none"), request(user).copy(reasoningEffort = "max"),
            request(user).copy(model = "unknown", reasoningEffort = "high"), request(user).copy(parallelToolCalls = false),
            request(user).copy(temperature = 0.2)).forEach {
            assertThrows(IllegalArgumentException::class.java) { GeminiInteractionsRequest.build(it, source) }
        }
        assertFalse(GeminiInteractionsRequest.build(request(user).copy(model = "unknown"), source)["generation_config"]!!.jsonObject.containsKey("thinking_level"))
    }

    @Test fun `signed history survives serialization and model switching but not provider switching`() {
        val steps = Json.parseToJsonElement("""[{"type":"thought","signature":"opaque+/=="},{"type":"function_call","id":"call1","name":"read","arguments":{"path":"a"}}]""").jsonArray
        val assistant = ChatCompletionMessage("assistant", protocolState = ChatCompletionProtocolState(gemini = GeminiMessageProtocolState(sourceProvider = source, steps = steps)))
        val restored = Json.decodeFromString<ChatCompletionMessage>(Json.encodeToString(assistant))
        val tool = ChatCompletionMessage("tool", JsonPrimitive("done"), toolCallId = "call1", protocolState = ChatCompletionProtocolState(toolResultIsError = true))
        val body = GeminiInteractionsRequest.build(request(user, restored, tool).copy(model = "gemini-4-argon"), source)
        val input = body["input"]!!.jsonArray
        assertEquals(steps[0], input[1])
        assertEquals(steps[1], input[2])
        assertEquals(JsonPrimitive(true), input[3].jsonObject["is_error"])
        assertFalse(GeminiInteractionsRequest.build(request(user, restored, tool), "other-provider").toString().contains("opaque+/=="))
        assertFalse(ChatCompletionProtocolMetadata.withoutReplayData(body).toString().contains("opaque+/=="))
    }

    @Test fun `a new user message can continue interrupted historical calls without executing them`() {
        val assistant = ChatCompletionMessage("assistant", protocolState = ChatCompletionProtocolState(gemini = GeminiMessageProtocolState(
            sourceProvider = source, steps = Json.parseToJsonElement("""[{"type":"thought","signature":"preserved"},{"type":"function_call","id":"old","name":"write","arguments":{}}]""").jsonArray,
        )))
        val history = GeminiInteractionsRequest.history(listOf(assistant, user), source)
        assertEquals("function_result", history[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), history[2].jsonObject["is_error"])
        assertEquals("old", history[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("user_input", history.last().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `user images and tool screenshots stay image content`() {
        val image = Json.parseToJsonElement("""[{"type":"text","text":"image"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]""")
        val steps = Json.parseToJsonElement("""[{"type":"function_call","id":"c","name":"screenshot","arguments":{}}]""").jsonArray
        val messages = request(ChatCompletionMessage("user", image), ChatCompletionMessage("assistant", protocolState = ChatCompletionProtocolState(gemini = GeminiMessageProtocolState(sourceProvider = source, steps = steps))), ChatCompletionMessage("tool", image, toolCallId = "c"))
        val input = GeminiInteractionsRequest.build(messages, source)["input"]!!.jsonArray
        assertEquals("image", input[0].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", input[2].jsonObject["result"]!!.jsonArray[1].jsonObject["mime_type"]!!.jsonPrimitive.content)
    }

    @Test fun `stream preserves ordered steps and complete opaque signature`() {
        val decoder = GeminiInteractionsStream(source)
        fun feed(raw: String) = decoder.accept(null, raw)
        feed("""{"event_type":"step.start","index":0,"step":{"type":"thought"}}""")
        feed("""{"event_type":"step.delta","index":0,"delta":{"type":"thought_signature","signature":"old"}}""")
        feed("""{"event_type":"step.delta","index":0,"delta":{"type":"thought_signature","signature":"opaque"}}""")
        feed("""{"event_type":"step.stop","index":0}""")
        feed("""{"event_type":"step.start","index":2,"step":{"type":"function_call","id":"c","name":"read","arguments":{}}}""")
        feed("""{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"{\"path\":"}}""")
        feed("""{"event_type":"step.delta","index":2,"delta":{"type":"arguments_delta","arguments":"\"a\"}"}}""")
        feed("""{"event_type":"step.stop","index":2}""")
        val output = feed("""{"event_type":"interaction.completed","interaction":{"status":"requires_action","usage":{"total_input_tokens":10,"total_output_tokens":4,"total_thought_tokens":6,"total_tokens":20,"total_cached_tokens":5}}}""")
        assertTrue(decoder.terminal)
        assertEquals("[DONE]", output.last())
        val final = obj(output.first())
        val state = final[ChatCompletionProtocolMetadata.GEMINI_STREAM_STATE_FIELD]!!.jsonObject
        assertEquals(obj("""{"type":"thought","signature":"opaque"}"""), state["steps"]!!.jsonArray.first())
        assertEquals("a", state["steps"]!!.jsonArray[1].jsonObject["arguments"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(10, final["usage"]!!.jsonObject["completion_tokens"]!!.jsonPrimitive.int)
        assertEquals(20, final["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
        assertTrue(feed("""{"event_type":"interaction.completed","interaction":{"status":"completed"}}""").isEmpty())
    }

    @Test fun `incomplete or unsuccessful responses cannot authorize tools`() {
        for (status in listOf("completed", "incomplete", "budget_exceeded", "failed", "cancelled")) {
            val decoder = GeminiInteractionsStream(source)
            assertThrows(IllegalArgumentException::class.java) {
                decoder.complete(obj("""{"status":"$status","steps":[{"type":"function_call","id":"c","name":"read","arguments":{}}]}"""))
            }
            assertFalse(decoder.terminal)
        }
        assertThrows(IllegalArgumentException::class.java) { GeminiInteractionsStream(source).accept("done", "[DONE]") }
        assertThrows(IllegalArgumentException::class.java) {
            val decoder = GeminiInteractionsStream(source)
            decoder.accept(null, """{"event_type":"step.start","index":0,"step":{"type":"model_output"}}""")
            decoder.accept(null, """{"event_type":"interaction.completed","interaction":{"status":"completed"}}""")
        }
    }

    @Test fun `catalog filters non generation models and exposes pagination`() {
        val page = GeminiModelCatalog.parse("""{"models":[{"name":"models/gemini-3.8-flash","inputTokenLimit":1000,"outputTokenLimit":100},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next"}""")
        assertEquals("next", page.nextPageToken)
        assertEquals("gemini-3.8-flash", page.models.single().id)
        assertEquals(1000, page.models.single().inputLimit)
        assertEquals(listOf("low", "medium", "high"), page.models.single().supportedReasoningLevels)
        assertEquals(false, page.models.single().reasoningCanDisable)
    }

    @Test fun `schema projection keeps required constraints and avoids copying references`() {
        val schema = obj("""{"type":"object","properties":{"x":{"type":"string"},"y":{"\u0024ref":"#/definitions/y"}},"anyOf":[{"required":["x"]},{"required":["y"]}]}""")
        val projected = GeminiToolSchema.project(schema)
        assertEquals("string", projected["anyOf"]!!.jsonArray[0].jsonObject["properties"]!!.jsonObject["x"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(projected["anyOf"]!!.jsonArray[1].jsonObject.containsKey("properties"))
        assertFalse(schema["anyOf"]!!.jsonArray[0].jsonObject.containsKey("properties"))
    }
}
