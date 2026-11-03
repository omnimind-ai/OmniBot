package cn.com.omnimind.bot.resource

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Shares resource files through the app's existing FileProvider. */
object ResourceFileIntents {
    private const val SHARED_EXPORT_DIR = "shared_exports"
    private const val MAX_SHARED_EXPORT_FILES = 24
    private const val SHARED_EXPORT_RETENTION_MS = 2L * 24L * 60L * 60L * 1000L

    /** Callers must grant read access to the returned URI on their outgoing intent. */
    fun contentUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return try {
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: IllegalArgumentException) {
            val stagedFile = stageFileInCache(context, file)
            FileProvider.getUriForFile(context, authority, stagedFile)
        }
    }

    private fun stageFileInCache(context: Context, sourceFile: File): File {
        val exportDir = File(context.cacheDir, SHARED_EXPORT_DIR)
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
        cleanupSharedExports(exportDir)

        val safeName = sourceFile.name.ifBlank { "shared_file" }
        val prefix = System.currentTimeMillis().toString()
        val stagedFile = File(exportDir, "${prefix}_$safeName")
        sourceFile.inputStream().use { input ->
            stagedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return stagedFile
    }

    private fun cleanupSharedExports(directory: File) {
        val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        val now = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            val expired = now - file.lastModified() > SHARED_EXPORT_RETENTION_MS
            if (expired || index >= MAX_SHARED_EXPORT_FILES) {
                runCatching { file.delete() }
            }
        }
    }
}
