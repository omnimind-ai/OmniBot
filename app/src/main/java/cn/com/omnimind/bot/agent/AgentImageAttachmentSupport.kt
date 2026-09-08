package cn.com.omnimind.bot.agent

import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import java.util.Locale

internal object AgentImageAttachmentSupport {
    private const val TAG = "AgentImageAttachmentSupport"
    internal data class PreparedAttachments(
        val runtimeAttachments: List<Map<String, Any?>>,
        val modelAttachments: List<Map<String, Any?>>,
        val historyAttachments: List<Map<String, Any?>>
    )

    internal data class FileReadImageResult(
        val payload: Map<String, Any?>,
        val imageDataUrl: String
    )

    internal interface Backend {
        fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String?

    }

    private object RealBackend : Backend {
        override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String? {
            if (!file.exists() || !file.isFile) {
                return null
            }
            return runCatching {
                val mimeType = normalizeImageMimeType(mimeTypeHint, file.name)
                val encoded = Base64.encodeToString(
                    readAgentAttachmentBytes(file),
                    Base64.NO_WRAP
                )
                "data:$mimeType;base64,$encoded"
            }.onFailure { error ->
                OmniLog.w(TAG, "read image file failed: ${file.absolutePath}: ${error.message}")
            }.getOrNull()
        }

    }

    @Volatile
    internal var backend: Backend = RealBackend

    internal fun resetBackendForTests() {
        backend = RealBackend
    }

    fun prepareAttachments(rawAttachments: List<Map<String, Any?>>): PreparedAttachments {
        if (rawAttachments.isEmpty()) {
            return PreparedAttachments(
                runtimeAttachments = emptyList(),
                modelAttachments = emptyList(),
                historyAttachments = emptyList()
            )
        }
        val runtimeAttachments = mutableListOf<Map<String, Any?>>()
        val modelAttachments = mutableListOf<Map<String, Any?>>()
        val historyAttachments = mutableListOf<Map<String, Any?>>()
        rawAttachments.forEach { raw ->
            val prepared = prepareSingleAttachment(raw) ?: return@forEach
            val shouldSendToModel = shouldSendAttachmentToModel(raw)
            val isImage = prepared.second["isImage"] == true
            if (shouldSendToModel && isImage) {
                modelAttachments += prepared.first
            }
            runtimeAttachments += if (shouldSendToModel && isImage) {
                prepared.first
            } else {
                prepared.second
            }
            historyAttachments += prepared.second
        }
        return PreparedAttachments(
            runtimeAttachments = runtimeAttachments,
            modelAttachments = modelAttachments,
            historyAttachments = historyAttachments
        )
    }

    internal fun isImageAttachment(attachment: Map<String, Any?>): Boolean {
        val localPath = localPathFromAttachment(attachment)
        val remoteUrl = remoteUrlFromAttachment(attachment)
        val dataUrl = dataUrlFromAttachment(attachment)
        val mimeType = mimeTypeFromAttachment(attachment)
        return detectImageAttachment(
            attachment = attachment,
            mimeType = mimeType,
            localPath = localPath,
            remoteUrl = remoteUrl,
            dataUrl = dataUrl
        )
    }

    fun resolveImageAttachmentUrl(attachment: Map<String, Any?>): String {
        localPathFromAttachment(attachment)?.let { path ->
            val file = File(path)
            val dataUrl = backend.readFileAsDataUrl(file, mimeTypeFromAttachment(attachment))
            if (!dataUrl.isNullOrBlank()) {
                return normalizeImageDataUrl(dataUrl)
            }
        }

        val dataUrl = dataUrlFromAttachment(attachment)
        if (dataUrl.isNotBlank()) {
            return normalizeImageDataUrl(dataUrl)
        }

        val remoteUrl = remoteUrlFromAttachment(attachment)
        if (remoteUrl.isNotBlank()) {
            return remoteUrl
        }
        return ""
    }

