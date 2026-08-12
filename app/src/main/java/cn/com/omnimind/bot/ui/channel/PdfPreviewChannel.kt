package cn.com.omnimind.bot.ui.channel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File

class PdfPreviewChannel {
    companion object {
        private const val CHANNEL = "cn.com.omnimind.bot/pdf_preview"
        private const val TAG = "PdfPreviewChannel"
        private const val DEFAULT_RENDER_WIDTH_PX = 1080
        private const val MAX_RENDER_WIDTH_PX = 1800
    }

    private var context: Context? = null
    private var methodChannel: MethodChannel? = null

    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "getPdfInfo" -> getPdfInfo(call, result)
                "renderPdfPage" -> renderPdfPage(call, result)
                else -> result.notImplemented()
            }
        }
    }

    private fun getPdfInfo(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val ctx = context
        if (ctx == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        val source = call.argument<String>("path")?.trim().orEmpty()
        if (source.isBlank()) {
            result.error("INVALID_ARGS", "path is required", null)
            return
        }

        try {
            val payload = openRenderer(ctx, source).use { holder ->
                val pages = ArrayList<Map<String, Int>>(holder.renderer.pageCount)
                for (index in 0 until holder.renderer.pageCount) {
                    holder.renderer.openPage(index).use { page ->
                        pages += mapOf(
                            "width" to page.width,
                            "height" to page.height
                        )
                    }
                }
                mapOf(
                    "pageCount" to holder.renderer.pageCount,
                    "pages" to pages
                )
            }
            result.success(payload)
        } catch (error: Exception) {
            NativeChannelErrorPrivacy.deliver(
                result,
                TAG,
                "PDF_INFO_FAILED",
                error,
            )
        }
    }

    private fun renderPdfPage(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val ctx = context
        if (ctx == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        val source = call.argument<String>("path")?.trim().orEmpty()
        val pageIndex = call.argument<Int>("pageIndex")
        val targetWidthPx = call.argument<Int>("targetWidthPx")
            ?.coerceIn(240, MAX_RENDER_WIDTH_PX)
            ?: DEFAULT_RENDER_WIDTH_PX

        if (source.isBlank() || pageIndex == null) {
            result.error("INVALID_ARGS", "path and pageIndex are required", null)
            return
        }

        try {
            val payload = openRenderer(ctx, source).use { holder ->
                require(pageIndex in 0 until holder.renderer.pageCount) {
                    "pageIndex out of range"
                }
                holder.renderer.openPage(pageIndex).use { page ->
                    val scale = targetWidthPx.toFloat() / page.width.toFloat()
                    val bitmapWidth = targetWidthPx.coerceAtLeast(1)
                    val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
                    val renderMatrix = Matrix().apply {
                        setScale(scale, scale)
                    }
                    val bitmap = Bitmap.createBitmap(
                        bitmapWidth,
                        bitmapHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(Color.WHITE)
                    try {
                        page.render(
                            bitmap,
                            null,
                            renderMatrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            stream.toByteArray()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            result.success(payload)
        } catch (error: Exception) {
            NativeChannelErrorPrivacy.deliver(
                result,
                TAG,
                "PDF_RENDER_FAILED",
                error,
            )
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

    private fun openRenderer(
        context: Context,
        source: String
    ): RendererHolder {
        val descriptor = openDescriptor(context, source)
        return try {
            RendererHolder(
                descriptor = descriptor,
                renderer = PdfRenderer(descriptor)
            )
        } catch (error: Exception) {
            try {
                descriptor.close()
            } catch (_: Exception) {
                // Closing is best effort; do not replace the original failure.
            }
            throw error
        }
    }

    private fun openDescriptor(
        context: Context,
        source: String
    ): ParcelFileDescriptor {
        return when {
            source.startsWith("content://", ignoreCase = true) -> {
                context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
                    ?: error("Unable to open PDF content uri")
            }

            source.startsWith("file://", ignoreCase = true) -> {
                val file = File(Uri.parse(source).path ?: "")
                require(file.exists()) { "PDF file does not exist" }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            else -> {
                val file = File(source)
                require(file.exists()) { "PDF file does not exist" }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
    }

    private class RendererHolder(
        private val descriptor: ParcelFileDescriptor,
        val renderer: PdfRenderer
    ) : AutoCloseable {
        override fun close() {
            try {
                renderer.close()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
            try {
                descriptor.close()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
    }
}
