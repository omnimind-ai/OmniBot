package cn.com.omnimind.bot.agent

import android.util.Base64
import android.net.Uri
import cn.com.omnimind.baselib.util.OmniLog
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal const val MAX_AGENT_ATTACHMENT_BYTES = 20L * 1024L * 1024L
internal const val MAX_AGENT_ATTACHMENT_COUNT = 8
internal const val MAX_AGENT_ATTACHMENT_BATCH_BYTES = 64L * 1024L * 1024L

internal class AgentAttachmentPreparationException(message: String) :
    IllegalArgumentException(message)

internal fun readAgentAttachmentBytes(file: File): ByteArray {
    if (!file.exists() || !file.isFile) {
        throw AgentAttachmentPreparationException("附件文件不可读：${file.name}")
    }
    if (file.length() > MAX_AGENT_ATTACHMENT_BYTES) {
        throw AgentAttachmentPreparationException(
            "附件过大，最大支持 ${MAX_AGENT_ATTACHMENT_BYTES / (1024L * 1024L)} MB：${file.name}"
        )
    }
    val output = ByteArrayOutputStream(file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    file.inputStream().use { input ->
        copyAttachmentStream(input, output)
    }
    return output.toByteArray()
}

private fun copyAttachmentStream(input: InputStream, output: java.io.OutputStream): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > MAX_AGENT_ATTACHMENT_BYTES) {
            throw AgentAttachmentPreparationException(
                "附件过大，最大支持 ${MAX_AGENT_ATTACHMENT_BYTES / (1024L * 1024L)} MB"
            )
        }
        output.write(buffer, 0, count)
    }
    return total
}

internal object AgentWorkspaceAttachmentSupport {
    private const val TAG = "AgentWorkspaceAttachment"

    fun prepareAttachmentsForRuntime(
        context: android.content.Context,
        taskId: String,
        rawAttachments: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        if (rawAttachments.isEmpty()) {
            return emptyList()
        }
        if (rawAttachments.size > MAX_AGENT_ATTACHMENT_COUNT) {
            throw AgentAttachmentPreparationException(
                "附件数量过多，最多支持 $MAX_AGENT_ATTACHMENT_COUNT 个"
            )
        }
        val workspaceManager = AgentWorkspaceManager(context)
        workspaceManager.ensureRuntimeDirectories()
        val batchDirectory = createAttachmentBatchDirectory(workspaceManager, taskId)
            ?: throw AgentAttachmentPreparationException("无法创建附件工作区")
        return try {
            var totalBytes = 0L
            val prepared = rawAttachments.map { attachment ->
                val prepared = prepareSingleAttachment(
                    context = context,
                    workspaceManager = workspaceManager,
                    batchDirectory = batchDirectory,
                    rawAttachment = attachment
                )
                totalBytes += preparedAttachmentBytes(prepared)
                if (totalBytes > MAX_AGENT_ATTACHMENT_BATCH_BYTES) {
                    throw AgentAttachmentPreparationException(
                        "附件总大小超过 ${MAX_AGENT_ATTACHMENT_BATCH_BYTES / (1024L * 1024L)} MB"
                    )
                }
                prepared
            }
            if (batchDirectory.listFiles().isNullOrEmpty()) {
                runCatching { batchDirectory.delete() }
            }
            prepared
        } catch (error: Throwable) {
            runCatching { batchDirectory.deleteRecursively() }
            throw error
        }
    }

