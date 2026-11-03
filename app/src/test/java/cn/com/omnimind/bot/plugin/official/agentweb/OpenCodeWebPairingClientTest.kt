package cn.com.omnimind.bot.plugin.official.agentweb

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

class OpenCodeWebPairingClientTest {
    @Test
    fun `each open requests a fresh official pairing code with local Basic authentication`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OpenCodeWebPairingClient()
            val endpoint = OpenCodeWebEndpoint(server.url("/").toString(), "fixture-password")
            for (code in listOf("a".repeat(22), "b".repeat(22))) {
                server.enqueue(MockResponse.Builder().body("""{"code":"$code","expires_in":300}""").build())
                assertEquals(server.url("/auth/connect/$code").toString(), client.createLink(endpoint))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/api/pair", request.url.encodedPath)
                assertEquals(Credentials.basic("opencode", "fixture-password"), request.headers["Authorization"])
                assertEquals("application/json", request.headers["Accept"])
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `pairing refuses redirects without sending credentials to their destination`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://example.com/auth").build())
            val failure = runCatching {
                OpenCodeWebPairingClient().createLink(OpenCodeWebEndpoint(server.url("/").toString(), "fixture-password"))
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(1, server.requestCount)
            assertFalse(failure.toString().contains("fixture-password"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `invalid or expired pairing codes cannot become browser destinations`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val client = OpenCodeWebPairingClient()
            val endpoint = OpenCodeWebEndpoint(server.url("/").toString(), "fixture-password")
            for (body in listOf(
                """{"code":"../../other","expires_in":300}""",
                """{"code":"${"x".repeat(22)}","expires_in":0}""",
                """{"url":"https://example.com","expires_in":300}""",
            )) {
                server.enqueue(MockResponse.Builder().body(body).build())
                assertTrue(runCatching { client.createLink(endpoint) }.isFailure)
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `only root loopback URLs can receive pairing credentials`() = runBlocking {
        for (url in listOf("https://example.com:443", "http://127.0.0.1.example.com:4096",
            "http://user@127.0.0.1:4096", "http://127.0.0.1:4096/other", "http://127.0.0.1:4096/?next=evil")) {
            assertFalse(url, isOpenCodeLoopbackServerUrl(url))
            assertTrue(runCatching { OpenCodeWebPairingClient().createLink(OpenCodeWebEndpoint(url, "fixture-password")) }.isFailure)
        }
        assertTrue(isOpenCodeLoopbackServerUrl("http://[::1]:4096"))
    }
}
