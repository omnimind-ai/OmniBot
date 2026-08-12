package cn.com.omnimind.bot.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.util.BoundedStreamCopy
import cn.com.omnimind.bot.util.ContentSizeLimitExceededException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SharedOpenDraftAttachment(
    val id: String,
    val name: String,
    val path: String,
    val size: Long?,
    val mimeType: String?,
    val isImage: Boolean,
    val promptPath: String? = null,
    val sendToModel: Boolean = true,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "path" to path,
        "size" to size,
        "mimeType" to mimeType,
        "isImage" to isImage,
        "promptPath" to promptPath,
        "sendToModel" to sendToModel,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("path", path)
        put("size", size)
        put("mimeType", mimeType)
        put("isImage", isImage)
        put("promptPath", promptPath)
        put("sendToModel", sendToModel)
    }
}

data class SharedOpenDraft(
    val requestKey: String,
    val text: String?,
    val attachments: List<SharedOpenDraftAttachment>,
    val createdAt: Long,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "requestKey" to requestKey,
        "text" to text,
        "createdAt" to createdAt,
        "attachments" to attachments.map { it.toMap() },
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("requestKey", requestKey)
        put("text", text)
        put("createdAt", createdAt)
        put(
            "attachments",
            JSONArray().apply {
                attachments.forEach { put(it.toJson()) }
            },
        )
    }
}

object SharedOpenDraftStore {
    private const val TAG = "[SharedOpenDraftStore]"
    private const val PREFS_NAME = "shared_open_draft"
    private const val KEY_PENDING_DRAFT = "pending_draft"
    private const val DIR_NAME = "shared_open_drafts"
    private const val WORKSPACE_DIR_NAME = "shared-open"
    private const val FILE_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L
    private const val MAX_ATTACHMENTS = 10
    private const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
    private const val MAX_SHARED_TEXT_CHARS = 100_000

    fun store(
        context: Context,
        text: String?,
        imageUris: List<Uri>,
        mimeTypeHint: String? = null,
    ): SharedOpenDraft? {
        cleanupStaleFiles(context)
        val now = System.currentTimeMillis()
        val normalizedText = text?.trim()?.take(MAX_SHARED_TEXT_CHARS)?.ifEmpty { null }
        val attachments = imageUris.take(MAX_ATTACHMENTS).mapNotNull { uri ->
            copyAttachmentSafely("shared image") {
                copyImageToLocalDraft(context, uri, mimeTypeHint)
            }
        }
        if (normalizedText == null && attachments.isEmpty()) {
            return null
        }

        val draft = SharedOpenDraft(
            requestKey = now.toString(),
            text = normalizedText,
            attachments = attachments,
            createdAt = now,
        )

        prefs(context)
            .edit()
            .putString(KEY_PENDING_DRAFT, draft.toJson().toString())
            .apply()
        return draft
    }

    fun storeWorkspaceDraft(
        context: Context,
        text: String?,
        uris: List<Uri>,
        mimeTypeHint: String? = null,
    ): SharedOpenDraft? {
        cleanupStaleFiles(context)
        val now = System.currentTimeMillis()
        val normalizedText = text?.trim()?.take(MAX_SHARED_TEXT_CHARS)?.ifEmpty { null }
        val attachments = uris.take(MAX_ATTACHMENTS).mapNotNull { uri ->
            copyAttachmentSafely("workspace file") {
                copyUriToWorkspace(context, uri, mimeTypeHint, now)
            }
        }
        if (normalizedText == null && attachments.isEmpty()) {
            return null
        }

        val draft = SharedOpenDraft(
            requestKey = now.toString(),
            text = normalizedText,
            attachments = attachments,
            createdAt = now,
        )

        prefs(context)
            .edit()
            .putString(KEY_PENDING_DRAFT, draft.toJson().toString())
            .apply()
        return draft
    }