    private fun prepareSingleAttachment(
        context: android.content.Context,
        workspaceManager: AgentWorkspaceManager,
        batchDirectory: File,
        rawAttachment: Map<String, Any?>
    ): Map<String, Any?> {
        val attachment = LinkedHashMap(rawAttachment)
        val isImage = AgentImageAttachmentSupport.isImageAttachment(attachment)
        attachment["isImage"] = isImage
        val promptPath = attachment["promptPath"]?.toString()?.trim().orEmpty()
        val workspacePath = attachment["workspacePath"]?.toString()?.trim().orEmpty()
        if (promptPath.isNotEmpty() || workspacePath.isNotEmpty()) {
            if (promptPath.isEmpty() && workspacePath.isNotEmpty()) {
                attachment["promptPath"] = workspacePath
            }
            return attachment
        }

        if (!isImage) {
            attachment["sendToModel"] = false
        }

        val localPath = attachment["path"]?.toString()?.trim().orEmpty()
            .ifEmpty {
                when (val rawUrl = attachment["url"]) {
                    is Map<*, *> -> rawUrl["url"]?.toString()?.trim().orEmpty()
                    else -> rawUrl?.toString()?.trim().orEmpty()
                }
            }
        if (localPath.startsWith("http://", ignoreCase = true) ||
            localPath.startsWith("https://", ignoreCase = true)
        ) {
            return attachment
        }

        val sourceUri = localPath.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        if (sourceUri?.scheme.equals("content", ignoreCase = true)) {
            val contentUri = sourceUri ?: return attachment
            return copyContentUriIntoWorkspace(
                context = context,
                workspaceManager = workspaceManager,
                batchDirectory = batchDirectory,
                uri = contentUri,
                attachment = attachment
            ) ?: throw AgentAttachmentPreparationException(
                "无法读取附件，请重新选择后再试：${resolveAttachmentName(attachment, "attachment")}"
            )
        }

        val source = when {
            sourceUri?.scheme.equals("file", ignoreCase = true) ->
                sourceUri?.path?.let(::File)
            else -> localPath.takeIf { it.isNotEmpty() }?.let(::File)
        }
        if (source != null && source.exists() && source.isFile) {
            return copyIntoWorkspace(
                workspaceManager = workspaceManager,
                batchDirectory = batchDirectory,
                source = source,
                attachment = attachment
            ) ?: if (isImage) {
                throw AgentAttachmentPreparationException(
                    "无法读取图片附件，请重新选择后再试：${source.name}"
                )
            } else {
                attachment
            }
        }

        val dataUrl = extractDataUrl(attachment)
        if (dataUrl.isEmpty()) {
            if (isImage && localPath.isNotEmpty()) {
                throw AgentAttachmentPreparationException(
                    "图片附件不存在或已失去访问权限：${resolveAttachmentName(attachment, localPath)}"
                )
            }
            return attachment
        }

        return copyDataUrlIntoWorkspace(
            workspaceManager = workspaceManager,
            batchDirectory = batchDirectory,
            dataUrl = dataUrl,
            attachment = attachment
        ) ?: throw AgentAttachmentPreparationException(
            "无法读取图片附件，请重新选择后再试：${resolveAttachmentName(attachment, "attachment")}"
        )
    }

