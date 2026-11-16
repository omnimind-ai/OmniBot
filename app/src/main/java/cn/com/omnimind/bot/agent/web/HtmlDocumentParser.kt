package cn.com.omnimind.bot.agent.web

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal data class WebPageLink(
    val id: Int,
    val text: String,
    val url: String,
)

internal data class WebPageDocument(
    val url: String,
    val title: String?,
    val content: String,
    val contentType: String,
    val format: String,
    val links: List<WebPageLink>,
    val truncated: Boolean,
    val truncationReasons: List<String>,
    val linksTruncated: Boolean = false,
    val titleTruncated: Boolean = false,
)

internal data class WebPageContentPage(
    val offsetChars: Int,
    val nextOffsetChars: Int,
    val totalChars: Int,
    val content: String,
    val hasMore: Boolean,
    val sourceTruncated: Boolean,
)

internal class WebPageContentException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

internal sealed interface HtmlNode {
    data class Text(val raw: String) : HtmlNode
    class Element(
        val tagName: String,
        val attributes: Map<String, String>,
        val children: MutableList<HtmlNode> = mutableListOf(),
    ) : HtmlNode {
        val idAttr: String get() = attributes["id"].orEmpty()
        val classes: Set<String> by lazy {
            attributes["class"]?.split(WHITESPACE)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }

        fun attr(name: String): String = attributes[name.lowercase(Locale.ROOT)].orEmpty()
        fun hasAttr(name: String): Boolean = attributes.containsKey(name.lowercase(Locale.ROOT))
        fun hasClass(name: String): Boolean = name in classes

        fun text(ensureActive: () -> Unit = {}): String {
            val sb = StringBuilder()
            collectText(this, sb, ensureActive)
            return HtmlEntities.decode(sb.toString()).replace(WHITESPACE, " ").trim()
        }

        private fun collectText(node: HtmlNode, sb: StringBuilder, ensureActive: () -> Unit) {
            ensureActive()
            when (node) {
                is Text -> sb.append(node.raw)
                is Element -> {
                    if (node.tagName in SKIP_TEXT_TAGS) return
                    val isBlock = node.tagName in BLOCK_OR_BREAK_TAGS
                    if (isBlock && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                    for (child in node.children) {
                        collectText(child, sb, ensureActive)
                    }
                    if (isBlock && sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                }
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val SKIP_TEXT_TAGS = setOf("script", "style", "noscript", "template")
        val BLOCK_OR_BREAK_TAGS = setOf(
            "p", "div", "section", "article", "main", "li", "tr", "td", "th",
            "h1", "h2", "h3", "h4", "h5", "h6", "br", "blockquote", "pre",
        )
    }
}

/**
 * 轻量级容错 HTML DOM 解析器（无第三方解析依赖）。
 */
internal object SimpleHtmlParser {
    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )
    private val RAW_TEXT_TAGS = setOf("script", "style", "textarea", "title")
    private val IMPLICIT_CLOSE_IN_PARENTS = mapOf(
        "p" to setOf("p", "div", "section", "article", "main", "header", "footer", "nav", "aside", "ul", "ol", "li", "table", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "form"),
        "li" to setOf("li"),
        "dt" to setOf("dt", "dd"),
        "dd" to setOf("dt", "dd"),
        "tr" to setOf("tr"),
        "td" to setOf("td", "th", "tr"),
        "th" to setOf("td", "th", "tr"),
        "option" to setOf("option", "optgroup"),
    )

    fun parse(html: String, ensureActive: () -> Unit = {}): HtmlNode.Element {
        val root = HtmlNode.Element("#document", emptyMap())
        val stack = ArrayDeque<HtmlNode.Element>()
        stack.addLast(root)
        var i = 0
        val n = html.length

        while (i < n) {
            if ((i and 0x3FF) == 0) ensureActive()
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                appendText(stack.last(), html.substring(i))
                break
            }
            if (lt > i) {
                appendText(stack.last(), html.substring(i, lt))
            }
            if (lt + 1 >= n) {
                appendText(stack.last(), "<")
                break
            }

            // Comment
            if (html.startsWith("<!--", lt)) {
                val close = html.indexOf("-->", lt + 4)
                i = if (close >= 0) close + 3 else n
                continue
            }
            // DOCTYPE / declaration / processing instruction
            if (html[lt + 1] == '!' || html[lt + 1] == '?') {
                val gt = html.indexOf('>', lt + 2)
                i = if (gt >= 0) gt + 1 else n
                continue
            }

            // Closing tag
            if (html[lt + 1] == '/') {
                val gt = html.indexOf('>', lt + 2)
                if (gt < 0) {
                    appendText(stack.last(), html.substring(lt))
                    break
                }
                val rawName = html.substring(lt + 2, gt).trim()
                val tagName = rawName.takeWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)
                popUntil(stack, tagName)
                i = gt + 1
                continue
            }

            // Opening tag
            val tagHeader = readOpeningTag(html, lt + 1)
            if (tagHeader == null) {
                appendText(stack.last(), "<")
                i = lt + 1
                continue
            }
            val (tagName, attrs, selfClosing, nextIndex) = tagHeader
            i = nextIndex

            if (tagName.isEmpty()) continue
            closeImplicitParents(stack, tagName)

            val element = HtmlNode.Element(tagName, attrs)
            stack.last().children.add(element)

            if (selfClosing || tagName in VOID_TAGS) {
                continue
            }

            if (tagName in RAW_TEXT_TAGS) {
                val closePattern = "</$tagName"
                val closeIdx = findRawCloseTag(html, i, closePattern)
                if (closeIdx < 0) {
                    if (i < n) appendText(element, html.substring(i))
                    i = n
                } else {
                    if (closeIdx > i) appendText(element, html.substring(i, closeIdx))
                    val gt = html.indexOf('>', closeIdx + closePattern.length)
                    i = if (gt >= 0) gt + 1 else n
                }
                continue
            }

            stack.addLast(element)
        }
        return root
    }

    private fun appendText(parent: HtmlNode.Element, raw: String) {
        if (raw.isNotEmpty()) {
            parent.children.add(HtmlNode.Text(raw))
        }
    }

    private fun popUntil(stack: ArrayDeque<HtmlNode.Element>, tagName: String) {
        if (tagName.isEmpty()) return
        val matchIndex = stack.indexOfLast { it.tagName == tagName }
        if (matchIndex <= 0) return // Don't pop #document root
        while (stack.size > matchIndex) {
            stack.removeLast()
        }
    }

    private fun closeImplicitParents(stack: ArrayDeque<HtmlNode.Element>, nextTag: String) {
        while (stack.size > 1) {
            val parentTag = stack.last().tagName
            val closers = IMPLICIT_CLOSE_IN_PARENTS[parentTag] ?: break
            if (nextTag in closers) {
                stack.removeLast()
            } else {
                break
            }
        }
    }

    private fun findRawCloseTag(html: String, start: Int, closePrefix: String): Int {
        var pos = start
        while (pos < html.length) {
            val idx = html.indexOf(closePrefix, pos, ignoreCase = true)
            if (idx < 0) return -1
            val after = idx + closePrefix.length
            if (after >= html.length || html[after] == '>' || html[after].isWhitespace() || html[after] == '/') {
                return idx
            }
            pos = idx + 1
        }
        return -1
    }

    private data class TagHeader(
        val name: String,
        val attrs: Map<String, String>,
        val selfClosing: Boolean,
        val nextIndex: Int,
    )

    private fun readOpeningTag(html: String, start: Int): TagHeader? {
        val n = html.length
        var i = start
        if (i >= n || !isTagNameStart(html[i])) return null
        val nameStart = i
        while (i < n && isTagNameChar(html[i])) i++
        val name = html.substring(nameStart, i).lowercase(Locale.ROOT)
        val attrs = linkedMapOf<String, String>()
        var selfClosing = false

        while (i < n) {
            while (i < n && html[i].isWhitespace()) i++
            if (i >= n) break
            if (html[i] == '>') {
                i++
                return TagHeader(name, attrs, selfClosing, i)
            }
            if (html[i] == '/' && i + 1 < n && html[i + 1] == '>') {
                selfClosing = true
                i += 2
                return TagHeader(name, attrs, true, i)
            }
            if (html[i] == '/') {
                i++
                continue
            }
            val attrNameStart = i
            while (i < n && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') {
                i++
            }
            val attrName = html.substring(attrNameStart, i).lowercase(Locale.ROOT)
            while (i < n && html[i].isWhitespace()) i++
            var attrValue = ""
            if (i < n && html[i] == '=') {
                i++
                while (i < n && html[i].isWhitespace()) i++
                if (i < n && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i++]
                    val valStart = i
                    while (i < n && html[i] != quote) i++
                    attrValue = HtmlEntities.decode(html.substring(valStart, i))
                    if (i < n && html[i] == quote) i++
                } else {
                    val valStart = i
                    while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                    attrValue = HtmlEntities.decode(html.substring(valStart, i))
                }
            }
            if (attrName.isNotEmpty() && attrName !in attrs) {
                attrs[attrName] = attrValue
            }
        }
        return TagHeader(name, attrs, selfClosing, n)
    }

