package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

enum class ScheduledTasksTab { Tasks, Alarms }

/** Presentation row; the schedule fields are the scheduler owner's payload. */
@Immutable
data class ScheduledTaskItem(
    val taskId: String,
    val title: String,
    val targetKind: String,
    val scheduleType: String,
    val fixedTime: String?,
    val countdownMinutes: Int?,
    val repeatDaily: Boolean,
    val notificationEnabled: Boolean,
    val nextExecutionTime: Long?,
    val createdAt: Long,
    val subagentConversationId: String?,
    val parentConversationId: String?,
    val parentConversationMode: String?,
    val subagentPrompt: String?,
    val displayTimeText: String,
    val nextTimeText: String,
    val expired: Boolean,
) {
    override fun toString(): String = "ScheduledTaskItem(taskId=$taskId, title=$title)"
}

@Immutable
data class ExactAlarmItem(
    val alarmId: String,
    val title: String,
    val message: String,
    val triggerAtMillis: Long,
    val timezone: String,
    val timeText: String,
)

/** Sheet result; the ViewModel rebuilds the full owner payload from the task. */
@Immutable
data class ScheduledTaskEdit(
    val scheduleType: String,
    val fixedTime: String?,
    val countdownMinutes: Int?,
    val repeatDaily: Boolean,
)

@Immutable
data class ScheduledTasksState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val tab: ScheduledTasksTab = ScheduledTasksTab.Tasks,
    val tasks: List<ScheduledTaskItem> = emptyList(),
    val alarms: List<ExactAlarmItem> = emptyList(),
    val editTaskId: String? = null,
    val deletingTaskId: String? = null,
    val deletingAlarmId: String? = null,
    @StringRes val notice: Int? = null,
    val noticeArg: String? = null,
)

data class ScheduledTasksActions(
    val setTab: (ScheduledTasksTab) -> Unit,
    val openEditor: (String) -> Unit,
    val closeEditor: () -> Unit,
    val confirmEdit: (String, ScheduledTaskEdit) -> Unit,
    val confirmDeleteTask: (String?) -> Unit,
    val deleteTaskConfirmed: () -> Unit,
    val confirmDeleteAlarm: (String?) -> Unit,
    val deleteAlarmConfirmed: () -> Unit,
    val dismissNotice: () -> Unit,
)
