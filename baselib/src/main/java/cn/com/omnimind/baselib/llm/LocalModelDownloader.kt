package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Secure streaming downloader for the curated local-model catalog.
 * Partial downloads use .part files and are atomically renamed after verification.
 */
class LocalModelDownloader(
    private val httpClient: OkHttpClient,
    private val onProgress: (modelId: String, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
) {
    private companion object {
        const val TAG = "LocalModelDownloader"
        const val BUFFER_SIZE = 1024 * 1024
    }

    suspend fun downloadCatalogModel(
        entry: LocalModelCatalogEntry,
        destinationPath: String,
    ): Boolean = downloadModel(
        sourceUrl = entry.downloadUrl,
        destinationPath = destinationPath,
        modelId = entry.id,
        expectedChecksum = entry.sha256,
        expectedSize = entry.sizeBytes,
    )

    suspend fun downloadModel(
        sourceUrl: String,
        destinationPath: String,
        modelId: String,
        expectedChecksum: String? = null,
        expectedSize: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isApprovedCatalogUrl(modelId, sourceUrl)) {
            OmniLog.e(TAG, "Rejected non-catalog model URL: $sourceUrl")
            return@withContext false
        }
        if (!sourceUrl.startsWith("https://", ignoreCase = true)) {
            OmniLog.e(TAG, "Rejected non-HTTPS model URL")
            return@withContext false
        }
        if (expectedChecksum.isNullOrBlank()) {
            OmniLog.e(TAG, "Rejected model without SHA-256 checksum: $modelId")
            return@withContext false
        }

        val target = File(destinationPath)
        val part = File("$destinationPath.part")
        target.parentFile?.mkdirs()

        try {
            if (target.exists()) {
                val validSize = expectedSize == null || target.length() == expectedSize
                if (validSize && verifyChecksum(target, expectedChecksum)) {
                    onProgress(modelId, target.length(), expectedSize ?: target.length())
                    return@withContext true
                }
                target.delete()
            }

            val existing = part.length()
            if (expectedSize != null && existing > expectedSize) {
                part.delete()
            }

            val start = part.length()
            val requestBuilder = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", "OmniBot/1.0")
                .header("Accept", "application/octet-stream")
            if (start > 0L) {
                requestBuilder.header("Range", "bytes=$start-")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    OmniLog.e(TAG, "Model download failed: HTTP ${response.code}")
                    return@withContext false
                }
                val body = response.body ?: return@withContext false
                val append = start > 0L && response.code == 206
                val initialBytes = if (append) start else 0L
                if (!append && start > 0L) {
                    part.delete()
                }

                val responseLength = body.contentLength().takeIf { it >= 0L }
                val totalBytes = expectedSize ?: if (append && responseLength != null) {
                    initialBytes + responseLength
                } else {
                    responseLength ?: -1L
                }
                if (expectedSize != null && totalBytes > 0L && totalBytes != expectedSize) {
                    OmniLog.e(TAG, "Unexpected model size: expected=$expectedSize actual=$totalBytes")
                    return@withContext false
                }

                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(if (append) initialBytes else 0L)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = if (append) initialBytes else 0L
                        while (true) {
                            if (!isActive) {
                                // Preserve .part so a later run can resume.
                                return@withContext false
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            downloaded += read
                            onProgress(modelId, downloaded, totalBytes)
                        }
                    }
                }
            }

            val finalSize = part.length()
            if (expectedSize != null && finalSize != expectedSize) {
                OmniLog.e(TAG, "Incomplete model: expected=$expectedSize actual=$finalSize")
                return@withContext false
            }
            if (!verifyChecksum(part, expectedChecksum)) {
                OmniLog.e(TAG, "Model checksum mismatch: $modelId")
                part.delete()
                return@withContext false
            }

            if (target.exists() && !target.delete()) {
                OmniLog.e(TAG, "Unable to replace existing model: $destinationPath")
                return@withContext false
            }
            if (!part.renameTo(target)) {
                OmniLog.e(TAG, "Atomic model rename failed: $destinationPath")
                return@withContext false
            }
            onProgress(modelId, target.length(), expectedSize ?: target.length())
            OmniLog.i(TAG, "Model installed: $modelId")
            true
        } catch (e: Exception) {
            OmniLog.e(TAG, "Model download error for $modelId: ${e.message}")
            false
        }
    }

    fun cancelDownload(destinationPath: String) {
        // Explicit cancellation is destructive; pause/coroutine cancellation preserves .part.
        File("$destinationPath.part").delete()
        File(destinationPath).delete()
    }

    private fun isApprovedCatalogUrl(modelId: String, url: String): Boolean {
        return LocalModelCatalog.find(modelId)?.downloadUrl == url
    }

    private fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        if (!file.exists() || !file.isFile) return false
        val actual = calculateFileSha256(file)
        return actual.equals(expectedChecksum.trim(), ignoreCase = true)
    }

    private fun calculateFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
