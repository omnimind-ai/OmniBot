package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import java.lang.reflect.InvocationTargetException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerChatGptOAuthTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `OAuth request uses stateless Responses contract with complete tool history`() {
        val payload = oauthPayload("""
            {
              "model":"ignored", "stream":false,
              "max_completion_tokens":2048, "temperature":0.7, "top_p":0.9,
              "previous_response_id":"resp_old", "background":true,
              "conversation":"old", "metadata":{"private":"value"},
              "messages":[
                {"role":"system","content":"Follow user instructions."},
                {"role":"user","content":"Read the file"},
                {"role":"assistant","reasoning_content":"not signed replay state",
                 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}]},
                {"role":"tool","tool_call_id":"call_1","content":"file content"},
                {"role":"user","content":"Summarize it"}
              ],
              "tools":[{"type":"function","function":{"name":"read_file","description":"Read a file","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}],
              "tool_choice":{"type":"function","function":{"name":"read_file"}}
            }
        """.trimIndent())

        assertEquals("gpt-6.1-sol", payload["model"]?.jsonPrimitive?.content)
        assertEquals("true", payload["stream"]?.jsonPrimitive?.content)
        assertEquals("false", payload["store"]?.jsonPrimitive?.content)
        assertEquals("Follow user instructions.", payload["instructions"]?.jsonPrimitive?.content)
        listOf("max_output_tokens", "temperature", "top_p", "previous_response_id", "background", "conversation", "metadata", "tools")
            .forEach { assertFalse("Must omit $it", payload.containsKey(it)) }
        val input = payload["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("additional_tools", input.first()["type"]?.jsonPrimitive?.content)
        assertEquals("developer", input.first()["role"]?.jsonPrimitive?.content)
        assertEquals("read_file", input.first()["tools"]!!.jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        assertFalse(input.any { it["role"]?.jsonPrimitive?.content == "system" })
        assertFalse(input.any { it["type"]?.jsonPrimitive?.content == "reasoning" })
        val call = input.single { it["type"]?.jsonPrimitive?.content == "function_call" }
        val output = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(call["call_id"], output["call_id"])
        assertEquals("read_file", call["name"]?.jsonPrimitive?.content)
        assertEquals("file content", output["output"]?.jsonPrimitive?.content)
        assertEquals("read_file", payload["tool_choice"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals(2, input.count { it["role"]?.jsonPrimitive?.content == "user" })
    }

    @Test
    fun `OAuth text only request does not advertise tools`() {
        val payload = oauthPayload("""{"model":"unused","messages":[{"role":"user","content":"hello"}]}""")
        assertEquals(1, payload["input"]!!.jsonArray.size)
        assertFalse(payload.containsKey("tools"))
    }

    @Test
    fun `OAuth catalog preserves account order and selects visible slugs`() {
        val method = HttpController::class.java.getDeclaredMethod("parseChatGptModelsResponse", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val models = method.invoke(HttpController, """
            {"models":[
              {"slug":"z-model","id":"wrong-id","display_name":"First model","visibility":"list","context_window":128000},
              {"slug":"hidden-model","display_name":"Hidden","visibility":"hidden"},
              {"slug":"a-model","display_name":"Second model","visibility":"list"},
              {"slug":"unspecified","display_name":"Unspecified"}
            ]}
        """.trimIndent()) as List<ProviderModelOption>
        assertEquals(listOf("z-model", "a-model"), models.map { it.id })
        assertEquals(listOf("First model", "Second model"), models.map { it.displayName })
        assertEquals(128000, models.first().contextLimit)
    }

    @Test
    fun `OAuth credentials are request scoped and restricted to official endpoints`() {
        val payload = oauthPayload("""{"model":"unused","messages":[{"role":"user","content":"hello"}]}""")
        val catalog = oauthRequest("catalog-token", null)
        val inference = oauthRequest("new-token", payload)
        assertEquals("https://api.openai.com/v1/models", catalog.url.toString())
        assertEquals("https://api.openai.com/v1/responses", inference.url.toString())
        assertEquals("GET", catalog.method)
        assertEquals("POST", inference.method)
        assertEquals("Bearer catalog-token", catalog.header("Authorization"))
        assertEquals("Bearer new-token", inference.header("Authorization"))
        assertEquals("text/event-stream", inference.header("Accept"))
        val encoded = Buffer().also { inference.body!!.writeTo(it) }.readUtf8()
        assertFalse(encoded.contains("new-token"))
        assertEquals(payload, json.parseToJsonElement(encoded))
    }

    @Test
    fun `OAuth transport cannot forward credentials through redirects`() {
        val getter = HttpController::class.java.getDeclaredMethod("getChatGptModelsClient")
        getter.isAccessible = true
        val client = getter.invoke(HttpController) as OkHttpClient
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "/token-sink"))
            client.newCall(Request.Builder().url(server.url("/models"))
                .header("Authorization", "Bearer test-token").build()).execute().use { response ->
                assertEquals(307, response.code)
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `OAuth stream rejects EOF and DONE without official completion`() {
        listOf(false, true).forEach { sendDone ->
            var failure: Throwable? = null
            val chunks = mutableListOf<String>()
            val listener = HttpController.wrapResponsesListener(object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { chunks += data }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t }
            }, requireCompleted = true)
            val source = source()
            listener.onEvent(source, null, "response.output_text.delta", """{"delta":"partial answer"}""")
            if (sendDone) listener.onEvent(source, null, null, "[DONE]")
            listener.onClosed(source)
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("response.completed"))
            assertFalse(chunks.contains("[DONE]"))
        }
    }

    @Test
    fun `OAuth tool stream completes only on response completed and uses existing accumulator`() {
        val chunks = mutableListOf<String>()
        val listener = HttpController.wrapResponsesListener(object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { chunks += data }
        }, requireCompleted = true)
        val source = source()
        listener.onEvent(source, null, "response.output_item.added", """{"item":{"id":"item_1","type":"function_call","call_id":"call_1","name":"read_file","arguments":""}}""")
        listener.onEvent(source, null, "response.function_call_arguments.delta", """{"item_id":"item_1","delta":"{\"path\":\"a.txt\"}"}""")
        listener.onEvent(source, null, "response.function_call_arguments.done", """{"item_id":"item_1","name":"read_file","arguments":"{\"path\":\"a.txt\"}"}""")
        assertFalse(chunks.contains("[DONE]"))
        listener.onEvent(source, null, "response.completed", """{"response":{"status":"completed","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""")
        assertEquals(1, chunks.count { it == "[DONE]" })
        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()
        assertEquals("read_file", turn.message.toolCalls!!.single().function.name)
        assertEquals("{\"path\":\"a.txt\"}", turn.message.toolCalls!!.single().function.arguments)
        assertEquals("tool_calls", turn.finishReason)
        assertEquals(12, turn.usage?.totalTokens)
    }

    @Test
    fun `OAuth quota failure after partial output never becomes successful completion`() {
        val chunks = mutableListOf<String>()
        var failure: Throwable? = null
        var closed = false
        val listener = HttpController.wrapResponsesListener(object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { chunks += data }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t }
            override fun onClosed(eventSource: EventSource) { closed = true }
        }, requireCompleted = true)
        val source = source()
        listener.onEvent(source, null, "response.output_text.delta", """{"delta":"partial answer"}""")
        listener.onEvent(source, null, "response.failed", """{"response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"ChatGPT usage limit reached"}}}""")
        listener.onClosed(source)
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("usage limit"))
        assertFalse(closed)
        assertFalse(chunks.contains("[DONE]"))
    }

    @Test
    fun `OAuth completion rejects missing or contradictory response status`() {
        listOf("{}", "{\"response\":{}}", "{\"response\":{\"status\":\"incomplete\"}}")
            .forEach { payload ->
                var failure: Throwable? = null
                val chunks = mutableListOf<String>()
                val listener = HttpController.wrapResponsesListener(object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { chunks += data }
                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { failure = t }
                }, requireCompleted = true)
                listener.onEvent(source(), null, "response.completed", payload)
                assertNotNull(failure)
                assertFalse(chunks.contains("[DONE]"))
            }
    }

    @Test
    fun `OAuth semantic failure preserves exact error code and parameter`() {
        var failureBody: String? = null
        val listener = HttpController.wrapResponsesListener(object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                failureBody = response?.body?.string()
            }
        }, requireCompleted = true)
        listener.onEvent(source(), null, "response.failed", """{"response":{"error":{"code":"subscription_sharing_unsupported_capability","param":"tools","message":"Unsupported tool"}}}""")
        val error = json.parseToJsonElement(failureBody!!).jsonObject["error"]!!.jsonObject
        assertEquals("subscription_sharing_unsupported_capability", error["code"]?.jsonPrimitive?.content)
        assertEquals("tools", error["param"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deleted bound account cannot fall back to another configured provider`() {
        val another = ModelProviderProfile("other-account", "Other", baseUrl = "https://other.example/v1", apiKey = "key")
        val failure = runCatching { selectProvider(null, null, false, true, null, another) }.exceptionOrNull()
        assertTrue(failure is InvocationTargetException)
        assertTrue(failure!!.cause is IllegalStateException)
    }

    @Test
    fun `bound OAuth account remains selected when logged out`() {
        val oauth = ModelProviderProfile("chatgpt", "ChatGPT", baseUrl = "https://api.openai.com/v1", sourceType = "chatgpt_oauth", ready = false)
        val another = ModelProviderProfile("other-account", "Other", baseUrl = "https://other.example/v1", apiKey = "key")
        assertEquals(oauth, selectProvider(null, null, true, false, oauth, another))
    }

    @Test
    fun `explicit OAuth selection rejects stale converted profiles and overrides scene selection`() {
        val oauth = ModelProviderProfile("chatgpt", "ChatGPT", baseUrl = "https://api.openai.com/v1", sourceType = "chatgpt_oauth")
        val another = ModelProviderProfile("other-account", "Other", baseUrl = "https://other.example/v1", apiKey = "key")
        assertEquals(oauth, selectProvider(oauth.id, oauth, true, false, another, another))
        listOf<ModelProviderProfile?>(null, oauth.copy(sourceType = "custom")).forEach { stale ->
            val failure = runCatching { selectProvider(oauth.id, stale, false, false, null, another) }.exceptionOrNull()
            assertTrue(failure is InvocationTargetException)
            assertTrue(failure!!.cause is IllegalStateException)
        }
    }

    @Test
    fun `deleted direct VLM provider is authoritative over a healthy dispatch binding`() {
        val direct = SceneModelBindingEntry("scene.vlm.operation.primary", "deleted-account", "vision-model")
        val shared = SceneModelBindingEntry("scene.dispatch.model", "healthy-account", "shared-model")
        val method = HttpController::class.java.getDeclaredMethod("selectSceneBinding",
            SceneModelBindingEntry::class.java, SceneModelBindingEntry::class.java)
        method.isAccessible = true
        val selected = method.invoke(HttpController, direct, shared) as SceneModelBindingEntry
        assertEquals("deleted-account", selected.providerProfileId)
        val healthy = ModelProviderProfile("healthy-account", "Healthy", baseUrl = "https://other.example/v1", apiKey = "key")
        val failure = runCatching { selectProvider(null, null, false, true, null, healthy) }.exceptionOrNull()
        assertTrue(failure is InvocationTargetException)
        assertTrue(failure!!.cause is IllegalStateException)
        assertEquals(shared, method.invoke(HttpController, null, shared))
    }

    @Test
    fun `cleared bound provider cannot use editing provider credentials`() {
        val cleared = ModelProviderProfile("cleared", "Cleared", baseUrl = "")
        val fallback = ModelProviderProfile("other", "Other", baseUrl = "https://other.example/v1", apiKey = "key")
        val failure = runCatching { selectProvider(null, null, false, !cleared.isConfigured(), cleared, fallback) }.exceptionOrNull()
        assertTrue(failure is InvocationTargetException)
        assertTrue(failure!!.cause is IllegalStateException)
    }

    private fun selectProvider(
        explicitId: String?, explicit: ModelProviderProfile?, bindingApplied: Boolean,
        bindingMissing: Boolean, bound: ModelProviderProfile?, fallback: ModelProviderProfile?,
    ): ModelProviderProfile? {
        val method = HttpController::class.java.getDeclaredMethod("selectRequestProvider",
            String::class.java, ModelProviderProfile::class.java, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, ModelProviderProfile::class.java, ModelProviderProfile::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(HttpController, explicitId, explicit, bindingApplied, bindingMissing, bound, fallback, null) as? ModelProviderProfile
    }

    private fun oauthPayload(raw: String): JsonObject {
        val method = HttpController::class.java.getDeclaredMethod("buildChatGptResponsesRequestPayload", JsonObject::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(HttpController, json.parseToJsonElement(raw).jsonObject, "gpt-6.1-sol") as JsonObject
    }

    private fun oauthRequest(token: String, payload: JsonObject?): Request {
        val method = HttpController::class.java.getDeclaredMethod("buildChatGptRequest", String::class.java, JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(HttpController, token, payload) as Request
    }

    private fun source() = object : EventSource {
        override fun request() = Request.Builder().url("https://api.openai.com/v1/responses").build()
        override fun cancel() = Unit
    }
}
