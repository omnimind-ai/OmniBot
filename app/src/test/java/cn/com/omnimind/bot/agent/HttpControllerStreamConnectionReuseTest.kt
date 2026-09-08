package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpControllerStreamConnectionReuseTest {
    private fun streamClient(forceHttp1: Boolean): OkHttpClient {
        val method = HttpController::class.java.getDeclaredMethod("openAIStreamClient", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(HttpController, forceHttp1) as OkHttpClient
    }

    @Test
    fun `successive stream requests reuse their established connection`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("data: [DONE]\n\n")) }
            repeat(2) {
                // Obtain the client exactly as each streaming request does.
                streamClient(false).newCall(Request.Builder().url(server.url("/v1/chat/completions")).build())
                    .execute().use { response -> response.body!!.string() }
            }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals("Second request must avoid another TCP/TLS handshake", 1, server.takeRequest().sequenceNumber)
        }
    }

    @Test
    fun `HTTP1 stream requests also reuse connections`() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("data: [DONE]\n\n")) }
            repeat(2) {
                streamClient(true).newCall(Request.Builder().url(server.url("/v1/messages")).build())
                    .execute().use { response -> response.body!!.string() }
            }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        }
    }
}