    private fun isTagNameStart(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'
    private fun isTagNameChar(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == ':'

    fun findAll(root: HtmlNode.Element, predicate: (HtmlNode.Element) -> Boolean): List<HtmlNode.Element> {
        val matches = mutableListOf<HtmlNode.Element>()
        fun walk(el: HtmlNode.Element) {
            if (predicate(el)) matches.add(el)
            for (child in el.children) {
                if (child is HtmlNode.Element) walk(child)
            }
        }
        for (child in root.children) {
            if (child is HtmlNode.Element) walk(child)
        }
        return matches
    }

    fun findFirst(root: HtmlNode.Element, predicate: (HtmlNode.Element) -> Boolean): HtmlNode.Element? {
        for (child in root.children) {
            if (child is HtmlNode.Element) {
                if (predicate(child)) return child
                findFirst(child, predicate)?.let { return it }
            }
        }
        return null
    }
}

internal object HtmlEntities {
    private val ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]{1,31});")
    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
        "ensp" to " ",
        "emsp" to " ",
        "thinsp" to " ",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "middot" to "·",
        "bull" to "•",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "laquo" to "«",
        "raquo" to "»",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "times" to "×",
        "divide" to "÷",
        "plusmn" to "±",
        "deg" to "°",
        "euro" to "€",
        "yen" to "¥",
        "pound" to "£",
        "cent" to "¢",
        "larr" to "←",
        "rarr" to "→",
        "uarr" to "↑",
        "darr" to "↓",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY_REGEX.replace(text) { match ->
            val token = match.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) -> {
                    val code = token.drop(2).toIntOrNull(16)
                    if (code != null && code > 0 && Character.isValidCodePoint(code)) {
                        String(Character.toChars(code))
                    } else {
                        match.value
                    }
                }
                token.startsWith("#") -> {
                    val code = token.drop(1).toIntOrNull()
                    if (code != null && code > 0 && Character.isValidCodePoint(code)) {
                        String(Character.toChars(code))
                    } else {
                        match.value
                    }
                }
                else -> NAMED[token] ?: NAMED[token.lowercase(Locale.ROOT)] ?: match.value
            }
        }
    }
}

