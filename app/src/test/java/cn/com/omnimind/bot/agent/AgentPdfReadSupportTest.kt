package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.res.AssetManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

class AgentPdfReadSupportTest {
    @Before fun resources() {
        val context = mock(Context::class.java)
        val assets = mock(AssetManager::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.assets).thenReturn(assets)
        `when`(assets.open(anyString())).thenAnswer { call ->
            checkNotNull(javaClass.classLoader!!.getResourceAsStream("assets/${call.arguments[0]}"))
        }
        PDFBoxResourceLoader.init(context)
    }
    private fun pdf(file: File, text: String?) {
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            if (text != null) PDPageContentStream(doc, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(30f, 700f); it.showText(text); it.endText()
            }
            doc.save(file)
        }
    }
    @Test fun `actual PDF text is paged across reopen and source stays intact`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-test").toFile()
        try {
            val file = File(dir, "sample.pdf"); pdf(file, "Invoice total 12345")
            val original = file.readBytes()
            val first = AgentPdfReadSupport.read(file, dir, maxChars = 8)
            assertEquals(true, first["contentAvailable"])
            assertEquals(true, first["hasMore"])
            val second = AgentPdfReadSupport.read(file, dir, offset = first["nextOffset"] as Long)
            assertTrue((first["content"].toString() + second["content"]).contains("Invoice total 12345"))
            assertArrayEquals(original, file.readBytes())
            assertEquals(listOf("sample.pdf"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `empty and malformed PDFs never report body read`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-fail").toFile()
        try {
            val file = File(dir, "empty.pdf"); pdf(file, null)
            val blank = AgentPdfReadSupport.read(file, dir)
            assertEquals(false, blank["contentAvailable"])
            assertEquals("document_ocr_required", blank["errorCode"])
            file.writeText("not a PDF")
            val corrupt = AgentPdfReadSupport.read(file, dir)
            assertEquals(false, corrupt["contentAvailable"])
            assertEquals("document_parse_failed", corrupt["errorCode"])
            assertFalse(corrupt.containsKey("content"))
            assertEquals(listOf("empty.pdf"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `actual upload regression fixture exposes values not supplied in prompt`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-fixture").toFile()
        try {
            val fixture = File("../scripts/fixtures/chat-upload-invoice.pdf")
            assertTrue("Run from the app Gradle test working directory", fixture.isFile)
            val result = AgentPdfReadSupport.read(fixture, dir)
            assertEquals(true, result["contentAvailable"])
            val content = result["content"].toString()
            assertTrue(content.contains("CEDAR-7391"))
            assertTrue(content.contains("Total: 37"))
            assertTrue(content.contains("MAPLE-8264"))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `password protected PDF reports required password without exposing body`() = runBlocking {
        val dir = Files.createTempDirectory("pdf-password").toFile()
        try {
            val file = File(dir, "protected.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-secret", "reader-secret",
                    com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(),
                ))
                doc.save(file)
            }
            val result = AgentPdfReadSupport.read(file, dir)
            assertEquals(false, result["contentAvailable"])
            assertEquals("document_password_required", result["errorCode"])
            assertFalse(result.containsKey("content"))
            assertEquals(listOf("protected.pdf"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
}
