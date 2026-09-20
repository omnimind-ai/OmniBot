package cn.com.omnimind.bot.agent

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentOfficeReadSupportTest {
    private fun zip(file: File, parts: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { out -> parts.forEach { (name, xml) ->
            out.putNextEntry(ZipEntry(name)); out.write(xml.toByteArray()); out.closeEntry()
        } }
    }
    private suspend fun read(file: File, offset: Long = 0, size: Int = 65536) =
        AgentOfficeReadSupport.read(file, offset, null, null, size)
    @Test fun `real DOCX paragraphs tables and pagination survive reopening`() = runBlocking {
        val file = File("../scripts/fixtures/chat-upload-office.docx")
        val before = file.readBytes()
        val first = read(file, size = 12)
        assertEquals(true, first["contentAvailable"])
        val second = read(file, first["nextOffset"] as Long)
        val text = first["content"].toString() + second["content"]
        assertTrue(text.contains("CEDAR-7391"))
        assertTrue(text.contains("37"))
        assertTrue(text.contains("MAPLE-8264"))
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun `real XLSX preserves sheets cells boolean and distinguishes formula cache`() = runBlocking {
        val result = read(File("../scripts/fixtures/chat-upload-office.xlsx"))
        assertEquals(true, result["contentAvailable"])
        val text = result["content"].toString()
        for (expected in listOf("Invoice", "History", "A1", "CEDAR-7391", "37", "true", "SUM(B1:B2)", "未重新计算", "MAPLE-8264")) {
            assertTrue("missing $expected in $text", text.contains(expected))
        }
    }
    @Test fun `shared strings resolve by relationships and invalid indexes fail`() = runBlocking {
        val dir = Files.createTempDirectory("office-shared").toFile()
        try {
            val parts = workbook("<c r=\"A1\" t=\"s\"><v>0</v></c>").toMutableMap()
            parts["xl/_rels/workbook.xml.rels"] = parts.getValue("xl/_rels/workbook.xml.rels").replace("</Relationships>",
                "<Relationship Id=\"strings\" Type=\"$REL/sharedStrings\" Target=\"strings/custom.xml\"/></Relationships>")
            parts["xl/strings/custom.xml"] = "<sst xmlns=\"$SHEET\"><si><r><t>Real </t></r><r><t>value</t></r></si></sst>"
            val file = File(dir, "shared.xlsx"); zip(file, parts)
            assertTrue(read(file)["content"].toString().contains("Real value"))
            parts["xl/worksheets/sheet1.xml"] = parts.getValue("xl/worksheets/sheet1.xml").replace("<v>0</v>", "<v>9</v>")
            zip(file, parts)
            assertEquals(false, read(file)["contentAvailable"])
        } finally { dir.deleteRecursively() }
    }
    @Test fun `empty workbook cannot count sheet names as extracted body`() = runBlocking {
        val dir = Files.createTempDirectory("office-empty").toFile()
        try {
            val file = File(dir, "empty.xlsx"); zip(file, workbook(""))
            assertEquals("document_no_text", read(file)["errorCode"])
        } finally { dir.deleteRecursively() }
    }
    @Test fun `external entities and external sheet targets are rejected without partial success`() = runBlocking {
        val dir = Files.createTempDirectory("office-external").toFile()
        try {
            val doc = File(dir, "bad.docx")
            zip(doc, mapOf("word/document.xml" to """<!DOCTYPE document [<!ENTITY x SYSTEM "file:///etc/passwd">]><document xmlns="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><p><r><t>&x;</t></r></p></document>"""))
            assertEquals(false, read(doc)["contentAvailable"])
            val parts = workbook("<c r=\"A1\"><v>37</v></c>").toMutableMap()
            parts["xl/_rels/workbook.xml.rels"] = parts.getValue("xl/_rels/workbook.xml.rels").replace("Target=", "TargetMode=\"External\" Target=")
            val sheet = File(dir, "external.xlsx"); zip(sheet, parts)
            val result = read(sheet)
            assertEquals(false, result["contentAvailable"])
            assertFalse(result.containsKey("content"))
            doc.writeText("broken")
            assertEquals("document_parse_failed", read(doc)["errorCode"])
        } finally { dir.deleteRecursively() }
    }
    @Test fun `deleted and moved-from Word revisions are not duplicated as current text`() = runBlocking {
        val dir = Files.createTempDirectory("office-revisions").toFile()
        try {
            val file = File(dir, "revised.docx")
            zip(file, mapOf("word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:del><w:r><w:t>DELETED</w:t></w:r></w:del><w:moveFrom><w:r><w:t>MOVED</w:t></w:r></w:moveFrom><w:r><w:t>CURRENT</w:t></w:r></w:p></w:body></w:document>"""))
            val result = read(file)
            assertEquals(true, result["contentAvailable"])
            assertEquals("CURRENT", result["content"].toString().trim())
        } finally { dir.deleteRecursively() }
    }
    private fun workbook(cells: String) = mapOf(
        "xl/workbook.xml" to """<workbook xmlns="$SHEET" xmlns:r="$REL"><sheets><sheet name="Example" r:id="sheet"/></sheets></workbook>""",
        "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="sheet" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
        "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$SHEET"><sheetData><row>$cells</row></sheetData></worksheet>""",
    )
    companion object {
        const val SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    }
}
