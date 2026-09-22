package cn.com.omnimind.bot.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatLinkPreviewRepositoryTest {
    private val repository = ChatLinkPreviewRepository { error("Unexpected HTTP request") }

    @Test fun resourceUrlsImagesAndCommandsAreNotWebPreviews() {
        assertEquals(listOf("https://example.com/docs"), repository.extractUrls(
            "先看 omnibot://workspace/demo/index.html 再看 https://example.com/docs",
        ))
        assertEquals(emptyList<String>(), repository.extractUrls(
            "![screenshot_tab_1.jpg](omnibot://browser/id/screenshot_tab_1.jpg) https://example.com/image.png",
        ))
        assertEquals(listOf("https://github.com/docs"), repository.extractUrls(
            "先执行 diagnostics.getprop 和 settings_control.get，再打开 github.com/docs",
        ))
    }

    @Test fun domainsAndMarkdownPunctuationPreserveOrderAndIdentity() {
        assertEquals(listOf("https://example.co.uk/guide", "https://docs.github.io/reference"), repository.extractUrls(
            "文档在 example.co.uk/guide 和 docs.github.io/reference",
        ))
        assertEquals(listOf("https://example.com/a_(b)", "https://www.example.com/docs/#one"), repository.extractUrls(
            "[a](https://example.com/a_(b)) https://www.example.com/docs/#one https://example.com/docs#two",
        ))
        assertEquals(emptyList<String>(), repository.extractUrls("test@example.com report.pdf", maxCount = 3))
        assertEquals(emptyList<String>(), repository.extractUrls("https://example.com", maxCount = 0))
    }

    @Test fun reconcilePreservesSavedResultsAndRemovesObsoleteUrls() = runBlocking {
        val ready = mapOf("url" to "https://example.com/news", "title" to "Saved", "status" to "ready")
        val rows = repository.reconcile(
            "资源 [报告](omnibot://workspace/demo/report.html) 和网页 https://www.example.com/news#fragment",
            listOf(ready, mapOf("url" to "https://old.com")),
        )
        assertEquals(1, rows.size)
        assertEquals("Saved", rows.single()["title"])
        assertEquals("ready", rows.single()["status"])
        assertEquals("https://example.com/news", rows.single()["url"])
    }

    @Test fun htmlMetadataMatchesExistingCardPresentation() {
        val preview = ChatLinkPreviewRepository.parseHtml("https://example.com/docs/page", """
            <title>Fallback</title><meta name=twitter:title content='Twitter'>
            <meta content="A &amp; B &#x1F600;" property="og:title">
            <meta name='description' content='Fallback description'>
            <meta property='og:description' content=' One   line &#33; '>
            <meta property='og:image' content='../cover.png'>
            <meta property='og:site_name' content='Example'>
        """.trimIndent())
        assertEquals("A & B 😀", preview["title"])
        assertEquals("One line !", preview["description"])
        assertEquals("https://example.com/cover.png", preview["imageUrl"])
        assertEquals("Example", preview["siteName"])
        assertEquals("ready", preview["status"])
    }

    @Test fun concurrentEnginesShareFetchAndFailuresAreCached() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val repository = ChatLinkPreviewRepository {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            null
        }
        val a = async { repository.load("https://example.com/docs") }
        entered.await()
        val b = async { repository.load("https://www.example.com/docs#section") }
        release.complete(Unit)
        assertEquals("failed", a.await()["status"])
        assertEquals("https://www.example.com/docs#section", b.await()["url"])
        assertEquals("failed", repository.load("https://example.com/docs")["status"])
        assertEquals(1, calls.get())
    }
}
