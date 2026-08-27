package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * Downloads local models with resume support, progress tracking, and integrity verification.
 * Uses OkHttp for HTTPS streaming with byte-range requests for resumable downloads.
 */
class LocalModelDownloader(
    private val httpClient: OkHttpClient,
    private val onProgress: (modelId: String, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> }
) {
    private companion object {
        const val TAG = "LocalModelDownloader"
        const val BUFFER_SIZE = 8192
    }
    
    /**
     * Download a model file with resume support.
     * Returns true on success, false on failure.
     */
    suspend fun downloadModel(
        sourceUrl: String,
        destinationPath: String,
        modelId: String,
        expectedChecksum: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(destinationPath)
            file.parentFile?.mkdirs()
            
            // Check if partial download exists
            var downloadedBytes = 0L
            if (file.exists()) {
                downloadedBytes = file.length()
            }
            
            // Get total file size
            val totalBytes = getRemoteFileSize(sourceUrl) ?: run {
                OmniLog.w(TAG, "Could not determine remote file size: $sourceUrl")
                return@withContext false
            }
            
            // If already fully downloaded, verify and return
            if (downloadedBytes == totalBytes) {
                OmniLog.d(TAG, "Model already fully downloaded: $modelId")
                val verifyResult = verifyChecksum(destinationPath, expectedChecksum)
                return@withContext verifyResult
            }
            
            // Resume download
            val request = Request.Builder()
                .url(sourceUrl)
                .header("Range", "bytes=$downloadedBytes-")
                .header("User-Agent", "OmniBot/1.0")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                OmniLog.e(TAG, "Download failed with status ${response.code}: $sourceUrl")
                return@withContext false
            }
            
            val body = response.body ?: return@withContext false
            
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(downloadedBytes)
                
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalDownloaded = downloadedBytes
                    
                    while (input.read(buffer).also { bytesRead = it } > 0) {
                        if (!isActive) {
                            OmniLog.d(TAG, "Download cancelled: $modelId")
                            return@withContext false
                        }
                        
                        raf.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead
                        
                        onProgress(modelId, totalDownloaded, totalBytes)
                    }
                }
            }
            
            // Verify checksum if provided
            if (expectedChecksum != null) {
                if (!verifyChecksum(destinationPath, expectedChecksum)) {
                    OmniLog.e(TAG, "Checksum verification failed: $modelId")
                    file.delete()
                    return@withContext false
                }
            }
            
            OmniLog.i(TAG, "Download completed: $modelId ($totalBytes bytes)")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "Download error for $modelId: ${e.message}")
            false
        }
    }
    
    /**
     * Get the size of a remote file without downloading it.
     * Returns null if unable to determine.
     */
    private fun getRemoteFileSize(sourceUrl: String): Long? {
        return try {
            val request = Request.Builder()
                .url(sourceUrl)
                .head()
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()
            } else {
                OmniLog.w(TAG, "Could not get file size (HTTP ${response.code}): $sourceUrl")
                null
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error getting remote file size: ${e.message}")
            null
        }
    }
    
    /**
     * Verify file checksum (SHA256).
     */
    private fun verifyChecksum(filePath: String, expectedChecksum: String?): Boolean {
        if (expectedChecksum == null) {
            return true
        }
        
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return false
            }
            
            val actualChecksum = calculateFileSha256(file)
            if (actualChecksum == expectedChecksum) {
                OmniLog.d(TAG, "Checksum verified: $filePath")
                true
            } else {
                OmniLog.e(TAG, "Checksum mismatch: expected=$expectedChecksum, actual=$actualChecksum")
                false
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error verifying checksum: ${e.message}")
            false
        }
    }
    
    private fun calculateFileSha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } > 0) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Cancel download and cleanup partial file.
     */
    fun cancelDownload(destinationPath: String) {
        try {
            val file = File(destinationPath)
            if (file.exists()) {
                file.delete()
                OmniLog.d(TAG, "Deleted cancelled download: $destinationPath")
            }
        } catch (e: Exception) {
            OmniLog.w(TAG, "Failed to cleanup cancelled download: ${e.message}")
        }
    }
}
