package cn.com.omnimind.bot.preferences

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import java.io.DataInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

/** Derives small display previews without changing the source used by the overlay runtime. */
internal class PetPreviewRenderer(private val workspaceRoot: File) {
    fun preview(source: File): File? = when (source.extension.lowercase(Locale.ROOT)) {
        "png", "webp", "jpg", "jpeg", "gif" -> {
            if (!usableImage(source)) null
            else if (isAtlas(source)) rasterAtlas(source) ?: source
            else source
        }
        "svg" -> rasterSvg(source)
        "html", "htm" -> previewHtml(source)
        else -> null
    }

    fun usableImage(file: File): Boolean {
        if (!file.isFile || file.length() !in 12..MAX_SOURCE_BYTES) return false
        val name = file.name.lowercase(Locale.ROOT)
        if (name.endsWith(".omnibot-preview.png")) return false
        val header = runCatching { file.inputStream().use { input ->
            ByteArray(12).also { DataInputStream(input).readFully(it) }
        } }.getOrNull() ?: return false
        val valid = when (file.extension.lowercase(Locale.ROOT)) {
            "png" -> header.size >= 8 && header.take(8).toByteArray().contentEquals(PNG_HEADER)
            "jpg", "jpeg" -> header.size >= 3 && header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
            "webp" -> header.size >= 12 && header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                header.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            "gif" -> header.size >= 6 && header.copyOfRange(0, 3).contentEquals("GIF".toByteArray())
            else -> false
        }
        if (!valid) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    fun usableSvg(file: File): Boolean = file.isFile && file.length() in 12..MAX_SVG_BYTES &&
        runCatching { file.inputStream().bufferedReader().use { it.readText().take(1024).contains("<svg", true) } }
            .getOrDefault(false)

    fun isAtlas(file: File): Boolean = file.name.lowercase(Locale.ROOT) in setOf(
        "spritesheet.webp", "spritesheet.png", "atlas.webp", "atlas.png",
    )

    fun previewHtml(htmlFile: File): File? {
        if (!htmlFile.isFile || htmlFile.length() !in 1..MAX_HTML_BYTES) return null
        val html = runCatching { htmlFile.readText() }.getOrNull() ?: return null
        val references = Regex("(?:src|href)=[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
        for (match in references.findAll(html)) {
            val value = match.groupValues[1].substringBefore('#').substringBefore('?')
            if (value.startsWith("http:", true) || value.startsWith("https:", true) || value.startsWith("data:", true)) continue
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: continue
            val file = File(htmlFile.parentFile, decoded)
            if (!insideWorkspace(file)) continue
            if (file.extension.lowercase(Locale.ROOT) !in setOf("png", "webp", "jpg", "jpeg", "gif", "svg")) continue
            preview(file)?.let { return it }
        }
        val inlineSvg = Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
        val svgFile = File(htmlFile.path + ".omnibot-preview.svg")
        return runCatching {
            if (!svgFile.isFile || svgFile.readText() != inlineSvg) svgFile.writeText(inlineSvg)
            svgFile.setLastModified(htmlFile.lastModified())
            rasterSvg(svgFile)
        }.getOrNull()
    }

    private fun rasterAtlas(source: File): File? = cachedOrRender(source) { bitmap ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth != 1536 || bounds.outHeight !in setOf(1872, 2288)) return@cachedOrRender false
        val atlas = BitmapFactory.decodeFile(source.path) ?: return@cachedOrRender false
        try {
            val target = RectF(20f, 0f, 492f, 512f)
            Canvas(bitmap).drawBitmap(atlas, Rect(0, 0, 192, 208), target, Paint(Paint.FILTER_BITMAP_FLAG))
            true
        } finally { atlas.recycle() }
    }

    private fun rasterSvg(source: File): File? {
        if (!usableSvg(source)) return null
        return cachedOrRender(source) { bitmap ->
            source.inputStream().use { input ->
                val picture = SVG.getFromInputStream(input).renderToPicture(PREVIEW_SIZE, PREVIEW_SIZE)
                Canvas(bitmap).drawPicture(picture)
            }
            true
        }
    }

    private fun cachedOrRender(source: File, render: (Bitmap) -> Boolean): File? {
        if (!insideWorkspace(source)) return null
        val output = File(source.path + ".omnibot-preview.png")
        if (output.isFile && output.lastModified() >= source.lastModified() && validGeneratedPreview(output)) return output
        val bitmap = Bitmap.createBitmap(PREVIEW_SIZE, PREVIEW_SIZE, Bitmap.Config.ARGB_8888)
        val temp = File(source.parentFile, ".${output.name}.${UUID.randomUUID()}.tmp")
        return try {
            if (!render(bitmap)) return null
            temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            try {
                Files.move(temp.toPath(), output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            output.setLastModified(source.lastModified())
            output
        } catch (_: Exception) { if (validGeneratedPreview(output)) output else null }
        finally { bitmap.recycle(); temp.delete() }
    }

    private fun validGeneratedPreview(file: File): Boolean = file.isFile && file.length() >= 12 &&
        runCatching { BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth > 0 && it.outHeight > 0 } }
            .getOrDefault(false)

    private fun insideWorkspace(file: File): Boolean = runCatching {
        val workspace = workspaceRoot.canonicalFile
        file.canonicalFile.path.startsWith(workspace.path + File.separator)
    }.getOrDefault(false)

    private companion object {
        const val PREVIEW_SIZE = 512
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        const val MAX_SVG_BYTES = 2L * 1024 * 1024
        const val MAX_HTML_BYTES = 2L * 1024 * 1024
        val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
