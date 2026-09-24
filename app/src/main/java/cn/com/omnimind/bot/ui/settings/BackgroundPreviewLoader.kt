package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/** A downsampled image for the settings preview. The caller owns [bitmap] and should recycle it. */
internal data class PreviewImage(val bitmap: Bitmap, val sampledLuminance: Double)

/**
 * Loads only when the selected image source changes. Adjustment sliders should reuse the returned
 * bitmap and luminance instead of issuing another file read or network request.
 */
internal class BackgroundPreviewLoader(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun load(sourceType: String, localPath: String, remoteUrl: String): PreviewImage? =
        withContext(Dispatchers.IO) {
            try {
                val openStream: (() -> InputStream?) = when (sourceType) {
                    "local" -> localStream(localPath.trim()) ?: return@withContext null
                    "remote" -> {
                        val bytes = fetchRemote(remoteUrl.trim()) ?: return@withContext null
                        val source: () -> InputStream? = { ByteArrayInputStream(bytes) }
                        source
                    }
                    else -> return@withContext null
                }
                val bitmap = decodePreview(openStream) ?: return@withContext null
                try {
                    PreviewImage(bitmap, sampleLuminance(bitmap))
                } catch (error: Exception) {
                    bitmap.recycle()
                    throw error
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    private fun localStream(path: String): (() -> InputStream?)? {
        if (path.isEmpty()) return null
        val uri = Uri.parse(path)
        return when (uri.scheme) {
            "content" -> { { resolver.openInputStream(uri) } }
            null, "file" -> {
                val file = if (uri.scheme == "file") File(uri.path ?: return null) else File(path)
                if (!file.isFile) return null
                val source: () -> InputStream? = { file.inputStream() }
                source
            }
            else -> null
        }
    }

    private fun decodePreview(openStream: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openStream() ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (longestSide / sampleSize > PREVIEW_MAX_SIDE && sampleSize <= Int.MAX_VALUE / 2) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun fetchRemote(rawUrl: String): ByteArray? {
        var url = validatedHttpUrl(rawUrl) ?: return null
        val deadline = SystemClock.elapsedRealtime() + REMOTE_DEADLINE_MS
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null

            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = min(CONNECT_TIMEOUT_MS.toLong(), remaining).toInt().coerceAtLeast(1)
                readTimeout = min(READ_TIMEOUT_MS.toLong(), remaining).toInt().coerceAtLeast(1)
                setRequestProperty("Accept", "image/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                when (connection.responseCode) {
                    in 200..299 -> {
                        if (connection.contentLengthLong > MAX_REMOTE_BYTES) return null
                        val contentType = connection.contentType?.substringBefore(';')?.trim()
                        if (contentType != null &&
                            !contentType.startsWith("image/", ignoreCase = true) &&
                            !contentType.equals("application/octet-stream", ignoreCase = true)
                        ) return null
                        return connection.inputStream.use { input ->
                            readBounded(input, connection, deadline)
                        }
                    }
                    in 300..399 -> {
                        if (redirectCount == MAX_REDIRECTS) return null
                        val location = connection.getHeaderField("Location") ?: return null
                        url = validatedHttpUrl(URL(url, location).toExternalForm()) ?: return null
                    }
                    else -> return null
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun readBounded(
        input: InputStream,
        connection: HttpURLConnection,
        deadline: Long,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            connection.readTimeout = min(READ_TIMEOUT_MS.toLong(), remaining).toInt().coerceAtLeast(1)
            val count = input.read(chunk)
            if (count < 0) return output.toByteArray()
            total += count
            if (total > MAX_REMOTE_BYTES) return null
            output.write(chunk, 0, count)
        }
    }

    private fun validatedHttpUrl(value: String): URL? {
        val url = runCatching { URL(value) }.getOrNull() ?: return null
        if (url.protocol != "http" && url.protocol != "https") return null
        if (url.host.isNullOrBlank()) return null
        return url
    }

    private fun sampleLuminance(bitmap: Bitmap): Double {
        val sample = Bitmap.createScaledBitmap(bitmap, LUMINANCE_SIDE, LUMINANCE_SIDE, true)
        try {
            val pixels = IntArray(LUMINANCE_SIDE * LUMINANCE_SIDE)
            sample.getPixels(pixels, 0, LUMINANCE_SIDE, 0, 0, LUMINANCE_SIDE, LUMINANCE_SIDE)
            var weightedSum = 0.0
            var alphaSum = 0.0
            for (pixel in pixels) {
                val alpha = (pixel ushr 24 and 0xff) / 255.0
                val red = (pixel ushr 16 and 0xff) / 255.0
                val green = (pixel ushr 8 and 0xff) / 255.0
                val blue = (pixel and 0xff) / 255.0
                weightedSum += (0.2126 * red + 0.7152 * green + 0.0722 * blue) * alpha
                alphaSum += alpha
            }
            return if (alphaSum > 0.0) (weightedSum / alphaSum).coerceIn(0.0, 1.0) else 0.72
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    private companion object {
        const val PREVIEW_MAX_SIDE = 1024
        const val LUMINANCE_SIDE = 24
        const val MAX_REMOTE_BYTES = 12 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val REMOTE_DEADLINE_MS = 12_000L
        const val CONNECT_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 4_000L
    }
}
