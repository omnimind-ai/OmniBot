package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private val ChipTeal = Color(0xFF009688)

/**
 * Scheduled tasks and exact alarms. Presentation only; scheduling, storage and
 * the alarm records stay with their existing owners behind the host ViewModel.
 */
@Composable
fun ScheduledTasksScreen(
    state: ScheduledTasksState,
    actions: ScheduledTasksActions,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { if (state.noticeArg != null) stringResource(it, state.noticeArg) else stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.dismissNotice()
        }
    }
    Scaffold(
        containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_scheduled_page_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Spacer(Modifier.height(8.dp))
            TabRowWithContour(
                listOf(stringResource(R.string.omni_scheduled_tab_tasks),
                    stringResource(R.string.omni_scheduled_tab_alarms)),
                if (state.tab == ScheduledTasksTab.Tasks) 0 else 1,
                { index -> actions.setTab(if (index == 0) ScheduledTasksTab.Tasks else ScheduledTasksTab.Alarms) },
                colors = TabRowDefaults.tabRowColors(backgroundColor = palette.segmentTrack,
                    contentColor = palette.secondaryText, selectedBackgroundColor = palette.segmentThumb,
                    selectedContentColor = palette.accent),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else when (state.tab) {
                ScheduledTasksTab.Tasks -> TaskList(state, actions)
                ScheduledTasksTab.Alarms -> AlarmList(state, actions)
            }
        }
        val editTask = state.tasks.firstOrNull { it.taskId == state.editTaskId }
        OverlayBottomSheet(
            show = editTask != null,
            title = stringResource(R.string.omni_scheduled_editor_title),
            backgroundColor = palette.page,
            onDismissRequest = actions.closeEditor,
        ) {
            if (editTask != null) {
                key(editTask.taskId) {
                    ScheduleEditSheet(editTask, onConfirm = { edit ->
                        actions.confirmEdit(editTask.taskId, edit)
                    })
                }
            }
        }
        val deletingTask = state.tasks.firstOrNull { it.taskId == state.deletingTaskId }
        OverlayDialog(
            show = deletingTask != null,
            title = stringResource(R.string.omni_scheduled_delete_title),
            summary = stringResource(R.string.omni_scheduled_delete_confirm, deletingTask?.title.orEmpty()),
            backgroundColor = palette.page,
            onDismissRequest = { actions.confirmDeleteTask(null) },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.omni_cancel), { actions.confirmDeleteTask(null) },
                    modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_agent_delete), actions.deleteTaskConfirmed,
                    modifier = Modifier.weight(1f))
            }
        }
        val deletingAlarm = state.alarms.firstOrNull { it.alarmId == state.deletingAlarmId }
        OverlayDialog(
            show = deletingAlarm != null,
            title = stringResource(R.string.omni_scheduled_alarm_delete_title),
            summary = stringResource(R.string.omni_scheduled_alarm_delete_confirm, deletingAlarm?.title.orEmpty()),
            backgroundColor = palette.page,
            onDismissRequest = { actions.confirmDeleteAlarm(null) },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.omni_cancel), { actions.confirmDeleteAlarm(null) },
                    modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_agent_delete), actions.deleteAlarmConfirmed,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TaskList(state: ScheduledTasksState, actions: ScheduledTasksActions) {
    val palette = LocalOmniPalette.current
    if (state.tasks.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            OmniIcon(R.drawable.omni_calendar_clock, size = 64.dp, tint = palette.tertiaryText)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.omni_scheduled_empty_tasks), fontSize = 16.sp,
                color = palette.secondaryText)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.omni_scheduled_empty_tasks_hint), fontSize = 14.sp,
                color = palette.tertiaryText)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.tasks, key = { it.taskId }) { task -> TaskRow(task, actions) }
    }
}

