package cn.com.omnimind.bot.mcp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.BoundedStreamCopy
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-app file inbox for MCP file transfer.
 */
data class McpFileRecord(
    val id: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val path: String,
    val createdAt: Long,
    @Volatile var downloadToken: String,
    @Volatile var tokenExpiresAt: Long,
)

object McpFileInbox {
    private const val TAG = "[McpFileInbox]"
    private const val MAX_FILES = 20
    private const val MAX_FILE_BYTES = 25L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 100L * 1024L * 1024L
    private const val FILE_TTL_MS = 2 * 60 * 60 * 1000L
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L

    private val lock = Any()
    private val records = ConcurrentHashMap<String, McpFileRecord>()
    private var inboxInitialized = false

    /**
     * Removes files left by a previous process before the inbox can be served.
     * Records are intentionally memory-only, so a process restart invalidates every old file.
     */
    fun initialize(context: Context): Boolean = synchronized(lock) {
        initializeInboxLocked(File(context.applicationContext.filesDir, "mcp_inbox"))
    }

    fun storeFromUri(context: Context, uri: Uri, mimeTypeHint: String? = null): McpFileRecord? {
        if (uri.scheme != "content") {
            OmniLog.w(TAG, "Rejected non-content file share")
            return null
        }
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val meta = runCatching { queryMeta(resolver = resolver, uri = uri) }
            .onFailure { OmniLog.w(TAG, "Unable to read shared file metadata type=${it.javaClass.simpleName}") }
            .getOrElse { return null }
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull() ?: mimeTypeHint
        var fileName = sanitizeFileName(meta.displayName ?: "shared_$now")
        fileName = ensureExtension(fileName, mimeType)

        val dir = File(context.filesDir, "mcp_inbox")
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.e(TAG, "Failed to create inbox dir: ${dir.absolutePath}")
            return null
        }

        return synchronized(lock) {
            if (!initializeInboxLocked(dir)) {
                OmniLog.e(TAG, "Inbox initialization failed closed")
                return@synchronized null
            }
            cleanupLocked()
            val currentBytes = records.values.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            val remainingBytes = (MAX_TOTAL_BYTES - currentBytes).coerceAtLeast(0L)
            val allowedBytes = minOf(MAX_FILE_BYTES, remainingBytes)
            if (allowedBytes <= 0L || (meta.sizeBytes ?: 0L) > allowedBytes) {
                OmniLog.w(TAG, "Rejected shared file because the inbox size limit was reached")
                return@synchronized null
            }

            val fileId = UUID.randomUUID().toString()
            val targetFile = File(dir, "${fileId}_$fileName")
            val sizeBytes = copyUriToFile(context, uri, targetFile, allowedBytes)
                ?: run {
                    if (targetFile.exists()) targetFile.delete()
                    return@synchronized null
                }

            val record = McpFileRecord(
                id = fileId,
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                path = targetFile.absolutePath,
                createdAt = now,
                downloadToken = "",
                tokenExpiresAt = 0L,
            )
            records[record.id] = record
            cleanupLocked()
            OmniLog.i(TAG, "Stored shared file (${record.sizeBytes} bytes)")
            record
        }
    }

    fun latest(): McpFileRecord? = synchronized(lock) {
        cleanupLocked()
        records.values.maxByOrNull { it.createdAt }
    }

    fun list(limit: Int? = null): List<McpFileRecord> = synchronized(lock) {
        cleanupLocked()
        val sorted = records.values.sortedByDescending { it.createdAt }
        if (limit != null && limit > 0) sorted.take(limit) else sorted
    }

    fun getFile(fileId: String): McpFileRecord? = synchronized(lock) {
        cleanupLocked()
        records[fileId]
    }

    fun removeFile(fileId: String): Boolean = synchronized(lock) {
        removeLocked(fileId)
    }

    fun clearAll(): Int = synchronized(lock) {
        val ids = records.keys.toList()
        ids.count { removeLocked(it) }
    }

    fun issueDownloadToken(record: McpFileRecord): McpFileRecord? = synchronized(lock) {
        val current = records[record.id] ?: return@synchronized null
        val now = System.currentTimeMillis()
        if (current.downloadToken.isBlank() || now > current.tokenExpiresAt) {
            current.downloadToken = generateToken()
            current.tokenExpiresAt = now + TOKEN_TTL_MS
        }
        // Return an immutable snapshot so another request cannot mutate credentials
        // while this response is being serialized.
        current.copy()
    }

    fun isTokenValid(record: McpFileRecord, token: String?): Boolean = synchronized(lock) {
        if (token.isNullOrBlank()) return false
        if (token.length > 512 || System.currentTimeMillis() > record.tokenExpiresAt) return false
        MessageDigest.isEqual(
            record.downloadToken.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    private fun cleanupLocked() {
        val now = System.currentTimeMillis()
        val expiredIds = records.values.filter { record ->
            val expired = now - record.createdAt > FILE_TTL_MS
            val missing = !File(record.path).exists()
            expired || missing
        }.map { it.id }

        expiredIds.forEach { removeLocked(it) }

        val overflow = records.size - MAX_FILES
        if (overflow > 0) {
            val oldest = records.values.sortedBy { it.createdAt }.take(overflow)
            oldest.forEach { removeLocked(it.id) }
        }
    }

    private fun initializeInboxLocked(dir: File): Boolean {
        if (inboxInitialized) return true
        if (!dir.exists()) {
            inboxInitialized = true
            return true
        }
        val orphans = dir.listFiles()
        if (orphans == null) {
            OmniLog.e(TAG, "Unable to enumerate stale inbox files")
            return false
        }
        val failedCount = orphans.count { orphan ->
            !runCatching { orphan.delete() }.getOrDefault(false)
        }
        if (failedCount > 0) {
            OmniLog.e(TAG, "Unable to remove $failedCount stale inbox file(s)")
            return false
        }
        inboxInitialized = true
        return true
    }

    private fun removeLocked(fileId: String): Boolean {
        val record = records[fileId] ?: return false
        val file = File(record.path)
        val deleted = !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        if (!deleted) {
            OmniLog.e(TAG, "Unable to remove shared file")
            return false
        }
        records.remove(fileId, record)
        OmniLog.i(TAG, "Removed shared file")
        return true
    }

    private fun copyUriToFile(context: Context, uri: Uri, target: File, maxBytes: Long): Long? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { output ->
                    BoundedStreamCopy.copy(source, output, maxBytes)
                }
            }
        } catch (e: cn.com.omnimind.bot.util.ContentSizeLimitExceededException) {
            OmniLog.w(TAG, "Rejected shared file because it exceeds the size limit")
            null
        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to copy shared file: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "shared_file" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(160)
    }

    private fun ensureExtension(name: String, mimeType: String?): String {
        if (mimeType.isNullOrBlank()) return name
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        return if (!ext.isNullOrBlank()) "$name.$ext" else name
    }

    private fun queryMeta(resolver: android.content.ContentResolver, uri: Uri): FileMeta {
        var displayName: String? = null
        var sizeBytes: Long? = null
        val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    displayName = it.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                    sizeBytes = it.getLong(sizeIndex)
                }
            }
        }
        return FileMeta(displayName, sizeBytes)
    }

    private fun generateToken(): String {
        val random = SecureRandom()
        val buffer = ByteArray(24)
        random.nextBytes(buffer)
        return Base64.encodeToString(buffer, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private data class FileMeta(
        val displayName: String?,
        val sizeBytes: Long?,
    )
}
