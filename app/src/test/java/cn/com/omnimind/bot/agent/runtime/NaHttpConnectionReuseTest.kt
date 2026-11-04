package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NaHttpConnectionReuseTest {
    private fun client() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .readTimeout(3, TimeUnit.SECONDS).build()

    @Test fun sessionCreationDoesNotReuseAnIdleSocketClosedByTheServer() = runBlocking {
        // The peer closes only after the GET body has been consumed and pooled.
        // No Connection: close response header announces that idle close.
        val consumed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val requests = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val listener = ServerSocket(0).apply { soTimeout = 5000 }
        val peer = thread(name = "na-idle-close-test") {
            try {
                listener.accept().use { socket ->
                    readRequest(socket).also { assertTrue(it.startsWith("GET /api/status ")) }
                    requests.incrementAndGet()
                    respond(socket, "200 OK", "ready")
                    check(consumed.await(5, TimeUnit.SECONDS))
                }
                closed.countDown()
                listener.accept().use { socket ->
                    readRequest(socket).also { assertTrue(it.startsWith("POST /api/conversations ")) }
                    requests.incrementAndGet()
                    respond(socket, "201 Created", "created")
                }
            } catch (error: Throwable) { failure.set(error); closed.countDown() }
        }
        val client = client()
        try {
            val url = "http://127.0.0.1:${listener.localPort}"
            assertEquals("ready", naHttpResponse(client, Request.Builder().url("$url/api/status").build()) { it.body.string() })
            consumed.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            val create = Request.Builder().url("$url/api/conversations").post("{}".toRequestBody()).build()
            assertEquals("created", naHttpResponse(client, create) { it.body.string() })
            peer.join(5000)
            assertFalse(peer.isAlive)
            assertNull(failure.get())
            assertEquals(2, requests.get())
        } finally {
            consumed.countDown(); listener.close(); peer.join(5000)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun lostWriteResponseNeverReplaysAnAdmittedRequest() = runBlocking {
        for (method in listOf("POST", "PUT", "DELETE")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
                server.enqueue(MockResponse().setBody("must not be used"))
                server.start()
                val client = client()
                try {
                    val request = Request.Builder().url(server.url("/api/conversations/c/messages"))
                        .method(method, "{\"text\":\"once\"}".toRequestBody()).build()
                    val error = runCatching { naHttpResponse(client, request) { it.body.string() } }.exceptionOrNull()
                    assertTrue("$method must surface the uncertain result", error is IOException)
                    assertEquals(1, server.requestCount)
                    assertEquals(method, server.takeRequest().method)
                } finally {
                    client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test fun readsContinueToReuseALiveConnection() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("ready")) }
            server.start()
            val client = client()
            try {
                repeat(2) {
                    assertEquals("ready", naHttpResponse(client, Request.Builder().url(server.url("/api/status")).build()) { it.body.string() })
                }
                assertEquals(0, server.takeRequest().sequenceNumber)
                assertEquals(1, server.takeRequest().sequenceNumber)
            } finally {
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun readRequest(socket: Socket): String {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val start = reader.readLine()
        var length = 0
        while (true) {
            val line = reader.readLine() ?: error("missing headers")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
        }
        repeat(length) { check(reader.read() >= 0) }
        return start
    }

    private fun respond(socket: Socket, status: String, body: String) {
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\nConnection: keep-alive\r\n\r\n$body".toByteArray(Charsets.US_ASCII))
            flush()
        }
    }
}
