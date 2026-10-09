package cn.com.omnimind.bot.ui.channel

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.AssistsUtil
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File

class FileSaveChannel {
    companion object {
        private const val TAG = "FileSaveChannel"
        private const val CHANNEL = "cn.com.omnimind.bot/file_save"
        private const val REQUEST_CODE_CREATE_DOCUMENT = 39121

        @Volatile
        private var pendingResult: MethodChannel.Result? = null

        @Volatile
        private var pendingSourcePath: String? = null

        fun onActivityResult(
            activity: Activity,
            requestCode: Int,
            resultCode: Int,
            data: Intent?
        ): Boolean {
            if (requestCode != REQUEST_CODE_CREATE_DOCUMENT) return false

            val result = pendingResult
            val sourcePath = pendingSourcePath
            pendingResult = null
            pendingSourcePath = null

            if (result == null) return true

            if (resultCode != Activity.RESULT_OK) {
                result.success(null)
                return true
            }

            val targetUri = data?.data
            if (targetUri == null) {
                result.error("SAVE_FAILED", "Target uri is null", null)
                return true
            }
            if (sourcePath.isNullOrBlank()) {
                result.error("SAVE_FAILED", "Source path is null", null)
                return true
            }

            try {
                val source = File(sourcePath)
                if (!source.exists()) {
                    result.error("SAVE_FAILED", "Source file missing", sourcePath)
                    return true
                }
                if (source.length() <= 0L) {
                    result.error("SAVE_FAILED", "Source file is empty", sourcePath)
                    return true
                }
                copyFileToUri(activity, source, targetUri)
                result.success(targetUri.toString())
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to save file", e)
                result.error("SAVE_FAILED", e.message, e.toString())
            }

            return true
        }

        private fun copyFileToUri(context: Context, source: File, targetUri: Uri) {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Cannot open output stream for target uri")
        }
    }

    private var channel: MethodChannel? = null
    private var context: Context? = null

