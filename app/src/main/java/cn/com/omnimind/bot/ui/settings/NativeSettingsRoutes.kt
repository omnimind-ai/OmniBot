package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.com.omnimind.bot.manager.AppPermissionAccess
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.settings.AboutActions
import cn.com.omnimind.nativeui.settings.AboutScreen
import cn.com.omnimind.nativeui.settings.PermissionSetting
import cn.com.omnimind.nativeui.settings.PermissionsActions
import cn.com.omnimind.nativeui.settings.PermissionsScreen
import cn.com.omnimind.nativeui.settings.MiscSettingsScreen
import cn.com.omnimind.nativeui.settings.BackgroundSettingsScreen
import cn.com.omnimind.nativeui.settings.PetSettingsScreen

@Composable
internal fun NativeAboutRoute(
    viewModel: NativeAboutViewModel,
    host: Context,
    openLegacy: (LegacyDestination) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    AboutScreen(state, AboutActions(
        primary = viewModel::primary,
        dismissUpdate = viewModel::dismissUpdate,
        confirmUpdate = {
            if (state.canInstall) viewModel.installConfirmed()
            else {
                viewModel.dismissUpdate()
                try {
                    val uri = Uri.parse(state.releaseUrl)
                    require(uri.scheme == "https" || uri.scheme == "http")
                    require(!uri.host.isNullOrBlank())
                    host.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { viewModel.browserFailed() }
            }
        },
        setBeta = viewModel::setBeta,
        setDownloadSource = viewModel::setSource,
        openRequestLogs = { openLegacy(LegacyDestination.Page.RequestLogs) },
        openRuntimeLogs = { openLegacy(LegacyDestination.Page.RuntimeLogs) },
        openUserGuide = { openLegacy(LegacyDestination.Page.UserGuide) },
    ), onBack)
}

@Composable
internal fun NativePermissionsRoute(
    viewModel: NativePermissionsViewModel,
    access: AppPermissionAccess,
    host: Context,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.pendingSetting) {
        val setting = state.pendingSetting ?: return@LaunchedEffect
        viewModel.consumeSetting()
        try {
            when (setting) {
                PermissionSetting.Accessibility -> access.openAccessibilitySettings()
                PermissionSetting.Background -> access.openBatterySettings(host)
                PermissionSetting.Overlay -> access.openOverlaySettings(host)
                PermissionSetting.InstalledApps -> access.openInstalledAppsSettings(host)
                PermissionSetting.PublicStorage -> access.openPublicStorageSettings(host)
                PermissionSetting.Shizuku -> error("Shizuku authorization belongs to its confirmation flow")
            }
        } catch (error: Exception) { viewModel.settingFailed(error) }
    }
    PermissionsScreen(state, PermissionsActions(
        refresh = viewModel::refresh,
        select = viewModel::select,
        setNotificationsEnabled = viewModel::setNotifications,
        dismissPrompt = viewModel::dismissPrompt,
        confirmPrompt = viewModel::confirmPrompt,
    ), onBack)
}

@Composable
internal fun NativeMiscSettingsRoute(
    viewModel: NativeMiscSettingsViewModel,
    access: AppPermissionAccess,
    openLegacy: (LegacyDestination) -> Unit,
    onHomeSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val actions = viewModel.actions.copy(setCompletionNotification = { enabled ->
        if (!enabled || access.isNotificationGranted()) {
            viewModel.actions.setCompletionNotification(enabled)
        } else {
            access.requestNotificationPermission { granted ->
                if (granted) viewModel.actions.setCompletionNotification(true)
                else viewModel.notificationPermissionDenied()
            }
        }
    })
    MiscSettingsScreen(state, actions, onHomeSettings, openLegacy, onBack)
}

@Composable
internal fun NativeBackgroundSettingsRoute(
    viewModel: NativeBackgroundViewModel,
    onPickImage: () -> Unit,
    onPet: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackgroundSettingsScreen(state, viewModel.actions, onPickImage, onPet, onBack)
}

@Composable
internal fun NativePetSettingsRoute(
    viewModel: NativePetSettingsViewModel,
    onPickPackage: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    PetSettingsScreen(state, viewModel.actions, onPickPackage, onBack)
}
