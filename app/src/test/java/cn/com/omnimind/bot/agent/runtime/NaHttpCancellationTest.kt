package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NaHttpCancellationTest {
    @Test fun cancellationClosesAStreamingReadWithoutWaitingForTheReadTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(Buffer().writeUtf8(": connected\n\n" + "x".repeat(256)), 14)
                .throttleBody(14, 10, TimeUnit.SECONDS))
            server.start()
            val opened = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(20, TimeUnit.SECONDS).build()
            val task = launch {
                naHttpResponse(client, Request.Builder().url(server.url("/api/events")).build(), retryReads = false) { response ->
                    opened.complete(Unit)
                    try { response.body.string() } finally { closed.complete(Unit) }
                }
            }
            withTimeout(3000) {
                opened.await()
                task.cancelAndJoin()
                closed.await()
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun bodyFailureIsNotRetriedWithAnOldSseCursor() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("frame"))
            server.start()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            try {
                naHttpResponse(client, Request.Builder().url(server.url("/api/events?after=3")).build(), retryReads = false) {
                    throw IOException("disconnect after visible output")
                }
                fail("must return to snapshot recovery")
            } catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
}