internal class WebPageLinks {
    private val byUrl = linkedMapOf<String, WebPageLink>()
    private var retainedChars = 0
    var truncated: Boolean = false
        private set
    val items: List<WebPageLink> get() = byUrl.values.toList()

    fun add(url: String, label: String): WebPageLink? {
        if (url.isBlank() || url.any { it.isWhitespace() || it.isISOControl() }) return null
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if ((!parsed.scheme.equals("http", ignoreCase = true) && !parsed.scheme.equals("https", ignoreCase = true)) ||
            parsed.rawAuthority.isNullOrBlank()
        ) {
            return null
        }
        byUrl[url]?.let { return it }
        val cleanLabel = takeCompleteCharacters(
            label.replace(WHITESPACE, " ").trim().ifBlank { url },
            WebPageContent.MAX_LINK_TEXT_CHARS,
        )
        if (url.length > WebPageContent.MAX_LINK_URL_CHARS ||
            byUrl.size >= WebPageContent.MAX_LINKS ||
            retainedChars + url.length + cleanLabel.length > WebPageContent.MAX_LINK_TOTAL_CHARS
        ) {
            truncated = true
            return null
        }
        return WebPageLink(byUrl.size + 1, cleanLabel, url).also {
            byUrl[url] = it
            retainedChars += url.length + cleanLabel.length
        }
    }

