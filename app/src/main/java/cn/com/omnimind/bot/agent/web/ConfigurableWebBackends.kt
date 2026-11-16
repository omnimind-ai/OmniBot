package cn.com.omnimind.bot.agent.web

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal data class RemoteFetchedDocument(
    val provider: String,
    val document: WebPageDocument,
    val status: Int,
    val charset: String,
    val decodingLossy: Boolean,
)

/**
 * 可配置的远端网页检索与抓取后端：支持 DeepSeek `/v1/tools/{search,fetch}` 与自定义 JSON/文本接口。
 */
internal object ConfigurableWebBackends {
    private const val DEEPSEEK_MAX_QUERY_CHARS = 128
    private const val MAX_TITLE_CHARS = 512
    private const val MAX_SNIPPET_CHARS = 600
    private const val MAX_SUMMARY_CHARS = 1_200
    private const val MAX_RESULT_CHARS = 24_000
    private const val MAX_SCAN_ITEMS = 100

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun searchDeepSeek(
        transport: WebHttpTransport,
        baseUrl: String,
        apiKey: String,
        query: String,
        maxResults: Int,
        ensureActive: () -> Unit,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): WebSearchPage {
        validateLimit(maxResults)
        val trimmedQuery = validateQuery(query)
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw WebSearchException(
                "SEARCH_AUTH_REQUIRED",
                "DeepSeek 搜索未配置 API Key，请在「网页搜索与读取」设置或 DeepSeek 模型提供商中配置",
            )
        }
        val endpoint = deepSeekEndpoint(baseUrl, "search")
        val reasons = linkedSetOf<String>()
        val effectiveQuery = if (trimmedQuery.length > DEEPSEEK_MAX_QUERY_CHARS) {
            reasons += "query_truncated"
            takeCompleteCharacters(trimmedQuery, DEEPSEEK_MAX_QUERY_CHARS)
        } else {
            trimmedQuery
        }
        val body = buildJsonObject {
            put("query", effectiveQuery)
            put("count", maxResults)
            put("need_content", false)
        }.toString().toByteArray(Charsets.UTF_8)

