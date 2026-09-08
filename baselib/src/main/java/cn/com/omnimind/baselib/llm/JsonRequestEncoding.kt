package cn.com.omnimind.baselib.llm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import okio.Buffer
import okio.BufferedSink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import java.io.OutputStream

/**
 * Encode through segmented UTF-8 storage. Json.encodeToString grows a contiguous
 * UTF-16 array, which can transiently require several times the size of inline
 * images and exhaust Android's heap before the HTTP request is even submitted.
 * Compatibility callers still receive exactly the same JSON string.
 */
@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Json.encodeRequestToString(value: T): String = Buffer().use { buffer ->
    encodeToStream(serializersModule.serializer<T>(), value, buffer.outputStream())
    buffer.readUtf8()
}

/** Serialize only as OkHttp sends the body, without retaining a second image-sized request. */
@OptIn(ExperimentalSerializationApi::class)
fun Json.toStreamingRequestBody(value: JsonElement): RequestBody {
    val json = this
    return object : RequestBody() {
        // Keep Content-Length for providers that reject chunked uploads.
        // Count encoded bytes without allocating an image-sized byte array.
        private val encodedLength by lazy {
            var count = 0L
            val counter = object : OutputStream() {
                override fun write(value: Int) { count++ }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { count += length }
            }
            json.encodeToStream(JsonElement.serializer(), value, counter)
            count
        }
        override fun contentType() = "application/json; charset=utf-8".toMediaType()
        override fun contentLength() = encodedLength
        override fun writeTo(sink: BufferedSink) {
            json.encodeToStream(JsonElement.serializer(), value, sink.outputStream())
        }
    }
}

/** Diagnostics retain request structure, not another copy of inline binary attachments. */
fun Json.requestLogJson(value: JsonElement): String {
    fun withoutImageBytes(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, child) ->
            if (key == "data" && (element["type"] as? JsonPrimitive)?.contentOrNull == "base64") {
                JsonPrimitive("[inline binary omitted]")
            } else withoutImageBytes(child)
        })
        is JsonArray -> JsonArray(element.map(::withoutImageBytes))
        is JsonPrimitive -> if (element.isString && element.content.startsWith("data:image/")) {
            JsonPrimitive(element.content.substringBefore(',') + ",[inline binary omitted]")
        } else element
    }
    return encodeRequestToString(withoutImageBytes(value))
}
