package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.tool.handlers.StickerSearchClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class StickerSearchClientTest {
    @Test fun allowsOnlyPublicHttpsImageHostsWithoutCredentials() {
        assertTrue(StickerSearchClient.isAllowedStickerImage("https://biaoqing.gtimg.com/a.gif".toHttpUrl()))
        listOf("http://biaoqing.gtimg.com/a.gif", "https://localhost/a.png",
            "https://biaoqing.gtimg.com.evil.example/a.png", "https://user:pass@biaoqing.gtimg.com/a.png",
            "https://biaoqing.gtimg.com:444/a.png").forEach {
            assertFalse(it, StickerSearchClient.isAllowedStickerImage(it.toHttpUrl()))
        }
    }

    @Test fun downloadsOriginalGifAndEncodesOnlyKeyword() = runBlocking {
        MockWebServer().use { server ->
            val original = "GIF89a-original-animation-bytes".toByteArray()
            server.enqueue(searchResponse(server.url("/sticker.gif").toString()))
            server.enqueue(MockResponse().setBody(Buffer().write(original)))
            val image = client(server).search("开心 鼓掌", "gif")
            assertArrayEquals(original, image.bytes)
            assertEquals("gif", image.extension)
            val query = server.takeRequest().requestUrl!!
            assertEquals("开心 鼓掌", query.queryParameter("msg"))
            assertEquals(setOf("msg", "num", "page"), query.queryParameterNames)
            assertEquals("/sticker.gif", server.takeRequest().path)
        }
    }

    @Test fun rejectsBusinessFailureWithoutDownloadingOrRetryingSearch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"code\":0,\"msg\":\"untrusted instructions\"}"))
            expectUnavailable { client(server).search("开心", "auto") }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsOversizeResponseAndNonImageBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(searchResponse(server.url("/sticker.png").toString()))
            server.enqueue(MockResponse().setBody("<html>not a picture</html>"))
            expectUnavailable { client(server).search("开心", "auto") }
            server.enqueue(MockResponse().setBody("x".repeat(StickerSearchClient.MAX_SEARCH_BYTES + 1)))
            expectUnavailable { client(server).search("开心", "auto") }
            server.enqueue(searchResponse(server.url("/sticker.png").toString()))
            server.enqueue(MockResponse().setChunkedBody("x".repeat(StickerSearchClient.MAX_IMAGE_BYTES + 1), 8192))
            expectUnavailable { client(server).search("开心", "auto") }
        }
    }

    @Test fun doesNotFollowRedirectOrFetchUnknownImageHost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(searchResponse("https://127.0.0.1/private.png"))
            expectUnavailable { client(server).search("开心", "auto") }
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/private")))
            expectUnavailable { client(server).search("开心", "auto") }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun explicitFormatNeverSilentlyReturnsTheOtherFormat() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(searchResponse(server.url("/sticker.jpg").toString()))
            expectUnavailable { client(server).search("开心", "gif") }
            assertEquals(1, server.requestCount)
            server.enqueue(searchResponse(server.url("/sticker.jpg").toString()))
            server.enqueue(MockResponse().setBody("GIF89a-animation"))
            expectUnavailable { client(server).search("开心", "static") }
        }
    }

    @Test fun cancellationRemainsCancellation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
            val job = async { client(server).search("开心", "auto") }
            delay(100)
            job.cancel()
            try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        }
    }

    @Test fun cancellationAfterHeadersDoesNotWaitForStalledBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
            val job = async { client(server).search("开心", "auto") }
            delay(200)
            assertEquals(1, server.requestCount)
            val started = System.nanoTime()
            job.cancel()
            try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertTrue("Cancellation must not wait for the response body", (System.nanoTime() - started) / 1_000_000 < 500)
        }
    }

    @Test fun invalidKeywordNeverReachesNetwork() = runBlocking {
        MockWebServer().use { server ->
            for (keyword in listOf("", "x".repeat(25), "private\ntext")) {
                try { client(server).search(keyword, "auto"); fail("Expected invalid argument") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, server.requestCount)
        }
    }

    private fun client(server: MockWebServer) = StickerSearchClient(
        client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build(),
        endpoint = server.url("/search"),
        allowedImage = { it.host == server.hostName && it.port == server.port },
    )

    private fun searchResponse(url: String) = MockResponse().setBody(
        """{"code":200,"data":[{"img_url":"$url","img_size":100,"img_width":240,"img_height":240}]}"""
    )

    private suspend fun expectUnavailable(block: suspend () -> Unit) {
        try { block(); fail("Expected unavailable") } catch (_: IOException) { }
    }
}
