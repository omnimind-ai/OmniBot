package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.NativeScheduledTask
import cn.com.omnimind.bot.agent.NativeScheduledTasksRepository
import cn.com.omnimind.bot.ui.nativehome.resolveNativeHomeLocale
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.ExactAlarmItem
import cn.com.omnimind.nativeui.settings.ScheduledTaskEdit
import cn.com.omnimind.nativeui.settings.ScheduledTaskItem
import cn.com.omnimind.nativeui.settings.ScheduledTasksActions
import cn.com.omnimind.nativeui.settings.ScheduledTasksState
import cn.com.omnimind.nativeui.settings.ScheduledTasksTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Scheduled tasks + exact alarms page. Scheduling, storage and the Flutter
 * mirror stay with `WorkspaceScheduledTaskScheduler`; alarm records stay with
 * `AgentAlarmToolService`. The page never touches AlarmManager itself.
 */
internal class NativeScheduledTasksViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val repository = NativeScheduledTasksRepository(appContext)
    private val mutableState = MutableStateFlow(ScheduledTasksState())
    private var mutating = false
    private var tasksById: Map<String, NativeScheduledTask> = emptyMap()
    val state = mutableState.asStateFlow()

    val actions = ScheduledTasksActions(
        setTab = { tab -> mutableState.update { it.copy(tab = tab) } },
        openEditor = { taskId ->
            if (!mutating) mutableState.update { it.copy(editTaskId = taskId) }
        },
        closeEditor = { mutableState.update { it.copy(editTaskId = null) } },
        confirmEdit = ::confirmEdit,
        confirmDeleteTask = { taskId ->
            if (!mutating) mutableState.update { it.copy(deletingTaskId = taskId) }
        },
        deleteTaskConfirmed = ::deleteTaskConfirmed,
        confirmDeleteAlarm = { alarmId ->
            if (!mutating) mutableState.update { it.copy(deletingAlarmId = alarmId) }
        },
        deleteAlarmConfirmed = ::deleteAlarmConfirmed,
        dismissNotice = { mutableState.update { it.copy(notice = null, noticeArg = null) } },
    )

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading || mutating) return
        if (current.loaded && !force) return
        mutableState.update { it.copy(loading = true) }
        viewModelScope.launch { reload() }
    }

    /** Entry lifecycle resume: pick up edits made through the Flutter pages. */
    fun resume() {
        if (mutating) return
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            val (tasks, alarms) = withContext(Dispatchers.IO) {
                repository.listTasks() to repository.listAlarms()
            }
            tasksById = tasks.associateBy { it.taskId }
            val now = System.currentTimeMillis()
            mutableState.update {
                it.copy(
                    loaded = true,
                    loading = false,
                    tasks = tasks.sortedBy { task -> task.nextExecutionTime ?: 0L }
                        .map { task -> task.toItem(now) },
                    alarms = alarms.sortedBy { it.triggerAtMillis }
                        .map { alarm ->
                            ExactAlarmItem(
                                alarmId = alarm.alarmId,
                                title = alarm.title,
                                message = alarm.message,
                                triggerAtMillis = alarm.triggerAtMillis,
                                timezone = alarm.timezone,
                                timeText = alarmTimeText(alarm.triggerAtMillis),
                            )
                        },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniLog.e("NativeScheduledTasks", "Scheduled tasks load failed", error)
            mutableState.update { it.copy(loading = false) }
        }
    }

    private fun confirmEdit(taskId: String, edit: ScheduledTaskEdit) {
        val task = tasksById[taskId] ?: return
        if (mutating) return
        mutating = true
        mutableState.update { it.copy(editTaskId = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveTask(
                        task.toSchedulerPayload(edit, nextExecutionAt(edit)),
                    )
                }
                reload()
                mutableState.update {
                    it.copy(
                        notice = R.string.omni_scheduled_updated,
                        noticeArg = editDisplayText(edit),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeScheduledTasks", "Scheduled task save failed", error)
                mutableState.update { it.copy(notice = R.string.omni_scheduled_save_failed) }
            } finally {
                mutating = false
            }
        }
    }

    private fun deleteTaskConfirmed() {
        val taskId = state.value.deletingTaskId ?: return
        if (mutating) return
        mutating = true
        mutableState.update { it.copy(deletingTaskId = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.deleteTask(taskId) }
                reload()
                mutableState.update { it.copy(notice = R.string.omni_scheduled_deleted) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeScheduledTasks", "Scheduled task delete failed", error)
                mutableState.update { it.copy(notice = R.string.omni_scheduled_delete_failed) }
            } finally {
                mutating = false
            }
        }
    }

    private fun deleteAlarmConfirmed() {
        val alarmId = state.value.deletingAlarmId ?: return
        if (mutating) return
        mutating = true
        mutableState.update { it.copy(deletingAlarmId = null, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.deleteAlarm(alarmId) }
                reload()
                mutableState.update { it.copy(notice = R.string.omni_scheduled_alarm_deleted) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeScheduledTasks", "Alarm delete failed", error)
                mutableState.update { it.copy(notice = R.string.omni_scheduled_alarm_delete_failed) }
            } finally {
                mutating = false
            }
        }
    }

    /** Same formula as the Flutter sheet's `calculateNextExecutionTime`. */
    private fun nextExecutionAt(edit: ScheduledTaskEdit): Long {
        val now = System.currentTimeMillis()
        if (edit.scheduleType == "countdown") {
            return now + (edit.countdownMinutes ?: 30).coerceAtLeast(1) * 60_000L
        }
        val parts = edit.fixedTime?.split(":") ?: return now
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 0
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val zone = ZoneId.systemDefault()
        val nowLocal = LocalDateTime.now(zone)
        var target = nowLocal.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (target.isBefore(nowLocal)) target = target.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    private fun editDisplayText(edit: ScheduledTaskEdit): String {
        if (edit.scheduleType != "countdown") return edit.fixedTime ?: "--:--"
        return countdownText((edit.countdownMinutes ?: 30).coerceAtLeast(1))
    }

    private fun countdownText(minutes: Int): String {
        if (minutes >= 60) {
            val hours = minutes / 60
            val mins = minutes % 60
            if (mins > 0) return text(R.string.omni_scheduled_display_countdown_hm, hours, mins)
            return text(R.string.omni_scheduled_display_countdown_h, hours)
        }
        return text(R.string.omni_scheduled_display_countdown_m, minutes)
    }

    private fun nextTimeText(nextExecutionTime: Long?): String {
        if (nextExecutionTime == null) return text(R.string.omni_scheduled_next_not_set)
        val diffMs = nextExecutionTime - System.currentTimeMillis()
        if (diffMs < 0) return text(R.string.omni_scheduled_expired)
        val minutes = diffMs / 60_000L
        val hours = diffMs / 3_600_000L
        val days = diffMs / 86_400_000L
        return when {
            days > 0 -> text(R.string.omni_scheduled_next_days, days)
            hours > 0 -> text(R.string.omni_scheduled_next_hours, hours)
            minutes > 0 -> text(R.string.omni_scheduled_next_minutes, minutes)
            else -> text(R.string.omni_scheduled_next_soon)
        }
    }

    private fun alarmTimeText(triggerAtMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMillis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))

    private fun text(resource: Int, vararg args: Any): String {
        val preferences =
            appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val locale = resolveNativeHomeLocale(preferences.getString("flutter.language_option", "system"))
        val configuration = Configuration(appContext.resources.configuration).apply { setLocale(locale) }
        return appContext.createConfigurationContext(configuration).getString(resource, *args)
    }

    private fun NativeScheduledTask.toItem(now: Long): ScheduledTaskItem = ScheduledTaskItem(
        taskId = taskId,
        title = title,
        targetKind = targetKind,
        scheduleType = scheduleType,
        fixedTime = fixedTime,
        countdownMinutes = countdownMinutes,
        repeatDaily = repeatDaily,
        notificationEnabled = notificationEnabled,
        nextExecutionTime = nextExecutionTime,
        createdAt = createdAt,
        subagentConversationId = subagentConversationId,
        parentConversationId = parentConversationId,
        parentConversationMode = parentConversationMode,
        subagentPrompt = subagentPrompt,
        displayTimeText = if (scheduleType == "countdown") {
            countdownText((countdownMinutes ?: 0).coerceAtLeast(0))
        } else {
            fixedTime ?: "--:--"
        },
        nextTimeText = nextTimeText(nextExecutionTime),
        expired = nextExecutionTime?.let { it < now } == true,
    )

    private fun NativeScheduledTask.toSchedulerPayload(
        edit: ScheduledTaskEdit,
        nextExecutionTime: Long,
    ): Map<String, Any?> = toUpsertPayload(
        scheduleType = edit.scheduleType,
        fixedTime = if (edit.scheduleType == "countdown") null else edit.fixedTime,
        countdownMinutes = if (edit.scheduleType == "countdown") edit.countdownMinutes else null,
        repeatDaily = edit.repeatDaily,
        nextExecutionTime = nextExecutionTime,
    )

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeScheduledTasksViewModel(appContext) as T
    }
}
