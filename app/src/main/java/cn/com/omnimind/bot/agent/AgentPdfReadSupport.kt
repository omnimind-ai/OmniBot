package cn.com.omnimind.bot.agent

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FilterWriter
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local extraction only; the original attachment remains the source of truth. */
internal object AgentPdfReadSupport {
    fun accepts(file: File, mime: String) = file.extension.equals("pdf", true) ||
        mime.substringBefore(';').equals("application/pdf", true)

    suspend fun read(context: Context, file: File, offset: Long, lineStart: Int?,
                     lineCount: Int?, maxChars: Int): Map<String, Any?> {
        PDFBoxResourceLoader.init(context.applicationContext)
        return read(file, context.cacheDir, offset, lineStart, lineCount, maxChars)
    }

    internal suspend fun read(file: File, cache: File, offset: Long = 0,
                              lineStart: Int? = null, lineCount: Int? = null,
                              maxChars: Int = AgentFileReadSupport.PAGE_CHARS): Map<String, Any?> {
        require(maxChars in 2..AgentFileReadSupport.PAGE_CHARS)
        if (file.length() > 64L * 1024 * 1024) return failure("document_too_large", "PDF 超过内置解析大小限制，请使用文档解析工具。")
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val text = File.createTempFile("pdf-read-", ".txt", cache)
        try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly(128L * 1024 * 1024)
                .setTempDir(cache)).use { document ->
                if (!document.currentAccessPermission.canExtractContent())
                    return failure("document_access_denied", "PDF 不允许提取正文。")
                var characters = 0L
                text.bufferedWriter().use { target ->
                    val bounded = object : FilterWriter(target) {
                        private fun account(length: Int) {
                            coroutine.ensureActive()
                            characters += length
                            if (characters > 16L * 1024 * 1024) throw ExtractionLimit()
                        }
                        override fun write(c: Int) { account(1); out.write(c) }
                        override fun write(c: CharArray, off: Int, len: Int) { account(len); out.write(c, off, len) }
                        override fun write(s: String, off: Int, len: Int) { account(len); out.write(s, off, len) }
                    }
                    PDFTextStripper().apply { sortByPosition = true }.writeText(document, bounded)
                }
                coroutine.ensureActive()
                if (text.bufferedReader().use { reader ->
                    val buffer = CharArray(8192)
                    var hasText = false
                    while (!hasText) {
                        coroutine.ensureActive()
                        val n = reader.read(buffer)
                        if (n < 0) break
                        hasText = (0 until n).any { !buffer[it].isWhitespace() }
                    }
                    !hasText
                }) return failure("document_ocr_required", "PDF 没有可提取文字，可能是扫描件或空白文档；需要 OCR，尚未读取正文。")
                return AgentFileReadSupport.read(text, offset, lineStart, lineCount, maxChars).toPayload() + mapOf(
                    "contentAvailable" to true, "parser" to "pdfbox-android/2.0.27.0",
                    "pageCount" to document.numberOfPages,
                    "extractionNote" to "提取文字层，不包含图片理解；扫描页可能没有正文，复杂表格与多栏排版需核对原件。",
                    "continuation" to "使用同一 PDF 路径及 nextOffset 继续读取提取文字。原文件保持完整。",
                )
            }
        } catch (e: InvalidPasswordException) {
            return failure("document_password_required", "PDF 受密码保护，未读取正文。请提供可读取的副本。")
        } catch (e: ExtractionLimit) {
            return failure("document_too_large", "PDF 提取文字超过内置解析限制；未返回截断内容作为完整结果。")
        } catch (e: IOException) {
            return failure("document_parse_failed", "PDF 解析失败，文件可能损坏或格式不受支持；原文件保留。")
        } finally {
            text.delete()
        }
    }

    private class ExtractionLimit : IOException()
    private fun failure(code: String, message: String): Map<String, Any?> = mapOf(
        "kind" to "document", "contentAvailable" to false, "errorCode" to code,
        "message" to message,
    )
}
