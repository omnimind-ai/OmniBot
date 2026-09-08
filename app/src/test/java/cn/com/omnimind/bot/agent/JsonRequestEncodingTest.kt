package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.encodeRequestToString
import cn.com.omnimind.baselib.llm.toStreamingRequestBody
import cn.com.omnimind.baselib.llm.requestLogJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer

class JsonRequestEncodingTest {
    @Test
    fun `large HTTP body writes bounded chunks and can be sent again without mutation`() {
        val data = "data:image/png;base64," + "abcd".repeat(6_000_000)
        val payload = buildJsonObject { put("image", data) }
        val body = Json.toStreamingRequestBody(payload)
        assertEquals(data.length.toLong() + 12, body.contentLength())
        repeat(2) {
            var written = 0L
            val output = object : Sink {
                override fun write(source: Buffer, byteCount: Long) {
                    assertTrue("HTTP must not buffer the entire image", byteCount <= 16_384)
                    source.skip(byteCount)
                    written += byteCount
                }
                override fun flush() = Unit
                override fun close() = Unit
                override fun timeout() = Timeout.NONE
            }.buffer()
            output.use { body.writeTo(it) }
            assertEquals(data.length.toLong() + 12, written)
        }
        val diagnostic = Json.requestLogJson(payload)
        assertTrue(diagnostic.length < 200)
        assertEquals(data, payload["image"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `segmented encoding preserves JSON escaping unicode and formatting`() {
        val value = buildJsonObject {
            put("text", "中文 😀 \\ \" \n \u0000")
            put("image", "data:image/png;base64,YWJjZA==")
        }
        for (pretty in listOf(false, true)) {
            val json = Json { prettyPrint = pretty }
            assertEquals(json.encodeToString(value), json.encodeRequestToString(value))
            val body = json.toStreamingRequestBody(value)
            val buffer = Buffer()
            body.writeTo(buffer)
            assertEquals(buffer.size, body.contentLength())
            assertEquals(json.encodeToString(value), buffer.readUtf8())
        }
    }

    @Test
    fun `large inline image survives encoding without a growing UTF16 request buffer`() {
        val data = "data:image/png;base64," + "abcd".repeat(6_000_000)
        val encoded = Json.encodeRequestToString(buildJsonObject { put("image", data) })
        assertEquals(data.length + 12, encoded.length)
        assertTrue(encoded.regionMatches(10, data, 0, data.length))
        assertTrue(encoded.endsWith("\"}"))
    }
}