    fun addPlainTextLinks(text: String, ensureActive: () -> Unit) {
        for (match in HTTP_URL.findAll(text)) {
            ensureActive()
            var url = match.value.trimEnd('.', ',', ';', '!', '。', '，', '；', '！')
            var end = url.length
            for ((closing, opening) in listOf(')' to '(', ']' to '[', '}' to '{')) {
                var extra = url.count { it == closing } - url.count { it == opening }
                while (end > 0 && url[end - 1] == closing && extra > 0) {
                    end--
                    extra--
                }
            }
            url = url.substring(0, end)
            add(url, url)
            if (truncated) break
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val HTTP_URL = Regex("https?://[^\\s<>\"，。；！、\\u0000-\\u001f]+", RegexOption.IGNORE_CASE)
    }
}

internal data class HtmlPageExtraction(
    val title: String?,
    val content: String,
    val links: List<WebPageLink>,
    val contentTruncated: Boolean,
    val linksTruncated: Boolean,
    val titleTruncated: Boolean,
)

internal object HtmlContentExtractor {
    private val PARAGRAPH_TAGS = setOf(
        "p", "div", "section", "article", "main", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "figure", "table",
    )
    private val LINE_TAGS = setOf("li", "ul", "ol", "tr", "dl", "dt", "dd", "figcaption", "hr")
    private val NOISE_TAGS = setOf(
        "script", "style", "noscript", "template", "iframe", "object", "embed",
        "svg", "canvas", "nav", "aside", "form", "button", "input", "select", "textarea",
    )
    private val NOISE_ROLES = setOf("navigation", "banner", "complementary")

    fun extract(html: String, baseUrl: String, ensureActive: () -> Unit = {}): HtmlPageExtraction {
        ensureActive()
        val document = SimpleHtmlParser.parse(html, ensureActive)
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()

        val rawTitle = SimpleHtmlParser.findFirst(document) { it.tagName == "title" }
            ?.text(ensureActive)
            ?.takeIf { it.isNotBlank() }
            ?: SimpleHtmlParser.findFirst(document) {
                it.tagName == "meta" && it.attr("property").equals("og:title", ignoreCase = true)
            }?.attr("content")?.trim().orEmpty()

        val body = SimpleHtmlParser.findFirst(document) { it.tagName == "body" } ?: document
        val primary = SimpleHtmlParser.findFirst(body) {
            !isNoise(it, body) &&
                (it.tagName == "main" || it.attr("role").equals("main", ignoreCase = true)) &&
                it.text(ensureActive).isNotBlank()
        } ?: SimpleHtmlParser.findFirst(body) {
            !isNoise(it, body) && it.tagName == "article" && it.text(ensureActive).isNotBlank()
        } ?: body

        val title = rawTitle.ifBlank {
            SimpleHtmlParser.findFirst(primary) { !isNoise(it, body) && it.tagName == "h1" }
                ?.text(ensureActive)
                .orEmpty()
        }

        val links = WebPageLinks()
        val output = HtmlTextBuffer(WebPageContent.MAX_CONTENT_CHARS)
        var preformattedDepth = 0

        fun visit(node: HtmlNode, parentElement: HtmlNode.Element?) {
            ensureActive()
            when (node) {
                is HtmlNode.Text -> {
                    val decoded = HtmlEntities.decode(node.raw)
                    output.appendText(decoded, preformattedDepth > 0)
                }
                is HtmlNode.Element -> {
                    if (isNoise(node, body)) return
                    val tag = node.tagName
                    if (tag in PARAGRAPH_TAGS) output.lineBreak(2)
                    else if (tag in LINE_TAGS) output.lineBreak(1)

                    when (tag) {
                        "pre" -> preformattedDepth++
                        "br" -> output.lineBreak(1)
                        "li" -> output.appendToken("- ")
                        "img" -> node.attr("alt").trim().takeIf { it.isNotBlank() }?.let { alt ->
                            output.appendText("[图片：$alt]", preformatted = false)
                        }
                    }

                    for (child in node.children) {
                        visit(child, node)
                    }

                    when (tag) {
                        "a" -> {
                            val resolvedHref = resolveHttpUrl(baseUri, node.attr("href"))
                            val label = node.text(ensureActive).ifBlank {
                                node.attr("aria-label").ifBlank { node.attr("title") }
                            }
                            if (resolvedHref != null) {
                                links.add(resolvedHref, label)?.let { link ->
                                    output.appendToken(" [${link.id}]")
                                }
                            }
                        }
                        "pre" -> if (preformattedDepth > 0) preformattedDepth--
                        "td", "th" -> output.appendToken(" | ")
                    }

                    if (tag in PARAGRAPH_TAGS) output.lineBreak(2)
                    else if (tag in LINE_TAGS) output.lineBreak(1)
                }
            }
        }

        visit(primary, null)
        ensureActive()
        return HtmlPageExtraction(
            title = takeCompleteCharacters(title, WebPageContent.MAX_TITLE_CHARS).takeIf { it.isNotEmpty() },
            content = output.content(),
            links = links.items,
            contentTruncated = output.truncated,
            linksTruncated = links.truncated,
            titleTruncated = title.length > WebPageContent.MAX_TITLE_CHARS,
        )
    }

