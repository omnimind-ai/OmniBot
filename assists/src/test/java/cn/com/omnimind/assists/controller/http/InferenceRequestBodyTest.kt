package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.toStreamingRequestBody
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceRequestBodyTest {
    private fun streamClient(forceHttp1: Boolean): OkHttpClient = HttpController::class.java
        .getDeclaredMethod("openAIStreamClient", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }
        .invoke(HttpController, forceHttp1) as OkHttpClient

    @Test
    fun `503 Retry-After zero is returned once and explicit next call sends identical bytes`() {
        val payload = Json.parseToJsonElement("""{"model":"fixture","stream":true,"input":"你好"}""")
        val bodies = listOf(
            payload.toString().toRequestBody("application/json".toMediaType()),
            Json.toStreamingRequestBody(payload),
        )
        bodies.forEach { original ->
            listOf(false, true).forEach { forceHttp1 ->
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                    server.enqueue(MockResponse().setBody("recovered"))
                    val body = original.asSingleAttemptInferenceBody()
                    assertTrue(body.isOneShot())
                    assertEquals(original.contentType(), body.contentType())
                    assertEquals(original.contentLength(), body.contentLength())
                    val request = Request.Builder().url(server.url("/responses")).post(body).build()
                    val client = streamClient(forceHttp1)

                    client.newCall(request).execute().use { response -> assertEquals(503, response.code) }
                    assertEquals("OkHttp must not consume the outer retry budget", 1, server.requestCount)
                    val first = server.takeRequest().body.readByteString()

                    client.newCall(request).execute().use { response -> assertEquals(200, response.code) }
                    assertEquals(2, server.requestCount)
                    val second = server.takeRequest().body.readByteString()
                    assertEquals(first, second)
                    assertEquals(payload.toString(), second.utf8())
                }
            }
        }
    }

    @Test
    fun `inference redirects cannot convert a POST into an implicit GET attempt`() {
        listOf(301, 302, 303, 307, 308).forEach { status ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", "/redirected"))
                server.enqueue(MockResponse().setBody("unexpected follow-up"))
                val request = Request.Builder().url(server.url("/responses"))
                    .post("{}".toRequestBody().asSingleAttemptInferenceBody()).build()

                streamClient(false).newCall(request).execute().use { response -> assertEquals(status, response.code) }

                assertEquals(1, server.requestCount)
                assertEquals("POST", server.takeRequest().method)
            }
        }
    }

    @Test
    fun `provider model catalogs retain their finite GET redirect behavior`() {
        val client = HttpController::class.java.getDeclaredMethod("getProviderModelsClient")
            .apply { isAccessible = true }.invoke(HttpController) as OkHttpClient
        assertTrue(client.retryOnConnectionFailure)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/model-list"))
            server.enqueue(MockResponse().setBody("{\"data\":[]}"))

            client.newCall(Request.Builder().url(server.url("/models")).build()).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals(2, server.requestCount)
            assertEquals("/models", server.takeRequest().path)
            assertEquals("/model-list", server.takeRequest().path)
        }
    }
}
