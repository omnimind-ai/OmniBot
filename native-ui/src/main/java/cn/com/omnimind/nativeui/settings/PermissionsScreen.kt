package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun PermissionsScreen(state: PermissionsState, actions: PermissionsActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_authorize_page_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "overview") {
                Column {
                    Text(if (state.loaded) stringResource(R.string.omni_permissions_ready_count, state.readyCoreCount)
                        else stringResource(if (state.notice == PermissionNotice.ReadFailed) R.string.omni_permissions_read_failed else R.string.omni_permissions_reading), fontSize = 20.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.text)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.omni_permissions_overview), fontSize = 12.sp, lineHeight = 19.2.sp, color = palette.secondaryText)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = if (!state.loaded && state.refreshing) null else state.readyCoreCount / 4f, height = 6.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = palette.accent,
                            backgroundColor = palette.border.copy(alpha = if (palette.dark) .9f else .72f)))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(if (state.loaded && state.readyCoreCount == 4) R.string.omni_permissions_core_complete else R.string.omni_permissions_core_incomplete),
                        fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                        color = if (state.loaded && state.readyCoreCount == 4) palette.accent else palette.secondaryText)
                    state.notice?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(it.messageResource()), fontSize = 12.sp, color = palette.secondaryText)
                        TextButton(stringResource(R.string.omni_retry), actions.refresh, enabled = !state.busy && !state.refreshing)
                    }
                }
            }
            item(key = "notifications") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_permissions_notifications), stringResource(R.string.omni_permissions_notifications_summary))
                    val label = stringResource(R.string.omni_authorize_receive_notifications)
                    PreferenceRow(label, stringResource(R.string.omni_authorize_notifications_desc), icon = R.drawable.omni_bell, isLast = true,
                        enabled = state.loaded && !state.busy, onClick = { actions.setNotificationsEnabled(!state.notificationsEnabled) }) {
                        Switch(state.notificationsEnabled, actions.setNotificationsEnabled, enabled = state.loaded && !state.busy,
                            colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent, uncheckedTrackColor = palette.strongBorder,
                                checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                            modifier = Modifier.semantics { contentDescription = label })
                    }
                }
            }
            item(key = "core") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_permissions_core), stringResource(R.string.omni_permissions_core_summary))
                    PermissionRow(PermissionSetting.Accessibility, R.drawable.omni_accessibility, R.string.omni_permission_accessibility,
                        R.string.omni_permission_accessibility_summary, state.accessibilityReady, state, actions)
                    PreferenceDivider()
                    PermissionRow(PermissionSetting.Background, R.drawable.omni_battery_charging, R.string.omni_permission_background,
                        R.string.omni_permission_background_summary, state.backgroundAllowed, state, actions)
                    PreferenceDivider()
                    PermissionRow(PermissionSetting.Overlay, R.drawable.omni_picture_in_picture_2, R.string.omni_permission_overlay,
                        R.string.omni_permission_overlay_summary, state.overlayAllowed, state, actions)
                    PreferenceDivider()
                    PermissionRow(PermissionSetting.InstalledApps, R.drawable.omni_layout_grid, R.string.omni_permission_apps,
                        R.string.omni_permission_apps_summary, state.installedAppsAllowed, state, actions, isLast = true)
                }
            }
            item(key = "advanced") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_permissions_advanced), stringResource(R.string.omni_permissions_advanced_summary))
                    PermissionRow(PermissionSetting.PublicStorage, R.drawable.omni_folder_open, R.string.omni_permission_storage,
                        R.string.omni_permission_storage_summary, state.publicStorageAllowed, state, actions)
                    PreferenceDivider()
                    PreferenceRow(stringResource(R.string.omni_permission_shizuku), stringResource(state.shizuku.guideResource()),
                        icon = R.drawable.omni_usb, isLast = true, enabled = state.loaded && !state.busy,
                        onClick = { actions.select(PermissionSetting.Shizuku) }) {
                        PermissionTrailing(stringResource(state.shizuku.labelResource()), state.shizuku.granted)
                    }
                }
            }
        }
        PermissionDialogs(state, actions)
    }
}

