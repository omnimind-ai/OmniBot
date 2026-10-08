package cn.com.omnimind.bot.ui.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/*
 * Link previews under chat messages (batch 5e-6), ported from
 * `ui/lib/services/link_preview_service.dart` and `models/chat_link_preview.dart`:
 * the same URL extraction (explicit and bare domains, Markdown punctuation,
 * `omnibot://` resources and image links skipped, at most three per message),
 * the same Open Graph > Twitter Card > HTML title/description order, and the
 * same `content.linkPreviews` storage shape, so both pages read one value.
 */

internal object LinkPreviewStatus {
    const val LOADING = "loading"
    const val READY = "ready"
    const val FAILED = "failed"
}

internal data class LinkPreview(
    val url: String,
    val domain: String,
    val siteName: String = "",
    val title: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val status: String = LinkPreviewStatus.LOADING,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "url" to url, "domain" to domain, "siteName" to siteName, "title" to title,
        "description" to description, "imageUrl" to imageUrl, "status" to status,
    )

    companion object {
        fun loading(url: String) = LinkPreview(url, domainFor(url), status = LinkPreviewStatus.LOADING)
        fun failed(url: String) = LinkPreview(url, domainFor(url), status = LinkPreviewStatus.FAILED)

        fun fromMap(map: Map<*, *>): LinkPreview {
            fun str(key: String) = map[key]?.toString()?.trim().orEmpty()
            val url = str("url")
            val status = when (str("status")) {
                LinkPreviewStatus.READY -> LinkPreviewStatus.READY
                LinkPreviewStatus.FAILED -> LinkPreviewStatus.FAILED
                else -> LinkPreviewStatus.LOADING
            }
            return LinkPreview(
                url = url, domain = str("domain").ifEmpty { domainFor(url) }, siteName = str("siteName"),
                title = str("title"), description = str("description"), imageUrl = str("imageUrl"), status = status,
            )
        }

        private fun domainFor(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
    }
}

internal object LinkPreviewExtractor {
    const val MAX_PER_MESSAGE = 3

    private val EXPLICIT = Regex("""https?://[^\s<>"\]\[]+""", RegexOption.IGNORE_CASE)
    private val BARE = Regex(
        """(?:www\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z][a-z0-9-]{1,62}(?:/[^\s<>"\]\[]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val OMNIBOT = Regex("""omnibot://[^\s<>"\]\[)]+""", RegexOption.IGNORE_CASE)
    private val SCHEME = Regex("""^https?://""", RegexOption.IGNORE_CASE)
    private val TRAILING = setOf(
        '.', ',', '!', '?', ':', ';', ')', ']', '"', '\'', '*', '_', '`',
        '。', '，', '；', '：', '！', '？', '）', '】', '》', '”', '’',
    )
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif")
    private val FILE_EXTENSIONS = IMAGE_EXTENSIONS + setOf(
        "pdf", "txt", "md", "json", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "zip", "rar", "7z", "mp3", "wav", "m4a", "mp4", "mov", "avi",
    )
    private val BARE_SUFFIXES = setOf(
        "com", "net", "org", "io", "ai", "app", "dev", "cn", "cc", "me", "tv", "fm", "xyz", "info", "top",
        "tech", "site", "online", "cloud", "shop", "store", "blog", "pro", "biz", "name", "edu", "gov", "mil",
        "int", "us", "uk", "ca", "au", "eu", "de", "fr", "jp", "kr", "sg", "hk", "tw", "in", "br", "ru", "it",
        "es", "nl", "co.uk", "org.uk", "ac.uk", "gov.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "co.jp", "ne.jp", "or.jp", "com.au", "net.au", "org.au", "com.hk", "com.tw", "com.sg", "com.br",
    )

    /** Dart `extractUrls`. */
    fun extractUrls(text: String, maxCount: Int = MAX_PER_MESSAGE): List<String> {
        if (text.isBlank() || maxCount <= 0) return emptyList()
        // Resource links keep their length so match offsets stay comparable.
        val masked = OMNIBOT.replace(text) { " ".repeat(it.value.length) }
        val candidates = (
            EXPLICIT.findAll(masked).map { it.range.first to it.value } +
                BARE.findAll(masked).filter { match ->
                    val previous = if (match.range.first > 0) masked[match.range.first - 1] else ' '
                    previous !in setOf('@', '/', '.', '_', '-')
                }.map { it.range.first to it.value }
            ).sortedBy { it.first }
        val urls = ArrayList<String>()
        val seen = HashSet<String>()
        for ((_, raw) in candidates) {
            val normalized = normalize(raw) ?: continue
            if (looksLikeImage(normalized)) continue
            val key = canonicalKey(normalized) ?: continue
            if (key in seen) continue
            val uri = runCatching { URI(normalized) }.getOrNull() ?: continue
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) continue
            seen.add(key)
            urls.add(normalized)
            if (urls.size >= maxCount) break
        }
        return urls
    }