    private fun copyContentUriIntoWorkspace(
        context: android.content.Context,
        workspaceManager: AgentWorkspaceManager,
        batchDirectory: File,
        uri: Uri,
        attachment: LinkedHashMap<String, Any?>
    ): Map<String, Any?>? {
        val preferredName = ensureExtension(
            resolveAttachmentName(attachment, "attachment"),
            context.contentResolver.getType(uri).orEmpty()
        )
        val target = File(batchDirectory, "${UUID.randomUUID()}_${sanitizeFileName(preferredName)}")
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw AgentAttachmentPreparationException("系统未授予附件读取权限")
            input.use { source ->
                target.outputStream().use { sink ->
                    copyAttachmentStream(source, sink)
                }
            }
            buildPreparedAttachment(
                workspaceManager = workspaceManager,
                target = target,
                attachment = attachment,
                preferredName = preferredName,
                mimeTypeHint = context.contentResolver.getType(uri).orEmpty()
            )
        } catch (error: AgentAttachmentPreparationException) {
            runCatching { target.delete() }
            throw error
        } catch (error: Exception) {
            OmniLog.w(TAG, "Failed to copy content URI attachment: ${error.message}")
            runCatching { target.delete() }
            null
        }
    }

    private fun copyIntoWorkspace(
        workspaceManager: AgentWorkspaceManager,
        batchDirectory: File,
        source: File,
        attachment: LinkedHashMap<String, Any?>
    ): Map<String, Any?>? {
        val preferredName = resolveAttachmentName(attachment, source.name)
        val target = File(batchDirectory, "${UUID.randomUUID()}_${sanitizeFileName(preferredName)}")
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    copyAttachmentStream(input, output)
                }
            }
            buildPreparedAttachment(workspaceManager, target, attachment, preferredName)
        } catch (error: AgentAttachmentPreparationException) {
            runCatching { target.delete() }
            throw error
        } catch (error: Exception) {
            OmniLog.w(
                TAG,
                "Failed to copy attachment into workspace: ${source.absolutePath}: ${error.message}"
            )
            runCatching { target.delete() }
            null
        }
    }

    private fun copyDataUrlIntoWorkspace(
        workspaceManager: AgentWorkspaceManager,
        batchDirectory: File,
        dataUrl: String,
        attachment: LinkedHashMap<String, Any?>
    ): Map<String, Any?>? {
        val decoded = decodeDataUrl(dataUrl) ?: return null
        val preferredName = ensureExtension(
            resolveAttachmentName(
                attachment,
                defaultDataUrlFileName(decoded.mimeType)
            ),
            decoded.mimeType
        )
        val target = File(batchDirectory, "${UUID.randomUUID()}_${sanitizeFileName(preferredName)}")
        return try {
            decoded.bytes.use { source ->
                target.outputStream().use { sink ->
                    copyAttachmentStream(source, sink)
                }
            }
            buildPreparedAttachment(
                workspaceManager = workspaceManager,
                target = target,
                attachment = attachment,
                preferredName = preferredName,
                mimeTypeHint = decoded.mimeType
            )
        } catch (error: AgentAttachmentPreparationException) {
            runCatching { target.delete() }
            throw error
        } catch (error: Exception) {
            OmniLog.w(TAG, "Failed to persist dataUrl attachment: ${error.message}")
            runCatching { target.delete() }
            null
        }
    }

    private fun createAttachmentBatchDirectory(
        workspaceManager: AgentWorkspaceManager,
        taskId: String
    ): File? {
        // Timestamp alone is not an ownership key: two prompts can prepare
        // attachments in the same second. A unique batch keeps rollback of a
        // failed prompt from deleting another prompt's files.
        val batchName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
            "_" + UUID.randomUUID().toString().take(8)
        val dir = File(
            workspaceManager.attachmentsDirectory(),
            "${sanitizeSegment(taskId)}/$batchName"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.w(TAG, "Failed to create workspace attachment dir: ${dir.absolutePath}")
            return null
        }
        return dir
    }

    private fun preparedAttachmentBytes(attachment: Map<String, Any?>): Long {
        val path = attachment["path"]?.toString()?.trim().orEmpty()
        val pathBytes = path.takeIf { it.isNotEmpty() }?.let(::File)
            ?.takeIf { it.isFile }
            ?.length()
        if (pathBytes != null) return pathBytes
        return when (val rawSize = attachment["size"] ?: attachment["sizeBytes"]) {
            is Number -> rawSize.toLong().coerceAtLeast(0L)
            is String -> rawSize.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            else -> 0L
        }
    }

    private fun buildPreparedAttachment(
        workspaceManager: AgentWorkspaceManager,
        target: File,
        attachment: LinkedHashMap<String, Any?>,
        preferredName: String,
        mimeTypeHint: String = ""
    ): Map<String, Any?> {
        val shellPath = workspaceManager.shellPathForAndroid(target) ?: target.absolutePath
        return LinkedHashMap(attachment).apply {
            put("path", target.absolutePath)
            put("promptPath", shellPath)
            put("workspacePath", shellPath)
            if (attachment["size"] == null && attachment["sizeBytes"] == null) {
                put("size", target.length())
            }
            val mimeType = attachment["mimeType"]?.toString()?.trim().orEmpty()
                .ifEmpty { mimeTypeHint }
                .ifEmpty { workspaceManager.guessMimeType(target) }
            if (mimeType.isNotEmpty()) {
                put("mimeType", mimeType)
            }
            if (attachment["name"]?.toString()?.trim().isNullOrEmpty()) {
                put("name", preferredName)
            }
            if (attachment["fileName"]?.toString()?.trim().isNullOrEmpty()) {
                put("fileName", preferredName)
            }
        }
    }

    private fun extractDataUrl(attachment: Map<String, Any?>): String {
        val direct = attachment["dataUrl"]?.toString()?.trim().orEmpty()
        if (direct.startsWith("data:", ignoreCase = true)) {
            return direct
        }
        val path = attachment["path"]?.toString()?.trim().orEmpty()
        if (path.startsWith("data:", ignoreCase = true)) {
            return path
        }
        val url = attachment["url"]
        val nestedUrl = when (url) {
            is Map<*, *> -> url["url"]?.toString()?.trim().orEmpty()
            else -> url?.toString()?.trim().orEmpty()
        }
        return nestedUrl.takeIf { it.startsWith("data:", ignoreCase = true) }.orEmpty()
    }

    private data class DecodedDataUrl(
        val mimeType: String,
        val bytes: InputStream
    )

    private fun decodeDataUrl(dataUrl: String): DecodedDataUrl? {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) {
            return null
        }
        val meta = dataUrl.substring(5, commaIndex)
        val payload = dataUrl.substring(commaIndex + 1).replace(Regex("\\s+"), "")
        val isBase64 = meta.split(';').any { it.equals("base64", ignoreCase = true) }
        if (!isBase64) {
            return null
        }
        val mimeType = meta.substringBefore(';').trim().ifEmpty {
            "application/octet-stream"
        }
        val maxEncodedLength = (MAX_AGENT_ATTACHMENT_BYTES * 4L / 3L) + 4L
        if (payload.length.toLong() > maxEncodedLength) {
            throw AgentAttachmentPreparationException(
                "附件过大，最大支持 ${MAX_AGENT_ATTACHMENT_BYTES / (1024L * 1024L)} MB"
            )
        }
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
            ?: return null
        return DecodedDataUrl(mimeType, ByteArrayInputStream(bytes))
    }

    private fun defaultDataUrlFileName(mimeType: String): String {
        return "attachment.${extensionForMimeType(mimeType)}"
    }

    private fun ensureExtension(fileName: String, mimeType: String): String {
        val normalized = fileName.trim().ifEmpty { defaultDataUrlFileName(mimeType) }
        val base = normalized.substringBeforeLast('/', normalized)
            .substringBeforeLast('\\', normalized)
        if (base.substringAfterLast('.', "").isNotEmpty()) {
            return normalized
        }
        return "$normalized.${extensionForMimeType(mimeType)}"
    }

    private fun extensionForMimeType(mimeType: String): String {
        return when (mimeType.lowercase(Locale.US)) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp" -> "bmp"
            "text/plain" -> "txt"
            "text/markdown" -> "md"
            "application/pdf" -> "pdf"
            else -> "bin"
        }
    }

    private fun resolveAttachmentName(
        attachment: Map<String, Any?>,
        fallback: String
    ): String {
        val name = attachment["name"]?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return name
        }
        val fileName = attachment["fileName"]?.toString()?.trim().orEmpty()
        if (fileName.isNotEmpty()) {
            return fileName
        }
        return fallback
    }

    private fun sanitizeSegment(value: String): String {
        val normalized = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return normalized.ifEmpty { "agent" }
    }

    private fun sanitizeFileName(value: String): String {
        val normalized = value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return normalized.ifEmpty { "attachment" }
    }
}
