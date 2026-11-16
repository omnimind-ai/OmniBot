package cn.com.omnimind.bot.agent.web

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull

internal data class DecodedWebResponse(
    val text: String,
    val contentType: String,
    val charset: String,
    val lossy: Boolean,
)

/**
 * 依据 BOM、HTTP 响应头与 HTML meta 声明可靠解码响应正文，并拦截二进制资源。
 */
internal object WebResponseDecoder {
    private val HTML_PREFIX = Regex(
        "(?is)^\\s*(?:<!doctype\\s+html|<!--|<(?:html|head|body|main|article|script|iframe|h[1-6]|div|font|table|a|style|title|b|br|p)(?=[\\s/>]))",
    )
    private val META_CHARSET = Regex(
        """<meta\b[^>]*\bcharset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val META_HTTP_EQUIV_TAG = Regex(
        """<meta\b[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTR_REGEX = Regex(
        """([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    )

    fun decode(response: WebHttpResponse): DecodedWebResponse {
        val bytes = response.bytes
        if (bytes.size > WebHttpTransport.MAX_RESPONSE_BYTES) {
            throw WebRequestException("WEB_DECODE_LIMIT", "网页响应超过解码容量限制")
        }
        val bom = when {
            bytes.startsWith(0xef, 0xbb, 0xbf) -> "UTF-8" to 3
            bytes.startsWith(0xfe, 0xff) -> "UTF-16BE" to 2
            bytes.startsWith(0xff, 0xfe) -> "UTF-16LE" to 2
            else -> null
        }
        val offset = bom?.second ?: 0
        val prefixBytes = bytes.copyOfRange(offset, minOf(bytes.size, offset + 8_192))
        val prefixCharset = bom?.first?.let(Charset::forName) ?: Charsets.ISO_8859_1
        val prefix = decodeBytes(prefixBytes, 0, prefixCharset, CodingErrorAction.REPLACE, endOfInput = false)
        val declared = response.contentType?.toMediaTypeOrNull()

        if (declared == null && bom == null && hasBinarySignature(prefixBytes)) {
            throw WebRequestException("BINARY_CONTENT", "响应包含二进制文件内容，请使用其他合适的工具处理")
        }

        val mime = declared?.let { "${it.type}/${it.subtype}" }?.lowercase(Locale.ROOT)
            ?: if (HTML_PREFIX.containsMatchIn(prefix)) "text/html" else "text/plain"

        if (!isReadableMime(mime)) {
            throw WebRequestException(
                "UNSUPPORTED_CONTENT_TYPE",
                "此网址内容类型（$mime）不是可直接读取的文本网页，请改用浏览器或下载工具",
            )
        }

        val headerCharset = declared?.parameter("charset")
        val htmlCharset = if ((mime == "text/html" || mime == "application/xhtml+xml") &&
            bom == null &&
            headerCharset == null
        ) {
            detectHtmlCharset(prefix)
        } else {
            null
        }

        val charsetName = bom?.first ?: headerCharset ?: htmlCharset ?: "UTF-8"
        val charset = try {
            Charset.forName(charsetName.trim())
        } catch (_: IllegalArgumentException) {
            throw WebRequestException("UNSUPPORTED_CHARSET", "网页声明了当前环境不支持的字符编码: $charsetName")
        }

        var lossy = false
        val text = try {
            decodeBytes(bytes, offset, charset, CodingErrorAction.REPORT, !response.truncated)
        } catch (_: CharacterCodingException) {
            lossy = true
            decodeBytes(bytes, offset, charset, CodingErrorAction.REPLACE, !response.truncated)
        }

        if (text.any(::isBinaryControl)) {
            throw WebRequestException("BINARY_CONTENT", "响应包含二进制控制字符，不能作为网页正文读取")
        }
        return DecodedWebResponse(text, mime, charset.name(), lossy)
    }

    private fun isReadableMime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xhtml+xml", "application/xml") ||
            (mime.startsWith("application/") && (mime.endsWith("+json") || mime.endsWith("+xml")))

    private fun detectHtmlCharset(headHtml: String): String? {
        META_CHARSET.find(headHtml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return it
        }
        for (tag in META_HTTP_EQUIV_TAG.findAll(headHtml)) {
            val attrs = ATTR_REGEX.findAll(tag.value).associate { match ->
                val name = match.groupValues[1].lowercase(Locale.ROOT)
                val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groupValues[4]
                name to value.trim()
            }
            if (attrs["http-equiv"]?.equals("content-type", ignoreCase = true) == true) {
                val charset = attrs["content"]?.toMediaTypeOrNull()?.parameter("charset")
                if (!charset.isNullOrBlank()) return charset
            }
        }
        return null
    }

    private fun decodeBytes(
        bytes: ByteArray,
        offset: Int,
        charset: Charset,
        policy: CodingErrorAction,
        endOfInput: Boolean,
    ): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(policy)
            .onUnmappableCharacter(policy)
        val length = (bytes.size - offset).coerceAtLeast(0)
        val input = ByteBuffer.wrap(bytes, offset, length)
        val capacity = (length * decoder.maxCharsPerByte()).toInt().coerceAtLeast(1) + 2
        val output = CharBuffer.allocate(capacity)
        val result = decoder.decode(input, output, endOfInput)
        if (result.isError) result.throwException()
        if (result.isOverflow) throw WebRequestException("WEB_DECODE_LIMIT", "网页解码超过容量限制")
        if (endOfInput) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
            if (flushed.isOverflow) throw WebRequestException("WEB_DECODE_LIMIT", "网页解码超过容量限制")
        }
        output.flip()
        return output.toString()
    }

    private fun hasBinarySignature(bytes: ByteArray): Boolean =
        bytes.startsWith(0x25, 0x50, 0x44, 0x46, 0x2d) || // PDF
            bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) || // PNG
            bytes.startsWith(0xff, 0xd8, 0xff) || // JPEG
            bytes.startsWith(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) || // GIF87a
            bytes.startsWith(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) || // GIF89a
            bytes.startsWith(0x50, 0x4b, 0x03, 0x04) || // ZIP
            bytes.startsWith(0x50, 0x4b, 0x05, 0x06) ||
            bytes.startsWith(0x50, 0x4b, 0x07, 0x08) ||
            bytes.startsWith(0x1f, 0x8b) || // GZIP
            bytes.any { isBinaryControl((it.toInt() and 0xff).toChar()) }

    private fun isBinaryControl(character: Char): Boolean =
        character.code in 0..8 || character.code == 11 || character.code in 14..26 || character.code in 28..31

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xff) == prefix[it] }
}
