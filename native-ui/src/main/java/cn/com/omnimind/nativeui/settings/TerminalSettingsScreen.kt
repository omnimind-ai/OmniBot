package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.components.OmniTabRow
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Terminal settings. Presentation only; the setup inventory, rootfs
 * preparation, boot tasks and workspace mounts stay with their existing owners
 * behind the host ViewModel.
 */
@Composable
fun TerminalSettingsScreen(
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
    onOpenSetup: (List<String>) -> Unit,
    onOpenTerminal: () -> Unit,
    onPickMountDirectory: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let {
        if (state.noticeArg != null) stringResource(it, state.noticeArg) else stringResource(it)
    }
    OmniPage(stringResource(R.string.omni_settings_alpine_title), onBack,
        notice = notice, onNoticeShown = actions.dismissNotice) { insets ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "distribution") { DistributionSection(state, actions) }
                item(key = "intro") { IntroSection(state) }
                if (state.detectFailed) {
                    item(key = "detect-error") {
                        ErrorCard(stringResource(R.string.omni_terminal_detect_failed))
                    }
                }
                val groups = state.items.groupBy { it.definition.groupRes }
                items(groups.size, key = { groups.keys.elementAt(it) }) { index ->
                    val groupRes = groups.keys.elementAt(index)
                    EnvironmentGroup(stringResource(groupRes), groups.getValue(groupRes),
                        state, actions)
                }
                item(key = "setup") { SetupButton(state, onOpenSetup) }
                item(key = "boot") { AutoStartSection(state, actions, onOpenTerminal) }
                item(key = "mounts") { MountSection(state, actions, onPickMountDirectory) }
            }
        }
    }
    // Boot-task editor sheet.
    val editing = state.autoStartTasks.firstOrNull { it.id == state.editTaskId }
    OverlayBottomSheet(
        show = state.taskEditorOpen,
        title = stringResource(if (editing == null) R.string.omni_terminal_add_boot_task
            else R.string.omni_terminal_edit_task),
        backgroundColor = palette.page,
        onDismissRequest = actions.closeTaskEditor,
    ) {
        if (state.taskEditorOpen) {
            key(state.editTaskId) {
                BootTaskEditor(editing, state.autoStartBusy, actions)
            }
        }
    }
    // Mount alias dialog.
    val aliasPath = state.mountAliasPickerPath
    if (aliasPath != null) {
        MountAliasDialog(state, actions)
    }
    // Delete task confirmation.
    val deletingTask = state.autoStartTasks.firstOrNull { it.id == state.deletingTaskId }
    OmniConfirmDialog(
        show = deletingTask != null,
        title = stringResource(R.string.omni_terminal_delete_task),
        summary = stringResource(R.string.omni_terminal_delete_task_msg, deletingTask?.name.orEmpty()),
        confirmText = stringResource(R.string.omni_agent_delete),
        onConfirm = actions.deleteTaskConfirmed,
        onDismiss = { actions.confirmDeleteTask(null) },
    )
    // Unmount confirmation.
    val unmounting = state.mounts.firstOrNull { it.linkPath == state.unmountLinkPath }
    OmniConfirmDialog(
        show = unmounting != null,
        title = stringResource(R.string.omni_terminal_unmount_title),
        summary = stringResource(R.string.omni_terminal_unmount_msg, unmounting?.alias.orEmpty()),
        confirmText = stringResource(R.string.omni_terminal_unmount),
        onConfirm = actions.unmountConfirmed,
        onDismiss = { actions.confirmUnmount(null) },
    )
}

@Composable
private fun DistributionSection(state: TerminalSettingsState, actions: TerminalSettingsActions) {
    val palette = LocalOmniPalette.current
    SectionTitle(stringResource(R.string.omni_terminal_distro_title),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    Text(stringResource(R.string.omni_terminal_distro_desc), fontSize = 13.sp, lineHeight = 20.8.sp,
        fontWeight = FontWeight.Medium, color = palette.secondaryText)
    Spacer(Modifier.height(14.dp))
    val distributions = listOf("alpine", "ubuntu")
    OmniTabRow(
        distributions.map { if (it == "alpine") "Alpine" else "Ubuntu" },
        distributions.indexOf(state.distributionId).coerceAtLeast(0),
        { index -> actions.switchDistribution(distributions[index]) },
    )
    if (state.distributionLoading || state.switchingDistributionId != null) {
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(progress = state.switchProgress, modifier = Modifier.fillMaxWidth())
        if (state.switchingDistributionId != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.switchCancelling) stringResource(R.string.omni_terminal_cancelling)
                    else state.switchStage ?: stringResource(R.string.omni_terminal_switching),
                    Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = palette.secondaryText,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(stringResource(R.string.omni_terminal_cancel_download),
                    { actions.cancelSwitch() }, enabled = !state.switchCancelling)
            }
        }
    }
}

