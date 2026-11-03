package cn.com.omnimind.bot.resource

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ResourceFileIntentsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a file already covered by the provider does not need a cache copy`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.newFile("page.html").apply { writeText("<h1>Original</h1>") }
        val expected = Uri.parse("content://${context.packageName}.fileprovider/files/page.html")

        mockStatic(FileProvider::class.java).use { provider ->
            provider.`when`<Uri> {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            }.thenReturn(expected)

            assertEquals(expected, ResourceFileIntents.contentUri(context, source))
            provider.verify {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            }
            provider.verifyNoMoreInteractions()
        }
    }

    @Test
    fun `provider fallback stages matching bytes and removes expired exports`() {
        val context = RuntimeEnvironment.getApplication()
        val source = temporary.newFile("page.html").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val exportDir = File(context.cacheDir, "shared_exports").apply { mkdirs() }
        val expired = File(exportDir, "expired.html").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L)
        }
        val expected = Uri.parse("content://${context.packageName}.fileprovider/cache/staged.html")
        var staged: File? = null

        mockStatic(FileProvider::class.java).use { provider ->
            provider.`when`<Uri> {
                FileProvider.getUriForFile(eq(context), eq("${context.packageName}.fileprovider"), any(File::class.java))
            }.thenAnswer { invocation ->
                val file = invocation.getArgument<File>(2)
                if (file == source) throw IllegalArgumentException("Outside provider roots")
                staged = file
                expected
            }

            assertEquals(expected, ResourceFileIntents.contentUri(context, source))
            val sharedFile = requireNotNull(staged)
            assertEquals(exportDir, sharedFile.parentFile)
            assertTrue(sharedFile.name.endsWith("_page.html"))
            assertArrayEquals(source.readBytes(), sharedFile.readBytes())
            assertFalse(expired.exists())
            assertTrue(source.exists())
        }
    }
}
