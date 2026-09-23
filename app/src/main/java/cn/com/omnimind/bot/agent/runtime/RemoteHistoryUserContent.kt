package cn.com.omnimind.bot.agent.runtime

/** Attachment compatibility belongs to history import; Flutter receives ready-to-display fields. */
internal object RemoteHistoryUserContent {
    data class Content(val text: String, val attachments: List<Map<String, Any?>>)
    private val fields = listOf("text", "content", "message", "input", "value", "delta", "summary", "text_elements", "parts", "attachments", "images")
    private fun string(value: Any?) = RemoteHistoryCompatibility.string(value)
    private fun map(value: Any?) = RemoteHistoryCompatibility.map(value)
    private fun first(row: Map<String, Any?>, vararg keys: String): Any? = keys.firstNotNullOfOrNull { row[it] }

    fun extract(value: Any?): Content {
        val text = StringBuilder()
        val attachments = mutableListOf<Map<String, Any?>>()
        fun visit(node: Any?, depth: Int = 0) {
            if (node == null || depth > 32) return
            if (node is String || node is Number || node is Boolean) { text.append(node); return }
            if (node is List<*>) { node.forEach { visit(it, depth + 1) }; return }
            val row = map(node) ?: return
            val type = string(row["type"])?.lowercase()?.replace('-', '_').orEmpty()
            if (type in setOf("text", "input_text", "message_text")) {
                text.append(RemoteHistoryCompatibility.text(first(row, "text", "content", "value", "input")))
                return
            }
            val mime = string(first(row, "mimeType", "mime_type", "mediaType", "media_type"))?.lowercase()
            if (type in setOf("image", "input_image", "image_url", "screenshot") || type.endsWith("_image") ||
                mime?.startsWith("image/") == true || listOf("image", "imageUrl", "image_url", "dataUrl", "data_url").any(row::containsKey)) {
                image(row, attachments.size)?.let(attachments::add)
                return
            }
            for (key in fields) {
                if (key !in row) continue
                val size = text.length
                val count = attachments.size
                visit(row[key], depth + 1)
                if (text.length != size || attachments.size != count) return
            }
            text.append(RemoteHistoryCompatibility.text(row))
        }
        visit(value)
        return Content(text.toString(), attachments)
    }

    private fun source(value: Any?, depth: Int = 0): String? {
        if (depth > 32) return null
        if (value is String) return value.trim().takeIf(String::isNotEmpty)
        val row = map(value) ?: return null
        return listOf("url", "dataUrl", "data_url", "src", "source", "path").firstNotNullOfOrNull { source(row[it], depth + 1) }
    }

    private fun isUrl(value: String) = value.trim().lowercase().let {
        it.startsWith("data:") || it.startsWith("http://") || it.startsWith("https://")
    }

    private fun pathName(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("data:", true)) return null
        val path = raw.substringBefore('?').substringBefore('#').trim().trimEnd('/')
        return path.split('/').lastOrNull(String::isNotEmpty)
    }

    private fun image(row: Map<String, Any?>, index: Int): Map<String, Any?>? {
        val source = listOf("dataUrl", "data_url", "url", "imageUrl", "image_url", "image", "src", "source")
            .firstNotNullOfOrNull { source(row[it]) }
        val path = string(first(row, "path", "filePath", "file_path", "filename", "fileName")) ?: source?.takeUnless(::isUrl)
        val url = source?.takeIf(::isUrl)
        val base64 = string(first(row, "base64", "b64_json"))?.takeUnless { it.startsWith("data:") }
        val explicitMime = string(first(row, "mimeType", "mime_type", "mediaType", "media_type"))?.lowercase()
        val dataMime = url?.takeIf { it.startsWith("data:", true) }?.drop(5)?.substringBefore(',')?.substringBefore(';')
            ?.trim()?.lowercase()?.takeIf { it.startsWith("image/") }
        val extension = (path ?: url).orEmpty().substringBefore('?').substringBefore('#').lowercase().substringAfterLast('.')
        val pathMime = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png", "gif", "webp", "bmp", "heic", "heif" -> "image/$extension"
            else -> null
        }
        val mime = explicitMime?.let { if (it.startsWith("image/")) it else "image/$it" } ?: dataMime ?: pathMime
        val dataUrl = url?.takeIf { it.startsWith("data:") } ?: base64?.let { "data:${mime ?: "image/png"};base64,$it" }
        val effectiveUrl = dataUrl ?: url
        val effectivePath = if (dataUrl == null && url == null) path else null
        if (effectiveUrl.isNullOrEmpty() && effectivePath.isNullOrEmpty()) return null
        val suffix = when (mime) {
            "image/jpeg" -> "jpg"
            "image/gif", "image/webp", "image/bmp", "image/heic", "image/heif" -> mime.removePrefix("image/")
            else -> "png"
        }
        val name = string(first(row, "name", "fileName", "filename")) ?: pathName(effectivePath) ?: pathName(effectiveUrl)
            ?: if (index == 0) "image.$suffix" else "image-${index + 1}.$suffix"
        return linkedMapOf<String, Any?>("id" to "codex-image-$index", "name" to name, "isImage" to true, "sendToModel" to true).apply {
            if (mime != null) put("mimeType", mime)
            if (dataUrl != null) put("dataUrl", dataUrl) else if (effectiveUrl != null) put("url", effectiveUrl)
            if (effectivePath != null) put("path", effectivePath)
        }
    }
}
