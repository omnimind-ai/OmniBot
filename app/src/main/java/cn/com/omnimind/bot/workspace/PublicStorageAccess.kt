package cn.com.omnimind.bot.workspace

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import cn.com.omnimind.bot.distribution.AppEditionCapabilities

object PublicStorageAccess {
    const val REQUIRED_PERMISSION_NAME = "公共文件访问"
    const val PUBLIC_STORAGE_ROOT_PATH = "/storage"
    private const val SDCARD_ROOT_PATH = "/sdcard"
    private const val PUBLIC_URI_PREFIX = "omnibot://public"

    fun isSupported(): Boolean = AppEditionCapabilities.canManagePublicStorage

    fun isGranted(): Boolean {
        if (!isSupported()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requiredPermissionNames(): List<String> = listOf(REQUIRED_PERMISSION_NAME)

    fun buildSettingsIntent(packageName: String): Intent {
        check(isSupported()) { "Public storage access is unavailable in this app edition." }
        return buildSettingsIntentForPackage(
            packageName = packageName,
            useAppSpecificAction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        )
    }

    fun buildFallbackSettingsIntent(): Intent {
        check(isSupported()) { "Public storage access is unavailable in this app edition." }
        return buildFallbackSettingsIntent(
            useManageAllFilesAction = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        )
    }

    fun buildSettingsIntentForPackage(
        packageName: String,
        useAppSpecificAction: Boolean
    ): Intent {
        return if (useAppSpecificAction) {
            Intent(resolveAppSpecificSettingsAction()).apply {
                data = Uri.parse(packageSettingsUri(packageName))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            buildFallbackSettingsIntent(useManageAllFilesAction = false)
        }
    }

    fun buildFallbackSettingsIntent(useManageAllFilesAction: Boolean): Intent {
        return if (useManageAllFilesAction) {
            Intent(resolveFallbackSettingsAction(useManageAllFilesAction)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            Intent(resolveFallbackSettingsAction(useManageAllFilesAction)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    fun resolveAppSpecificSettingsAction(): String {
        return Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
    }

    fun resolveFallbackSettingsAction(useManageAllFilesAction: Boolean): String {
        return if (useManageAllFilesAction) {
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        } else {
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
    }

    fun packageSettingsUri(packageName: String): String = "package:$packageName"

    fun isPublicStorageInput(inputPath: String?): Boolean {
        val trimmed = inputPath?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        return isPublicStoragePath(trimmed) || isPublicStorageUri(trimmed)
    }

    fun isPublicStoragePath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed == PUBLIC_STORAGE_ROOT_PATH ||
            trimmed.startsWith("$PUBLIC_STORAGE_ROOT_PATH/") ||
            trimmed == SDCARD_ROOT_PATH ||
            trimmed.startsWith("$SDCARD_ROOT_PATH/")
    }

    fun isPublicStorageUri(uriText: String): Boolean {
        val trimmed = uriText.trim()
        if (trimmed == PUBLIC_URI_PREFIX || trimmed.startsWith("$PUBLIC_URI_PREFIX/")) {
            return true
        }
        if (!trimmed.startsWith("omnibot://")) {
            return false
        }
        val absolutePath = absoluteOmnibotPublicPath(trimmed) ?: return false
        return isPublicStoragePath(absolutePath)
    }

    private fun normalizeOmnibotAbsolutePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    }

    private fun absoluteOmnibotPublicPath(uriText: String): String? {
        val remainder = uriText.trim().removePrefix("omnibot://")
        return when {
            remainder.startsWith('/') -> normalizeOmnibotAbsolutePath(remainder)
            remainder == "storage" || remainder.startsWith("storage/") -> "/$remainder"
            remainder == "sdcard" || remainder.startsWith("sdcard/") -> "/$remainder"
            else -> null
        }
    }
}
