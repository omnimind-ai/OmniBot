package cn.com.omnimind.nativeui.settings

import androidx.compose.runtime.Immutable

enum class StartupBehavior(val stored: String) {
    ResumeLast("resume_last"), NewConversation("new_conversation"),
}

enum class HabitualHandChoice(val stored: String) { Left("left"), Right("right") }

enum class MiscNotice { SaveFailed, NotificationPermissionRequired }

@Immutable
data class MiscSettingsState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val notice: MiscNotice? = null,
    val startup: StartupBehavior = StartupBehavior.ResumeLast,
    val recentOnly: Boolean = false,
    val hideFromRecents: Boolean = false,
    val vibration: Boolean = true,
    val independentSend: Boolean = true,
    val predictiveBack: Boolean = true,
    val preventSleep: Boolean = true,
    val completionNotification: Boolean = true,
    val habitualHand: HabitualHandChoice = HabitualHandChoice.Right,
)

data class MiscSettingsActions(
    val refresh: () -> Unit,
    val setStartup: (StartupBehavior) -> Unit,
    val setRecentOnly: (Boolean) -> Unit,
    val setHideFromRecents: (Boolean) -> Unit,
    val setVibration: (Boolean) -> Unit,
    val setIndependentSend: (Boolean) -> Unit,
    val setPredictiveBack: (Boolean) -> Unit,
    val setPreventSleep: (Boolean) -> Unit,
    val setCompletionNotification: (Boolean) -> Unit,
    val setHabitualHand: (HabitualHandChoice) -> Unit,
    val dismissNotice: () -> Unit,
)