@Composable
private fun IntroSection(state: TerminalSettingsState) {
    val palette = LocalOmniPalette.current
    SectionTitle(stringResource(R.string.omni_terminal_env_config),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    val readyCount = state.items.count { it.ready == true }
    Text(
        when {
            state.detecting -> stringResource(R.string.omni_terminal_detecting_desc)
            state.detectFailed || state.items.any { it.ready == null } ->
                stringResource(R.string.omni_terminal_detect_incomplete)
            else -> stringResource(R.string.omni_terminal_ready_count, readyCount, state.items.size)
        },
        fontSize = 13.sp, lineHeight = 20.8.sp, fontWeight = FontWeight.Medium,
        color = palette.secondaryText,
    )
}

@Composable
private fun EnvironmentGroup(
    groupTitle: String,
    items: List<TerminalEnvironmentItem>,
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
) {
    val palette = LocalOmniPalette.current
    SectionTitle(groupTitle, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    items.forEachIndexed { index, item ->
        EnvironmentRow(item, state, actions)
        if (index < items.lastIndex) {
            Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp)
                .background(palette.border.copy(alpha = .6f)))
        }
    }
}

@Composable
private fun EnvironmentRow(
    item: TerminalEnvironmentItem,
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
) {
    val palette = LocalOmniPalette.current
    val ready = item.ready == true
    val unknown = item.ready == null
    val selected = item.definition.id in state.selectedPackageIds
    Row(Modifier.fillMaxWidth().semantics { contentDescription = item.definition.title }) {
        Box(Modifier.padding(top = 2.dp), contentAlignment = Alignment.Center) {
            if (ready) {
                OmniIcon(R.drawable.omni_square_check, size = 22.dp, tint = Color(0xFF16A34A))
            } else {
                Checkbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = { actions.togglePackage(item.definition.id, !selected) },
                    enabled = !state.detecting && !unknown && state.switchingDistributionId == null,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.definition.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = palette.text)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(item.definition.descriptionRes), fontSize = 12.sp,
                fontWeight = FontWeight.Medium, color = palette.secondaryText)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.widthIn(max = 150.dp), horizontalAlignment = Alignment.End) {
            if (state.detecting && item.ready == null) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            } else {
                StatusTag(
                    label = when {
                        unknown -> stringResource(R.string.omni_terminal_status_unknown)
                        ready -> stringResource(R.string.omni_terminal_status_ready)
                        else -> stringResource(R.string.omni_terminal_status_lost)
                    },
                    ready = ready,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    !item.version.isNullOrBlank() -> item.version.trim()
                    unknown -> stringResource(R.string.omni_terminal_version_unknown)
                    ready -> stringResource(R.string.omni_terminal_version_detected)
                    else -> stringResource(R.string.omni_terminal_version_not_found)
                },
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.4.sp,
                color = palette.tertiaryText, textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun StatusTag(label: String, ready: Boolean) {
    val palette = LocalOmniPalette.current
    val background: Color
    val foreground: Color
    if (palette.dark) {
        background = palette.secondarySurface
        foreground = if (ready) Color(0xFFD6E7D6) else Color(0xFFD7DADF)
    } else {
        background = if (ready) Color(0xFFE8F7EE) else Color(0xFFEAF2FF)
        foreground = if (ready) Color(0xFF17803D) else Color(0xFF2563EB)
    }
    Box(
        Modifier.clip(CircleShape).background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = foreground)
    }
}

@Composable
private fun SetupButton(state: TerminalSettingsState, onOpenSetup: (List<String>) -> Unit) {
    var opening by remember { mutableStateOf(false) }
    val label = when {
        state.detecting -> stringResource(R.string.omni_terminal_detecting)
        state.selectedMissingCount > 0 ->
            stringResource(R.string.omni_terminal_start_config, state.selectedMissingCount)
        else -> stringResource(R.string.omni_terminal_all_ready)
    }
    val enabled = !state.detecting && !state.distributionLoading &&
        state.switchingDistributionId == null && !state.detectFailed &&
        state.selectedMissingCount > 0 && !opening
    Button(
        {
            opening = true
            onOpenSetup(state.selectedPackageIds.toList())
            opening = false
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (opening) {
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            OmniIcon(R.drawable.omni_square_terminal, size = 18.dp, tint = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun AutoStartSection(
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
    onOpenTerminal: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    SectionTitle(stringResource(R.string.omni_terminal_boot_tasks),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    Text(stringResource(R.string.omni_terminal_boot_tasks_desc), fontSize = 13.sp,
        lineHeight = 20.8.sp, fontWeight = FontWeight.Medium, color = palette.secondaryText)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedActionButton(R.drawable.omni_plus, stringResource(R.string.omni_terminal_add_task),
            enabled = !state.autoStartBusy, modifier = Modifier.weight(1f),
        ) { actions.openTaskEditor(null) }
        OutlinedActionButton(R.drawable.omni_square_terminal,
            stringResource(R.string.omni_terminal_open_terminal), enabled = !state.autoStartBusy,
            modifier = Modifier.weight(1f), onClick = onOpenTerminal)
    }
    Spacer(Modifier.height(14.dp))
    when {
        state.autoStartFailed -> ErrorCard(stringResource(R.string.omni_terminal_tasks_load_failed))
        state.autoStartLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.autoStartTasks.isEmpty() -> MutedBox(stringResource(R.string.omni_terminal_no_tasks))
        else -> Column {
            state.autoStartTasks.forEachIndexed { index, task ->
                AutoStartTaskRow(task, state.autoStartBusy, actions)
                if (index < state.autoStartTasks.lastIndex) {
                    Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp)
                        .background(palette.border.copy(alpha = .6f)))
                }
            }
        }
    }
}

@Composable
private fun AutoStartTaskRow(
    task: TerminalAutoStartTaskItem,
    busy: Boolean,
    actions: TerminalSettingsActions,
) {
    val palette = LocalOmniPalette.current
    Column(Modifier.semantics { contentDescription = task.name }) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(task.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniTag(
                        stringResource(if (task.enabled) R.string.omni_terminal_boot_on_open
                            else R.string.omni_terminal_not_enabled),
                        active = task.enabled,
                    )
                    MiniTag(
                        stringResource(if (task.running) R.string.omni_terminal_running
                            else R.string.omni_terminal_idle),
                        active = task.running,
                    )
                }
            }
            OmniSwitch(task.enabled, if (busy) null else ({ actions.toggleTask(task.id, it) }),
                contentDescription = task.name)
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(palette.secondarySurface)
                .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(task.command, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 19.2.sp, color = palette.text, fontFamily = FontFamily.Monospace)
            if (!task.workingDirectory.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.omni_terminal_work_dir_value, task.workingDirectory),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.secondaryText)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            RowAction(R.drawable.omni_play,
                stringResource(if (task.running) R.string.omni_terminal_running
                    else R.string.omni_terminal_start_now),
                enabled = !busy) { actions.runTask(task.id) }
            RowAction(R.drawable.omni_pencil, stringResource(R.string.omni_terminal_edit),
                enabled = !busy) { actions.openTaskEditor(task.id) }
            RowAction(R.drawable.omni_trash_2, stringResource(R.string.omni_agent_delete),
                enabled = !busy) { actions.confirmDeleteTask(task.id) }
        }
    }
}