        val response = transport.request(
            rawUrl = endpoint,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "application/json",
            ),
            body = body,
            contentType = "application/json; charset=utf-8",
            bindCancel = bindCancel,
        )
        requireSearchApiSuccess(response, "DeepSeek")
        if (response.truncated) {
            throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小限制，请缩小查询范围")
        }
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) {
            throw WebRequestException("SEARCH_ENCODING_ERROR", "搜索响应包含无法可靠解码的内容")
        }
        val root = runCatching { json.parseToJsonElement(decoded.text) as? JsonObject }.getOrNull()
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "DeepSeek 搜索返回了无效的 JSON 响应")
        val array = root["results"] as? JsonArray
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "DeepSeek 搜索响应缺少 results 字段")

        return parseJsonSearchResults(
            provider = "deepseek",
            searchUrl = endpoint,
            array = array,
            maxResults = maxResults,
            initialReasons = reasons,
            titleField = "title",
            urlField = "url",
            snippetField = "snippet",
            ensureActive = ensureActive,
        )
    }

    suspend fun searchCustom(
        transport: WebHttpTransport,
        config: CustomWebSearchConfig,
        apiKey: String,
        query: String,
        maxResults: Int,
        ensureActive: () -> Unit,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): WebSearchPage {
        validateLimit(maxResults)
        val trimmedQuery = validateQuery(query)
        val rawUrlTemplate = config.url.trim()
        if (rawUrlTemplate.isEmpty()) {
            throw WebSearchException("SEARCH_CONFIG_INCOMPLETE", "自定义搜索未配置接口地址")
        }
        val targetUrl = buildCustomSearchUrl(rawUrlTemplate, config.method, trimmedQuery, maxResults)
        val headers = parseCustomHeaders(config.headersText, apiKey)
        val bodyBytes = if (config.method == WebHttpMethod.POST) {
            val template = config.bodyTemplate.trim().ifEmpty { CustomWebSearchConfig.DEFAULT_BODY_TEMPLATE }
            renderSearchBody(template, trimmedQuery, maxResults).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val response = transport.request(
            rawUrl = targetUrl,
            method = config.method.name,
            headers = headers,
            body = bodyBytes,
            contentType = if (config.method == WebHttpMethod.POST) "application/json; charset=utf-8" else null,
            bindCancel = bindCancel,
        )
        requireSearchApiSuccess(response, "自定义")
        if (response.truncated) {
            throw WebRequestException("SEARCH_RESPONSE_TOO_LARGE", "搜索响应超过大小限制，请缩小查询范围")
        }
        val decoded = WebResponseDecoder.decode(response)
        if (decoded.lossy) {
            throw WebRequestException("SEARCH_ENCODING_ERROR", "搜索响应包含无法可靠解码的内容")
        }
        val parsedRoot = runCatching { json.parseToJsonElement(decoded.text) }.getOrNull()
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "自定义搜索接口未返回有效的 JSON")
        val resultsNode = resolveJsonPath(parsedRoot, config.resultsPath.trim().ifEmpty { "results" })
        val array = resultsNode as? JsonArray
            ?: throw WebSearchException("SEARCH_PARSE_FAILED", "未在自定义搜索响应路径中找到结果数组")

        return parseJsonSearchResults(
            provider = "custom",
            searchUrl = response.finalUrl,
            array = array,
            maxResults = maxResults,
            initialReasons = emptySet(),
            titleField = config.titleField.trim().ifEmpty { "title" },
            urlField = config.urlField.trim().ifEmpty { "url" },
            snippetField = config.snippetField.trim().ifEmpty { "snippet" },
            ensureActive = ensureActive,
        )
    }

    suspend fun fetchDeepSeek(
        transport: WebHttpTransport,
        baseUrl: String,
        apiKey: String,
        targetUrl: String,
        ensureActive: () -> Unit,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): RemoteFetchedDocument {
        val validatedUrl = transport.validateDestination(targetUrl).toString()
        val key = apiKey.trim()
        if (key.isEmpty()) {
            throw WebRequestException("WEB_AUTH_REQUIRED", "DeepSeek 网页读取未配置 API Key")
        }
        val endpoint = deepSeekEndpoint(baseUrl, "fetch")
        val body = buildJsonObject {
            put("url", validatedUrl)
        }.toString().toByteArray(Charsets.UTF_8)

        val response = transport.request(
            rawUrl = endpoint,
            method = "POST",
            headers = mapOf(
                "Authorization" to "Bearer $key",
                "Accept" to "application/json",
            ),
            body = body,
            contentType = "application/json; charset=utf-8",
            bindCancel = bindCancel,
        )
        requireFetchApiSuccess(response)
        val decoded = WebResponseDecoder.decode(response)
        ensureActive()
        val root = runCatching { json.parseToJsonElement(decoded.text) as? JsonObject }.getOrNull()
            ?: throw WebPageContentException("WEB_PARSE_FAILED", "DeepSeek 读取返回了无效的 JSON")
        val content = (root["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) {
            throw WebPageContentException("EMPTY_PAGE_CONTENT", "DeepSeek 未提取到可用的网页正文")
        }
        val rawTitle = (root["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val document = buildMarkdownDocument(
            content = content,
            rawTitle = rawTitle,
            finalUrl = validatedUrl,
            sourceTruncated = response.truncated,
            decodingLossy = decoded.lossy,
            ensureActive = ensureActive,
        )
        return RemoteFetchedDocument(
            provider = "deepseek",
            document = document,
            status = response.status,
            charset = decoded.charset,
            decodingLossy = decoded.lossy,
        )
    }

    suspend fun fetchCustom(
        transport: WebHttpTransport,
        config: CustomWebFetchConfig,
        apiKey: String,
        targetUrl: String,
        ensureActive: () -> Unit,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): RemoteFetchedDocument {
        val validatedUrl = transport.validateDestination(targetUrl).toString()
        val rawUrlTemplate = config.url.trim()
        if (rawUrlTemplate.isEmpty()) {
            throw WebRequestException("WEB_CONFIG_INCOMPLETE", "自定义网页读取未配置接口地址")
        }
        val requestUrl = buildCustomFetchUrl(rawUrlTemplate, config.method, validatedUrl)
        val headers = parseCustomHeaders(config.headersText, apiKey)
        val bodyBytes = if (config.method == WebHttpMethod.POST) {
            val template = config.bodyTemplate.trim().ifEmpty { CustomWebFetchConfig.DEFAULT_BODY_TEMPLATE }
            renderFetchBody(template, validatedUrl).toByteArray(Charsets.UTF_8)
        } else {
            null
        }
        val response = transport.request(
            rawUrl = requestUrl,
            method = config.method.name,
            headers = headers,
            body = bodyBytes,
            contentType = if (config.method == WebHttpMethod.POST) "application/json; charset=utf-8" else null,
            bindCancel = bindCancel,
        )
        requireFetchApiSuccess(response)
        val decoded = WebResponseDecoder.decode(response)
        ensureActive()
        val mime = decoded.contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val contentField = config.contentField.trim()
        val parsedJson = if (contentField.isNotEmpty() || "json" in mime) {
            runCatching { json.parseToJsonElement(decoded.text) as? JsonObject }.getOrNull()
        } else {
            null
        }
        val document = if (parsedJson != null && contentField.isNotEmpty()) {
            val content = stringAtPath(parsedJson, contentField)?.trim().orEmpty()
            if (content.isEmpty()) {
                throw WebPageContentException("EMPTY_PAGE_CONTENT", "自定义读取接口未返回有效正文")
            }
            val titleField = config.titleField.trim()
            val rawTitle = if (titleField.isNotEmpty()) {
                stringAtPath(parsedJson, titleField)?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            buildMarkdownDocument(
                content = content,
                rawTitle = rawTitle,
                finalUrl = validatedUrl,
                sourceTruncated = response.truncated,
                decodingLossy = decoded.lossy,
                ensureActive = ensureActive,
            )
        } else {
            val extracted = WebPageContent.extract(
                text = decoded.text,
                contentType = decoded.contentType.ifBlank { "text/markdown" },
                finalUrl = validatedUrl,
                sourceTruncated = response.truncated,
                ensureActive = ensureActive,
            )
            if (extracted.content.isBlank()) {
                throw WebPageContentException("EMPTY_PAGE_CONTENT", "自定义读取接口返回的正文为空")
            }
            if (decoded.lossy) {
                extracted.copy(
                    truncated = true,
                    truncationReasons = extracted.truncationReasons + "decoding_lossy",
                )
            } else {
                extracted
            }
        }
        return RemoteFetchedDocument(
            provider = "custom",
            document = document,
            status = response.status,
            charset = decoded.charset,
            decodingLossy = decoded.lossy,
        )
    }

    internal fun deepSeekEndpoint(rawBaseUrl: String, tool: String): String {
        val candidate = rawBaseUrl.trim().ifEmpty { DeepSeekWebConfig.DEFAULT_BASE_URL }.trimEnd('/')
        val validated = WebHttpTransport.validateUrl(candidate, allowPrivateAddresses = true).toString().trimEnd('/')
        val parsed = validated.toHttpUrlOrNull()
        val normalizedBase = if (parsed != null && parsed.host.equals("api.deepseek.com", ignoreCase = true)) {
            when {
                validated.endsWith("/anthropic/v1", ignoreCase = true) ->
                    validated.dropLast("/anthropic/v1".length)
                validated.endsWith("/anthropic", ignoreCase = true) ->
                    validated.dropLast("/anthropic".length)
                else -> validated
            }
        } else {
            validated
        }
        return when {
            normalizedBase.endsWith("/v1/tools/$tool") || normalizedBase.endsWith("/tools/$tool") -> normalizedBase
            normalizedBase.endsWith("/v1/tools") || normalizedBase.endsWith("/tools") -> "$normalizedBase/$tool"
            normalizedBase.endsWith("/v1") -> "$normalizedBase/tools/$tool"
            else -> "$normalizedBase/v1/tools/$tool"
        }
    }

    internal fun parseCustomHeaders(headersText: String, apiKey: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headersText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw WebRequestException("INVALID_ARGUMENT", "自定义请求头格式应为 Header: Value")
            }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (name.isEmpty()) {
                throw WebRequestException("INVALID_ARGUMENT", "自定义请求头名称不能为空")
            }
            result[name] = value
        }
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty() && result.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            result["Authorization"] = "Bearer $trimmedKey"
        }
        return result
    }

    internal fun resolveJsonPath(root: JsonElement?, path: String): JsonElement? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "$") return root
        var current: JsonElement? = root
        val segments = trimmed.removePrefix("$.").split('.').filter { it.isNotEmpty() }
        for (segment in segments) {
            current = when (val node = current) {
                is JsonObject -> node[segment]
                is JsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    if (index !in node.indices) null else node[index]
                }
                else -> return null
            }
            if (current == null || current is JsonNull) return null
        }
        return current
    }

    private fun parseJsonSearchResults(
        provider: String,
        searchUrl: String,
        array: JsonArray,
        maxResults: Int,
        initialReasons: Set<String>,
        titleField: String,
        urlField: String,
        snippetField: String,
        ensureActive: () -> Unit,
    ): WebSearchPage {
        val reasons = LinkedHashSet(initialReasons)
        if (array.size > MAX_SCAN_ITEMS) reasons += "scan_limit"
        val results = mutableListOf<WebSearchResult>()
        val seen = hashSetOf<String>()
        var skipped = 0
        var chars = 0
        val scanCount = minOf(array.size, MAX_SCAN_ITEMS)

        for (index in 0 until scanCount) {
            ensureActive()
            val item = array[index] as? JsonObject
            if (item == null) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            val rawUrl = stringAtPath(item, urlField)
            val validatedUrl = rawUrl?.let { candidate ->
                runCatching { WebHttpTransport.validateUrl(candidate, allowPrivateAddresses = false).toString() }.getOrNull()
            }
            val rawTitle = stringAtPath(item, titleField).orEmpty().trim()
            if (validatedUrl == null || rawTitle.isBlank()) {
                skipped++
                reasons += "invalid_result"
                continue
            }
            if (!seen.add(validatedUrl)) continue
            if (results.size >= maxResults) {
                reasons += "result_limit"
                continue
            }
            val rawSnippet = stringAtPath(item, snippetField)?.takeIf { it.isNotBlank() }
                ?: stringAtPath(item, "summary")?.takeIf { it.isNotBlank() }
                ?: stringAtPath(item, "content")?.takeIf { it.isNotBlank() }
                ?: stringAtPath(item, "description").orEmpty()
            val rawSummary = stringAtPath(item, "summary")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != rawSnippet.trim() }

            val title = takeCompleteCharacters(rawTitle, MAX_TITLE_CHARS)
            val snippet = takeCompleteCharacters(rawSnippet.trim(), MAX_SNIPPET_CHARS)
            val summary = rawSummary?.let { takeCompleteCharacters(it, MAX_SUMMARY_CHARS) }
            if (title.length != rawTitle.length ||
                snippet.length != rawSnippet.trim().length ||
                (rawSummary != null && summary?.length != rawSummary.length)
            ) {
                reasons += "text_limit"
            }

            val siteName = stringAtPath(item, "site_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { takeCompleteCharacters(it, 120) }
            val date = stringAtPath(item, "date")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { takeCompleteCharacters(it, 64) }
            val authority = (item["authority"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            val rerankScore = (item["rerank_score"] as? JsonPrimitive)?.doubleOrNull
                ?.takeIf { !it.isNaN() && !it.isInfinite() }

            val cost = title.length + snippet.length + (summary?.length ?: 0) + validatedUrl.length
            if (chars + cost > MAX_RESULT_CHARS) {
                reasons += "output_limit"
                continue
            }
            results += WebSearchResult(
                title = title,
                url = validatedUrl,
                snippet = snippet,
                summary = summary,
                siteName = siteName,
                date = date,
                authority = authority,
                rerankScore = rerankScore,
            )
            chars += cost
        }
        ensureActive()
        return WebSearchPage(
            provider = provider,
            searchUrl = searchUrl,
            results = results,
            limited = reasons.isNotEmpty(),
            scope = "api",
            limitReasons = reasons.toList(),
            skippedResults = skipped,
        )
    }

    private fun buildMarkdownDocument(
        content: String,
        rawTitle: String?,
        finalUrl: String,
        sourceTruncated: Boolean,
        decodingLossy: Boolean,
        ensureActive: () -> Unit,
    ): WebPageDocument {
        val extracted = WebPageContent.extract(
            text = content,
            contentType = "text/markdown",
            finalUrl = finalUrl,
            sourceTruncated = sourceTruncated,
            ensureActive = ensureActive,
        )
        val title = rawTitle?.let { takeCompleteCharacters(it, WebPageContent.MAX_TITLE_CHARS) }
        val titleTruncated = rawTitle != null && title != null && title.length < rawTitle.length
        val reasons = if (decodingLossy) extracted.truncationReasons + "decoding_lossy" else extracted.truncationReasons
        return extracted.copy(
            title = title ?: extracted.title,
            titleTruncated = titleTruncated || extracted.titleTruncated,
            truncated = extracted.truncated || decodingLossy,
            truncationReasons = reasons,
        )
    }

    private fun buildCustomSearchUrl(
        template: String,
        method: WebHttpMethod,
        query: String,
        maxResults: Int,
    ): String {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val hasPlaceholder = "{{query}}" in template || "{{raw_query}}" in template || "{{count}}" in template
        val interpolated = template
            .replace("{{query}}", encodedQuery)
            .replace("{{raw_query}}", query)
            .replace("{{count}}", maxResults.toString())
        val base = WebHttpTransport.validateUrl(interpolated, allowPrivateAddresses = true)
        return if (method == WebHttpMethod.GET && !hasPlaceholder && base.queryParameter("q") == null) {
            base.newBuilder().addQueryParameter("q", query).build().toString()
        } else {
            base.toString()
        }
    }

    private fun buildCustomFetchUrl(
        template: String,
        method: WebHttpMethod,
        targetUrl: String,
    ): String {
        val encodedUrl = URLEncoder.encode(targetUrl, Charsets.UTF_8.name())
        val hasPlaceholder = "{{url}}" in template || "{{raw_url}}" in template
        val interpolated = template
            .replace("{{url}}", encodedUrl)
            .replace("{{raw_url}}", targetUrl)
        val base = WebHttpTransport.validateUrl(interpolated, allowPrivateAddresses = true)
        return if (method == WebHttpMethod.GET && !hasPlaceholder && base.queryParameter("url") == null) {
            base.newBuilder().addQueryParameter("url", targetUrl).build().toString()
        } else {
            base.toString()
        }
    }

    private fun renderSearchBody(template: String, query: String, maxResults: Int): String {
        val quotedQuery = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(query))
        val escapedInner = quotedQuery.substring(1, quotedQuery.length - 1)
        return template
            .replace("{{query_json}}", quotedQuery)
            .replace("\"{{query}}\"", quotedQuery)
            .replace("{{query}}", escapedInner)
            .replace("{{count}}", maxResults.toString())
    }

    private fun renderFetchBody(template: String, targetUrl: String): String {
        val quotedUrl = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(targetUrl))
        val escapedInner = quotedUrl.substring(1, quotedUrl.length - 1)
        return template
            .replace("{{url_json}}", quotedUrl)
            .replace("\"{{url}}\"", quotedUrl)
            .replace("{{url}}", escapedInner)
    }

    private fun stringAtPath(root: JsonObject, path: String): String? {
        val value = resolveJsonPath(root, path) ?: return null
        return (value as? JsonPrimitive)?.contentOrNull
    }

    private fun requireSearchApiSuccess(response: WebHttpResponse, providerLabel: String) {
        if (response.status in 200..299) return
        throw when (response.status) {
            400 -> WebRequestException("SEARCH_INVALID_REQUEST", "${providerLabel}搜索请求参数无效（HTTP 400）", 400)
            401 -> WebRequestException("SEARCH_AUTH_FAILED", "${providerLabel}搜索鉴权失败，请检查 API Key（HTTP 401）", 401)
            402 -> WebRequestException("SEARCH_INSUFFICIENT_BALANCE", "${providerLabel}账号余额不足（HTTP 402）", 402)
            403 -> WebRequestException("WEB_ACCESS_DENIED", "${providerLabel}搜索请求被拒绝（HTTP 403）", 403)
            408, 504 -> WebRequestException("WEB_TIMEOUT", "${providerLabel}搜索服务响应超时（HTTP ${response.status}）", response.status)
            429 -> WebRequestException("SEARCH_RATE_LIMITED", "${providerLabel}搜索请求触发限流，请稍后重试（HTTP 429）", 429)
            else -> WebRequestException("WEB_HTTP_ERROR", "${providerLabel}搜索请求失败（HTTP ${response.status}）", response.status)
        }
    }

    private fun requireFetchApiSuccess(response: WebHttpResponse) {
        if (response.status in 200..299) return
        throw when (response.status) {
            401, 403 -> WebRequestException("WEB_ACCESS_DENIED", "网页读取服务拒绝访问（HTTP ${response.status}）", response.status)
            402 -> WebRequestException("WEB_INSUFFICIENT_BALANCE", "网页读取服务账号余额不足（HTTP 402）", 402)
            404 -> WebRequestException("WEB_NOT_FOUND", "网页读取服务未能提取该网址的正文（HTTP 404）", 404)
            408, 504 -> WebRequestException("WEB_TIMEOUT", "网页读取服务超时（HTTP ${response.status}）", response.status)
            429 -> WebRequestException("WEB_RATE_LIMITED", "网页读取服务触发限流（HTTP 429）", 429)
            else -> WebRequestException("WEB_HTTP_ERROR", "网页读取服务请求失败（HTTP ${response.status}）", response.status)
        }
    }

    private fun validateQuery(query: String): String {
        if (query.isBlank() || query.length > PublicWebSearchClient.MAX_QUERY_CHARS || '\u0000' in query ||
            !Charsets.UTF_8.newEncoder().canEncode(query)
        ) {
            throw WebSearchException("INVALID_ARGUMENT", "query 必须为 1 到 ${PublicWebSearchClient.MAX_QUERY_CHARS} 个字符的有效文本")
        }
        return query.trim()
    }

    private fun validateLimit(maxResults: Int) {
        if (maxResults !in 1..PublicWebSearchClient.MAX_ALLOWED_RESULTS) {
            throw WebSearchException("INVALID_ARGUMENT", "max_results 必须为 1 到 ${PublicWebSearchClient.MAX_ALLOWED_RESULTS}")
        }
    }
}
