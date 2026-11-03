package cn.com.omnimind.bot.agent.tool.handlers

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Dns
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public, credential-free lookup. Only a short keyword leaves the device. */
internal class StickerSearchClient(
    private val client: OkHttpClient = publicClient(),
    private val endpoint: HttpUrl = "https://xiaoapi.cn/v1/meme.php".toHttpUrl(),
    private val allowedImage: (HttpUrl) -> Boolean = ::isAllowedStickerImage,
) {
    data class Image(val bytes: ByteArray, val extension: String, val sourceUrl: String)

    suspend fun search(keyword: String, format: String): Image = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank() && keyword.codePointCount(0, keyword.length) <= 24 &&
            keyword.none { it.isISOControl() }) { "Use a short emotion or action keyword (1–24 characters)" }
        require(format in setOf("auto", "static", "gif")) { "Unsupported sticker format" }
        withTimeoutOrNull(20_000) {
            val url = endpoint.newBuilder().addQueryParameter("msg", keyword)
                .addQueryParameter("page", "1").addQueryParameter("num", "12").build()
            val root = Json.parseToJsonElement(fetch(url, MAX_SEARCH_BYTES).toString(Charsets.UTF_8)) as? JsonObject
                ?: throw IOException("Invalid sticker search response")
            if ((root["code"] as? JsonPrimitive)?.intOrNull != 200) {
                // Provider messages may contain arbitrary text; never turn them into instructions.
                throw IOException("Sticker search unavailable")
            }
            val candidates = (root["data"] as? JsonArray).orEmpty().take(40).mapNotNull { item ->
                val data = item as? JsonObject ?: return@mapNotNull null
                val imageUrl = (data["img_url"] as? JsonPrimitive)?.content?.toHttpUrlOrNull()
                    ?.takeIf(allowedImage) ?: return@mapNotNull null
                val size = (data["img_size"] as? JsonPrimitive)?.intOrNull ?: 0
                val width = (data["img_width"] as? JsonPrimitive)?.intOrNull ?: 0
                val height = (data["img_height"] as? JsonPrimitive)?.intOrNull ?: 0
                if (size > MAX_IMAGE_BYTES || width > MAX_DIMENSION || height > MAX_DIMENSION) return@mapNotNull null
                val gif = imageUrl.encodedPath.endsWith(".gif", ignoreCase = true)
                if (format == "gif" && !gif || format == "static" && gif) return@mapNotNull null
                imageUrl
            }.distinct().take(12).shuffled()
            // Bounded alternate candidates, never a repeated search or a logical-turn retry.
            for (candidate in candidates.take(3)) {
                try {
                    val bytes = fetch(candidate, MAX_IMAGE_BYTES)
                    val extension = imageExtension(bytes) ?: continue
                    if (format == "gif" && extension != "gif" || format == "static" && extension == "gif") continue
                    return@withTimeoutOrNull Image(bytes, extension, candidate.toString())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // An expired result need not prevent another result from the same search.
                }
            }
            throw IOException("No usable sticker found")
        } ?: throw IOException("Sticker search timed out")
    }

    private suspend fun fetch(url: HttpUrl, limit: Int): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            // Keep cancellation attached until the bounded body is consumed, not just until headers arrive.
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!it.isSuccessful) throw IOException("Sticker request failed")
                            val body = it.body
                            if (body.contentLength() > limit) throw IOException("Sticker response too large")
                            val source = body.source()
                            source.request(limit.toLong() + 1)
                            if (source.buffer.size > limit) throw IOException("Sticker response too large")
                            source.readByteArray()
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    companion object {
        const val MAX_SEARCH_BYTES = 256 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val MAX_DIMENSION = 2048

        fun isAllowedStickerImage(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.host in setOf("biaoqing.gtimg.com", "tugelepic.mse.sogou.com")

        fun imageExtension(bytes: ByteArray): String? = when {
            bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif"
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> "png"
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }

        private fun publicClient() = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { address ->
                        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                            address.isSiteLocalAddress || address.isMulticastAddress ||
                            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
                    }) throw UnknownHostException("Sticker host must resolve to a public address")
                }
            }).build()
    }
}
