package cn.com.omnimind.bot.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.permission.PermissionRequest
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.shizuku.ShizukuStatus
import cn.com.omnimind.bot.util.AssistsUtil
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Shared platform operations. Flutter error mapping and Compose presentation stay outside. */
class AppPermissionAccess(context: Context) {
    private val application = context.applicationContext
    private val accessibility by lazy { AndroidGuiEnvironment(application) }
    private val shizuku by lazy { ShizukuCapabilityManager.get(application) }

    fun isIgnoringBatteryOptimizations(): Boolean = AssistsUtil.Setting.isIgnoringBatteryOptimizations(application)
    fun isBackgroundRunAllowed(): Boolean = AssistsUtil.Setting.isBackgroundRunAllowed(application)
    fun isOverlayAllowed(): Boolean = AssistsUtil.Setting.isOverlayPermission(application)
    fun isAccessibilityEnabled(): Boolean = accessibility.isAccessibilityEnabled()
    fun isAccessibilityReady(): Boolean = accessibility.isReady()
    suspend fun awaitAccessibilityReady(): Boolean = accessibility.awaitReady(timeoutMs = 4_000)
    fun isInstalledAppsAllowed(): Boolean = AssistsUtil.Setting.isInstalledAppsPermissionGranted(application)
    fun isPublicStorageAllowed(): Boolean = PublicStorageAccess.isGranted()
    fun shizukuStatus(): ShizukuStatus = shizuku.getStatus()
    fun isShizukuInstalled(): Boolean = shizuku.isShizukuInstalled()
    fun openShizuku(): Boolean = shizuku.openShizukuDownloadOrApp()
    suspend fun requestShizuku(): ShizukuStatus = shizuku.requestPermission()

    // These existing helpers require an Activity context; never retain it in a ViewModel.
    fun openBatterySettings(host: Context) = AssistsUtil.Setting.openBatteryOptimizationSettings(host)
    fun openOverlaySettings(host: Context) = AssistsUtil.Setting.openOverlaySettings(host)
    fun openInstalledAppsSettings(host: Context) = AssistsUtil.Setting.openInstalledAppsSettings(host)
    fun openAccessibilitySettings() = accessibility.openAccessibilitySettings()
    fun openPublicStorageSettings(host: Context) {
        runCatching { host.startActivity(PublicStorageAccess.buildSettingsIntent(host.packageName)) }
            .recoverCatching { host.startActivity(PublicStorageAccess.buildFallbackSettingsIntent()) }
            .getOrThrow()
    }

    /** This is the existing application preference, not Android POST_NOTIFICATIONS. */
    fun notificationsEnabled(): Boolean = MMKV.defaultMMKV().decodeBool("notification_enabled", false)
    fun setNotificationsEnabled(enabled: Boolean) {
        check(MMKV.defaultMMKV().encode("notification_enabled", enabled))
    }

    fun isNotificationGranted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun requestNotificationPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(true)
            return
        }
        CoroutineScope(Dispatchers.Default).launch { AssistsUtil.UI.closeChatBotDialog() }
        PermissionRequest.requestPermissions(application, arrayOf(Manifest.permission.POST_NOTIFICATIONS)) {
            onResult(it[Manifest.permission.POST_NOTIFICATIONS] == true)
        }
    }
}
