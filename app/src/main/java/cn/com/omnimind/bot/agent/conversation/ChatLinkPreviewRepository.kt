package cn.com.omnimind.bot.agent

import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Shared by all Flutter engines. Parsing and unauthenticated web I/O never run on the UI thread. */
internal class ChatLinkPreviewRepository(
    private val fetchHtml: suspend (String) -> String? = ::fetchPreviewHtml,
) {
    companion object {
        val shared by lazy { ChatLinkPreviewRepository() }
        private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif")
        private val fileExtensions = imageExtensions + setOf(
            "pdf", "txt", "md", "json", "csv", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip", "rar", "7z", "mp3", "wav", "m4a", "mp4", "mov", "avi",
        )
        private val suffixes = ("com net org io ai app dev cn cc me tv fm xyz info top tech site online " +
            "cloud shop store blog pro biz name edu gov mil int us uk ca au eu de fr jp kr sg hk tw " +
            "in br ru it es nl co.uk org.uk ac.uk gov.uk com.cn net.cn org.cn gov.cn edu.cn co.jp " +
            "ne.jp or.jp com.au net.au org.au com.hk com.tw com.sg com.br").split(' ').toSet()
        private val webUrl = Regex("""https?://[^\s<>"\]\[]+""", RegexOption.IGNORE_CASE)
        private val bareUrl = Regex(
            """(?:www\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z][a-z0-9-]{1,62}(?:/[^\s<>"\]\[]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val ignoredUrl = Regex("""omnibot://[^\s<>"\]\[)]+""", RegexOption.IGNORE_CASE)
        private val trailingPunctuation = ".,!?:;)]\"'*_`。，；：！？）】》”’".toSet()
        private val metaTag = Regex("<meta\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val attribute = Regex("""([^\s=/>]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""")
        private val titleTag = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
        private val whitespace = Regex("\\s+")

        private fun uri(value: String) = runCatching { URI(value) }.getOrNull()
        private fun key(value: String): String? {
            val uri = uri(value.trim()) ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf(String::isNotEmpty) ?: return null
            val port = uri.port.takeUnless { it == -1 || scheme == "http" && it == 80 || scheme == "https" && it == 443 }
            val path = uri.rawPath.orEmpty().let { if (it.length > 1) it.trimEnd('/').ifEmpty { "/" } else it }
            return "$scheme://$host${port?.let { ":$it" }.orEmpty()}$path${uri.rawQuery?.let { "?$it" }.orEmpty()}"
        }

        private fun preview(url: String, status: String) = linkedMapOf(
            "url" to url, "domain" to uri(url)?.host.orEmpty(), "siteName" to "", "title" to "",
            "description" to "", "imageUrl" to "", "status" to status,
        )

        private fun clean(value: String): String = whitespace.replace(entity.replace(value) { match ->
            val name = match.groupValues[1]
            val code = when {
                name.startsWith("#x", ignoreCase = true) -> name.drop(2).toIntOrNull(16)
                name.startsWith('#') -> name.drop(1).toIntOrNull()
                else -> null
            }
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else when (name) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> match.value
            }
        }, " ").trim()

        internal fun parseHtml(url: String, html: String): Map<String, String> {
            val metas = metaTag.findAll(html).map { tag ->
                attribute.findAll(tag.value).associate { match ->
                    match.groupValues[1].lowercase(Locale.ROOT) to
                        (match.groups[3]?.value ?: match.groups[4]?.value ?: match.groupValues[5]).trim()
                }
            }.toList()
            fun meta(attr: String, name: String) = metas.asSequence()
                .filter { it[attr]?.lowercase(Locale.ROOT) == name }
                .map { clean(it["content"].orEmpty()) }.firstOrNull(String::isNotEmpty).orEmpty()
            val image = meta("property", "og:image").ifEmpty { meta("name", "twitter:image") }
            return preview(url, "ready") + mapOf(
                "siteName" to meta("property", "og:site_name"),
                "title" to meta("property", "og:title").ifEmpty { meta("name", "twitter:title") }
                    .ifEmpty { clean(titleTag.find(html)?.groupValues?.get(1).orEmpty()) },
                "description" to meta("property", "og:description").ifEmpty { meta("name", "twitter:description") }
                    .ifEmpty { meta("name", "description") },
                "imageUrl" to if (image.isEmpty()) "" else runCatching { URI(url).resolve(image).toString() }.getOrDefault(""),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, Map<String, String>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>) = size > 256
    }
    private val inFlight = mutableMapOf<String, Deferred<Map<String, String>>>()

    internal fun extractUrls(text: String, maxCount: Int = 3): List<String> {
        if (text.isBlank() || maxCount <= 0) return emptyList()
        val masked = ignoredUrl.replace(text) { " ".repeat(it.value.length) }
        val candidates = (webUrl.findAll(masked) + bareUrl.findAll(masked).filter {
            it.range.first == 0 || masked[it.range.first - 1] !in "@/._-"
        }).sortedBy { it.range.first }
        val seen = mutableSetOf<String>()
        val urls = mutableListOf<String>()
        for (match in candidates) {
            var candidate = match.value.trim()
            val explicit = candidate.startsWith("http://", true) || candidate.startsWith("https://", true)
            while (candidate.isNotEmpty() && candidate.last() in trailingPunctuation) {
                if (candidate.last() == ')' && candidate.count { it == ')' } <= candidate.count { it == '(' }) break
                candidate = candidate.dropLast(1)
            }
            if (!explicit) {
                if (candidate.none { it in "/?#" } && candidate.substringAfterLast('.').lowercase(Locale.ROOT) in fileExtensions) continue
                val host = uri("https://$candidate")?.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
                if (suffixes.none { host == it || host.endsWith(".$it") }) continue
                candidate = "https://$candidate"
            }
            val path = uri(candidate)?.path.orEmpty()
            if (path.substringAfterLast('.').lowercase(Locale.ROOT) in imageExtensions) continue
            val key = key(candidate) ?: continue
            if (!seen.add(key)) continue
            urls.add(candidate)
            if (urls.size >= maxCount) break
        }
        return urls
    }

    suspend fun reconcile(text: String, existing: Any?, maxCount: Int = 3): List<Map<String, String>> = withContext(Dispatchers.Default) {
        val previous = linkedMapOf<String, Map<String, String>>()
        (existing as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { raw ->
            val url = raw["url"]?.toString()?.trim().orEmpty()
            key(url)?.let { key ->
                if (key !in previous) previous[key] = preview(url, "loading").mapValues { (field, fallback) ->
                    raw[field]?.toString()?.trim() ?: fallback
                }.let { row -> row + ("status" to row["status"].takeIf { it == "ready" || it == "failed" }.orEmpty().ifEmpty { "loading" }) }
            }
        }
        extractUrls(text, maxCount).map { url ->
            val key = key(url)!!
            previous[key] ?: synchronized(lock) { cache[key] } ?: preview(url, "loading")
        }
    }

    suspend fun load(url: String): Map<String, String> {
        val key = key(url) ?: return preview(url, "failed")
        val pending = synchronized(lock) {
            cache[key]?.let { return it + ("url" to url) }
            inFlight.getOrPut(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    val result = runCatching { fetchHtml(url)?.let { parseHtml(url, it) } }.getOrNull()
                        ?: preview(url, "failed")
                    synchronized(lock) { cache[key] = result }
                    result
                }.also { job ->
                    job.invokeOnCompletion { synchronized(lock) { if (inFlight[key] === job) inFlight.remove(key) } }
                }
            }
        }
        // Preserve the caller's URL identity even when a canonical alias shares the request.
        return pending.await() + ("url" to url)
    }
}

private val previewHttpClient by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build() }

private fun fetchPreviewHtml(url: String): String? {
    val request = Request.Builder().url(url).header("accept", "text/html,application/xhtml+xml").build()
    return previewHttpClient.newCall(request).execute().use { response ->
        if (response.code !in 200..399) return@use null
        val source = response.body.source()
        // A preview only needs the document head; bound memory for unexpectedly large responses.
        val limit = 2L * 1024 * 1024
        source.request(limit)
        source.readUtf8(minOf(source.buffer.size, limit))
    }
}
