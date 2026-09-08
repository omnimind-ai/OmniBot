package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ExternalApkInstallResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val filePath: String? = null
)

object ExternalApkInstaller {
    private const val TAG = "ExternalApkInstaller"
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"
    private const val DOWNLOAD_DIR_NAME = "external_apk"
    private const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "app_update_download"
    private const val DOWNLOAD_NOTIFICATION_CHANNEL_NAME = "应用更新下载"
    private const val DOWNLOAD_NOTIFICATION_ID = 1102

    const val STATUS_INSTALLER_LAUNCHED = "installer_launched"
    const val STATUS_INSTALL_PERMISSION_REQUIRED = "install_permission_required"
    const val STATUS_DOWNLOAD_FAILED = "download_failed"
    const val STATUS_INSTALL_FAILED = "install_failed"

    private fun fileProviderAuthority(context: Context): String {
        return "${context.packageName}$FILE_PROVIDER_AUTHORITY_SUFFIX"
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        apkFileName: String,
        displayName: String
    ): ExternalApkInstallResult {
        val appContext = context.applicationContext
        val notifier = UpdateDownloadNotifier(appContext, displayName)
        if (!canInstallPackages(appContext)) {
            withContext(Dispatchers.Main) {
                openInstallPermissionSettings(context)
            }
            return ExternalApkInstallResult(
                success = false,
                status = STATUS_INSTALL_PERMISSION_REQUIRED,
                message = "请先允许本应用安装未知应用，然后再次点击安装 $displayName。"
            )
        }

        notifier.showStarting()
        val existingApk = existingDownloadedApk(appContext, apkFileName)
        val apkFile = existingApk ?: downloadApk(
            context = appContext,
            downloadUrl = downloadUrl,
            apkFileName = apkFileName,
            notifier = notifier
        ) ?: run {
            notifier.showFailed("$displayName 安装包下载失败，请稍后重试。")
            return ExternalApkInstallResult(
                success = false,
                status = STATUS_DOWNLOAD_FAILED,
                message = "$displayName 安装包下载失败，请稍后重试。"
            )
        }

        notifier.showCompleted(apkFile)
        val launched = withContext(Dispatchers.Main) {
            installApk(context, apkFile)
        }
        return if (launched) {
            notifier.showCompleted(apkFile)
            ExternalApkInstallResult(
                success = true,
                status = STATUS_INSTALLER_LAUNCHED,
                message = "$displayName 安装包已下载完成，已打开系统安装界面。",
                filePath = apkFile.absolutePath
            )
        } else {
            notifier.showFailed(
                "$displayName 安装包已下载完成，但无法打开系统安装界面。",
                apkFile = apkFile
            )
            ExternalApkInstallResult(
                success = false,
                status = STATUS_INSTALL_FAILED,
                message = "$displayName 安装包已下载完成，但无法打开系统安装界面。",
                filePath = apkFile.absolutePath
            )
        }
    }

    private fun existingDownloadedApk(context: Context, apkFileName: String): File? {
        val apkFile = File(File(context.filesDir, DOWNLOAD_DIR_NAME), apkFileName)
        return apkFile.takeIf { it.exists() && it.length() > 0L }
    }

