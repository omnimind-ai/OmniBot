package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Remote PC Bridge settings with debounced autosave. Presentation only; the
 * store, probe, remote-fs listing and cache key stay behind the host ViewModel.
 */
@Composable
fun RemoteBridgeScreen(
    state: RemoteBridgeState,
    actions: RemoteBridgeActions,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.dismissNotice()
        }
    }
    Scaffold(
        containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_agents_remote_bridge), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
            ) {
                item(key = "header") {
                    SectionTitle(stringResource(R.string.omni_bridge_section),
                        Modifier.padding(start = 4.dp, end = 4.dp))
                    Text(stringResource(R.string.omni_bridge_section_subtitle), fontSize = 12.sp,
                        lineHeight = 18.sp, color = palette.secondaryText,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "enable") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.omni_bridge_enable), Modifier.weight(1f),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                        Switch(state.enabled, actions.setEnabled,
                            enabled = state.status != RemoteBridgeSaveStatus.Saving,
                            colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent,
                                uncheckedTrackColor = palette.strongBorder,
                                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(if (state.enabled) R.string.omni_bridge_enabled_hint
                            else R.string.omni_bridge_disabled_hint),
                        fontSize = 12.sp, color = palette.secondaryText,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                item(key = "url") {
                    BridgeField(state.url, actions.editUrl, stringResource(R.string.omni_bridge_url_label),
                        "ws://192.168.1.10:17321/codex")
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "cwd") {
                    BridgeField(state.cwd, actions.editCwd, stringResource(R.string.omni_bridge_cwd_label),
                        "/Users/name/code/project",
                        trailing = {
                            FieldIcon(R.drawable.omni_folder_open,
                                stringResource(R.string.omni_bridge_choose_directory), actions.openPicker)
                        })
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "token") {
                    BridgeField(state.token, actions.editToken,
                        stringResource(R.string.omni_bridge_token_label), "OMNIBOT_BRIDGE_TOKEN",
                        masked = !state.tokenVisible,
                        trailing = {
                            FieldIcon(
                                if (state.tokenVisible) R.drawable.omni_eye else R.drawable.omni_eye_off,
                                stringResource(if (state.tokenVisible) R.string.omni_bridge_hide_token
                                    else R.string.omni_bridge_show_token),
                                actions.toggleTokenVisible,
                            )
                        })
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedAction(R.drawable.omni_scan_qr, stringResource(R.string.omni_bridge_scan_qr),
                            enabled = state.status != RemoteBridgeSaveStatus.Saving, onClick = onScanQr)
                        OutlinedAction(R.drawable.omni_radio_tower,
                            stringResource(if (state.testing) R.string.omni_bridge_testing
                                else R.string.omni_bridge_test),
                            enabled = !state.testing, busy = state.testing, onClick = actions.test)
                    }
                }
                if (state.errorRes != null || state.status != RemoteBridgeSaveStatus.None) {
                    item(key = "status") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            state.errorRes?.let { stringResource(it) } ?: stringResource(
                                when (state.status) {
                                    RemoteBridgeSaveStatus.Incomplete -> R.string.omni_bridge_status_incomplete
                                    RemoteBridgeSaveStatus.Pending -> R.string.omni_bridge_status_pending
                                    RemoteBridgeSaveStatus.Saving -> R.string.omni_bridge_status_saving
                                    RemoteBridgeSaveStatus.Saved -> R.string.omni_bridge_status_saved
                                    RemoteBridgeSaveStatus.None -> R.string.omni_bridge_status_saved
                                },
                            ),
                            fontSize = 12.sp,
                            color = if (state.errorRes != null) MiuixTheme.colorScheme.error
                                else palette.secondaryText,
                        )
                    }
                }
            }
        }
        val picker = state.picker
        OverlayBottomSheet(
            show = picker != null,
            title = stringResource(R.string.omni_bridge_picker_title),
            backgroundColor = palette.page,
            onDismissRequest = actions.closePicker,
        ) {
            if (picker != null) DirectoryPickerContent(picker, actions)
        }
    }
}

@Composable
private fun BridgeField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    hint: String,
    masked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    TextField(
        value, onValue, singleLine = true,
        label = label, useLabelAsPlaceholder = false,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label $hint" },
    )
}

@Composable
private fun FieldIcon(icon: Int, label: String, onClick: () -> Unit) {
    TooltipBox(label) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            OmniIcon(icon, tint = LocalOmniPalette.current.tertiaryText)
        }
    }
}

@Composable
private fun OutlinedAction(
    icon: Int,
    label: String,
    enabled: Boolean,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.accent.copy(alpha = if (enabled) 1f else .4f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
        } else {
            OmniIcon(icon, size = 17.dp, tint = palette.accent.copy(alpha = if (enabled) 1f else .4f))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = palette.accent.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun DirectoryPickerContent(picker: RemoteDirectoryPickerState, actions: RemoteBridgeActions) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                picker.currentPath.ifEmpty { stringResource(R.string.omni_bridge_picker_default_dir) },
                fontSize = 12.sp, lineHeight = 17.sp, color = palette.secondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            PickerIcon(R.drawable.omni_house, stringResource(R.string.omni_bridge_picker_home),
                enabled = picker.home != null && !picker.loading, onClick = actions.pickerHome)
            PickerIcon(R.drawable.omni_arrow_up, stringResource(R.string.omni_bridge_picker_parent),
                enabled = picker.parent != null && !picker.loading, onClick = actions.pickerUp)
            PickerIcon(R.drawable.omni_refresh_cw, stringResource(R.string.omni_pet_refresh),
                enabled = !picker.loading, onClick = actions.pickerRefresh)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            when {
                picker.loading -> Box(Modifier.fillMaxWidth().height(160.dp),
                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                picker.failed -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.omni_bridge_picker_failed), fontSize = 13.sp,
                        color = palette.secondaryText)
                    Spacer(Modifier.height(12.dp))
                    TextButton(stringResource(R.string.omni_retry), actions.pickerRefresh)
                }
                picker.entries.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.omni_bridge_picker_empty), fontSize = 13.sp,
                        color = palette.secondaryText)
                }
                else -> LazyColumn(Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(picker.entries, key = { it.path }) { entry ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button) { actions.pickerOpen(entry.path) }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .semantics { contentDescription = entry.name },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OmniIcon(R.drawable.omni_folder_open, size = 20.dp, tint = palette.accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, fontSize = 14.sp, color = palette.text,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.path, fontSize = 11.sp, color = palette.tertiaryText,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            actions.pickerSelect,
            enabled = !picker.loading && !picker.failed && picker.currentPath.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.omni_bridge_picker_select), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PickerIcon(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(label) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            OmniIcon(icon, size = 19.dp,
                tint = LocalOmniPalette.current.text.copy(alpha = if (enabled) 1f else .35f))
        }
    }
}
