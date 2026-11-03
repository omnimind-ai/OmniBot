package cn.com.omnimind.bot.ui.channel

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import cn.com.omnimind.bot.activity.HtmlPreviewActivity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class FileSaveChannelHtmlPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `HTML opens the explicit native activity without a read permission grant`() {
        for (extension in listOf("html", "HTM")) {
            val source = temporary.newFile("page.$extension").apply { writeText("<h1>Hello</h1>") }
            val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get()
            val channel = FileSaveChannel().apply { onCreate(activity) }
            val result = RecordingResult()

            channel.openHtmlPreview(
                MethodCall("openHtmlPreview", mapOf("sourcePath" to source.path, "title" to "Preview")),
                result,
            )

            assertEquals(true, result.value)
            assertNull(result.errorCode)
            val intent = activity.startedIntent
            assertNotNull(intent)
            assertEquals(HtmlPreviewActivity::class.java.name, intent!!.component?.className)
            assertEquals(activity.packageName, intent.component?.packageName)
            assertEquals(source.absolutePath, intent.getStringExtra(HtmlPreviewActivity.EXTRA_SOURCE_PATH))
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(1, result.responses)
        }
    }

    @Test
    fun `missing directory and non HTML inputs do not launch an activity`() {
        val text = temporary.newFile("page.txt").apply { writeText("<html></html>") }
        val xhtml = temporary.newFile("page.xhtml").apply { writeText("<html></html>") }
        val directory = temporary.newFolder("folder.html")
        val missing = File(temporary.root, "missing.html")
        for (sourcePath in listOf(null, "", text.path, xhtml.path, directory.path, missing.path)) {
            val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get()
            val channel = FileSaveChannel().apply { onCreate(activity) }
            val result = RecordingResult()

            channel.openHtmlPreview(
                MethodCall("openHtmlPreview", mapOf("sourcePath" to sourcePath)),
                result,
            )

            assertEquals("INVALID_ARGS", result.errorCode)
            assertNull(result.value)
            assertNull(activity.startedIntent)
            assertEquals(1, result.responses)
        }
    }

    @Test
    fun `activity launch failure is returned instead of pretending preview opened`() {
        val source = temporary.newFile("page.html").apply { writeText("<html></html>") }
        val activity = Robolectric.buildActivity(RecordingActivity::class.java).setup().get().apply {
            failLaunch = true
        }
        val channel = FileSaveChannel().apply { onCreate(activity) }
        val result = RecordingResult()

        channel.openHtmlPreview(
            MethodCall("openHtmlPreview", mapOf("sourcePath" to source.path)),
            result,
        )

        assertEquals("OPEN_FAILED", result.errorCode)
        assertNull(result.value)
        assertNull(activity.startedIntent)
        assertEquals(1, activity.launchAttempts)
        assertEquals(1, result.responses)
    }

    @Test
    fun `opening before activity attachment returns a channel error`() {
        val result = RecordingResult()
        FileSaveChannel().openHtmlPreview(MethodCall("openHtmlPreview", emptyMap<String, Any>()), result)
        assertEquals("INIT_FAILED", result.errorCode)
        assertEquals(1, result.responses)
    }

    class RecordingActivity : Activity() {
        var startedIntent: Intent? = null
        var failLaunch = false
        var launchAttempts = 0

        override fun startActivity(intent: Intent) {
            launchAttempts++
            if (failLaunch) throw ActivityNotFoundException("Test launch failure")
            startedIntent = intent
        }
    }

    private class RecordingResult : MethodChannel.Result {
        var value: Any? = null
        var errorCode: String? = null
        var responses = 0

        override fun success(result: Any?) {
            responses++
            value = result
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            responses++
            this.errorCode = errorCode
        }

        override fun notImplemented() = error("Unexpected unimplemented channel method")
    }
}
