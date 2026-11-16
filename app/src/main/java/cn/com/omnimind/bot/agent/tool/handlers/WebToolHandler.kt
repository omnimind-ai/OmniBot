package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.web.ConfigurableWebBackends
import cn.com.omnimind.bot.agent.web.PublicWebSearchClient
import cn.com.omnimind.bot.agent.web.WebHttpTransport
import cn.com.omnimind.bot.agent.web.WebPageContent
import cn.com.omnimind.bot.agent.web.WebPageContentException
import cn.com.omnimind.bot.agent.web.WebPageDocument
import cn.com.omnimind.bot.agent.web.WebRequestException
import cn.com.omnimind.bot.agent.web.WebResponseDecoder
import cn.com.omnimind.bot.agent.web.WebSearchException
import cn.com.omnimind.bot.agent.web.WebToolBackend
import cn.com.omnimind.bot.agent.web.WebToolsConfigStore
import cn.com.omnimind.bot.agent.web.WebToolsRuntimeConfig
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 提供公开网页检索（`web_search`）与静态网页正文抓取（`web_fetch`）。
 * 支持内置 DuckDuckGo / 本地静态抓取、DeepSeek `/v1/tools/{search,fetch}` 与自定义接口。
 * 抓取快照仅归属当前运行生命周期，续页不重复发起网络请求，并在 dispose 时释放。
 */
