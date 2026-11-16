package cn.com.omnimind.bot.agent.web

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentToolCatalog
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.agent.AgentToolExecutionPolicy
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.SubagentProfileRegistry
import cn.com.omnimind.bot.agent.inheritedSubagentCatalog
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebToolsUnitTest {
    @Test
    fun `web_search and web_fetch schemas are localized and reserved`() {
        for (locale in listOf(PromptLocale.ZH_CN, PromptLocale.EN_US)) {
            val definitions = AgentToolDefinitions.staticTools(locale)
            val searchFn = definitions
                .mapNotNull { it["function"] as? JsonObject }
                .single { it["name"]?.jsonPrimitive?.contentOrNull == "web_search" }
            val fetchFn = definitions
                .mapNotNull { it["function"] as? JsonObject }
                .single { it["name"]?.jsonPrimitive?.contentOrNull == "web_fetch" }

            assertEquals("search", searchFn["toolType"]?.jsonPrimitive?.contentOrNull)
            assertEquals("browser", fetchFn["toolType"]?.jsonPrimitive?.contentOrNull)
            assertFalse(searchFn.containsKey("postToolRule"))
            assertFalse(fetchFn.containsKey("postToolRule"))

            val searchParams = searchFn["parameters"] as JsonObject
            assertEquals(false, searchParams["additionalProperties"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(listOf("query"), (searchParams["required"] as JsonArray).map { it.jsonPrimitive.content })

            val fetchProps = (fetchFn["parameters"] as JsonObject)["properties"] as JsonObject
            assertEquals(setOf("url", "document_id", "offset_chars", "max_chars"), fetchProps.keys)
            assertFalse("maxChars" in fetchProps)

            if (locale == PromptLocale.EN_US) {
                assertEquals("Web Search", searchFn["displayName"]?.jsonPrimitive?.contentOrNull)
                assertEquals("Fetch Web Page", fetchFn["displayName"]?.jsonPrimitive?.contentOrNull)
                assertTrue(searchFn["description"]!!.jsonPrimitive.content.contains("Search public web pages"))
                assertTrue(fetchFn["description"]!!.jsonPrimitive.content.contains("static HTTP(S)"))
            } else {
                assertEquals("搜索网页", searchFn["displayName"]?.jsonPrimitive?.contentOrNull)
                assertEquals("读取网页", fetchFn["displayName"]?.jsonPrimitive?.contentOrNull)
            }
        }
        assertTrue("web_search" in AgentToolDefinitions.reservedToolNames())
        assertTrue("web_fetch" in AgentToolDefinitions.reservedToolNames())
        assertTrue(AgentToolExecutionPolicy.supportsParallelRead("web_search"))
        assertTrue(AgentToolExecutionPolicy.supportsParallelRead("web_fetch"))
        assertTrue(
            AgentConversationModePolicy.filterToolDefinitionsForConversationMode(
                AgentToolDefinitions.staticTools(PromptLocale.ZH_CN),
                AgentConversationModePolicy.CHAT_ONLY_MODE,
            ).isEmpty(),
        )
    }

    @Test
    fun `explorer subagent inherits web_search and web_fetch while planner cannot`() {
        val parent = object : AgentToolCatalog {
            override val toolsForModel = listOf("web_search", "web_fetch", "terminal_execute").map { name ->
                ChatCompletionTool(function = ChatCompletionFunction(name = name))
            }

            override fun runtimeDescriptor(toolName: String) = AgentToolRegistry.RuntimeToolDescriptor(
                name = toolName,
                displayName = toolName,
                toolType = when (toolName) {
                    "web_search" -> "search"
                    "web_fetch" -> "browser"
                    else -> "terminal"
                },
            )

            override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
        }

        val explorer = inheritedSubagentCatalog(parent, SubagentProfileRegistry.explorer)
        assertEquals(listOf("web_search", "web_fetch"), explorer.toolsForModel.map { it.function.name })
        explorer.validateArguments("web_search", JsonObject(emptyMap()))
        explorer.validateArguments("web_fetch", JsonObject(emptyMap()))

        val planner = inheritedSubagentCatalog(parent, SubagentProfileRegistry.planner)
        assertTrue(planner.toolsForModel.isEmpty())
    }

    @Test
    fun `public web search parses results unwraps redirects and ignores ads`() {
        val html = """
            <html><body>
            <div id="links">
              <div class="result results_links web-result result--ad">
                <h2 class="result__title"><a class="result__a" href="https://ad.example.com">Ad Result</a></h2>
                <a class="result__snippet">Sponsored</a>
              </div>
              <div class="result results_links web-result">
                <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fhome.html">Kotlin &amp; Docs</a></h2>
                <a class="result__snippet">Official &ldquo;Kotlin&rdquo; documentation.</a>
              </div>
              <div class="result results_links web-result">
                <h2 class="result__title"><a class="result__a" href="https://developer.android.com/">Android Dev</a></h2>
                <a class="result__snippet">Android platform guides.</a>
              </div>
            </div>
            </body></html>
        """.trimIndent()
        val client = PublicWebSearchClient(WebHttpTransport(allowPrivateAddresses = true))
        val page = client.parse(html, "https://html.duckduckgo.com/html/?q=kotlin", maxResults = 5)

        assertEquals(2, page.results.size)
        assertEquals("Kotlin & Docs", page.results[0].title)
        assertEquals("https://kotlinlang.org/docs/home.html", page.results[0].url)
        assertEquals("Official “Kotlin” documentation.", page.results[0].snippet)
        assertEquals("https://developer.android.com/", page.results[1].url)
        assertFalse(page.limited)
    }

    @Test
    fun `public web search distinguishes challenge rate limit empty notice and structural change`() {
        val client = PublicWebSearchClient(WebHttpTransport(allowPrivateAddresses = true))

        val challengeEx = assertThrowsWebSearch {
            client.parse(
                "<html><body><form id='challenge-form' action='/anomaly.js'></form></body></html>",
                "https://html.duckduckgo.com/html/?q=test",
            )
        }
        assertEquals("SEARCH_CHALLENGE", challengeEx.code)

        val rateEx = assertThrowsWebSearch {
            client.parse(
                "<html><head><title>429 Too Many Requests</title></head><body></body></html>",
                "https://html.duckduckgo.com/html/?q=test",
            )
        }
        assertEquals("SEARCH_RATE_LIMITED", rateEx.code)

        val empty = client.parse(
            "<html><body><div class='no-results'>No results.</div></body></html>",
            "https://html.duckduckgo.com/html/?q=xyzzynonexistent",
        )
        assertTrue(empty.results.isEmpty())

        val parseFailEx = assertThrowsWebSearch {
            client.parse(
                "<html><body><div>Unexpected markup</div></body></html>",
                "https://html.duckduckgo.com/html/?q=test",
            )
        }
        assertEquals("SEARCH_PARSE_FAILED", parseFailEx.code)
    }

    @Test
    fun `html extractor strips noise resolves relative links and pages safely on surrogate pairs`() {
        val html = """
            <html>
              <head><title>Test &mdash; Page</title><style>.x{color:red}</style></head>
              <body>
                <header><a href="/home">Top Nav</a></header>
                <nav>Navigation links</nav>
                <main>
                  <h1>Heading</h1>
                  <p>Read <a href="/docs/guide.html">Guide</a> and <a href="javascript:void(0)">Ignored</a>.</p>
                  <script>alert('xss')</script>
                  <p>Emoji test: 😀😀😀😀</p>
                </main>
                <footer>Footer text</footer>
              </body>
            </html>
        """.trimIndent()

        val doc = WebPageContent.extract(html, "text/html; charset=utf-8", "https://example.com/blog/post.html")
        assertEquals("Test — Page", doc.title)
        assertTrue(doc.content.contains("Heading"))
        assertTrue(doc.content.contains("Read Guide [1] and Ignored."))
        assertFalse(doc.content.contains("Top Nav"))
        assertFalse(doc.content.contains("Navigation links"))
        assertFalse(doc.content.contains("alert"))
        assertFalse(doc.content.contains("Footer text"))
        assertEquals(1, doc.links.size)
        assertEquals("https://example.com/docs/guide.html", doc.links.single().url)

        // Ensure surrogate pair boundary is never split across pages
        val emojiDoc = WebPageDocument(
            url = "https://example.com/",
            title = null,
            content = "A😀B",
            contentType = "text/plain",
            format = "text",
            links = emptyList(),
            truncated = false,
            truncationReasons = emptyList(),
        )
        val first = WebPageContent.page(emojiDoc, offsetChars = 0, maxChars = 2)
        assertEquals("A", first.content)
        assertEquals(1, first.nextOffsetChars)
        val second = WebPageContent.page(emojiDoc, offsetChars = first.nextOffsetChars, maxChars = 2)
        assertEquals("😀", second.content)
        assertEquals(3, second.nextOffsetChars)
    }

    @Test
    fun `decoder respects meta charset and blocks binary signatures`() {
        val gbkHtml = """<html><head><meta charset="GBK"></head><body><p>中文测试</p></body></html>"""
        val decoded = WebResponseDecoder.decode(
            WebHttpResponse(
                requestedUrl = "https://example.com",
                finalUrl = "https://example.com",
                status = 200,
                contentType = "text/html",
                bytes = gbkHtml.toByteArray(Charset.forName("GBK")),
                truncated = false,
            ),
        )
        assertEquals("GBK", decoded.charset)
        assertTrue(decoded.text.contains("中文测试"))

        try {
            WebResponseDecoder.decode(
                WebHttpResponse(
                    requestedUrl = "https://example.com/file.pdf",
                    finalUrl = "https://example.com/file.pdf",
                    status = 200,
                    contentType = null,
                    bytes = "%PDF-1.7\n...".toByteArray(),
                    truncated = false,
                ),
            )
            fail("Expected binary content rejection")
        } catch (expected: WebRequestException) {
            assertEquals("BINARY_CONTENT", expected.code)
        }
    }

    @Test
    fun `http transport rejects localhost in public mode and detects redirect loops`() = runBlocking {
        val publicTransport = WebHttpTransport(allowPrivateAddresses = false)
        try {
            publicTransport.validateDestination("http://localhost:8080/secret")
            fail("Expected DISALLOWED_HOST")
        } catch (expected: WebRequestException) {
            assertEquals("DISALLOWED_HOST", expected.code)
        }

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/b"))
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/a"))
        server.start()
        try {
            val testTransport = WebHttpTransport(OkHttpClient(), allowPrivateAddresses = true)
            try {
                testTransport.get(server.url("/a").toString())
                fail("Expected redirect loop")
            } catch (expected: WebRequestException) {
                assertEquals("WEB_REDIRECT_LOOP", expected.code)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `deepSeekEndpoint normalizes base urls and strips anthropic suffix on official host`() {
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            ConfigurableWebBackends.deepSeekEndpoint("https://api.deepseek.com", "search"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/fetch",
            ConfigurableWebBackends.deepSeekEndpoint("https://api.deepseek.com/v1/", "fetch"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/search",
            ConfigurableWebBackends.deepSeekEndpoint("https://api.deepseek.com/anthropic", "search"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/tools/fetch",
            ConfigurableWebBackends.deepSeekEndpoint("https://api.deepseek.com/anthropic/v1", "fetch"),
        )
        assertEquals(
            "https://proxy.example.com/custom/v1/tools/search",
            ConfigurableWebBackends.deepSeekEndpoint("https://proxy.example.com/custom", "search"),
        )
    }

    @Test
    fun `web tools config validation checks deepseek credentials and custom endpoints`() {
        // Built-in mode requires no credentials
        WebToolsConfigStore.validate(
            settings = WebToolsSettings(),
            credentials = WebToolsCredentials(),
            hasProviderDeepSeekKey = false,
        )
        // Linked DeepSeek provider key suffices
        WebToolsConfigStore.validate(
            settings = WebToolsSettings(searchBackend = WebToolBackend.DEEPSEEK),
            credentials = WebToolsCredentials(),
            hasProviderDeepSeekKey = true,
        )
        // Missing both standalone and provider DeepSeek key fails
        try {
            WebToolsConfigStore.validate(
                settings = WebToolsSettings(fetchBackend = WebToolBackend.DEEPSEEK),
                credentials = WebToolsCredentials(),
                hasProviderDeepSeekKey = false,
            )
            fail("Expected WebToolsValidationException")
        } catch (_: WebToolsValidationException) {
            // expected
        }
        // Invalid custom header format fails
        try {
            WebToolsConfigStore.validate(
                settings = WebToolsSettings(
                    searchBackend = WebToolBackend.CUSTOM,
                    customSearch = CustomWebSearchConfig(
                        url = "https://search.example.com/api",
                        headersText = "InvalidHeaderWithoutColon",
                    ),
                ),
                credentials = WebToolsCredentials(),
                hasProviderDeepSeekKey = false,
            )
            fail("Expected WebToolsValidationException")
        } catch (_: WebToolsValidationException) {
            // expected
        }
    }

    private fun assertThrowsWebSearch(block: () -> Unit): WebSearchException {
        try {
            block()
            fail("Expected WebSearchException")
        } catch (ex: WebSearchException) {
            return ex
        }
        throw AssertionError("Unreachable")
    }
}
