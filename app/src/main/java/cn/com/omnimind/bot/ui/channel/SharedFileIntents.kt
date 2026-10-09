package cn.com.omnimind.bot.ui.channel

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import java.io.File
import java.util.Locale

/**
 * Hands a local file to another app through a read-only FileProvider uri.
 * Shared by the Flutter `file_save` channel and the native workspace pages
 * (batch 5e-8a), so both stage files the same way. Files outside the provider
 * roots (the workspace lives under the app data dir) are copied into a
 * bounded cache folder first; the private path never leaves the app.
 */
internal object SharedFileIntents {
    private const val SHARED_EXPORT_DIR = "shared_exports"
    private const val MAX_SHARED_EXPORT_FILES = 24
    private const val SHARED_EXPORT_RETENTION_MS = 2L * 24L * 60L * 60L * 1000L

    sealed interface Outcome {
        data object Started : Outcome
        data object Missing : Outcome
        data object NoHandler : Outcome
        data class Failed(val error: Exception) : Outcome
    }

    fun open(activity: Activity, sourcePath: String, mimeType: String?): Outcome = launch(sourcePath) { file ->
        val uri = contentUri(activity, file)
        var type = resolveMimeType(file, mimeType)
        var intent = viewIntent(activity, uri, type, file.name)
        if (!hasHandler(activity, intent) && type != "*/*") {
            type = "*/*"
            intent = viewIntent(activity, uri, type, file.name)
        }
        if (!hasHandler(activity, intent)) return@launch Outcome.NoHandler
        grantToResolvers(activity, intent, uri)
        activity.startActivity(Intent.createChooser(intent, chooserTitle(activity, "打开文件", "Open File")).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(activity.contentResolver, file.name, uri)
        })
        Outcome.Started
    }

    /** Resolves the browser with an https probe, then gives it the same read-only uri. */
    fun openInBrowser(activity: Activity, sourcePath: String, mimeType: String?): Outcome = launch(sourcePath) { file ->
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = activity.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (browser.isNullOrBlank()) return@launch Outcome.NoHandler
        val uri = contentUri(activity, file)
        val intent = viewIntent(activity, uri, resolveMimeType(file, mimeType ?: "text/html"), file.name)
            .setPackage(browser)
        grantToResolvers(activity, intent, uri)
        activity.startActivity(intent)
        Outcome.Started
    }

    fun share(activity: Activity, sourcePath: String, fileName: String?, mimeType: String?): Outcome = launch(sourcePath) { file ->
        val uri = contentUri(activity, file)
        val title = fileName ?: file.name
        var type = resolveMimeType(file, mimeType)
        var intent = shareIntent(activity, uri, type, title)
        if (!hasHandler(activity, intent) && type != "*/*") {
            type = "*/*"
            intent = shareIntent(activity, uri, type, title)
        }
        if (!hasHandler(activity, intent)) return@launch Outcome.NoHandler
        grantToResolvers(activity, intent, uri)
        activity.startActivity(Intent.createChooser(intent, chooserTitle(activity, "分享文件", "Share File")).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(activity.contentResolver, title, uri)
        })
        Outcome.Started
    }

    private inline fun launch(sourcePath: String, block: (File) -> Outcome): Outcome {
        val file = File(sourcePath)
        if (sourcePath.isBlank() || !file.exists()) return Outcome.Missing
        return try {
            block(file)
        } catch (e: Exception) {
            Outcome.Failed(e)
        }
    }

    private fun chooserTitle(context: Context, zh: String, en: String) =
        if (AppLocaleManager.isEnglish(context)) en else zh

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private fun contentUri(context: Context, file: File): Uri = try {
        FileProvider.getUriForFile(context, authority(context), file)
    } catch (_: IllegalArgumentException) {
        FileProvider.getUriForFile(context, authority(context), stageInCache(context, file))
    }

    private fun stageInCache(context: Context, source: File): File {
        val dir = File(context.cacheDir, SHARED_EXPORT_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
            if (now - file.lastModified() > SHARED_EXPORT_RETENTION_MS || index >= MAX_SHARED_EXPORT_FILES) {
                runCatching { file.delete() }
            }
        }
        val staged = File(dir, "${now}_${source.name.ifBlank { "shared_file" }}")
        source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
        return staged
    }

    private fun resolveMimeType(file: File, preferred: String?): String {
        val trimmed = preferred?.trim().orEmpty()
        if (trimmed.isNotEmpty() && trimmed != "*/*") return trimmed
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.US)) ?: "*/*"
    }

    private fun viewIntent(context: Context, uri: Uri, type: String, label: String) =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newUri(context.contentResolver, label, uri)
        }

    private fun shareIntent(context: Context, uri: Uri, type: String, title: String) =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
        }

    private fun hasHandler(context: Context, intent: Intent) =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()

    private fun grantToResolvers(context: Context, intent: Intent, uri: Uri) {
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