    private fun isNoise(element: HtmlNode.Element, body: HtmlNode.Element): Boolean {
        if (element.tagName in NOISE_TAGS) return true
        if (element.hasAttr("hidden")) return true
        if (element.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        if (element.attr("role").lowercase(Locale.ROOT) in NOISE_ROLES) return true
        if ((element.tagName == "header" || element.tagName == "footer") && element in body.children) return true
        return false
    }

    private fun resolveHttpUrl(baseUri: URI?, href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isBlank()) return null
        return runCatching {
            val resolved = if (baseUri != null) baseUri.resolve(trimmed) else URI(trimmed)
            val scheme = resolved.scheme?.lowercase(Locale.ROOT)
            if (scheme == "http" || scheme == "https") resolved.toString() else null
        }.getOrNull()
    }
}

private class HtmlTextBuffer(private val limit: Int) {
    private val text = StringBuilder()
    private var pendingSpace = false
    var truncated: Boolean = false
        private set

    fun appendText(value: String, preformatted: Boolean) {
        if (truncated) return
        if (preformatted) {
            appendToken(value)
            return
        }
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch.isWhitespace() || ch == '\u00a0') {
                pendingSpace = true
                index++
                continue
            }
            if (pendingSpace && text.isNotEmpty() && !text.last().isWhitespace()) {
                appendToken(" ")
            }
            pendingSpace = false
            val width = if (ch.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
            if (text.length + width > limit) {
                truncated = true
                return
            }
            text.append(value, index, index + width)
            index += width
        }
    }

    fun appendToken(value: String) {
        if (truncated || value.isEmpty()) return
        pendingSpace = false
        val retained = takeCompleteCharacters(value, (limit - text.length).coerceAtLeast(0))
        text.append(retained)
        if (retained.length < value.length) truncated = true
    }

    fun lineBreak(count: Int) {
        if (truncated) return
        pendingSpace = false
        while (text.isNotEmpty() && (text.last() == ' ' || text.last() == '\t')) {
            text.setLength(text.length - 1)
        }
        if (text.isEmpty()) return
        var existing = 0
        var index = text.lastIndex
        while (index >= 0 && text[index] == '\n') {
            existing++
            index--
        }
        repeat((count - existing).coerceAtLeast(0)) {
            if (text.length < limit) text.append('\n')
        }
    }

    fun content(): String = text.toString().trim('\n', '\r')
}

internal object WebPageContent {
    const val MAX_INPUT_CHARS = 512_000
    const val MAX_CONTENT_CHARS = 200_000
    const val MAX_PAGE_CHARS = 16_000
    const val DEFAULT_PAGE_CHARS = 12_000
    const val MIN_PAGE_CHARS = 1_000
    const val MAX_LINKS = 200
    const val MAX_LINK_URL_CHARS = 4_096
    const val MAX_LINK_TEXT_CHARS = 200
    const val MAX_LINK_TOTAL_CHARS = 24_000
    const val MAX_TITLE_CHARS = 512