    fun storeMixedDraft(
        context: Context,
        text: String?,
        imageUris: List<Uri>,
        workspaceUris: List<Uri>,
        mimeTypeHint: String? = null,
    ): SharedOpenDraft? {
        cleanupStaleFiles(context)
        val now = System.currentTimeMillis()
        val normalizedText = text?.trim()?.take(MAX_SHARED_TEXT_CHARS)?.ifEmpty { null }
        val attachments = buildList {
            imageUris.take(MAX_ATTACHMENTS).mapNotNullTo(this) { uri ->
                copyAttachmentSafely("shared image") {
                    copyImageToLocalDraft(context, uri, mimeTypeHint)
                }
            }
            workspaceUris.take((MAX_ATTACHMENTS - size).coerceAtLeast(0)).mapNotNullTo(this) { uri ->
                copyAttachmentSafely("workspace file") {
                    copyUriToWorkspace(context, uri, mimeTypeHint, now)
                }
            }
        }
        if (normalizedText == null && attachments.isEmpty()) {
            return null
        }

        val draft = SharedOpenDraft(
            requestKey = now.toString(),
            text = normalizedText,
            attachments = attachments,
            createdAt = now,
        )

        prefs(context)
            .edit()
            .putString(KEY_PENDING_DRAFT, draft.toJson().toString())
            .apply()
        return draft
    }

    fun getPending(context: Context): Map<String, Any?>? {
        val raw = prefs(context).getString(KEY_PENDING_DRAFT, null) ?: return null
        return runCatching {
            fromJson(JSONObject(raw)).toMap()
        }.onFailure { error ->
            OmniLog.e(TAG, "Failed to consume shared draft", error)
        }.getOrNull()
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_DRAFT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private inline fun copyAttachmentSafely(
        label: String,
        block: () -> SharedOpenDraftAttachment?,
    ): SharedOpenDraftAttachment? {
        return runCatching(block)
            .onFailure { OmniLog.w(TAG, "Unable to import $label type=${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun copyImageToLocalDraft(
        context: Context,
        uri: Uri,
        mimeTypeHint: String?,
    ): SharedOpenDraftAttachment? {
        if (uri.scheme != "content") return null
        val resolver = context.contentResolver
        val meta = queryMeta(context, uri)
        if ((meta.size ?: 0L) > MAX_ATTACHMENT_BYTES) {
            OmniLog.w(TAG, "Rejected shared image because it exceeds the size limit")
            return null
        }
        val mimeType = resolveMimeType(context, uri, mimeTypeHint)
        val baseName = meta.displayName ?: "shared_image_${System.currentTimeMillis()}"
        val fileName = ensureExtension(sanitizeFileName(baseName), mimeType)
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.e(TAG, "Failed to create shared draft dir: ${dir.absolutePath}")
            return null
        }

        val localId = UUID.randomUUID().toString()
        val target = File(dir, "${localId}_$fileName")
        val size = try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink ->
                    BoundedStreamCopy.copy(source, sink, MAX_ATTACHMENT_BYTES)
                }
            }
        } catch (error: ContentSizeLimitExceededException) {
            OmniLog.w(TAG, "Rejected shared image because it exceeds the size limit")
            runCatching { target.delete() }
            return null
        } catch (error: Exception) {
            OmniLog.e(TAG, "Failed to copy shared image: ${error.javaClass.simpleName}")
            runCatching { target.delete() }
            return null
        }

