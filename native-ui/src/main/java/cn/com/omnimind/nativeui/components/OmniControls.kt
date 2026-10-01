package cn.com.omnimind.nativeui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text

/** Miuix [Switch] (drag, toggle haptics) in the Omni accent. */
@Composable
internal fun OmniSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val palette = LocalOmniPalette.current
    Switch(
        checked, onCheckedChange,
        modifier.then(if (contentDescription == null) Modifier else Modifier.semantics {
            this.contentDescription = contentDescription
        }),
        colors = SwitchDefaults.switchColors(
            checkedTrackColor = palette.accent, uncheckedTrackColor = palette.strongBorder,
            checkedThumbColor = Color.White, uncheckedThumbColor = Color.White,
        ),
        enabled = enabled,
    )
}

/** Miuix segmented [TabRowWithContour] on the Omni segment track. */
@Composable
internal fun OmniTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOmniPalette.current
    TabRowWithContour(
        tabs, selectedIndex, onSelect, modifier,
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = palette.segmentTrack, contentColor = palette.secondaryText,
            selectedBackgroundColor = palette.segmentThumb, selectedContentColor = palette.accent,
        ),
    )
}

/**
 * Single-choice row: the whole Miuix [BasicComponent] is the target and the trailing
 * [RadioButton] only renders, so the selection haptic is played here once.
 */
@Composable
internal fun OmniChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    BasicComponent(
        onClick = {
            if (!selected) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
            onClick()
        },
        role = Role.RadioButton,
        enabled = enabled,
        endActions = { RadioButton(selected, onClick = null, enabled = enabled) },
    ) {
        Text(label, color = LocalOmniPalette.current.text, fontSize = 14.sp)
    }
}