    fun buildFileReadImageResult(
        file: File,
        shellPath: String,
        mimeTypeHint: String,
        uri: String,
        sizeBytes: Long
    ): FileReadImageResult? {
        val dataUrl = backend.readFileAsDataUrl(file, mimeTypeHint) ?: return null
        val payload = linkedMapOf<String, Any?>(
            "path" to shellPath,
            "androidPath" to file.absolutePath,
            "uri" to uri,
            "size" to sizeBytes,
            "mimeType" to normalizeImageMimeType(mimeTypeHint, file.name),
            "kind" to "image"
        )
        return FileReadImageResult(
            payload = payload,
            imageDataUrl = normalizeImageDataUrl(dataUrl)
        )
    }

    private fun prepareSingleAttachment(
        raw: Map<String, Any?>
    ): Pair<Map<String, Any?>, Map<String, Any?>>? {
        val localPath = localPathFromAttachment(raw)
        val remoteUrl = remoteUrlFromAttachment(raw)
        val dataUrl = dataUrlFromAttachment(raw)
        val mimeType = mimeTypeFromAttachment(raw)
        val isImage = detectImageAttachment(
            attachment = raw,
            mimeType = mimeType,
            localPath = localPath,
            remoteUrl = remoteUrl,
            dataUrl = dataUrl
        )

        val base = linkedMapOf<String, Any?>()
        copyIfNotBlank(base, "id", raw["id"]?.toString())
        val normalizedName = attachmentName(raw, localPath)
        copyIfNotBlank(base, "name", normalizedName)
        copyIfNotBlank(base, "fileName", raw["fileName"]?.toString() ?: normalizedName)
        normalizedSize(raw["size"] ?: raw["sizeBytes"])?.let { base["size"] = it }
        if (mimeType.isNotBlank()) {
            base["mimeType"] = mimeType
        }
        base["isImage"] = isImage
        if (!localPath.isNullOrBlank()) {
            base["path"] = localPath
        }
        if (!remoteUrl.isNullOrBlank()) {
            base["url"] = remoteUrl
        }
        copyIfNotBlank(base, "promptPath", raw["promptPath"]?.toString())
        copyIfNotBlank(base, "workspacePath", raw["workspacePath"]?.toString())
        if (!shouldSendAttachmentToModel(raw)) {
            base["sendToModel"] = false
        }

        if (!isImage) {
            return base to base
        }

        val sourceDataUrl = when {
            !localPath.isNullOrBlank() -> {
                backend.readFileAsDataUrl(File(localPath), mimeType.takeIf { it.isNotBlank() })
                    ?: dataUrl.takeIf { it.isNotBlank() }
            }
            dataUrl.isNotBlank() -> dataUrl
            else -> null
        }

        if (!sourceDataUrl.isNullOrBlank()) {
            val modelAttachment = LinkedHashMap(base)
            modelAttachment["dataUrl"] = normalizeImageDataUrl(sourceDataUrl)
            return modelAttachment to LinkedHashMap(modelAttachment)
        }

        return base to base
    }