    fun extract(
        text: String,
        contentType: String,
        finalUrl: String,
        sourceTruncated: Boolean = false,
        ensureActive: () -> Unit = {},
    ): WebPageDocument {
        ensureActive()
        val mime = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val format = when {
            mime == "text/html" || mime == "application/xhtml+xml" -> "html"
            mime == "text/markdown" || mime == "text/x-markdown" -> "markdown"
            mime == "application/json" || (mime.startsWith("application/") && mime.endsWith("+json")) -> "json"
            mime == "application/xml" || mime == "text/xml" || (mime.startsWith("application/") && mime.endsWith("+xml")) -> "xml"
            mime.startsWith("text/") -> "text"
            else -> throw WebPageContentException("UNSUPPORTED_CONTENT_TYPE", "此内容类型不支持文本正文解析")
        }
        val reasons = mutableListOf<String>()
        if (sourceTruncated) reasons += "source_limit"
        val input = takeCompleteCharacters(text, MAX_INPUT_CHARS)
        if (input.length < text.length) reasons += "input_limit"

        if (format == "html") {
            val extracted = HtmlContentExtractor.extract(input, finalUrl, ensureActive)
            if (extracted.contentTruncated) reasons += "content_limit"
            return WebPageDocument(
                url = finalUrl,
                title = extracted.title,
                content = extracted.content,
                contentType = mime,
                format = format,
                links = extracted.links,
                truncated = reasons.isNotEmpty(),
                truncationReasons = reasons,
                linksTruncated = extracted.linksTruncated,
                titleTruncated = extracted.titleTruncated,
            )
        }

        val content = takeCompleteCharacters(input, MAX_CONTENT_CHARS)
        if (content.length < input.length) reasons += "content_limit"
        val links = WebPageLinks()
        links.addPlainTextLinks(content, ensureActive)
        ensureActive()
        return WebPageDocument(
            url = finalUrl,
            title = null,
            content = content,
            contentType = mime,
            format = format,
            links = links.items,
            truncated = reasons.isNotEmpty(),
            truncationReasons = reasons,
            linksTruncated = links.truncated,
        )
    }

    fun page(
        document: WebPageDocument,
        offsetChars: Int = 0,
        maxChars: Int = DEFAULT_PAGE_CHARS,
    ): WebPageContentPage {
        if (offsetChars !in 0..document.content.length || maxChars <= 0) {
            throw WebPageContentException("INVALID_CONTENT_RANGE", "正文分页范围无效")
        }
        if (offsetChars > 0 && offsetChars < document.content.length &&
            document.content[offsetChars].isLowSurrogate() &&
            document.content[offsetChars - 1].isHighSurrogate()
        ) {
            throw WebPageContentException(
                "INVALID_CONTENT_OFFSET",
                "正文游标位于 Unicode 字符中间，请使用上次返回的 next_offset_chars",
            )
        }
        val boundedMax = maxChars.coerceAtMost(MAX_PAGE_CHARS)
        val rawEnd = (offsetChars.toLong() + boundedMax).coerceAtMost(document.content.length.toLong()).toInt()
        val end = completeCharacterEnd(document.content, rawEnd)
        if (end == offsetChars && offsetChars < document.content.length) {
            throw WebPageContentException("CONTENT_PAGE_TOO_SMALL", "分页上限不足以返回一个完整 Unicode 字符")
        }
        return WebPageContentPage(
            offsetChars = offsetChars,
            nextOffsetChars = end,
            totalChars = document.content.length,
            content = document.content.substring(offsetChars, end),
            hasMore = end < document.content.length,
            sourceTruncated = document.truncated,
        )
    }
}

internal fun takeCompleteCharacters(text: String, maxChars: Int): String =
    text.substring(0, completeCharacterEnd(text, maxChars.coerceAtMost(text.length)))

private fun completeCharacterEnd(text: String, end: Int): Int =
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
        end - 1
    } else {
        end
    }
