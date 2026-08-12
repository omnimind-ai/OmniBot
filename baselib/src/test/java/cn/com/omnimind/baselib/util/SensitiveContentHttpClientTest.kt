package cn.com.omnimind.baselib.util

import cn.com.omnimind.baselib.http.OkHttpManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SensitiveContentHttpClientTest {
    @Test
    fun `sensitive content client never follows redirects`() {
        val client = OkHttpManager.sensitiveContentClient(
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        )

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun `final call gate rejects plaintext public destination`() {
        val request = Request.Builder()
            .url("http://example.com/v1/chat/completions")
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            OkHttpManager.sensitiveContentCall(OkHttpClient(), request)
        }
    }

    @Test
    fun `final call gate accepts explicit debug literal loopback only`() {
        val loopback = Request.Builder()
            .url("http://127.0.0.1:8080/v1/chat/completions")
            .build()
        OkHttpManager.sensitiveContentCall(
            client = OkHttpClient(),
            request = loopback,
            allowInsecureLoopback = true,
        ).cancel()

        val localhost = Request.Builder()
            .url("http://localhost:8080/v1/chat/completions")
            .build()
        assertThrows(IllegalArgumentException::class.java) {
            OkHttpManager.sensitiveContentCall(
                client = OkHttpClient(),
                request = localhost,
                allowInsecureLoopback = true,
            )
        }
    }

    @Test
    fun `sensitive call returns 3xx without contacting redirect target`() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 1_000
        }
        var acceptedConnections = 0
        val serverThread = thread(start = true) {
            server.use { listening ->
                val first = listening.accept()
                acceptedConnections += 1
                first.use { socket ->
                    val reader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)
                    )
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val location = "http://127.0.0.1:${listening.localPort}/redirected"
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 302 Found\r\n" +
                                "Location: $location\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(StandardCharsets.US_ASCII)
                    )
                    socket.getOutputStream().flush()
                }
                runCatching {
                    listening.accept().use { redirected ->
                        acceptedConnections += 1
                        redirected.getOutputStream().write(
                            (
                                "HTTP/1.1 200 OK\r\n" +
                                    "Content-Length: 0\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.US_ASCII)
                        )
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("http://127.0.0.1:${server.localPort}/sensitive")
            .build()
        OkHttpManager.sensitiveContentCall(
            client = OkHttpClient(),
            request = request,
            allowInsecureLoopback = true,
        ).execute().use { response ->
            assertEquals(302, response.code)
        }
        serverThread.join(2_000)
        assertEquals(1, acceptedConnections)
    }
}
