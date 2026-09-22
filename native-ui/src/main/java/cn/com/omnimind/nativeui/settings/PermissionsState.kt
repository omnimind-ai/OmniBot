package cn.com.omnimind.nativeui.settings

import androidx.compose.runtime.Immutable

enum class PermissionSetting { Accessibility, Background, Overlay, InstalledApps, PublicStorage, Shizuku }
enum class PermissionPrompt { Accessibility, Shizuku }
enum class PermissionNotice { ReadFailed, OpenFailed, SaveFailed, AccessibilityDisabled, ShizukuDenied }

/** The code and installed/running flags come from ShizukuCapabilityManager, never UI guesses. */
@Immutable
data class ShizukuAccess(val code: String = "", val installed: Boolean = false, val running: Boolean = false) {
    val granted: Boolean get() = code == "GRANTED_ADB" || code == "GRANTED_ROOT"
}

@Immutable
data class PermissionsState(
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val backgroundAllowed: Boolean = false,
    val accessibilityReady: Boolean = false,
    val overlayAllowed: Boolean = false,
    val installedAppsAllowed: Boolean = false,
    val publicStorageAllowed: Boolean = false,
    val shizuku: ShizukuAccess = ShizukuAccess(),
    val prompt: PermissionPrompt? = null,
    val pendingSetting: PermissionSetting? = null,
    val waitingForAccessibility: Boolean = false,
    val notice: PermissionNotice? = null,
) {
    val readyCoreCount: Int get() = listOf(backgroundAllowed, accessibilityReady, overlayAllowed, installedAppsAllowed).count { it }
}

data class PermissionsActions(
    val refresh: () -> Unit,
    val select: (PermissionSetting) -> Unit,
    val setNotificationsEnabled: (Boolean) -> Unit,
    val dismissPrompt: () -> Unit,
    val confirmPrompt: () -> Unit,
)
