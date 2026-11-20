package cn.com.omnimind.bot.voice

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekSpeechProviderTest {
    @Test
    fun `recognition sends complete multipart WAV only after frames finish`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"text":"  你好  ","duration_ms":5}"""))
            val framesRead = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val pcm = byteArrayOf(1, 2, 3, 4)
            val recognizer: SpeechRecognitionProvider = provider(server)
            val transcript = async {
                recognizer.transcribe(flow {
                    emit(pcm)
                    framesRead.complete(Unit)
                    finish.await()
                })
            }
            framesRead.await()
            assertEquals(0, server.requestCount)
            finish.complete(Unit)
            assertEquals("你好", transcript.await())

            val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/v1/tools/asr", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
            val body = request.body.readByteArray()
            val multipart = body.toString(Charsets.ISO_8859_1)
            assertTrue(multipart.contains("name=\"file\"; filename=\"speech.wav\""))
            val wav = ByteBuffer.wrap(body, multipart.indexOf("RIFF"), 44 + pcm.size)
                .slice().order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(16_000, wav.getInt(24))
            assertEquals(1, wav.getShort(22).toInt())
            assertEquals(16, wav.getShort(34).toInt())
            assertEquals(pcm.size, wav.getInt(40))
            assertArrayEquals(pcm, ByteArray(pcm.size).also { wav.position(44); wav.get(it) })
        }
    }

    @Test
    fun `cancelled recording does not upload`() = runBlocking {
        MockWebServer().use { server ->
            val framesRead = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val transcript = async {
                provider(server).transcribe(flow {
                    emit(byteArrayOf(1, 2))
                    framesRead.complete(Unit)
                    finish.await()
                })
            }
            framesRead.await()
            transcript.cancelAndJoin()
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `synthesis sends requested voice and publishes complete aligned PCM`() = runBlocking {
        MockWebServer().use { server ->
            val pcm = ByteArray(50) { it.toByte() }
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/octet-stream")
                    .setChunkedBody(Buffer().write(pcm), 3),
            )
            val synthesizer: SpeechSynthesisProvider = provider(server)
            val chunks = synthesizer.synthesize("你好", "tide").toList()
            assertTrue(chunks.all { it.size % 2 == 0 })
            assertArrayEquals(pcm, chunks.fold(byteArrayOf(), ByteArray::plus))

            val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/v1/tools/tts", request.path)
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("你好", json["text"].asString)
            assertEquals("tide", json["voice_id"].asString)
            assertEquals("pcm", json["format"].asString)
            assertTrue(json["filter_markdown"].asBoolean)
            assertFalse(json.has("model"))
        }
    }

    @Test
    fun `synthesis splits by billing weight without breaking unicode characters`() {
        val han = "𠀀"
        val text = han.repeat(5000) + "🌊"
        assertEquals(listOf(han.repeat(5000), "🌊"), splitDeepSeekSpeechText(text))
        assertEquals(listOf("a".repeat(8000) + "。", "b".repeat(2001)),
            splitDeepSeekSpeechText("a".repeat(8000) + "。" + "b".repeat(2001)))
    }

    @Test
    fun `missing credentials and rate limit surface without retry`() = runBlocking {
        MockWebServer().use { server ->
            val noKey = provider(server) { null }
            assertEquals("configuration", failureCode { noKey.transcribe(flowOf(byteArrayOf(1, 2))) })
            assertEquals(0, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals("rate_limit", failureCode { provider(server).synthesize("你好", "stella").toList() })
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `truncated synthesis surfaces an error after audio without restarting`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(ByteArray(256 * 1024)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            )
            val firstAudio = CountDownLatch(1)
            val listener = object : EventListener() {
                override fun responseFailed(call: Call, ioe: IOException) {
                    // Let the collector receive audio before the upstream failure cancels the flow.
                    assertTrue("No audio collected before the disconnect", firstAudio.await(5, TimeUnit.SECONDS))
                }
            }
            val failure = failureCode {
                provider(server, listener).synthesize("你好", "stella").collect { audio ->
                    if (audio.isNotEmpty()) firstAudio.countDown()
                }
            }
            assertEquals("network", failure)
            assertEquals(0L, firstAudio.count)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cancelling synthesis closes a response that is still streaming`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(Buffer().write(ByteArray(32 * 1024)))
                    .throttleBody(8192, 1, TimeUnit.SECONDS),
            )
            val firstFrame = CompletableDeferred<Unit>()
            val playback = async {
                provider(server).synthesize("你好", "stella").collect {
                    firstFrame.complete(Unit)
                }
            }
            withTimeout(2500) {
                firstFrame.await()
                playback.cancelAndJoin()
            }
            assertTrue(playback.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }

    private fun provider(
        server: MockWebServer,
        eventListener: EventListener = EventListener.NONE,
        key: () -> String? = { "test-key" },
    ): DeepSeekSpeechProvider {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .eventListener(eventListener)
            .build()
        return DeepSeekSpeechProvider(key) { request ->
            assertEquals("api.deepseek.com", request.url.host)
            client.newCall(request.newBuilder().url(server.url(request.url.encodedPath)).build())
        }
    }

    private suspend fun failureCode(block: suspend () -> Unit): String =
        try {
            block()
            error("Expected a speech failure")
        } catch (error: SpeechProviderException) {
            error.code
        }
}
