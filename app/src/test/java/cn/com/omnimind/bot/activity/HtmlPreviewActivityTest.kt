package cn.com.omnimind.bot.activity

import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class HtmlPreviewActivityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `html and htm files load in a native view attached to the activity`() {
        listOf("preview.html", "preview.htm", "uppercase.HTML").forEach { name ->
            val source = html(name)
            val controller = activity(source.absolutePath).setup()
            try {
                val activity = controller.get()
                assertFalse(activity.isFinishing)
                val views = descendants(activity.findViewById(android.R.id.content)).toList()
                val webView = views.filterIsInstance<WebView>().single()
                assertEquals(Uri.fromFile(source).toString(), shadowOf(webView).lastLoadedUrl)
                assertTrue(webView.parent is ViewGroup)
                assertFalse(views.any { it.javaClass.name.startsWith("io.flutter.") })
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test
    fun `missing blank directory and non-html paths finish without creating a webview`() {
        val directory = temporary.newFolder("directory.html")
        val textFile = temporary.newFile("notes.txt").apply { writeText("plain text") }
        listOf("", "  ", File(temporary.root, "missing.html").absolutePath,
            directory.absolutePath, textFile.absolutePath).forEach { path ->
            val controller = activity(path).create()
            try {
                val activity = controller.get()
                assertTrue("Path should be rejected: $path", activity.isFinishing)
                assertTrue(descendants(activity.window.decorView).none { it is WebView })
            } finally {
                controller.destroy()
            }
        }
    }

    @Test
    fun `missing source extra finishes without creating a webview`() {
        val controller = Robolectric.buildActivity(HtmlPreviewActivity::class.java).create()
        try {
            assertTrue(controller.get().isFinishing)
            assertTrue(descendants(controller.get().window.decorView).none { it is WebView })
        } finally {
            controller.destroy()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `local scripts can run without granting cross-origin file or content access`() {
        val controller = activity(html("scripted.html").absolutePath).setup()
        try {
            val webView = descendants(controller.get().window.decorView).filterIsInstance<WebView>().single()
            with(webView.settings) {
                assertTrue(javaScriptEnabled)
                assertTrue(domStorageEnabled)
                assertFalse(useWideViewPort)
                assertTrue(loadWithOverviewMode)
                assertTrue(allowFileAccess)
                assertFalse(allowContentAccess)
                assertFalse(allowFileAccessFromFileURLs)
                assertFalse(allowUniversalAccessFromFileURLs)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `activity lifecycle pauses resumes and destroys its webview`() {
        val controller = activity(html("lifecycle.html").absolutePath).create().start()
        val webView = descendants(controller.get().window.decorView).filterIsInstance<WebView>().single()
        val shadow = shadowOf(webView)
        assertFalse(shadow.wasOnResumeCalled())
        controller.resume()
        assertTrue(shadow.wasOnResumeCalled())
        assertFalse(shadow.wasOnPauseCalled())
        controller.pause()
        assertTrue(shadow.wasOnPauseCalled())
        controller.stop().destroy()
        assertTrue(shadow.wasDestroyCalled())
        assertNull(webView.parent)
        assertNull(shadow.webChromeClient)
    }

    private fun html(name: String) = temporary.newFile(name).apply {
        writeText("<!doctype html><html><body><h1>Preview</h1></body></html>")
    }

    private fun activity(path: String) = Robolectric.buildActivity(
        HtmlPreviewActivity::class.java,
        HtmlPreviewActivity.createIntent(RuntimeEnvironment.getApplication(), path, "Preview")
    )

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