    private fun chooserTitle(zh: String, en: String): String {
        val safeContext = context
        return if (safeContext != null && AppLocaleManager.isEnglish(safeContext)) en else zh
    }

    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "saveFileWithSystemDialog" -> saveFileWithSystemDialog(call, result)
                "openFile" -> openFile(call, result)
                "openInBrowser" -> openInBrowser(call, result)
                "shareFile" -> shareFile(call, result)
                "shareText" -> shareText(call, result)
                else -> result.notImplemented()
            }
        }
    }

    private fun openFile(call: MethodCall, result: MethodChannel.Result) =
        withSource(call, result, "OPEN_FAILED") { activity, args, sourcePath ->
            SharedFileIntents.open(activity, sourcePath, args["mimeType"] as? String ?: "*/*")
        }

    private fun openInBrowser(call: MethodCall, result: MethodChannel.Result) =
        withSource(call, result, "OPEN_FAILED") { activity, args, sourcePath ->
            SharedFileIntents.openInBrowser(activity, sourcePath, args["mimeType"] as? String ?: "text/html")
        }

    private fun shareFile(call: MethodCall, result: MethodChannel.Result) =
        withSource(call, result, "SHARE_FAILED") { activity, args, sourcePath ->
            SharedFileIntents.share(activity, sourcePath, args["fileName"] as? String, args["mimeType"] as? String ?: "*/*")
        }

    /** Validates `sourcePath` and maps a [SharedFileIntents.Outcome] to the channel's existing error codes. */
    private fun withSource(
        call: MethodCall,
        result: MethodChannel.Result,
        failureCode: String,
        block: (Activity, Map<*, *>, String) -> SharedFileIntents.Outcome,
    ) {
        val activity = context as? Activity
        if (activity == null) {
            result.error("INIT_FAILED", "Not attached to activity", null)
            return
        }
        val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
        val sourcePath = args["sourcePath"] as? String
        if (sourcePath.isNullOrBlank()) {
            result.error("INVALID_ARGS", "sourcePath is required", null)
            return
        }
        when (val outcome = block(activity, args, sourcePath)) {
            SharedFileIntents.Outcome.Started -> result.success(true)
            SharedFileIntents.Outcome.Missing -> result.error("INVALID_ARGS", "sourcePath does not exist", sourcePath)
            SharedFileIntents.Outcome.NoHandler -> result.error(failureCode, "No application can handle this file", null)
            is SharedFileIntents.Outcome.Failed -> {
                OmniLog.e(TAG, "File hand-off failed", outcome.error)
                result.error(failureCode, outcome.error.message, outcome.error.toString())
            }
        }
    }

    private fun shareText(call: MethodCall, result: MethodChannel.Result) {
        val activity = context as? Activity
        if (activity == null) {
            result.error("INIT_FAILED", "Not attached to activity", null)
            return
        }
        val args = call.arguments as? Map<*, *>
        val text = args?.get("text") as? String
        if (text.isNullOrBlank()) {
            result.error("INVALID_ARGS", "text is required", null)
            return
        }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!hasIntentHandler(activity, intent)) {
                result.error("SHARE_FAILED", "No application can share text", null)
                return
            }
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    chooserTitle("发送文本", "Share Text")
                )
            )
            result.success(true)
        } catch (e: Exception) {
            OmniLog.e(TAG, "Failed to share text", e)
            result.error("SHARE_FAILED", e.message, e.toString())
        }
    }

    private fun saveFileWithSystemDialog(call: MethodCall, result: MethodChannel.Result) {
        val activity = context as? Activity
        if (activity == null) {
            result.error("INIT_FAILED", "Not attached to activity", null)
            return
        }
        if (pendingResult != null) {
            result.error("BUSY", "Another save operation is in progress", null)
            return
        }

        val args = call.arguments as? Map<*, *>
        val sourcePath = args?.get("sourcePath") as? String
        val fileName = args?.get("fileName") as? String ?: "attachment"
        val mimeType = args?.get("mimeType") as? String ?: "*/*"

        if (sourcePath.isNullOrBlank()) {
            result.error("INVALID_ARGS", "sourcePath is required", null)
            return
        }

        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            result.error("INVALID_ARGS", "sourcePath does not exist", sourcePath)
            return
        }

        // When chat half-screen is showing, avoid opening a system activity to prevent
        // half-screen being dismissed after returning from the picker.
        if (AssistsUtil.UI.isChatBotDialogShowing()) {
            try {
                val savedUri = saveFileDirectlyToDownloads(activity, sourceFile, fileName, mimeType)
                if (savedUri != null) {
                    result.success(savedUri.toString())
                    return
                }
            } catch (e: Exception) {
                OmniLog.e(TAG, "Direct save failed, fallback to system dialog", e)
            }
        }

        pendingResult = result
        pendingSourcePath = sourcePath

        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimeType.isBlank()) "*/*" else mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            activity.startActivityForResult(intent, REQUEST_CODE_CREATE_DOCUMENT)
        } catch (e: Exception) {
            pendingResult = null
            pendingSourcePath = null
            result.error("INIT_FAILED", e.message, e.toString())
        }
    }

    private fun saveFileDirectlyToDownloads(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): Uri? {
        val resolver = context.contentResolver
        val safeMimeType = if (mimeType.isBlank()) "application/octet-stream" else mimeType
        val safeName = fileName.ifBlank { "attachment" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, safeMimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val targetUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null

            try {
                resolver.openOutputStream(targetUri, "w")?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open output stream for target uri")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(targetUri, values, null, null)
                return targetUri
            } catch (e: Exception) {
                resolver.delete(targetUri, null, null)
                throw e
            }
        }

        return null
    }

    private fun hasIntentHandler(context: Context, intent: Intent): Boolean {
        return context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ).isNotEmpty()
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