    private fun shouldSendAttachmentToModel(attachment: Map<String, Any?>): Boolean {
        return when (val raw = attachment["sendToModel"]) {
            is Boolean -> raw
            is String -> !raw.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun localPathFromAttachment(attachment: Map<String, Any?>): String? {
        val raw = attachment["path"]?.toString()?.trim().orEmpty()
        return raw.takeIf { it.isNotEmpty() && !it.startsWith("http://") && !it.startsWith("https://") }
    }

    private fun remoteUrlFromAttachment(attachment: Map<String, Any?>): String {
        val raw = extractUrlCandidate(attachment)
        return if (
            raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            raw
        } else {
            ""
        }
    }

    private fun dataUrlFromAttachment(attachment: Map<String, Any?>): String {
        val explicitDataUrl = attachment["dataUrl"]?.toString()?.trim().orEmpty()
        if (explicitDataUrl.startsWith("data:", ignoreCase = true)) {
            return normalizeImageDataUrl(explicitDataUrl)
        }
        val urlCandidate = extractUrlCandidate(attachment)
        return if (urlCandidate.startsWith("data:", ignoreCase = true)) {
            normalizeImageDataUrl(urlCandidate)
        } else {
            ""
        }
    }

    private fun extractUrlCandidate(attachment: Map<String, Any?>): String {
        val direct = sequenceOf(
            attachment["url"],
            attachment["imageUrl"],
            attachment["image_url"]
        ).mapNotNull { value ->
            when (value) {
                is Map<*, *> -> value["url"]?.toString()?.trim()
                else -> value?.toString()?.trim()
            }
        }.firstOrNull { it.isNotBlank() }
        return direct.orEmpty()
    }

    private fun mimeTypeFromAttachment(attachment: Map<String, Any?>): String {
        val explicit = attachment["mimeType"]?.toString()?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            return explicit
        }
        val dataUrl = dataUrlFromAttachment(attachment)
        if (dataUrl.startsWith("data:", ignoreCase = true)) {
            return extractMimeType(dataUrl)
        }
        val path = localPathFromAttachment(attachment)
        val url = remoteUrlFromAttachment(attachment)
        return inferMimeTypeFromPath(path ?: url)
    }

    private fun attachmentName(
        attachment: Map<String, Any?>,
        localPath: String?
    ): String {
        val name = attachment["name"]?.toString()?.trim().orEmpty()
        if (name.isNotBlank()) {
            return name
        }
        val fileName = attachment["fileName"]?.toString()?.trim().orEmpty()
        if (fileName.isNotBlank()) {
            return fileName
        }
        val path = localPath.orEmpty()
        if (path.isBlank()) {
            return ""
        }
        return path.replace('\\', '/').substringAfterLast('/')
    }

    private fun normalizedSize(rawSize: Any?): Long? {
        return when (rawSize) {
            is Number -> rawSize.toLong()
            is String -> rawSize.trim().toLongOrNull()
            else -> null
        }?.takeIf { it >= 0L }
    }

    private fun detectImageAttachment(
        attachment: Map<String, Any?>,
        mimeType: String,
        localPath: String?,
        remoteUrl: String,
        dataUrl: String
    ): Boolean {
        val explicit = when (val rawFlag = attachment["isImage"]) {
            is Boolean -> rawFlag
            is String -> rawFlag.equals("true", ignoreCase = true)
            else -> false
        }
        if (explicit) {
            return true
        }
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        if (dataUrl.startsWith("data:image/", ignoreCase = true)) {
            return true
        }
        return looksLikeImagePath(localPath) || looksLikeImagePath(remoteUrl)
    }

    private fun looksLikeImagePath(value: String?): Boolean {
        val normalized = value?.trim().orEmpty().lowercase(Locale.US).split('?').firstOrNull().orEmpty()
        return normalized.endsWith(".png") ||
            normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".webp") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".bmp") ||
            normalized.endsWith(".heic") ||
            normalized.endsWith(".heif")
    }

    private fun copyIfNotBlank(
        target: MutableMap<String, Any?>,
        key: String,
        value: String?
    ) {
        val normalized = value?.trim().orEmpty()
        if (normalized.isNotEmpty()) {
            target[key] = normalized
        }
    }

    private fun normalizeImageMimeType(mimeTypeHint: String?, pathHint: String): String {
        val normalizedHint = mimeTypeHint?.trim().orEmpty()
        if (normalizedHint.startsWith("image/", ignoreCase = true)) {
            return normalizedHint
        }
        val inferred = inferMimeTypeFromPath(pathHint)
        if (inferred.isNotBlank()) {
            return inferred
        }
        return "image/jpeg"
    }

    private fun inferMimeTypeFromPath(pathHint: String): String {
        val lower = pathHint.lowercase(Locale.US)
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".bmp") -> "image/bmp"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".heif") -> "image/heif"
            else -> ""
        }
    }

    private fun extractMimeType(dataUrl: String): String {
        val header = dataUrl.substringBefore(',', "")
        if (!header.startsWith("data:", ignoreCase = true)) {
            return "image/jpeg"
        }
        val mimeType = header.removePrefix("data:").substringBefore(';').trim()
        return if (mimeType.isBlank()) "image/jpeg" else mimeType
    }

    private fun normalizeImageDataUrl(dataUrl: String): String {
        val trimmed = dataUrl.trim()
        val separatorIndex = trimmed.indexOf(',')
        if (separatorIndex <= 0) {
            return trimmed
        }
        val header = trimmed.substring(0, separatorIndex)
        if (!header.startsWith("data:", ignoreCase = true) || !header.contains(";base64", ignoreCase = true)) {
            return trimmed
        }
        val payload = trimmed.substring(separatorIndex + 1).filterNot(Char::isWhitespace)
        return "$header,$payload"
    }
}
