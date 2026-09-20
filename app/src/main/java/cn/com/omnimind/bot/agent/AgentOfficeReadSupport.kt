package cn.com.omnimind.bot.agent

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Text projection of OOXML using platform ZIP/SAX, never executing macros or formulas. */
internal object AgentOfficeReadSupport {
    private const val LIMIT = 16 * 1024 * 1024
    private const val WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val SHEET = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    fun accepts(file: File) = file.extension.lowercase() in setOf("docx", "xlsx")

    suspend fun read(file: File, offset: Long, lineStart: Int?, lineCount: Int?,
                     maxChars: Int): Map<String, Any?> {
        require(maxChars in 2..AgentFileReadSupport.PAGE_CHARS)
        if (file.length() > 64L * 1024 * 1024) return failure("document_too_large", "Office 文件超过内置解析大小限制。")
        val coroutine = currentCoroutineContext()
        val output = StringBuilder()
        var contentFound = false
        fun append(value: String) {
            coroutine.ensureActive()
            if (output.length.toLong() + value.length > LIMIT) throw LimitExceeded()
            output.append(value)
        }
        try {
            ZipFile(file).use { zip ->
                if (zip.size() > 10000) throw LimitExceeded()
                val names = zip.entries().asSequence().map { it.name }.toList()
                require(names.size == names.toSet().size) { "duplicate package members" }
                var bytesRead = 0L
                fun xml(path: String, handler: DefaultHandler) {
                    coroutine.ensureActive()
                    val entry = zip.getEntry(path) ?: throw IOException("missing OOXML part")
                    if (entry.size > LIMIT) throw LimitExceeded()
                    val factory = SAXParserFactory.newInstance().apply {
                        isNamespaceAware = true
                        setFeature("http://xml.org/sax/features/external-general-entities", false)
                        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    }
                    val reader = factory.newSAXParser().xmlReader
                    reader.setProperty("http://xml.org/sax/properties/lexical-handler",
                        object : org.xml.sax.ext.DefaultHandler2() {
                            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                                throw SAXException("DOCTYPE is not allowed")
                            }
                        })
                    reader.contentHandler = handler
                    reader.errorHandler = handler
                    reader.setEntityResolver { _, _ -> throw SAXException("External entities are not allowed") }
                    zip.getInputStream(entry).use { raw ->
                        val bounded = object : FilterInputStream(raw) {
                            private fun count(n: Int): Int {
                                coroutine.ensureActive()
                                if (n > 0) bytesRead += n
                                if (bytesRead > 64L * 1024 * 1024) throw LimitExceeded()
                                return n
                            }
                            override fun read(): Int = super.read().also { count(if (it < 0) 0 else 1) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = count(`in`.read(b, off, len))
                        }
                        reader.parse(InputSource(bounded))
                    }
                }
                if (file.extension.equals("docx", true)) {
                    var inText = false
                    var deleted = 0
                    xml("word/document.xml", object : DefaultHandler() {
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            if (uri != WORD) return
                            if (local == "del" || local == "moveFrom") deleted++
                            if (deleted > 0) return
                            when (local) {
                                "t" -> inText = true
                                "tab" -> append("\t")
                                "br", "cr" -> append("\n")
                            }
                        }
                        override fun characters(ch: CharArray, start: Int, length: Int) {
                            if (inText && deleted == 0) {
                                val text = String(ch, start, length)
                                if (text.isNotBlank()) contentFound = true
                                append(text)
                            }
                        }
                        override fun endElement(uri: String, local: String, q: String) {
                            if (uri != WORD) return
                            if (local == "del" || local == "moveFrom") { deleted--; return }
                            if (deleted > 0) return
                            when (local) {
                                "t" -> inText = false
                                "p", "tr" -> append("\n")
                                "tc" -> append("\t")
                            }
                        }
                    })
                } else {
                    data class Relationship(val target: String, val type: String, val external: Boolean)
                    val relations = mutableMapOf<String, Relationship>()
                    xml("xl/_rels/workbook.xml.rels", object : DefaultHandler() {
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            if (uri == PACKAGE_REL && local == "Relationship") {
                                val id = a.getValue("Id") ?: throw SAXException("missing relationship id")
                                require(id !in relations) { "duplicate relationship" }
                                relations[id] = Relationship(a.getValue("Target") ?: "", a.getValue("Type") ?: "",
                                    a.getValue("TargetMode") == "External")
                            }
                        }
                    })
                    fun part(r: Relationship): String {
                        require(!r.external) { "external workbook part" }
                        val resolved = URI("/xl/workbook.xml").resolve(r.target).normalize()
                        require(!resolved.isAbsolute && resolved.authority == null && resolved.query == null && resolved.fragment == null) { "external part" }
                        val path = resolved.path.removePrefix("/")
                        require(path.startsWith("xl/") && !path.contains("\\") && !path.contains("..")) { "invalid workbook part" }
                        return path
                    }
                    val strings = mutableListOf<String>()
                    relations.values.firstOrNull { it.type == "$REL/sharedStrings" }?.let { shared ->
                        var text = StringBuilder()
                        var inText = false
                        var phoneticDepth = 0
                        xml(part(shared), object : DefaultHandler() {
                            override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                                if (uri != SHEET) return
                                if (local == "si") text = StringBuilder()
                                if (local == "rPh") phoneticDepth++
                                if (local == "t" && phoneticDepth == 0) inText = true
                            }
                            override fun characters(ch: CharArray, start: Int, length: Int) {
                                if (inText) text.append(ch, start, length)
                            }
                            override fun endElement(uri: String, local: String, q: String) {
                                if (uri != SHEET) return
                                if (local == "t") inText = false
                                if (local == "rPh") phoneticDepth--
                                if (local == "si") strings.add(text.toString())
                            }
                        })
                    }
                    val sheets = mutableListOf<Pair<String, String>>()
                    xml("xl/workbook.xml", object : DefaultHandler() {
                        override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                            if (uri == SHEET && local == "sheet") sheets.add(
                                (a.getValue("name") ?: "") to (a.getValue(REL, "id") ?: ""))
                        }
                    })
                    require(sheets.isNotEmpty()) { "missing worksheets" }
                    for ((name, id) in sheets) {
                        val relation = relations[id] ?: throw IOException("missing worksheet relationship")
                        require(relation.type == "$REL/worksheet") { "unsupported sheet type" }
                        append("[工作表: $name]\n")
                        var address = ""; var type = ""; var field = ""; var style = ""
                        var value = StringBuilder(); var formula = StringBuilder(); var hasFormula = false
                        xml(part(relation), object : DefaultHandler() {
                            override fun startElement(uri: String, local: String, q: String, a: Attributes) {
                                if (uri != SHEET) return
                                when (local) {
                                    "c" -> { address = a.getValue("r") ?: "?"; type = a.getValue("t") ?: "n"
                                        style = a.getValue("s") ?: "0"; value = StringBuilder(); formula = StringBuilder(); hasFormula = false }
                                    "v", "t", "f" -> { field = local; if (local == "f") hasFormula = true }
                                }
                            }
                            override fun characters(ch: CharArray, start: Int, length: Int) {
                                when (field) { "v", "t" -> value.append(ch, start, length); "f" -> formula.append(ch, start, length) }
                            }
                            override fun endElement(uri: String, local: String, q: String) {
                                if (uri != SHEET) return
                                if (local in setOf("v", "t", "f")) field = ""
                                if (local != "c") return
                                val raw = value.toString()
                                val rendered = when (type) {
                                    "s" -> strings.getOrNull(raw.toIntOrNull() ?: -1) ?: throw SAXException("invalid shared string index")
                                    "b" -> when (raw) { "1" -> "true"; "0" -> "false"; else -> throw SAXException("invalid boolean") }
                                    else -> raw
                                }
                                if (rendered.isNotBlank() || formula.isNotBlank()) contentFound = true
                                append("$address [type=$type, style=$style]: $rendered")
                                if (hasFormula) append(" [公式=$formula; 值为文件缓存，未重新计算]")
                                append("\n")
                            }
                        })
                    }
                }
            }
            if (!contentFound) return failure("document_no_text", "文档没有可提取正文；图片或嵌入对象未解析。")
            return AgentFileReadSupport.readPage(StringReader(output.toString()), offset, lineStart, lineCount, maxChars).toPayload() + mapOf(
                "contentAvailable" to true, "parser" to "ooxml-text/1",
                "extractionNote" to "仅正文文字/单元格。未解析图片、图表、页眉页脚与嵌入对象；Excel 数值为原始存储值，日期/百分比样式未转换，公式未计算。",
                "continuation" to "使用原附件路径及 nextOffset 继续读取提取文字。",
            )
        } catch (e: LimitExceeded) {
            return failure("document_too_large", "Office 解压或提取内容超过内置限制；未返回部分内容作为完整正文。")
        } catch (e: IOException) {
            return failure("document_parse_failed", "Office 解析失败，文件可能损坏、加密或不是受支持的 DOCX/XLSX。")
        } catch (e: SAXException) {
            return failure("document_parse_failed", "Office XML 无法安全解析；正文未读取。")
        } catch (e: IllegalArgumentException) {
            return failure("document_parse_failed", "Office 结构或引用不受支持；正文未读取。")
        }
    }
    private class LimitExceeded : IOException()
    private fun failure(code: String, message: String) = mapOf(
        "kind" to "document", "contentAvailable" to false, "errorCode" to code, "message" to message)
}