@Composable
private fun TaskRow(task: ScheduledTaskItem, actions: ScheduledTasksActions) {
    val palette = LocalOmniPalette.current
    val dim = task.expired
    val titleColor = if (dim) palette.tertiaryText else palette.text
    val secondary = if (dim) palette.tertiaryText else palette.secondaryText
    val accent = if (dim) palette.tertiaryText else palette.accent
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (dim) palette.secondarySurface else palette.surface)
            .padding(12.dp),
    ) {
        Column {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                    .background(palette.secondarySurface),
                contentAlignment = Alignment.Center,
            ) {
                OmniIcon(R.drawable.omni_bot, size = 14.dp, tint = secondary)
            }
            Spacer(Modifier.height(12.dp))
            Text(task.title, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = titleColor)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(ChipTeal.copy(alpha = .12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("SubAgent", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = if (palette.dark) palette.secondaryText else ChipTeal)
                }
                if (!task.notificationEnabled) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.omni_scheduled_notifications_off), fontSize = 10.sp,
                        color = secondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniIcon(
                    if (task.scheduleType == "countdown") R.drawable.omni_timer else R.drawable.omni_clock,
                    size = 14.dp, tint = accent,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    task.displayTimeText,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) { actions.openEditor(task.taskId) }
                        .padding(bottom = 1.dp)
                        .semantics { contentDescription = task.title },
                )
                if (task.repeatDaily) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(accent.copy(alpha = .1f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.omni_scheduled_repeat_daily), fontSize = 10.sp,
                            color = accent)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(task.nextTimeText, fontSize = 10.sp, color = secondary)
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { actions.confirmDeleteTask(task.taskId) }
                .semantics { contentDescription = task.title },
            contentAlignment = Alignment.Center,
        ) {
            OmniIcon(R.drawable.omni_x, size = 16.dp, tint = secondary)
        }
    }
}

