package cn.com.omnimind.bot.agent.web

import android.app.Application
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRunControl
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.WebToolHandler
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class WebToolHandlerTest {
    private lateinit var server: MockWebServer
    private lateinit var handler: WebToolHandler
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val transport = WebHttpTransport(OkHttpClient(), allowPrivateAddresses = true)
        val searchClient = PublicWebSearchClient(
            transport = transport,
            searchEndpoint = server.url("/html/").toString(),
            allowedHostPredicate = { host -> host == server.hostName },
        )
        val helper = SharedHelper(RuntimeEnvironment.getApplication(), json)
        handler = WebToolHandler(
            helper = helper,
            transport = transport,
            searchClient = searchClient,
            now = { Instant.parse("2026-11-16T02:00:00Z") },
        )
    }

    @After
    fun tearDown() {
        runBlocking { handler.dispose() }
        server.shutdown()
    }

    @Test
    fun `web_search executes against endpoint and returns structured results`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html><body><div id="links">
                      <div class="result web-result">
                        <a class="result__a" href="https://example.org/item1">Example Item 1</a>
                        <a class="result__snippet">Snippet one</a>
                      </div>
                    </div></body></html>
                    """.trimIndent(),
                ),
        )

        val result = handler.execute(
            toolCall = AssistantToolCall(id = "call-1", function = AssistantToolCallFunction("web_search", "{}")),
            args = buildJsonObject {
                put("query", "example query")
                put("max_results", 3)
            },
            runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor("web_search", "搜索网页", "search", supportsParallel = true),
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_search", "call-1"),
        ) as ToolExecutionResult.ContextResult

        assertTrue(result.success)
        val payload = json.parseToJsonElement(result.rawResultJson).jsonObject
        assertEquals("duckduckgo_html", payload["provider"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, payload["count"]?.jsonPrimitive?.intOrNull)
        val first = payload["results"]!!.jsonArray.single().jsonObject
        assertEquals("Example Item 1", first["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.org/item1", first["url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("untrusted_web_content", payload["source_role"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `web_fetch caches snapshot across continuation pages without second http request`() = runBlocking {
        val longBody = buildString {
            repeat(220) { index ->
                append("Paragraph $index content with enough text for paging. ")
            }
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Paged Article</title></head><body><main><p>$longBody</p></main></body></html>"),
        )

        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("web_fetch", "读取网页", "browser", supportsParallel = true)
        val firstResult = handler.execute(
            toolCall = AssistantToolCall(id = "fetch-1", function = AssistantToolCallFunction("web_fetch", "{}")),
            args = buildJsonObject {
                put("url", server.url("/article").toString())
                put("max_chars", 1_500)
            },
            runtimeDescriptor = descriptor,
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "fetch-1"),
        ) as ToolExecutionResult.ContextResult

        assertTrue(firstResult.success)
        val firstPayload = json.parseToJsonElement(firstResult.rawResultJson).jsonObject
        val documentId = firstPayload["document_id"]?.jsonPrimitive?.contentOrNull
        assertNotNull(documentId)
        assertEquals("Paged Article", firstPayload["title"]?.jsonPrimitive?.contentOrNull)
        assertTrue(firstPayload["has_more"]?.jsonPrimitive?.booleanOrNull == true)
        val nextOffset = firstPayload["next_offset_chars"]!!.jsonPrimitive.intOrNull!!
        assertEquals(1_500, nextOffset)
        assertEquals(1, server.requestCount)

        // Second page must reuse the cached snapshot without hitting MockWebServer again.
        val secondResult = handler.execute(
            toolCall = AssistantToolCall(id = "fetch-2", function = AssistantToolCallFunction("web_fetch", "{}")),
            args = buildJsonObject {
                put("document_id", documentId!!)
                put("offset_chars", nextOffset)
                put("max_chars", 1_500)
            },
            runtimeDescriptor = descriptor,
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "fetch-2"),
        ) as ToolExecutionResult.ContextResult

        assertTrue(secondResult.success)
        val secondPayload = json.parseToJsonElement(secondResult.rawResultJson).jsonObject
        assertEquals(nextOffset, secondPayload["offset_chars"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, server.requestCount)

        // Unknown document_id returns structured error.
        val expiredResult = handler.execute(
            toolCall = AssistantToolCall(id = "fetch-3", function = AssistantToolCallFunction("web_fetch", "{}")),
            args = buildJsonObject {
                put("document_id", "web_nonexistent")
            },
            runtimeDescriptor = descriptor,
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "fetch-3"),
        ) as ToolExecutionResult.ContextResult

        assertFalse(expiredResult.success)
        val expiredPayload = json.parseToJsonElement(expiredResult.rawResultJson).jsonObject
        assertEquals("WEB_DOCUMENT_EXPIRED", expiredPayload["error_code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `web_fetch returns structured failure on non-2xx and mutually exclusive url or document_id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found Secret"))
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("web_fetch", "读取网页", "browser", supportsParallel = true)

        val notFound = handler.execute(
            toolCall = AssistantToolCall(id = "fetch-404", function = AssistantToolCallFunction("web_fetch", "{}")),
            args = buildJsonObject { put("url", server.url("/missing").toString()) },
            runtimeDescriptor = descriptor,
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "fetch-404"),
        ) as ToolExecutionResult.ContextResult

        assertFalse(notFound.success)
        val payload = json.parseToJsonElement(notFound.rawResultJson).jsonObject
        assertEquals("WEB_HTTP_ERROR", payload["error_code"]?.jsonPrimitive?.contentOrNull)
        assertEquals(404, payload["status"]?.jsonPrimitive?.intOrNull)
        assertFalse(notFound.rawResultJson.contains("Not Found Secret"))

        val bothArgs = handler.execute(
            toolCall = AssistantToolCall(id = "fetch-bad", function = AssistantToolCallFunction("web_fetch", "{}")),
            args = buildJsonObject {
                put("url", server.url("/ok").toString())
                put("document_id", "web_1")
            },
            runtimeDescriptor = descriptor,
            env = FakeEnv,
            callback = NoOpCallback,
            toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "fetch-bad"),
        ) as ToolExecutionResult.ContextResult

        assertFalse(bothArgs.success)
        val badPayload = json.parseToJsonElement(bothArgs.rawResultJson).jsonObject
        assertEquals("INVALID_ARGUMENT", badPayload["error_code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `web_search uses deepseek backend with auth header and 128 char query cap`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "results": [
                        {
                          "title": "DeepSeek Search Guide",
                          "url": "https://api-docs.deepseek.com/guides/search",
                          "snippet": "Short snippet",
                          "summary": "Detailed summary from DeepSeek",
                          "site_name": "DeepSeek Docs",
                          "date": "2026-10-17T00:00:00Z",
                          "authority": 3,
                          "rerank_score": 2.4
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val deepSeekConfig = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.DEEPSEEK,
            deepSeekBaseUrl = server.url("/").toString().trimEnd('/'),
            deepSeekApiKey = "sk-deepseek-test",
        )
        val customHandler = WebToolHandler(
            helper = SharedHelper(RuntimeEnvironment.getApplication(), json),
            transport = WebHttpTransport(OkHttpClient(), allowPrivateAddresses = true),
            runtimeConfig = { deepSeekConfig },
            now = { Instant.parse("2026-11-16T02:00:00Z") },
        )
        try {
            val longQuery = "DeepSeek ".repeat(20)
            val result = customHandler.execute(
                toolCall = AssistantToolCall(id = "ds-1", function = AssistantToolCallFunction("web_search", "{}")),
                args = buildJsonObject {
                    put("query", longQuery)
                    put("max_results", 5)
                },
                runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor("web_search", "搜索网页", "search", supportsParallel = true),
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_search", "ds-1"),
            ) as ToolExecutionResult.ContextResult

            assertTrue(result.success)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/v1/tools/search", recorded.path)
            assertEquals("Bearer sk-deepseek-test", recorded.getHeader("Authorization"))
            val sentBody = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
            assertEquals(128, sentBody["query"]?.jsonPrimitive?.contentOrNull?.length)
            assertEquals(5, sentBody["count"]?.jsonPrimitive?.intOrNull)

            val payload = json.parseToJsonElement(result.rawResultJson).jsonObject
            assertEquals("deepseek", payload["provider"]?.jsonPrimitive?.contentOrNull)
            assertTrue(payload["truncated"]?.jsonPrimitive?.booleanOrNull == true)
            val item = payload["results"]!!.jsonArray.single().jsonObject
            assertEquals("Detailed summary from DeepSeek", item["summary"]?.jsonPrimitive?.contentOrNull)
            assertEquals("DeepSeek Docs", item["site_name"]?.jsonPrimitive?.contentOrNull)
            assertEquals(3, item["authority"]?.jsonPrimitive?.intOrNull)
        } finally {
            customHandler.dispose()
        }
    }

    @Test
    fun `web_fetch uses deepseek backend caches markdown and falls back to local on failure`() = runBlocking {
        // 1. Remote fetch succeeds
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "title": "Remote Markdown Title",
                      "content": "# Heading\n\n${"Markdown paragraph content. ".repeat(120)}"
                    }
                    """.trimIndent(),
                ),
        )
        // 2. Remote fetch fails with 404, then local static fetch succeeds
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Local Fallback Title</title></head><body><main>Fallback static content</main></body></html>"),
        )

        val deepSeekConfig = WebToolsRuntimeConfig(
            fetchBackend = WebToolBackend.DEEPSEEK,
            deepSeekBaseUrl = server.url("/v1").toString(),
            deepSeekApiKey = "sk-deepseek-test",
        )
        val customHandler = WebToolHandler(
            helper = SharedHelper(RuntimeEnvironment.getApplication(), json),
            transport = WebHttpTransport(OkHttpClient(), allowPrivateAddresses = true),
            runtimeConfig = { deepSeekConfig },
            now = { Instant.parse("2026-11-16T02:00:00Z") },
        )
        try {
            val descriptor = AgentToolRegistry.RuntimeToolDescriptor("web_fetch", "读取网页", "browser", supportsParallel = true)
            val first = customHandler.execute(
                toolCall = AssistantToolCall(id = "ds-fetch-1", function = AssistantToolCallFunction("web_fetch", "{}")),
                args = buildJsonObject {
                    put("url", server.url("/target-1").toString())
                    put("max_chars", 1_000)
                },
                runtimeDescriptor = descriptor,
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "ds-fetch-1"),
            ) as ToolExecutionResult.ContextResult

            assertTrue(first.success)
            val firstPayload = json.parseToJsonElement(first.rawResultJson).jsonObject
            assertEquals("deepseek", firstPayload["provider"]?.jsonPrimitive?.contentOrNull)
            assertEquals("markdown", firstPayload["format"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Remote Markdown Title", firstPayload["title"]?.jsonPrimitive?.contentOrNull)
            assertTrue(firstPayload["has_more"]?.jsonPrimitive?.booleanOrNull == true)

            val docId = firstPayload["document_id"]!!.jsonPrimitive.content
            val nextOffset = firstPayload["next_offset_chars"]!!.jsonPrimitive.intOrNull!!
            val page2 = customHandler.execute(
                toolCall = AssistantToolCall(id = "ds-fetch-2", function = AssistantToolCallFunction("web_fetch", "{}")),
                args = buildJsonObject {
                    put("document_id", docId)
                    put("offset_chars", nextOffset)
                    put("max_chars", 1_000)
                },
                runtimeDescriptor = descriptor,
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "ds-fetch-2"),
            ) as ToolExecutionResult.ContextResult
            assertTrue(page2.success)
            assertEquals(1, server.requestCount)

            // Fallback path
            val fallback = customHandler.execute(
                toolCall = AssistantToolCall(id = "ds-fetch-3", function = AssistantToolCallFunction("web_fetch", "{}")),
                args = buildJsonObject { put("url", server.url("/target-2").toString()) },
                runtimeDescriptor = descriptor,
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "ds-fetch-3"),
            ) as ToolExecutionResult.ContextResult

            assertTrue(fallback.success)
            val fallbackPayload = json.parseToJsonElement(fallback.rawResultJson).jsonObject
            assertEquals("builtin_local", fallbackPayload["provider"]?.jsonPrimitive?.contentOrNull)
            assertEquals("deepseek", fallbackPayload["fallback_from"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Local Fallback Title", fallbackPayload["title"]?.jsonPrimitive?.contentOrNull)
            assertTrue(fallbackPayload["content"]!!.jsonPrimitive.content.contains("Fallback static content"))
            assertEquals(3, server.requestCount)
        } finally {
            customHandler.dispose()
        }
    }

    @Test
    fun `custom search and fetch map configured json paths`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":{"items":[{"headline":"Custom Title","link":"https://example.org/custom","desc":"Custom Snippet"}]}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"data":{"page_title":"Custom Page","markdown":"Custom fetched markdown body"}}"""),
        )

        val customConfig = WebToolsRuntimeConfig(
            searchBackend = WebToolBackend.CUSTOM,
            fetchBackend = WebToolBackend.CUSTOM,
            customSearch = CustomWebSearchConfig(
                method = WebHttpMethod.POST,
                url = server.url("/custom/search").toString(),
                resultsPath = "data.items",
                titleField = "headline",
                urlField = "link",
                snippetField = "desc",
            ),
            customFetch = CustomWebFetchConfig(
                method = WebHttpMethod.POST,
                url = server.url("/custom/fetch").toString(),
                contentField = "data.markdown",
                titleField = "data.page_title",
            ),
        )
        val customHandler = WebToolHandler(
            helper = SharedHelper(RuntimeEnvironment.getApplication(), json),
            transport = WebHttpTransport(OkHttpClient(), allowPrivateAddresses = true),
            runtimeConfig = { customConfig },
            now = { Instant.parse("2026-11-16T02:00:00Z") },
        )
        try {
            val searchResult = customHandler.execute(
                toolCall = AssistantToolCall(id = "c-1", function = AssistantToolCallFunction("web_search", "{}")),
                args = buildJsonObject { put("query", "test \"quotes\"") },
                runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor("web_search", "搜索网页", "search", supportsParallel = true),
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_search", "c-1"),
            ) as ToolExecutionResult.ContextResult
            assertTrue(searchResult.success)
            val searchPayload = json.parseToJsonElement(searchResult.rawResultJson).jsonObject
            assertEquals("custom", searchPayload["provider"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Custom Title", searchPayload["results"]!!.jsonArray.single().jsonObject["title"]?.jsonPrimitive?.contentOrNull)

            val fetchResult = customHandler.execute(
                toolCall = AssistantToolCall(id = "c-2", function = AssistantToolCallFunction("web_fetch", "{}")),
                args = buildJsonObject { put("url", "https://example.org/target") },
                runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor("web_fetch", "读取网页", "browser", supportsParallel = true),
                env = FakeEnv,
                callback = NoOpCallback,
                toolHandle = NoOpAgentRunControl.beginToolExecution("web_fetch", "c-2"),
            ) as ToolExecutionResult.ContextResult
            assertTrue(fetchResult.success)
            val fetchPayload = json.parseToJsonElement(fetchResult.rawResultJson).jsonObject
            assertEquals("custom", fetchPayload["provider"]?.jsonPrimitive?.contentOrNull)
            assertEquals("Custom Page", fetchPayload["title"]?.jsonPrimitive?.contentOrNull)
            assertTrue(fetchPayload["content"]!!.jsonPrimitive.content.contains("Custom fetched markdown body"))
        } finally {
            customHandler.dispose()
        }
    }

    private object FakeEnv : AgentExecutionEnvironment {
        override val userMessage: String = "test"
        override val conversationMode: String = "agent"
        override val reasoningEffort: String? = null
        override val runControl: AgentRunControl = NoOpAgentRunControl
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val agentRunId: String = "test-run"
        override val runtimeContextRepository: AgentRuntimeContextRepository
            get() = throw UnsupportedOperationException("unused")
        override val workspaceDescriptor: AgentWorkspaceDescriptor
            get() = throw UnsupportedOperationException("unused")
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused")
        override val terminalEnvironment: Map<String, String> = emptyMap()
    }

    private object NoOpCallback : AgentCallback {
        override suspend fun onThinkingStart() = Unit
        override suspend fun onThinkingUpdate(thinking: String) = Unit
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit
        override suspend fun onToolCallProgress(toolName: String, progress: String, extras: Map<String, Any?>) = Unit
        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit
        override suspend fun onChatMessage(message: String) = Unit
        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
        override suspend fun onComplete(result: AgentResult) = Unit
        override suspend fun onError(error: String) = Unit
        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
