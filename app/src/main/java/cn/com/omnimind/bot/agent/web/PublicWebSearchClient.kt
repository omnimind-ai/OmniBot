package cn.com.omnimind.bot.agent.web

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val summary: String? = null,
    val siteName: String? = null,
    val date: String? = null,
    val authority: Int? = null,
    val rerankScore: Double? = null,
)

internal data class WebSearchPage(
    val provider: String,
    val searchUrl: String,
    val results: List<WebSearchResult>,
    val limited: Boolean,
    val scope: String = "first_page",
    val limitReasons: List<String> = emptyList(),
    val skippedResults: Int = 0,
)

internal class WebSearchException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

/**
 * 公开网页检索客户端（基于 DuckDuckGo HTML 接口）。
 * 验证挑战、限流与结构解析异常均会显式失败，不会伪装成 0 条结果。
 */
internal class PublicWebSearchClient(
    private val transport: WebHttpTransport,
    private val searchEndpoint: String = DEFAULT_SEARCH_ENDPOINT,
    private val allowedHostPredicate: (String) -> Boolean = ::isDefaultSearchHost,
) {
    suspend fun search(
        query: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): WebSearchPage {
        val url = buildSearchUrl(query, maxResults)
        val response = transport.get(url, bindCancel = bindCancel)
        if (response.status == 202) {
            throw WebRequestException("SEARCH_CHALLENGE", "搜索站点要求人工验证，未取得搜索结果", 202)
        }
        if (response.status == 429) {
            throw WebRequestException("SEARCH_RATE_LIMITED", "搜索站点暂时限制请求，请稍后重试", 429)
        }
        if (response.status !in 200..299) {
            throw WebRequestException("SEARCH_HTTP_ERROR", "搜索请求失败（HTTP ${response.status}）", response.status)
        }
        if (response.truncated) {
            throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小限制，请缩短或精简查询词")
        }
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) {
            throw WebRequestException("SEARCH_ENCODING_ERROR", "搜索页面包含无法可靠解码的内容")
        }
        return parse(decoded.text, response.finalUrl, maxResults, transport::ensureActive)
    }

    fun buildSearchUrl(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): String {
        validateLimit(maxResults)
        if (query.isBlank() || query.length > MAX_QUERY_CHARS || '\u0000' in query ||
            !Charsets.UTF_8.newEncoder().canEncode(query)
        ) {
            fail("INVALID_ARGUMENT", "query 必须为 1 到 $MAX_QUERY_CHARS 个字符的有效文本")
        }
        val endpointUrl = searchEndpoint.toHttpUrlOrNull()
            ?: fail("SEARCH_CONFIG_ERROR", "搜索端点配置无效")
        val url = endpointUrl.newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
            .toString()
        if (url.length > MAX_URL_CHARS) {
            fail("INVALID_ARGUMENT", "查询编码后的网址超过长度限制，请缩短 query")
        }
        return url
    }

    fun parse(
        html: String,
        finalUrl: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
        ensureActive: () -> Unit = {},
    ): WebSearchPage {
        validateLimit(maxResults)
        ensureActive()
        if (html.length > MAX_HTML_CHARS) {
            fail("SEARCH_RESPONSE_TOO_LARGE", "搜索页面超过解析大小限制")
        }
        val base = httpUrl(finalUrl)?.takeIf { allowedHostPredicate(it.host) }
            ?: fail("SEARCH_PARSE_FAILED", "搜索响应没有来自预期的搜索站点")

        val document = SimpleHtmlParser.parse(html, ensureActive)
        ensureActive()

        val hasChallenge = SimpleHtmlParser.findFirst(document) { el ->
            el.idAttr == "challenge-form" ||
                el.hasClass("anomaly-modal__modal") ||
                el.hasClass("anomaly-modal__puzzle") ||
                (el.tagName == "form" && el.hasAttr("action") &&
                    base.resolve(el.attr("action"))?.encodedPath == "/anomaly.js")
        } != null
        if (hasChallenge) {
            fail("SEARCH_CHALLENGE", "搜索站点要求人工验证，请稍后重试或改用浏览器手动验证")
        }

        if (isRateLimited(document, ensureActive)) {
            fail("SEARCH_RATE_LIMITED", "搜索站点暂时限制请求，请稍后重试")
        }

        val blocks = findResultBlocks(document)
        val reasons = linkedSetOf<String>()
        if (blocks.size > MAX_RESULT_BLOCKS) reasons += "scan_limit"

        val results = mutableListOf<WebSearchResult>()
        val seen = hashSetOf<String>()
        var skipped = 0
        var chars = 0

        for (block in blocks.take(MAX_RESULT_BLOCKS)) {
            ensureActive()
            val anchor = SimpleHtmlParser.findFirst(block) {
                it.tagName == "a" && it.hasAttr("href") && it.hasClass("result__a")
            } ?: SimpleHtmlParser.findFirst(block) { el ->
                el.tagName == "h2" && SimpleHtmlParser.findFirst(el) {
                    it.tagName == "a" && it.hasAttr("href")
                } != null
            }?.let { h2 ->
                SimpleHtmlParser.findFirst(h2) { it.tagName == "a" && it.hasAttr("href") }
            }

            val resultUrl = anchor?.attr("href")?.let { resolveResultUrl(it, base) }
            val rawTitle = anchor?.text(ensureActive).orEmpty().trim()
            if (resultUrl == null || rawTitle.isBlank()) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            if (!seen.add(resultUrl)) continue
            if (results.size >= maxResults) {
                reasons += "result_limit"
                continue
            }
            val rawSnippet = SimpleHtmlParser.findFirst(block) { it.hasClass("result__snippet") }
                ?.text(ensureActive)
                .orEmpty()
                .trim()
            val title = takeCompleteCharacters(rawTitle, MAX_TITLE_CHARS)
            val snippet = takeCompleteCharacters(rawSnippet, MAX_SNIPPET_CHARS)
            if (title.length != rawTitle.length || snippet.length != rawSnippet.length) {
                reasons += "text_limit"
            }
            val cost = title.length + snippet.length + resultUrl.length
            if (chars + cost > MAX_RESULT_CHARS) {
                reasons += "output_limit"
                continue
            }
            results += WebSearchResult(title, resultUrl, snippet)
            chars += cost
        }

        if (results.isEmpty()) {
            val emptyNotice = SimpleHtmlParser.findFirst(document) { it.hasClass("no-results") }
            if (blocks.isNotEmpty() || emptyNotice == null || emptyNotice.text(ensureActive).isBlank()) {
                fail("SEARCH_PARSE_FAILED", "未识别到搜索结果或明确的无结果提示，页面结构可能已变化")
            }
        }
        ensureActive()
        return WebSearchPage(
            provider = PROVIDER,
            searchUrl = base.toString(),
            results = results,
            limited = reasons.isNotEmpty(),
            limitReasons = reasons.toList(),
            skippedResults = skipped,
        )
    }

    private fun findResultBlocks(document: HtmlNode.Element): List<HtmlNode.Element> {
        val linksContainer = SimpleHtmlParser.findFirst(document) { it.idAttr == "links" }
        val inLinks = if (linksContainer != null) {
            SimpleHtmlParser.findAll(linksContainer) { el ->
                el.hasClass("web-result") && !el.hasClass("result--ad") && !el.hasClass("result--no-result")
            }
        } else {
            emptyList()
        }
        if (inLinks.isNotEmpty()) return inLinks

        val resultsContainers = SimpleHtmlParser.findAll(document) { it.hasClass("results") }
        return resultsContainers.flatMap { container ->
            container.children.filterIsInstance<HtmlNode.Element>().filter { el ->
                el.hasClass("result") && !el.hasClass("result--ad") && !el.hasClass("result--no-result")
            }
        }
    }

    private fun resolveResultUrl(href: String, base: HttpUrl): String? {
        if (href.isBlank() || href.length > MAX_URL_CHARS || href.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            return null
        }
        val resolved = base.resolve(href) ?: return null
        val target = if (allowedHostPredicate(resolved.host) && resolved.encodedPath.trimEnd('/') == "/l") {
            val uddg = resolved.queryParameter("uddg") ?: return null
            httpUrl(uddg) ?: return null
        } else {
            resolved
        }
        if (allowedHostPredicate(target.host) &&
            target.encodedPath.trimEnd('/') in setOf("/l", "/y.js", "/anomaly.js")
        ) {
            return null
        }
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        return target.toString().takeIf { it.length <= MAX_URL_CHARS }
    }

    private fun httpUrl(value: String): HttpUrl? {
        if (value.length > MAX_URL_CHARS || value.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        return value.toHttpUrlOrNull()
    }

    private fun isRateLimited(document: HtmlNode.Element, ensureActive: () -> Unit): Boolean {
        val titleText = SimpleHtmlParser.findFirst(document) { it.tagName == "title" }?.text(ensureActive).orEmpty()
        val headings = SimpleHtmlParser.findAll(document) {
            it.tagName == "h1" || it.hasClass("error-title") || it.idAttr == "error-title"
        }.map { it.text(ensureActive) }
        return (listOf(titleText) + headings).any { text ->
            val normalized = text.trim().lowercase(Locale.ROOT)
            normalized == "too many requests" ||
                normalized == "429 too many requests" ||
                normalized == "rate limit exceeded" ||
                normalized == "rate limited"
        }
    }

    private fun validateLimit(maxResults: Int) {
        if (maxResults !in 1..MAX_ALLOWED_RESULTS) {
            fail("INVALID_ARGUMENT", "max_results 必须为 1 到 $MAX_ALLOWED_RESULTS")
        }
    }

    private fun fail(code: String, message: String): Nothing = throw WebSearchException(code, message)

    companion object {
        const val PROVIDER = "duckduckgo_html"
        const val DEFAULT_SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/"
        const val DEFAULT_MAX_RESULTS = 5
        const val MAX_ALLOWED_RESULTS = 10
        const val MAX_QUERY_CHARS = 2_000
        const val MAX_URL_CHARS = 8_192
        const val MAX_HTML_CHARS = 512_000
        private const val MAX_TITLE_CHARS = 512
        private const val MAX_SNIPPET_CHARS = 1_200
        private const val MAX_RESULT_CHARS = 24_000
        private const val MAX_RESULT_BLOCKS = 100

        fun isDefaultSearchHost(host: String): Boolean =
            host.equals("duckduckgo.com", ignoreCase = true) ||
                host.endsWith(".duckduckgo.com", ignoreCase = true)
    }
}