@Composable
private fun AlarmList(state: ScheduledTasksState, actions: ScheduledTasksActions) {
    val palette = LocalOmniPalette.current
    if (state.alarms.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            OmniIcon(R.drawable.omni_bell, size = 64.dp, tint = palette.tertiaryText)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.omni_scheduled_empty_alarms), fontSize = 16.sp,
                color = palette.secondaryText)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.omni_scheduled_empty_alarms_hint), fontSize = 14.sp,
                color = palette.tertiaryText, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp))
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.alarms, key = { it.alarmId }) { alarm ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(palette.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                        .background(palette.accent.copy(alpha = .1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    OmniIcon(R.drawable.omni_bell, size = 16.dp, tint = palette.accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(alarm.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (alarm.message.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(alarm.message, fontSize = 12.sp, lineHeight = 16.sp,
                            color = palette.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${alarm.timeText}  ·  ${alarm.timezone}", fontSize = 11.sp,
                        color = palette.tertiaryText)
                }
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { actions.confirmDeleteAlarm(alarm.alarmId) }
                        .semantics { contentDescription = alarm.title },
                    contentAlignment = Alignment.Center,
                ) {
                    OmniIcon(R.drawable.omni_x, size = 16.dp, tint = palette.secondaryText)
                }
            }
        }
    }
}

/** The edit sheet keeps its draft local; confirming hands fields to the ViewModel. */
@Composable
private fun ScheduleEditSheet(
    task: ScheduledTaskItem,
    onConfirm: (ScheduledTaskEdit) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val initialParts = task.fixedTime?.split(":")
    var fixedTab by remember { mutableStateOf(task.scheduleType != "countdown") }
    var hour by remember { mutableStateOf(initialParts?.getOrNull(0)?.toIntOrNull() ?: 0) }
    var minute by remember { mutableStateOf(initialParts?.getOrNull(1)?.toIntOrNull() ?: 0) }
    var countdownMinutes by remember { mutableStateOf(task.countdownMinutes ?: 30) }
    var repeatDaily by remember { mutableStateOf(task.repeatDaily) }
    var showCountdownInput by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(palette.secondarySurface).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OmniIcon(R.drawable.omni_calendar_clock, size = 20.dp, tint = palette.accent)
            Spacer(Modifier.width(8.dp))
            Text(task.title, fontSize = 14.sp, color = palette.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(16.dp))
        TabRowWithContour(
            listOf(stringResource(R.string.omni_scheduled_fixed_time),
                stringResource(R.string.omni_scheduled_countdown)),
            if (fixedTab) 0 else 1,
            { index -> fixedTab = index == 0 },
            colors = TabRowDefaults.tabRowColors(backgroundColor = palette.segmentTrack,
                contentColor = palette.secondaryText, selectedBackgroundColor = palette.segmentThumb,
                selectedContentColor = palette.accent),
        )
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            if (fixedTab) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberPicker(hour, { hour = it }, range = 0..23, wrapAround = true,
                        label = { "%02d".format(it) }, modifier = Modifier.width(96.dp))
                    Text(":", fontSize = 26.sp, fontWeight = FontWeight.Medium, color = palette.text,
                        modifier = Modifier.padding(horizontal = 8.dp))
                    NumberPicker(minute, { minute = it }, range = 0..59, wrapAround = true,
                        label = { "%02d".format(it) }, modifier = Modifier.width(96.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CountdownButton("-", countdownMinutes > 5) { countdownMinutes -= 5 }
                    Spacer(Modifier.width(24.dp))
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(palette.secondarySurface)
                            .border(1.dp, palette.strongBorder, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { showCountdownInput = true }
                            .padding(horizontal = 24.dp, vertical = 16.dp)
                            .semantics { contentDescription = "$countdownMinutes" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(countdownText(countdownMinutes), fontSize = 36.sp,
                            fontWeight = FontWeight.Light, color = palette.text)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.omni_scheduled_countdown_suffix),
                            fontSize = 14.sp, color = palette.secondaryText)
                    }
                    Spacer(Modifier.width(24.dp))
                    CountdownButton("+", countdownMinutes < 1440) { countdownMinutes += 5 }
                }
            }
        }
        if (fixedTab) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.omni_scheduled_repeat_daily_switch),
                    Modifier.weight(1f), fontSize = 14.sp, color = palette.text)
                Switch(repeatDaily, { repeatDaily = it },
                    colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.strongBorder,
                        checkedThumbColor = Color.White, uncheckedThumbColor = Color.White))
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onConfirm(
                    ScheduledTaskEdit(
                        scheduleType = if (fixedTab) "fixed_time" else "countdown",
                        fixedTime = if (fixedTab) "%02d:%02d".format(hour, minute) else null,
                        countdownMinutes = if (fixedTab) null else countdownMinutes,
                        repeatDaily = repeatDaily,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.omni_scheduled_confirm))
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showCountdownInput) {
        CountdownInputDialog(countdownMinutes, onDismiss = { showCountdownInput = false },
            onConfirm = { countdownMinutes = it; showCountdownInput = false })
    }
}

@Composable
private fun CountdownButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .background(palette.accent.copy(alpha = if (enabled) .12f else .05f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium,
            color = palette.accent.copy(alpha = if (enabled) 1f else .4f))
    }
}

@Composable
private fun countdownText(minutes: Int): String {
    if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) return stringResource(R.string.omni_scheduled_display_countdown_hm, hours, mins)
        return stringResource(R.string.omni_scheduled_display_countdown_h, hours)
    }
    return stringResource(R.string.omni_scheduled_display_countdown_m, minutes)
}

@Composable
private fun CountdownInputDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var text by remember { mutableStateOf(initialMinutes.toString()) }
    var invalid by remember { mutableStateOf(false) }
    OverlayDialog(show = true, title = stringResource(R.string.omni_scheduled_countdown_dialog_title),
        backgroundColor = palette.page, onDismissRequest = onDismiss) {
        Column {
            TextField(
                text, { text = it; invalid = false }, singleLine = true,
                label = stringResource(R.string.omni_scheduled_minutes_suffix),
                useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.omni_scheduled_countdown_invalid), fontSize = 12.sp,
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.omni_cancel), onDismiss,
                    modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_pref_ok), {
                    val minutes = text.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0 || minutes > 1440) invalid = true
                    else onConfirm(minutes)
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
