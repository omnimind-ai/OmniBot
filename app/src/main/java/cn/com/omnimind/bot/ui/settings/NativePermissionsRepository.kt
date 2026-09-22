package cn.com.omnimind.bot.ui.settings

import android.content.Context
import cn.com.omnimind.bot.manager.AppPermissionAccess
import cn.com.omnimind.nativeui.settings.PermissionsState
import cn.com.omnimind.nativeui.settings.ShizukuAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class NativePermissionsRepository(context: Context) {
    private val access = AppPermissionAccess(context.applicationContext)

    suspend fun read(): PermissionsState = withContext(Dispatchers.IO) {
        val shizuku = access.shizukuStatus()
        PermissionsState(
            loaded = true,
            notificationsEnabled = access.notificationsEnabled(),
            backgroundAllowed = access.isBackgroundRunAllowed(),
            accessibilityReady = access.isAccessibilityReady(),
            overlayAllowed = access.isOverlayAllowed(),
            installedAppsAllowed = access.isInstalledAppsAllowed(),
            publicStorageAllowed = access.isPublicStorageAllowed(),
            shizuku = ShizukuAccess(shizuku.code.name, shizuku.installed, shizuku.running),
        )
    }

    suspend fun setNotifications(enabled: Boolean) = withContext(Dispatchers.IO) {
        access.setNotificationsEnabled(enabled)
    }

    suspend fun awaitAccessibilityReady(): Boolean = access.awaitAccessibilityReady()

    suspend fun confirmShizuku(): Boolean {
        val status = withContext(Dispatchers.IO) { access.shizukuStatus() }
        if (status.isGranted()) return true
        val opened = withContext(Dispatchers.Main) { access.openShizuku() }
        if (!status.installed || !status.running) {
            check(opened) { "Unable to open Shizuku" }
            return false
        }
        // An already running Binder (including Sui) can request permission without a launcher.
        return withContext(Dispatchers.IO) { access.requestShizuku().isGranted() }
    }
}