    private suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        apkFileName: String,
        notifier: UpdateDownloadNotifier
    ): File? = withContext(Dispatchers.IO) {
        val downloadDir = File(context.filesDir, DOWNLOAD_DIR_NAME)
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            OmniLog.e(TAG, "Failed to create external apk directory: ${downloadDir.absolutePath}")
            return@withContext null
        }

        val apkFile = File(downloadDir, apkFileName)
        val tempFile = File(downloadDir, "$apkFileName.download")

        runCatching {
            downloadDir.listFiles()?.forEach { file ->
                if (file != apkFile && file != tempFile) {
                    file.delete()
                }
            }
        }

        return@withContext try {
            val request = OkHttpManager.newBuilder()
                .url(downloadUrl)
                .get()
                .build()
            OkHttpManager.enqueue(request).use { response ->
                if (!response.isSuccessful) {
                    OmniLog.e(TAG, "Download apk failed with code: ${response.code}")
                    return@use null
                }

                val body = response.body ?: return@use null
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                tempFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            notifier.updateProgress(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            )
                        }
                        output.flush()
                    }
                }
                notifier.showDownloadFinished()

                if (apkFile.exists()) {
                    apkFile.delete()
                }
                if (!tempFile.renameTo(apkFile)) {
                    FileOutputStream(apkFile).use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                }
                notifier.showCompleted(apkFile)
                apkFile
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Download external apk failed", e)
            null
        } finally {
            if (tempFile.exists() && tempFile.length() == 0L) {
                tempFile.delete()
            }
        }
    }

    private fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) {
                OmniLog.e(TAG, "APK file does not exist: ${apkFile.absolutePath}")
                false
            } else {
                val intent = buildInstallIntent(context, apkFile).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Launch package installer failed", e)
            false
        }
    }

    private fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, fileProviderAuthority(context), apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildInstallPendingIntent(context: Context, apkFile: File): PendingIntent? {
        if (!apkFile.exists()) {
            return null
        }
        val requestCode = apkFile.absolutePath.hashCode().and(Int.MAX_VALUE)
        return PendingIntent.getActivity(
            context,
            requestCode,
            buildInstallIntent(context, apkFile),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private class UpdateDownloadNotifier(
        private val context: Context,
        private val displayName: String
    ) {
        private val notificationManager = NotificationManagerCompat.from(context)
        private var lastProgress = -1
        private var hasShownIndeterminateProgress = false

        init {
            createChannelIfNeeded()
        }

        fun showStarting() {
            hasShownIndeterminateProgress = true
            notify(
                baseBuilder()
                    .setContentText("正在下载最新版本")
                    .setProgress(0, 0, true)
                    .setOngoing(true)
            )
        }

        fun updateProgress(downloadedBytes: Long, totalBytes: Long) {
            if (totalBytes <= 0L) {
                if (hasShownIndeterminateProgress) {
                    return
                }
                hasShownIndeterminateProgress = true
                notify(
                    baseBuilder()
                        .setContentText("正在下载最新版本")
                        .setProgress(0, 0, true)
                        .setOngoing(true)
                )
                return
            }

            hasShownIndeterminateProgress = false
            val progress = ((downloadedBytes * 100) / totalBytes)
                .toInt()
                .coerceIn(0, 100)
            if (progress == lastProgress) {
                return
            }
            lastProgress = progress
            notify(
                baseBuilder()
                    .setContentText("已下载 $progress%")
                    .setProgress(100, progress, false)
                    .setOngoing(progress < 100)
            )
        }

        fun showDownloadFinished() {
            hasShownIndeterminateProgress = false
            lastProgress = 100
            notify(
                baseBuilder()
                    .setContentText("已下载 100%")
                    .setProgress(100, 100, false)
                    .setOngoing(false)
            )
        }

        fun showCompleted(apkFile: File) {
            val pendingIntent = buildInstallPendingIntent(context, apkFile)
            val builder = baseBuilder()
                .setContentText("下载完成，点击继续安装")
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
                builder.addAction(R.mipmap.ic_launcher, "立即安装", pendingIntent)
            }
            notify(builder)
        }

        fun showFailed(message: String, apkFile: File? = null) {
            val pendingIntent = apkFile?.let { buildInstallPendingIntent(context, it) }
            val builder = baseBuilder()
                .setContentText(message)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
                builder.addAction(R.mipmap.ic_launcher, "再次安装", pendingIntent)
            }
            notify(builder)
        }

        private fun baseBuilder(): NotificationCompat.Builder {
            return NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$displayName 版本更新")
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        private fun notify(builder: NotificationCompat.Builder) {
            if (!canNotify()) {
                return
            }
            try {
                notificationManager.notify(DOWNLOAD_NOTIFICATION_ID, builder.build())
            } catch (e: SecurityException) {
                // Permission can be revoked after canNotify() succeeds.
                OmniLog.w(TAG, "Download notification permission unavailable: " + e.message)
            }
        }

        private fun canNotify(): Boolean {
            if (!notificationManager.areNotificationsEnabled()) {
                return false
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun createChannelIfNeeded() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                DOWNLOAD_NOTIFICATION_CHANNEL_ID,
                DOWNLOAD_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示应用更新下载进度"
            }
            manager.createNotificationChannel(channel)
        }
    }
}