    /**
     * Dart `reconcilePreviewMaps`: keeps ready and failed previews already
     * stored for a URL, takes a cached one, and only gives new links a
     * loading placeholder.
     */
    fun reconcile(text: String, existing: Any?, cached: (String) -> LinkPreview? = { null }): List<LinkPreview> {
        val urls = extractUrls(text)
        if (urls.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, LinkPreview>()
        (existing as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map(LinkPreview::fromMap).forEach { preview ->
            canonicalKey(preview.url)?.let { byKey.putIfAbsent(it, preview) }
        }
        return urls.map { url ->
            val key = canonicalKey(url)
            key?.let(byKey::get) ?: cached(url) ?: LinkPreview.loading(url)
        }
    }

    fun canonicalKey(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.ifEmpty { null } ?: return null
        val port = if (uri.port != -1 && !((scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443))) {
            ":${uri.port}"
        } else ""
        var path = uri.rawPath.orEmpty()
        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$port$path$query"
    }

    private fun normalize(raw: String): String? {
        var candidate = raw.trim()
        if (candidate.isEmpty()) return null
        val explicit = SCHEME.containsMatchIn(candidate)
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            if (last !in TRAILING) break
            // A `)` that closes one opened inside the URL stays (Wikipedia-style links).
            if (last == ')' && candidate.count { it == ')' } <= candidate.count { it == '(' }) break
            candidate = candidate.dropLast(1)
        }
        if (!explicit && looksLikeBareFilename(candidate)) return null
        if (!explicit && !hasAllowedBareSuffix(candidate)) return null
        if (candidate.isNotEmpty() && !SCHEME.containsMatchIn(candidate)) candidate = "https://$candidate"
        return candidate.ifEmpty { null }
    }

    private fun looksLikeImage(url: String): Boolean {
        val path = (runCatching { URI(url).path }.getOrNull() ?: url).lowercase()
        return hasExtension(path, IMAGE_EXTENSIONS)
    }

    private fun looksLikeBareFilename(value: String): Boolean {
        val candidate = value.trim().lowercase()
        if (candidate.isEmpty() || '/' in candidate || '?' in candidate || '#' in candidate) return false
        return hasExtension(candidate, FILE_EXTENSIONS)
    }

    private fun hasAllowedBareSuffix(value: String): Boolean {
        val host = runCatching { URI("https://${value.trim()}").host }.getOrNull()
            ?.lowercase()?.removePrefix("www.").orEmpty()
        if (host.isEmpty()) return false
        return BARE_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    private fun hasExtension(value: String, extensions: Set<String>): Boolean {
        val pure = value.substringBefore('?').substringBefore('#')
        val dot = pure.lastIndexOf('.')
        if (dot <= 0 || dot == pure.length - 1) return false
        return pure.substring(dot + 1).lowercase() in extensions
    }
}

/**
 * Replaces the loading preview for [url] with [resolved] in a message's
 * content (Dart `_resolveUserMessageLinkPreview`). Null when nothing changed:
 * the preview already resolved, or the message no longer lists that link.
 */
internal fun contentWithResolvedPreview(content: Map<String, Any?>, url: String, resolved: LinkPreview): Map<String, Any?>? {
    val previews = content["linkPreviews"] as? List<*> ?: return null
    var changed = false
    val updated = previews.map { item ->
        val map = item as? Map<*, *> ?: return@map item
        val preview = LinkPreview.fromMap(map)
        if (preview.url != url || preview.status != LinkPreviewStatus.LOADING) {
            item
        } else {
            changed = true
            resolved.toMap()
        }
    }
    if (!changed) return null
    return LinkedHashMap(content).apply { put("linkPreviews", updated) }
}

/** Dart `_parseHtml`: Open Graph, then Twitter Card, then the page's own title and description. */
internal object LinkPreviewHtml {
    private val META = Regex("""<meta\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ATTRIBUTE = Regex("""([^\s=/>]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val WHITESPACE = Regex("""\s+""")

    fun parse(url: String, html: String): LinkPreview {
        val base = URI(url)
        fun meta(attribute: String, name: String) = metaContent(html, attribute, name)
        val ogTitle = meta("property", "og:title")
        val ogDescription = meta("property", "og:description")
        val ogImage = meta("property", "og:image")
        val twitterTitle = meta("name", "twitter:title")
        val twitterDescription = meta("name", "twitter:description")
        val twitterImage = meta("name", "twitter:image")
        val pageTitle = TITLE.find(html)?.groupValues?.get(1)?.let { collapse(decode(it)) }.orEmpty()
        return LinkPreview(
            url = url,
            domain = base.host.orEmpty(),
            siteName = meta("property", "og:site_name"),
            title = ogTitle.ifEmpty { twitterTitle.ifEmpty { pageTitle } },
            description = ogDescription.ifEmpty { twitterDescription.ifEmpty { meta("name", "description") } },
            imageUrl = resolve(base, ogImage.ifEmpty { twitterImage }),
            status = LinkPreviewStatus.READY,
        )
    }

    private fun metaContent(html: String, attribute: String, name: String): String {
        for (tag in META.findAll(html)) {
            val attributes = HashMap<String, String>()
            for (match in ATTRIBUTE.findAll(tag.value)) {
                val key = match.groupValues[1].trim().lowercase()
                val value = match.groups[3]?.value ?: match.groups[4]?.value ?: match.groups[5]?.value ?: ""
                if (key.isNotEmpty()) attributes[key] = value.trim()
            }
            if (attributes[attribute]?.trim()?.lowercase() != name) continue
            val content = collapse(decode(attributes["content"].orEmpty()))
            if (content.isNotEmpty()) return content
        }
        return ""
    }

    private fun resolve(base: URI, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val parsed = URI(trimmed)
            (if (parsed.scheme != null) parsed else base.resolve(parsed)).toString()
        }.getOrDefault("")
    }

    private fun collapse(value: String) = WHITESPACE.replace(value, " ").trim()

    private fun decode(value: String): String = ENTITY.replace(value) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) ->
                entity.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
            else -> when (entity) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> match.value
            }
        }
    }
}

