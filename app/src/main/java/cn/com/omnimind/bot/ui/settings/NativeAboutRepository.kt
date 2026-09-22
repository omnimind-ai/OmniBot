package cn.com.omnimind.bot.ui.settings

import android.content.Context
import cn.com.omnimind.bot.manager.AppPermissionAccess
import cn.com.omnimind.bot.manager.ExternalApkInstallResult
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.update.ApkDownloadSource
import cn.com.omnimind.nativeui.settings.AboutState
import cn.com.omnimind.nativeui.settings.UpdateDownloadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** One adapter over the update manager used by Flutter; no second downloader or update cache. */
internal class NativeAboutRepository(context: Context) {
    private val application = context.applicationContext
    private val permissions = AppPermissionAccess(application)

    suspend fun cached(): AboutState = withContext(Dispatchers.IO) {
        val update = AppUpdateManager.getCachedStatus(application)
        AboutState(
            currentVersion = update.currentVersion,
            latestVersion = update.latestVersion,
            hasUpdate = update.hasUpdate,
            canInstall = update.apkDownloadUrl.isNotBlank(),
            releaseUrl = update.releaseUrl,
            releaseNotes = update.releaseNotes,
            publishedAt = update.publishedAt,
            betaEnabled = AppUpdateManager.isBetaOptIn(application),
            downloadSource = if (AppUpdateManager.getApkDownloadSource(application) == ApkDownloadSource.GITHUB)
                UpdateDownloadSource.GitHub else UpdateDownloadSource.Worker,
        )
    }

    suspend fun check(): AboutState = withContext(Dispatchers.IO) {
        AppUpdateManager.checkNow(application, force = true)
        cached()
    }

    suspend fun setBeta(enabled: Boolean): AboutState = withContext(Dispatchers.IO) {
        AppUpdateManager.setBetaOptIn(application, enabled)
        // As in AppUpdateService, saving the preference succeeds even if its follow-up check fails.
        try { AppUpdateManager.checkNow(application, force = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Read the manager's cached state below. */ }
        cached()
    }

    suspend fun setSource(source: UpdateDownloadSource): AboutState = withContext(Dispatchers.IO) {
        AppUpdateManager.setApkDownloadSource(application, if (source == UpdateDownloadSource.GitHub) "github" else "worker")
        cached()
    }

    suspend fun requestDownloadNotifications(): Boolean = try {
        withContext(Dispatchers.Main) {
            if (permissions.isNotificationGranted()) return@withContext true
            suspendCancellableCoroutine { continuation ->
                permissions.requestNotificationPermission { granted ->
                    if (continuation.isActive) continuation.resume(granted)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Download notifications are optional, just as in the existing Flutter update flow.
        false
    }

    suspend fun install(): ExternalApkInstallResult = withContext(Dispatchers.IO) {
        AppUpdateManager.installLatestApk(application)
    }
}