internal class WebToolHandler(
    private val helper: SharedHelper,
    private val transport: WebHttpTransport = WebHttpTransport(),
    private val searchClient: PublicWebSearchClient = PublicWebSearchClient(transport),
    private val runtimeConfig: () -> WebToolsRuntimeConfig = { WebToolsConfigStore.resolveRuntimeConfig() },
    private val now: () -> Instant = Instant::now,
) : ToolHandler {
    private data class Snapshot(
        val document: WebPageDocument,
        val requestedUrl: String,
        val fetchedAt: String,
        val status: Int,
        val charset: String,
        val decodingLossy: Boolean,
        val provider: String = "builtin_local",
        val fallbackFrom: String? = null,
    )

    override val toolNames: Set<String> = setOf("web_search", "web_fetch")

    private val documents = LinkedHashMap<String, Snapshot>(8, 0.75f, true)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            helper.ensureRunActive()
            when (toolName) {
                "web_search" -> executeSearch(args, callback, toolHandle)
                "web_fetch" -> executeFetch(args, callback, toolHandle)
                else -> helper.errorResult(toolName, "不支持的网页工具：$toolName", "网页操作失败")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WebRequestException) {
            failureResult(toolName, failure.code, failure.message ?: "网页请求失败", failure.status)
        } catch (failure: WebPageContentException) {
            failureResult(toolName, failure.code, failure.message ?: "网页正文处理失败")
        } catch (failure: WebSearchException) {
            failureResult(toolName, failure.code, failure.message ?: "搜索结果解析失败")
        } catch (failure: IllegalArgumentException) {
            failureResult(toolName, "INVALID_ARGUMENT", failure.message ?: "网页工具参数无效")
        } catch (_: Exception) {
            failureResult(toolName, "WEB_ERROR", "网页操作失败，请稍后重试")
        }
    }

    override suspend fun dispose() {
        synchronized(documents) {
            documents.clear()
        }
        transport.close()
    }

    private suspend fun executeSearch(
        args: JsonObject,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) {
            throw WebSearchException("INVALID_ARGUMENT", "query 不能为空")
        }
        val maxResults = parseOptionalInt(args, "max_results") ?: PublicWebSearchClient.DEFAULT_MAX_RESULTS
        val progressText = if (helper.isEnglishLocale) {
            "Searching web: $query"
        } else {
            "正在搜索网页：$query"
        }
        helper.reportToolProgress(callback, "web_search", progressText, toolHandle = toolHandle)

        val config = runtimeConfig()
        val bindCancel: ((() -> Unit) -> Unit) = { cancelAction ->
            toolHandle.bindStopAction(cancelAction)
        }
        val page = when (config.searchBackend) {
            WebToolBackend.BUILTIN -> searchClient.search(
                query = query,
                maxResults = maxResults,
                bindCancel = bindCancel,
            )
            WebToolBackend.DEEPSEEK -> ConfigurableWebBackends.searchDeepSeek(
                transport = transport,
                baseUrl = config.deepSeekBaseUrl,
                apiKey = config.deepSeekApiKey,
                query = query,
                maxResults = maxResults,
                ensureActive = transport::ensureActive,
                bindCancel = bindCancel,
            )
            WebToolBackend.CUSTOM -> ConfigurableWebBackends.searchCustom(
                transport = transport,
                config = config.customSearch,
                apiKey = config.customSearchApiKey,
                query = query,
                maxResults = maxResults,
                ensureActive = transport::ensureActive,
                bindCancel = bindCancel,
            )
        }
        val items = page.results.mapIndexed { index, item ->
            linkedMapOf<String, Any?>(
                "id" to index + 1,
                "title" to item.title,
                "url" to item.url,
                "snippet" to item.snippet,
            ).apply {
                item.summary?.let { put("summary", it) }
                item.siteName?.let { put("site_name", it) }
                item.date?.let { put("date", it) }
                item.authority?.let { put("authority", it) }
                item.rerankScore?.let { put("rerank_score", it) }
            }
        }
        val payload = linkedMapOf<String, Any?>(
            "provider" to page.provider,
            "query" to query,
            "search_url" to page.searchUrl,
            "retrieved_at" to now().toString(),
            "scope" to page.scope,
            "results" to items,
            "count" to items.size,
            "truncated" to page.limited,
            "limit_reasons" to page.limitReasons,
            "skipped_results" to page.skippedResults,
            "source_role" to "untrusted_web_content",
            "citation_hint" to "引用检索结果时请使用其标题与原始 URL；摘要不等于已读取完整网页，如需正文请调用 web_fetch。",
        )
        val encoded = helper.encodeLocalizedPayload(payload)
        val summary = if (helper.isEnglishLocale) {
            "Found ${items.size} web search results"
        } else {
            "搜索完成，共返回 ${items.size} 条结果"
        }
        return ToolExecutionResult.ContextResult(
            toolName = "web_search",
            summaryText = summary,
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
        )
    }

    private suspend fun executeFetch(
        args: JsonObject,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val rawUrl = (args["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val documentIdArg = (args["document_id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        if ((rawUrl == null) == (documentIdArg == null)) {
            throw WebRequestException("INVALID_ARGUMENT", "url 与 document_id 必须且只能提供一个")
        }

        val offsetChars = parseOptionalInt(args, "offset_chars") ?: 0
        val maxChars = parseOptionalInt(args, "max_chars") ?: WebPageContent.DEFAULT_PAGE_CHARS
        if (offsetChars < 0 || maxChars !in WebPageContent.MIN_PAGE_CHARS..WebPageContent.MAX_PAGE_CHARS) {
            throw WebRequestException(
                "INVALID_ARGUMENT",
                "分页参数无效：offset_chars 须 >= 0，max_chars 须在 ${WebPageContent.MIN_PAGE_CHARS} 到 ${WebPageContent.MAX_PAGE_CHARS} 之间",
            )
        }

        val id: String
        val snapshot: Snapshot
        if (rawUrl != null) {
            val validatedUrl = transport.validateDestination(rawUrl).toString()
            val progressText = if (helper.isEnglishLocale) {
                "Fetching web page: $validatedUrl"
            } else {
                "正在读取网页：$validatedUrl"
            }
            helper.reportToolProgress(callback, "web_fetch", progressText, toolHandle = toolHandle)

            val bindCancel: ((() -> Unit) -> Unit) = { cancelAction ->
                toolHandle.bindStopAction(cancelAction)
            }
            val config = runtimeConfig()
            snapshot = when (config.fetchBackend) {
                WebToolBackend.BUILTIN -> fetchLocalSnapshot(validatedUrl, bindCancel = bindCancel)
                WebToolBackend.DEEPSEEK, WebToolBackend.CUSTOM -> {
                    val remoteProvider = if (config.fetchBackend == WebToolBackend.DEEPSEEK) "deepseek" else "custom"
                    try {
                        val remote = if (config.fetchBackend == WebToolBackend.DEEPSEEK) {
                            ConfigurableWebBackends.fetchDeepSeek(
                                transport = transport,
                                baseUrl = config.deepSeekBaseUrl,
                                apiKey = config.deepSeekApiKey,
                                targetUrl = validatedUrl,
                                ensureActive = transport::ensureActive,
                                bindCancel = bindCancel,
                            )
                        } else {
                            ConfigurableWebBackends.fetchCustom(
                                transport = transport,
                                config = config.customFetch,
                                apiKey = config.customFetchApiKey,
                                targetUrl = validatedUrl,
                                ensureActive = transport::ensureActive,
                                bindCancel = bindCancel,
                            )
                        }
                        Snapshot(
                            document = remote.document,
                            requestedUrl = validatedUrl,
                            fetchedAt = now().toString(),
                            status = remote.status,
                            charset = remote.charset,
                            decodingLossy = remote.decodingLossy,
                            provider = remote.provider,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: WebRequestException) {
                        if (failure.code in NON_FALLBACK_CODES) {
                            throw failure
                        }
                        transport.ensureActive()
                        fetchLocalSnapshot(validatedUrl, fallbackFrom = remoteProvider, bindCancel = bindCancel)
                    } catch (_: Exception) {
                        transport.ensureActive()
                        fetchLocalSnapshot(validatedUrl, fallbackFrom = remoteProvider, bindCancel = bindCancel)
                    }
                }
            }
            id = "web_${UUID.randomUUID()}"
            synchronized(documents) {
                transport.ensureActive()
                documents[id] = snapshot
                while (documents.size > MAX_DOCUMENTS ||
                    documents.values.sumOf { it.document.content.length } > MAX_RETAINED_CHARS
                ) {
                    val oldestKey = documents.keys.firstOrNull() ?: break
                    documents.remove(oldestKey)
                }
            }
        } else {
            id = documentIdArg!!
            if (id.length > 128) {
                throw WebRequestException("INVALID_ARGUMENT", "document_id 长度无效")
            }
            snapshot = synchronized(documents) { documents[id] }
                ?: throw WebRequestException("WEB_DOCUMENT_EXPIRED", "网页快照已失效或不属于当前运行，请重新提供 url 读取")
        }

        val document = snapshot.document
        val page = WebPageContent.page(document, offsetChars, maxChars)
        val linkIds = if (document.format == "html") {
            LINK_ID_REGEX.findAll(document.content)
                .filter { it.range.first < page.nextOffsetChars && it.range.last >= page.offsetChars }
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toSet()
        } else {
            emptySet()
        }
        val candidateLinks = document.links.filter { link ->
            if (document.format == "html") {
                link.id in linkIds
            } else {
                val searchStart = (page.offsetChars - link.url.length + 1).coerceAtLeast(0)
                val pos = document.content.indexOf(link.url, searchStart)
                pos >= 0 && pos < page.nextOffsetChars && pos + link.url.length > page.offsetChars
            }
        }
        val linksPayload = mutableListOf<Map<String, Any?>>()
        var linkChars = 0
        for (link in candidateLinks) {
            val item = linkedMapOf<String, Any?>(
                "id" to link.id,
                "text" to link.text,
                "url" to link.url,
            )
            val approxSize = link.text.length + link.url.length + 24
            if (linksPayload.size >= MAX_PAGE_LINKS || linkChars + approxSize > MAX_PAGE_LINK_CHARS) {
                break
            }
            linksPayload += item
            linkChars += approxSize
        }

        val payload = linkedMapOf<String, Any?>(
            "document_id" to id,
            "snapshot_scope" to "current_run",
            "requested_url" to snapshot.requestedUrl,
            "url" to document.url,
            "title" to document.title,
            "content_type" to document.contentType,
            "format" to document.format,
            "charset" to snapshot.charset,
            "decoding_lossy" to snapshot.decodingLossy,
            "status" to snapshot.status,
            "fetched_at" to snapshot.fetchedAt,
            "offset_chars" to page.offsetChars,
            "next_offset_chars" to page.nextOffsetChars,
            "returned_chars" to page.content.length,
            "total_chars" to page.totalChars,
            "has_more" to page.hasMore,
            "truncated" to page.sourceTruncated,
            "source_truncated" to page.sourceTruncated,
            "truncation_reasons" to document.truncationReasons,
            "content" to page.content,
            "links" to linksPayload,
            "links_truncated" to (document.linksTruncated || linksPayload.size < candidateLinks.size),
            "source_role" to "untrusted_web_content",
            "pagination_hint" to if (page.hasMore) {
                "正文尚未读取完整；继续传 document_id 与 offset_chars=${page.nextOffsetChars} 可读取下一页，不要再传 url。"
            } else {
                "已到达当前网页正文末尾。"
            },
        ).apply {
            if (snapshot.provider != "builtin_local" || snapshot.fallbackFrom != null) {
                put("provider", snapshot.provider)
            }
            snapshot.fallbackFrom?.let { put("fallback_from", it) }
        }
        val encoded = helper.encodeLocalizedPayload(payload)
        val summary = if (helper.isEnglishLocale) {
            val titlePart = document.title?.take(40)?.takeIf { it.isNotBlank() } ?: document.url
            "Fetched web page (${page.content.length} chars): $titlePart"
        } else {
            val titlePart = document.title?.take(40)?.takeIf { it.isNotBlank() } ?: document.url
            "已读取网页（${page.content.length} 字符）：$titlePart"
        }
        return ToolExecutionResult.ContextResult(
            toolName = "web_fetch",
            summaryText = summary,
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
        )
    }

    private suspend fun fetchLocalSnapshot(
        url: String,
        fallbackFrom: String? = null,
        bindCancel: ((() -> Unit) -> Unit)? = null,
    ): Snapshot {
        val response = transport.get(url, bindCancel = bindCancel)
        if (response.status !in 200..299) {
            val code = when (response.status) {
                401, 403 -> "WEB_ACCESS_DENIED"
                404 -> "WEB_NOT_FOUND"
                429 -> "WEB_RATE_LIMITED"
                else -> "WEB_HTTP_ERROR"
            }
            throw WebRequestException(code, "网页请求失败（HTTP ${response.status}）", response.status)
        }
        val decoded = withContext(Dispatchers.Default) {
            val decodedResponse = WebResponseDecoder.decode(response)
            val document = WebPageContent.extract(
                text = decodedResponse.text,
                contentType = decodedResponse.contentType,
                finalUrl = response.finalUrl,
                sourceTruncated = response.truncated,
                ensureActive = transport::ensureActive,
            )
            val retained = if (decodedResponse.lossy) {
                document.copy(
                    truncated = true,
                    truncationReasons = document.truncationReasons + "decoding_lossy",
                )
            } else {
                document
            }
            decodedResponse to retained
        }
        return Snapshot(
            document = decoded.second,
            requestedUrl = url,
            fetchedAt = now().toString(),
            status = response.status,
            charset = decoded.first.charset,
            decodingLossy = decoded.first.lossy,
            provider = "builtin_local",
            fallbackFrom = fallbackFrom,
        )
    }

    private fun parseOptionalInt(args: JsonObject, key: String): Int? {
        val raw = args[key] ?: return null
        val primitive = raw as? JsonPrimitive
            ?: throw IllegalArgumentException("参数 $key 类型必须为整数")
        return primitive.intOrNull
            ?: primitive.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("参数 $key 类型必须为整数")
    }

    private fun failureResult(
        toolName: String,
        code: String,
        message: String,
        status: Int? = null,
    ): ToolExecutionResult.ContextResult {
        val localizedMessage = helper.localized(message)
        val payload = linkedMapOf<String, Any?>(
            "available" to false,
            "error" to true,
            "error_code" to code,
            "message" to localizedMessage,
            "source_role" to "untrusted_web_content",
        )
        if (status != null) {
            payload["status"] = status
        }
        val encoded = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = localizedMessage,
            previewJson = encoded,
            rawResultJson = encoded,
            success = false,
        )
    }

    private companion object {
        const val MAX_DOCUMENTS = 8
        const val MAX_RETAINED_CHARS = 800_000
        const val MAX_PAGE_LINKS = 20
        const val MAX_PAGE_LINK_CHARS = 8_000
        val LINK_ID_REGEX = Regex("""\[(\d+)]""")
        val NON_FALLBACK_CODES = setOf(
            "WEB_CANCELLED",
            "INVALID_URL",
            "URL_CREDENTIALS_UNSUPPORTED",
            "DISALLOWED_HOST",
        )
    }
}
