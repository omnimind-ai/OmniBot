package cn.com.omnimind.bot.ui.chat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports ui/test/services/link_preview_service_test.dart plus the HTML and fetch rules (5e-6). */
class LinkPreviewServiceTest {
    @Test
    fun `omnibot resource urls are ignored`() {
        assertEquals(
            listOf("https://example.com/docs"),
            LinkPreviewExtractor.extractUrls("先看 omnibot://workspace/demo/index.html 再看 https://example.com/docs"),
        )
    }

    @Test
    fun `reconcile keeps only web previews`() {
        val previews = LinkPreviewExtractor.reconcile(
            "资源 [报告](omnibot://workspace/demo/report.html) 和网页 https://example.com/news", existing = null,
        )
        assertEquals(listOf("https://example.com/news"), previews.map { it.url })
        assertEquals(LinkPreviewStatus.LOADING, previews.single().status)
    }

    @Test
    fun `filenames inside markdown image syntax are ignored`() {
        assertTrue(
            LinkPreviewExtractor.extractUrls(
                "![screenshot_tab_1_1777050057660_1e40e3cd.jpg](omnibot://browser/f6d26871-5b43-4ba0-af49-7c09396569a8/screenshot_tab_1_1777050057660_1e40e3cd.jpg)",
            ).isEmpty(),
        )
    }

    @Test
    fun `dot commands without common domain suffixes are ignored`() {
        assertEquals(
            listOf("https://github.com/docs"),
            LinkPreviewExtractor.extractUrls("先执行 diagnostics.getprop 和 settings_control.get，再打开 github.com/docs"),
        )
    }

    @Test
    fun `common multi-part domain suffixes are kept`() {
        assertEquals(
            listOf("https://example.co.uk/guide", "https://docs.github.io/reference"),
            LinkPreviewExtractor.extractUrls("文档在 example.co.uk/guide 和 docs.github.io/reference"),
        )
    }

    @Test
    fun `markdown punctuation is trimmed, duplicates collapse, at most three`() {
        assertEquals(
            listOf("https://a.com/x", "https://b.com", "https://c.com/(1)"),
            LinkPreviewExtractor.extractUrls(
                "看 **https://a.com/x**。 再看 www.a.com/x/ 和 (https://b.com)， 还有 https://c.com/(1) 与 d.com 以及 e.com",
            ),
        )
        assertTrue(LinkPreviewExtractor.extractUrls("截图 https://img.example.com/a.png").isEmpty())
        // Like Dart, a URL runs on through CJK text that follows it without a space.
        assertEquals(listOf("https://a.com/x**。再看"), LinkPreviewExtractor.extractUrls("看 https://a.com/x**。再看"))
    }

    @Test
    fun `stored ready previews are kept for the same link`() {
        val stored = listOf(LinkPreview("https://www.example.com/a/", "example.com", title = "A", status = LinkPreviewStatus.READY).toMap())
        val previews = LinkPreviewExtractor.reconcile("https://example.com/a", stored)
        assertEquals(LinkPreviewStatus.READY, previews.single().status)
        assertEquals("A", previews.single().title)
    }

    @Test
    fun `open graph wins over twitter and the page title, entities decoded`() {
        val html = """
            <html><head><title>Page &amp; Title</title>
            <meta name="twitter:title" content="Twitter">
            <meta property='og:title' content="OG &quot;Title&quot;">
            <meta name="description" content="  plain
              description ">
            <meta property="og:image" content="/cover.png">
            <meta property="og:site_name" content="Example">
            </head></html>
        """.trimIndent()
        val preview = LinkPreviewHtml.parse("https://example.com/post", html)
        assertEquals("OG \"Title\"", preview.title)
        assertEquals("plain description", preview.description)
        assertEquals("https://example.com/cover.png", preview.imageUrl)
        assertEquals("Example", preview.siteName)
        assertEquals(LinkPreviewStatus.READY, preview.status)
        assertEquals("Page & Title", LinkPreviewHtml.parse("https://e.com", "<title>Page &amp; Title</title>").title)
    }

    @Test
    fun `a fetch parses the page, an error status fails, results are cached`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("<title>Hello</title>"))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val loader = LinkPreviewLoader()
            val ok = server.url("/ok").toString()
            assertEquals("Hello", loader.load(ok).title)
            assertEquals(LinkPreviewStatus.FAILED, loader.load(server.url("/missing").toString()).status)
            // Cached: no third request reaches the server.
            assertEquals("Hello", loader.load(ok).title)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a resolved preview replaces only the loading entry for its link`() {
        val content = mapOf(
            "text" to "a.com b.com",
            "linkPreviews" to listOf(LinkPreview.loading("https://a.com").toMap(), LinkPreview.loading("https://b.com").toMap()),
        )
        val ready = LinkPreview("https://a.com", "a.com", title = "A", status = LinkPreviewStatus.READY)
        val updated = contentWithResolvedPreview(content, "https://a.com", ready)!!
        val previews = (updated["linkPreviews"] as List<*>).map { LinkPreview.fromMap(it as Map<*, *>) }
        assertEquals(listOf(LinkPreviewStatus.READY, LinkPreviewStatus.LOADING), previews.map { it.status })
        assertEquals("A", previews.first().title)
        // Already resolved, or no longer listed: nothing to write.
        assertEquals(null, contentWithResolvedPreview(updated, "https://a.com", ready))
        assertEquals(null, contentWithResolvedPreview(mapOf("text" to "x"), "https://a.com", ready))
    }
}
