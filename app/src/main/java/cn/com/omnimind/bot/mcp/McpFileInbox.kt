package cn.com.omnimind.bot.mcp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
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
    private const val TOKEN_TTL_MS = 15 * 60 * 1000L

    private val lock = Any()
    private val records = ConcurrentHashMap<String, McpFileRecord>()

    fun storeFromUri(context: Context, uri: Uri, mimeTypeHint: String? = null): McpFileRecord? {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val meta = queryMeta(resolver = resolver, uri = uri)
        val mimeType = resolver.getType(uri) ?: mimeTypeHint
        var fileName = sanitizeFileName(meta.displayName ?: "shared_$now")
        fileName = ensureExtension(fileName, mimeType)

        val dir = File(context.filesDir, "mcp_inbox")
        if (!dir.exists() && !dir.mkdirs()) {
            OmniLog.e(TAG, "Failed to create inbox dir: ${dir.absolutePath}")
            return null
        }

        val fileId = UUID.randomUUID().toString()
        val targetFile = File(dir, "${fileId}_$fileName")
        val sizeBytes = copyUriToFile(context, uri, targetFile)
            ?: run {
                if (targetFile.exists()) targetFile.delete()
                return null
            }

        val record = McpFileRecord(
            id = fileId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = if (sizeBytes > 0) sizeBytes else meta.sizeBytes ?: 0L,
            path = targetFile.absolutePath,
            createdAt = now,
            downloadToken = "",
            tokenExpiresAt = 0L,
        )

        synchronized(lock) {
            records[record.id] = record
            cleanupLocked()
        }

        OmniLog.i(TAG, "Stored file ${record.fileName} (${record.sizeBytes} bytes) as ${record.id}")
        return record
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
        ids.forEach { removeLocked(it) }
        ids.size
    }

    fun issueDownloadToken(record: McpFileRecord): McpFileRecord {
        val now = System.currentTimeMillis()
        record.downloadToken = generateToken()
        record.tokenExpiresAt = now + TOKEN_TTL_MS
        return record
    }

    fun isTokenValid(record: McpFileRecord, token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        if (token != record.downloadToken) return false
        return System.currentTimeMillis() <= record.tokenExpiresAt
    }

    private fun cleanupLocked() {
        val missingIds = records.values.filter { record ->
            !File(record.path).exists()
        }.map { it.id }
        missingIds.forEach { removeLocked(it) }
    }

    private fun removeLocked(fileId: String): Boolean {
        val record = records.remove(fileId) ?: return false
        runCatching { File(record.path).delete() }
        OmniLog.i(TAG, "Removed file ${record.fileName} (${record.id})")
        return true
    }

    private fun copyUriToFile(context: Context, uri: Uri, target: File): Long? {
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                target.outputStream().use { output ->
                    source.copyTo(output)
                }
            }
            target.length()
        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to copy uri to file: ${e.message}")
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().ifBlank { "shared_file" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
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
                if (sizeIndex >= 0) {
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