/**
 * Fetches previews with an 8 s budget (Dart `_fetchPreview`); one request per
 * URL at a time, and finished previews are remembered for the process.
 */
internal class LinkPreviewLoader(
    private val client: OkHttpClient = DEFAULT_CLIENT,
) {
    private val cache = ConcurrentHashMap<String, LinkPreview>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<LinkPreview>>()

    fun cached(url: String): LinkPreview? {
        val key = LinkPreviewExtractor.canonicalKey(url) ?: return null
        return cache.entries.firstOrNull { LinkPreviewExtractor.canonicalKey(it.key) == key }?.value
    }

    suspend fun load(url: String): LinkPreview {
        cache[url]?.takeIf { it.status != LinkPreviewStatus.LOADING }?.let { return it }
        val created = CompletableDeferred<LinkPreview>()
        val pending = inFlight.putIfAbsent(url, created)
        if (pending != null) return pending.await()
        val preview = try {
            fetch(url)
        } finally {
            inFlight.remove(url)
        }
        cache[url] = preview
        created.complete(preview)
        return preview
    }

    private suspend fun fetch(url: String): LinkPreview = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("accept", "text/html,application/xhtml+xml").build()
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..399) return@use LinkPreview.failed(url)
                val html = response.body.bytes().toString(Charsets.UTF_8)
                LinkPreviewHtml.parse(url, html)
            }
        }.getOrElse { LinkPreview.failed(url) }
    }

    companion object {
        private val DEFAULT_CLIENT = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
        val shared = LinkPreviewLoader()
    }
}