@Composable
private fun RowAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(icon, size = 18.dp, tint = palette.accent.copy(alpha = if (enabled) 1f else .4f))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = palette.accent.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun MiniTag(label: String, active: Boolean) {
    val palette = LocalOmniPalette.current
    Box(
        Modifier.clip(CircleShape)
            .background(if (palette.dark) palette.secondarySurface
                else if (active) Color(0xFFEAF2FF) else Color(0xFFF1F5F9))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (palette.dark) palette.secondaryText
                else if (active) Color(0xFF2563EB) else Color(0xFF64748B))
    }
}

@Composable
private fun MountSection(
    state: TerminalSettingsState,
    actions: TerminalSettingsActions,
    onPickMountDirectory: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    SectionTitle(stringResource(R.string.omni_terminal_mount_section),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    Text(stringResource(R.string.omni_terminal_mount_section_desc), fontSize = 13.sp,
        lineHeight = 20.8.sp, fontWeight = FontWeight.Medium, color = palette.secondaryText)
    Spacer(Modifier.height(14.dp))
    OutlinedActionButton(R.drawable.omni_folder_open, stringResource(R.string.omni_terminal_mount_add),
        enabled = !state.mountsBusy, busy = state.mountsBusy, modifier = Modifier.fillMaxWidth(),
        onClick = onPickMountDirectory)
    Spacer(Modifier.height(14.dp))
    when {
        state.mountsFailed -> ErrorCard(stringResource(R.string.omni_terminal_mount_failed, ""))
        state.mountsLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.mounts.isEmpty() -> MutedBox(stringResource(R.string.omni_terminal_mount_empty))
        else -> Column {
            state.mounts.forEachIndexed { index, mount ->
                MountRow(mount, state.mountsBusy, actions)
                if (index < state.mounts.lastIndex) {
                    Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp)
                        .background(palette.border.copy(alpha = .6f)))
                }
            }
        }
    }
}

