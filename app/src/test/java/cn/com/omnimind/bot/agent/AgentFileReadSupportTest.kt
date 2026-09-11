package cn.com.omnimind.bot.agent

import java.io.File
import java.io.Reader
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import cn.com.omnimind.baselib.i18n.PromptLocale
import org.junit.Assert.*
import org.junit.Test

class AgentFileReadSupportTest {
    @Test
    fun `file read exposes bounded page size in both model locales`() {
        for (locale in listOf(PromptLocale.ZH_CN, PromptLocale.EN_US)) {
            val function = AgentToolDefinitions.staticTools(locale)
                .map { it.getValue("function").jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "file_read" }
            val size = function.getValue("parameters").jsonObject
                .getValue("properties").jsonObject.getValue("maxChars").jsonObject
            assertEquals("integer", size.getValue("type").jsonPrimitive.content)
            assertEquals("2", size.getValue("minimum").jsonPrimitive.content)
            assertEquals("65536", size.getValue("maximum").jsonPrimitive.content)
        }
    }

    @Test
    fun `small file pages recover single line body with unicode across reopen`() {
        val file = File.createTempFile("file-read-small-", ".html")
        try {
            val original = "<html>" + "body😀".repeat(15000) + "</html>"
            file.writeText(original)
            val first = AgentFileReadSupport.read(file, maxChars = 2048)
            assertTrue(first.content.length <= 2048)
            val body = StringBuilder(first.content)
            var offset = first.nextOffset
            while (offset != null) {
                // Reopen the original file, as a later tool call does.
                val page = AgentFileReadSupport.read(File(file.path), offset, maxChars = 2048)
                assertTrue(page.content.length <= 2048)
                assertFalse(page.content.lastOrNull()?.isHighSurrogate() == true)
                body.append(page.content)
                offset = page.nextOffset
            }
            assertEquals(original, body.toString())
            assertEquals(original, file.readText())
            for (invalid in listOf(-1, 0, 1, 65537, Int.MAX_VALUE)) {
                try {
                    AgentFileReadSupport.read(file, maxChars = invalid)
                    fail("Accepted invalid maxChars: $invalid")
                } catch (_: IllegalArgumentException) { }
            }
        } finally { file.delete() }
    }

    @Test
    fun `one enormous HTML line reads only a bounded page`() {
        var consumed = 0
        val reader = object : Reader() {
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                check(consumed + length <= AgentFileReadSupport.PAGE_CHARS + 8192) {
                    "Read beyond the first page of a huge HTML file"
                }
                chars.fill('x', offset, offset + length)
                consumed += length
                return length
            }
            override fun close() = Unit
        }
        val page = AgentFileReadSupport.readPage(reader)
        assertEquals(AgentFileReadSupport.PAGE_CHARS, page.content.length)
        assertEquals(page.content.length.toLong(), page.nextOffset)
        assertTrue(page.outputTruncated)
    }

    @Test
    fun `pages reconstruct unicode and original line endings without loss`() {
        val original = "1234567😀\r\nsecond\rthird\nend😀"
        var offset = 0L
        val reconstructed = StringBuilder()
        do {
            val page = AgentFileReadSupport.readPage(original.reader(), offset, maxChars = 8)
            assertFalse(page.content.lastOrNull()?.isHighSurrogate() == true)
            reconstructed.append(page.content)
            offset = page.nextOffset ?: break
        } while (true)
        assertEquals(original, reconstructed.toString())
    }

    @Test
    fun `line selection and character continuation use the original file positions`() {
        val text = "first\r\nsecond\rthird\nlast"
        val page = AgentFileReadSupport.readPage(text.reader(), lineStart = 2, lineCount = 2)
        assertEquals("second\rthird\n", page.content)
        assertEquals(7L, page.offset)
        assertEquals("last", AgentFileReadSupport.readPage(text.reader(), page.nextOffset!!).content)
        assertFalse(page.outputTruncated)
        assertEquals("first\r\n", AgentFileReadSupport.readPage(text.reader(), lineCount = 1).content)
        assertEquals("", AgentFileReadSupport.readPage(text.reader(), lineStart = Int.MAX_VALUE).content)
        assertNull(AgentFileReadSupport.readPage(text.reader(), offset = Long.MAX_VALUE).nextOffset)
    }

    @Test
    fun `PDF audio archives and binary bytes are not interpreted as plain text`() {
        val file = File.createTempFile("file-read-", ".bin")
        try {
            file.writeText("header")
            for (mime in listOf("application/pdf", "audio/wav", "video/mp4", "application/zip")) {
                assertTrue(mime, AgentFileReadSupport.isBinary(file, mime))
            }
            file.writeBytes(byteArrayOf(1, 0, 2, 3))
            assertTrue(AgentFileReadSupport.isBinary(file, "application/octet-stream"))
            file.writeText("<!DOCTYPE html>正常文本 😀</html>")
            assertFalse(AgentFileReadSupport.isBinary(file, "text/html"))
            file.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "正常文本".toByteArray(Charsets.UTF_16LE))
            assertFalse(AgentFileReadSupport.isBinary(file, "text/plain"))
            assertEquals("正常文本", AgentFileReadSupport.read(file).content)
        } finally { file.delete() }
    }
}