@Composable
private fun PermissionRow(setting: PermissionSetting, icon: Int, title: Int, summary: Int, granted: Boolean, state: PermissionsState, actions: PermissionsActions, isLast: Boolean = false) {
    PreferenceRow(stringResource(title), stringResource(summary), icon = icon, isLast = isLast, enabled = state.loaded && !state.busy,
        onClick = { actions.select(setting) }) {
        PermissionTrailing(stringResource(when {
            !state.loaded -> R.string.omni_permissions_reading
            granted -> R.string.omni_permission_enabled
            else -> R.string.omni_permission_enable
        }), granted)
    }
}

@Composable
private fun PermissionTrailing(label: String, granted: Boolean) {
    val palette = LocalOmniPalette.current
    Row {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (granted) palette.tertiaryText else palette.accent,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        OmniIcon(R.drawable.omni_chevron_right, size = 16.dp, tint = palette.tertiaryText)
    }
}

@Composable
private fun PermissionDialogs(state: PermissionsState, actions: PermissionsActions) {
    val palette = LocalOmniPalette.current
    OverlayDialog(show = state.prompt == PermissionPrompt.Accessibility, title = stringResource(R.string.omni_accessibility_prompt_title),
        backgroundColor = palette.page, onDismissRequest = actions.dismissPrompt) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.omni_accessibility_prompt_purpose), color = palette.text, fontSize = 14.sp)
            Text(stringResource(R.string.omni_accessibility_prompt_location), color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(stringResource(R.string.omni_accessibility_prompt_steps), color = palette.secondaryText, fontSize = 13.sp)
            if (state.refreshing) LinearProgressIndicator()
            state.notice?.let { Text(stringResource(it.messageResource()), color = palette.secondaryText, fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.omni_cancel), actions.dismissPrompt, modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_accessibility_prompt_open), actions.confirmPrompt,
                    enabled = !state.refreshing, modifier = Modifier.weight(1f))
            }
        }
    }
    OverlayDialog(show = state.prompt == PermissionPrompt.Shizuku, title = stringResource(R.string.omni_permission_shizuku),
        summary = stringResource(state.shizuku.guideResource()), backgroundColor = palette.page, onDismissRequest = actions.dismissPrompt) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(stringResource(R.string.omni_cancel), actions.dismissPrompt, modifier = Modifier.weight(1f))
            TextButton(stringResource(if (state.shizuku.installed) R.string.omni_shizuku_open else R.string.omni_shizuku_install),
                actions.confirmPrompt, enabled = !state.busy, modifier = Modifier.weight(1f))
        }
    }
}

private fun PermissionNotice.messageResource() = when (this) {
    PermissionNotice.ReadFailed -> R.string.omni_permissions_read_failed
    PermissionNotice.OpenFailed -> R.string.omni_permissions_open_failed
    PermissionNotice.SaveFailed -> R.string.omni_permissions_save_failed
    PermissionNotice.AccessibilityDisabled -> R.string.omni_accessibility_still_disabled
    PermissionNotice.ShizukuDenied -> R.string.omni_shizuku_denied
}

private fun ShizukuAccess.labelResource() = when (code) {
    "GRANTED_ROOT" -> R.string.omni_shizuku_granted_root
    "GRANTED_ADB" -> R.string.omni_shizuku_granted_adb
    "PERMISSION_DENIED" -> R.string.omni_shizuku_permission_denied
    "NOT_RUNNING" -> R.string.omni_shizuku_not_running
    "BINDER_DEAD" -> R.string.omni_shizuku_binder_dead
    "NOT_INSTALLED" -> R.string.omni_shizuku_not_installed
    else -> R.string.omni_permissions_reading
}

private fun ShizukuAccess.guideResource() = when (code) {
    "GRANTED_ROOT" -> R.string.omni_shizuku_guide_root
    "GRANTED_ADB" -> R.string.omni_shizuku_guide_adb
    "PERMISSION_DENIED" -> R.string.omni_shizuku_guide_permission
    "NOT_RUNNING" -> R.string.omni_shizuku_guide_start
    "BINDER_DEAD" -> R.string.omni_shizuku_guide_reconnect
    "NOT_INSTALLED" -> R.string.omni_shizuku_guide_install
    else -> R.string.omni_permissions_reading
}