        return SharedOpenDraftAttachment(
            id = localId,
            name = fileName,
            path = target.absolutePath,
            size = if (size > 0) size else meta.size,
            mimeType = mimeType,
            isImage = true,
        )
    }

    private fun copyUriToWorkspace(
        context: Context,
        uri: Uri,
        mimeTypeHint: String?,
        now: Long,
    ): SharedOpenDraftAttachment? {
        if (uri.scheme != "content") return null
        val resolver = context.contentResolver
        val meta = queryMeta(context, uri)
        if ((meta.size ?: 0L) > MAX_ATTACHMENT_BYTES) {
            OmniLog.w(TAG, "Rejected workspace file because it exceeds the size limit")
            return null
        }
        val mimeType = resolveMimeType(context, uri, mimeTypeHint)
        val baseName = meta.displayName ?: "shared_file_$now"
        val fileName = ensureExtension(sanitizeFileName(baseName), mimeType)
        val workspaceManager = AgentWorkspaceManager(context)
        workspaceManager.ensureRuntimeDirectories()
        val batchName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val dir = File(AgentWorkspaceManager.rootDirectory(context), "$WORKSPACE_DIR_NAME/$batchName")
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.e(TAG, "Failed to create shared workspace dir: ${dir.absolutePath}")
            return null
        }

        val localId = UUID.randomUUID().toString()
        val target = File(dir, "${localId}_$fileName")
        val size = try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { sink ->
                    BoundedStreamCopy.copy(source, sink, MAX_ATTACHMENT_BYTES)
                }
            }
        } catch (error: ContentSizeLimitExceededException) {
            OmniLog.w(TAG, "Rejected workspace file because it exceeds the size limit")
            runCatching { target.delete() }
            return null
        } catch (error: Exception) {
            OmniLog.e(TAG, "Failed to copy shared workspace file: ${error.javaClass.simpleName}")
            runCatching { target.delete() }
            return null
        }

        val resolvedMimeType = mimeType ?: workspaceManager.guessMimeType(target)
        return SharedOpenDraftAttachment(
            id = localId,
            name = fileName,
            path = target.absolutePath,
            size = if (size > 0) size else meta.size,
            mimeType = resolvedMimeType,
            isImage = isImageMimeOrName(resolvedMimeType, fileName),
            promptPath = workspaceManager.shellPathForAndroid(target) ?: target.absolutePath,
            sendToModel = false,
        )
    }

    private fun cleanupStaleFiles(context: Context) {
        val dir = File(context.filesDir, DIR_NAME)
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        files.forEach { file ->
            if (now - file.lastModified() > FILE_RETENTION_MS) {
                runCatching { file.delete() }
            }
        }
    }

    private fun queryMeta(context: Context, uri: Uri): DraftFileMeta {
        var displayName: String? = null
        var size: Long? = null
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    displayName = it.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                    size = it.getLong(sizeIndex)
                }
            }
        }
        return DraftFileMeta(displayName = displayName, size = size)
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifEmpty { "shared_image" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(160)
    }

    private fun ensureExtension(name: String, mimeType: String?): String {
        if (name.contains('.')) return name
        if (mimeType.isNullOrBlank()) return name
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (extension.isNullOrBlank()) name else "$name.$extension"
    }

    private fun resolveMimeType(context: Context, uri: Uri, mimeTypeHint: String?): String? {
        return context.contentResolver.getType(uri)?.takeUnless(::isWildcardMimeType)
            ?: mimeTypeHint?.takeUnless(::isWildcardMimeType)
    }

    private fun isWildcardMimeType(value: String): Boolean {
        val normalized = value.trim()
        return normalized.isEmpty() || normalized == "*/*"
    }

    private fun fromJson(json: JSONObject): SharedOpenDraft {
        val attachmentsJson = json.optJSONArray("attachments") ?: JSONArray()
        val attachments = buildList {
            for (index in 0 until attachmentsJson.length()) {
                val item = attachmentsJson.optJSONObject(index) ?: continue
                add(
                    SharedOpenDraftAttachment(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        path = item.optString("path"),
                        size = item.takeIf { !it.isNull("size") }?.optLong("size"),
                        mimeType = item.optString("mimeType").ifBlank { null },
                        isImage = item.optBoolean("isImage", false),
                        promptPath = item.optString("promptPath").ifBlank { null },
                        sendToModel = item.optBoolean("sendToModel", true),
                    ),
                )
            }
        }
        return SharedOpenDraft(
            requestKey = json.optString("requestKey"),
            text = json.optString("text").ifBlank { null },
            attachments = attachments,
            createdAt = json.optLong("createdAt"),
        )
    }

    private data class DraftFileMeta(
        val displayName: String?,
        val size: Long?,
    )

    private fun isImageMimeOrName(mimeType: String?, fileName: String): Boolean {
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) {
            return true
        }
        val lowerName = fileName.lowercase(Locale.US)
        return lowerName.endsWith(".png") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") ||
            lowerName.endsWith(".bmp") ||
            lowerName.endsWith(".heic") ||
            lowerName.endsWith(".heif")
    }
}