@Composable
private fun MountRow(mount: TerminalMountItem, busy: Boolean, actions: TerminalSettingsActions) {
    val palette = LocalOmniPalette.current
    Row(Modifier.semantics { contentDescription = mount.alias }) {
        Column(Modifier.weight(1f)) {
            Text(mount.shellPath, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text(mount.sourcePath, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                color = palette.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (mount.broken) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.omni_terminal_mount_broken, mount.sourcePath),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.5.sp,
                    color = Color(0xFFC2410C))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            MiniTag(
                stringResource(if (mount.broken) R.string.omni_terminal_mount_broken_tag
                    else R.string.omni_terminal_mount_mounted),
                active = !mount.broken,
            )
            Spacer(Modifier.height(10.dp))
            RowAction(R.drawable.omni_x, stringResource(R.string.omni_terminal_unmount),
                enabled = !busy) { actions.confirmUnmount(mount.linkPath) }
        }
    }
}

@Composable
private fun BootTaskEditor(
    editing: TerminalAutoStartTaskItem?,
    busy: Boolean,
    actions: TerminalSettingsActions,
) {
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var command by remember { mutableStateOf(editing?.command.orEmpty()) }
    var workingDirectory by remember { mutableStateOf(editing?.workingDirectory ?: "/workspace") }
    var enabled by remember { mutableStateOf(editing?.enabled ?: true) }
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextField(name, { name = it }, singleLine = true, enabled = !busy,
            label = stringResource(R.string.omni_terminal_task_name), modifier = Modifier.fillMaxWidth())
        TextField(command, { command = it }, minLines = 3, maxLines = 5, enabled = !busy,
            label = stringResource(R.string.omni_terminal_start_command),
            modifier = Modifier.fillMaxWidth())
        TextField(workingDirectory, { workingDirectory = it }, singleLine = true, enabled = !busy,
            label = stringResource(R.string.omni_terminal_work_dir), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.omni_terminal_auto_start), Modifier.weight(1f),
                fontSize = 14.sp, color = palette.text)
            OmniSwitch(enabled, { enabled = it }, enabled = !busy,
                contentDescription = stringResource(R.string.omni_terminal_auto_start))
        }
        Button(
            { actions.saveTaskEditor(name, command, workingDirectory, enabled) },
            enabled = !busy && name.isNotBlank() && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (editing == null) R.string.omni_terminal_add_boot_task
                else R.string.omni_agent_save))
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MountAliasDialog(state: TerminalSettingsState, actions: TerminalSettingsActions) {
    val palette = LocalOmniPalette.current
    var alias by remember(state.mountAliasPickerPath) { mutableStateOf(state.mountAliasSuggestion) }
    OverlayDialog(
        show = true,
        title = stringResource(R.string.omni_terminal_mount_alias_title),
        summary = stringResource(R.string.omni_terminal_mount_alias_hint),
        backgroundColor = palette.page,
        onDismissRequest = actions.dismissMountPicker,
    ) {
        Column {
            TextField(alias, { alias = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            if (state.mountAliasError) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.omni_terminal_mount_alias_illegal), fontSize = 12.sp,
                    color = Color(0xFFFF6464))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.omni_cancel), actions.dismissMountPicker,
                    modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_terminal_mount_save),
                    { actions.mountConfirmed(alias) },
                    enabled = alias.isNotBlank() && !state.mountsBusy,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OutlinedActionButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.accent.copy(alpha = if (enabled) 1f else .4f),
                RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        } else {
            OmniIcon(icon, size = 17.dp, tint = palette.accent.copy(alpha = if (enabled) 1f else .4f))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = palette.accent.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun ErrorCard(message: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFFBEB))
            .border(1.dp, Color(0xFFFCD34D), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.5.sp,
            color = Color(0xFF92400E))
    }
}

@Composable
private fun MutedBox(text: String) {
    val palette = LocalOmniPalette.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(palette.secondarySurface)
            .border(1.dp, palette.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.2.sp,
            color = palette.secondaryText)
    }
}
