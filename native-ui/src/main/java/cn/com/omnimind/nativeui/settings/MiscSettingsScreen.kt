package cn.com.omnimind.nativeui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private enum class MiscItem(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val summary: Int) {
    Alarm(R.drawable.omni_calendar_clock, R.string.omni_misc_alarm_title, R.string.omni_misc_alarm_summary),
    Home(R.drawable.omni_house, R.string.omni_misc_home_title, R.string.omni_misc_home_summary),
    Startup(R.drawable.omni_power, R.string.omni_misc_startup_title, R.string.omni_misc_startup_summary),
    Recent(R.drawable.omni_archive, R.string.omni_misc_recent_title, R.string.omni_misc_recent_summary),
    Hide(R.drawable.omni_eye_off, R.string.omni_misc_hide_title, R.string.omni_misc_hide_summary),
    Vibration(R.drawable.omni_vibrate, R.string.omni_misc_vibration_title, R.string.omni_misc_vibration_summary),
    IndependentSend(R.drawable.omni_corner_down_left, R.string.omni_misc_independent_title, R.string.omni_misc_independent_summary),
    PredictiveBack(R.drawable.omni_move_left, R.string.omni_misc_predictive_title, R.string.omni_misc_predictive_summary),
    PreventSleep(R.drawable.omni_monitor_smartphone, R.string.omni_misc_prevent_sleep_title, R.string.omni_misc_prevent_sleep_summary),
    CompletionNotification(R.drawable.omni_bell, R.string.omni_misc_completion_title, R.string.omni_misc_completion_summary),
    OpenWith(R.drawable.omni_folder_open, R.string.omni_misc_open_with_title, R.string.omni_misc_open_with_summary),
    HabitualHand(R.drawable.omni_hand, R.string.omni_misc_habitual_hand_title, R.string.omni_misc_habitual_hand_summary),
    QuickStart(R.drawable.omni_graduation_cap, R.string.omni_misc_quick_start_title, R.string.omni_misc_quick_start_summary),
}

private enum class Choice { Startup, HabitualHand }

private data class ToggleSpec(val checked: Boolean, val onChange: (Boolean) -> Unit)

private fun toggleFor(item: MiscItem, state: MiscSettingsState, actions: MiscSettingsActions): ToggleSpec? = when (item) {
    MiscItem.Recent -> ToggleSpec(state.recentOnly, actions.setRecentOnly)
    MiscItem.Hide -> ToggleSpec(state.hideFromRecents, actions.setHideFromRecents)
    MiscItem.Vibration -> ToggleSpec(state.vibration, actions.setVibration)
    MiscItem.IndependentSend -> ToggleSpec(state.independentSend, actions.setIndependentSend)
    MiscItem.PredictiveBack -> ToggleSpec(state.predictiveBack, actions.setPredictiveBack)
    MiscItem.PreventSleep -> ToggleSpec(state.preventSleep, actions.setPreventSleep)
    MiscItem.CompletionNotification -> ToggleSpec(state.completionNotification, actions.setCompletionNotification)
    else -> null
}

@Composable
fun MiscSettingsScreen(
    state: MiscSettingsState,
    actions: MiscSettingsActions,
    onHomeSettings: () -> Unit,
    openLegacy: (LegacyDestination.Page) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    var choice by rememberSaveable { mutableStateOf<Choice?>(null) }
    val enabled = state.loaded && !state.busy
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_misc_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "miscellaneous") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_misc_title))
                    MiscItem.entries.forEachIndexed { index, item ->
                        val title = stringResource(item.title)
                        val toggle = toggleFor(item, state, actions)
                        val click: () -> Unit = {
                            when (item) {
                                MiscItem.Alarm -> openLegacy(LegacyDestination.Page.Alarm)
                                MiscItem.Home -> onHomeSettings()
                                MiscItem.Startup -> choice = Choice.Startup
                                MiscItem.OpenWith -> openLegacy(LegacyDestination.Page.OpenWith)
                                MiscItem.HabitualHand -> choice = Choice.HabitualHand
                                MiscItem.QuickStart -> openLegacy(LegacyDestination.Page.QuickStart)
                                else -> toggle?.let { it.onChange(!it.checked) }
                            }
                        }
                        PreferenceRow(title, stringResource(item.summary), icon = item.icon,
                            isLast = index == MiscItem.entries.lastIndex, enabled = enabled, onClick = click) {
                            when (item) {
                                MiscItem.Startup -> ChoiceLabel(stringResource(if (state.startup == StartupBehavior.ResumeLast)
                                    R.string.omni_misc_startup_resume else R.string.omni_misc_startup_new))
                                MiscItem.HabitualHand -> ChoiceLabel(stringResource(if (state.habitualHand == HabitualHandChoice.Left)
                                    R.string.omni_misc_left else R.string.omni_misc_right))
                                else -> if (toggle != null) {
                                    Switch(toggle.checked, toggle.onChange, enabled = enabled,
                                        colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent,
                                            uncheckedTrackColor = palette.strongBorder,
                                            checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                                        modifier = Modifier.semantics { contentDescription = title })
                                } else OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText)
                            }
                        }
                        if (index < MiscItem.entries.lastIndex) PreferenceDivider()
                    }
                }
            }
        }
        OverlayDialog(show = choice != null,
            title = when (choice) {
                Choice.Startup -> stringResource(R.string.omni_misc_startup_title)
                Choice.HabitualHand -> stringResource(R.string.omni_misc_habitual_hand_title)
                null -> null
            }, backgroundColor = palette.page, onDismissRequest = { choice = null }) {
            Column {
                when (choice) {
                    Choice.Startup -> {
                        ChoiceRow(stringResource(R.string.omni_misc_startup_resume), state.startup == StartupBehavior.ResumeLast, enabled) {
                            actions.setStartup(StartupBehavior.ResumeLast); choice = null
                        }
                        ChoiceRow(stringResource(R.string.omni_misc_startup_new), state.startup == StartupBehavior.NewConversation, enabled) {
                            actions.setStartup(StartupBehavior.NewConversation); choice = null
                        }
                    }
                    Choice.HabitualHand -> {
                        ChoiceRow(stringResource(R.string.omni_misc_left), state.habitualHand == HabitualHandChoice.Left, enabled) {
                            actions.setHabitualHand(HabitualHandChoice.Left); choice = null
                        }
                        ChoiceRow(stringResource(R.string.omni_misc_right), state.habitualHand == HabitualHandChoice.Right, enabled) {
                            actions.setHabitualHand(HabitualHandChoice.Right); choice = null
                        }
                    }
                    null -> Unit
                }
            }
        }
        OverlayDialog(show = state.notice != null,
            title = stringResource(if (state.notice == MiscNotice.NotificationPermissionRequired)
                R.string.omni_misc_notification_permission else R.string.omni_misc_save_failed),
            backgroundColor = palette.page, onDismissRequest = actions.dismissNotice) {
            TextButton(stringResource(R.string.omni_misc_confirm), actions.dismissNotice, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ChoiceLabel(value: String) {
    val palette = LocalOmniPalette.current
    Row(Modifier.widthIn(max = 132.dp)) {
        Text(value, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        OmniIcon(R.drawable.omni_circle_chevron_down, tint = palette.tertiaryText)
    }
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val palette = LocalOmniPalette.current
    BasicComponent(onClick = onSelect, enabled = enabled,
        endActions = { RadioButton(selected, onSelect, enabled = enabled) }) {
        Text(title, color = palette.text, fontSize = 14.sp)
    }
}
