package com.rk.components.compose.preferences.switch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.rk.components.compose.preferences.base.PreferenceTemplate

/**
 * A Preference that provides a two-state toggleable option.
 *
 * Row layout and the animated switch follow the main OmnibotApp settings style.
 *
 * @author Aquiles Trindade (trindadedev).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreferenceSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,

) {
    val interactionSource = remember { MutableInteractionSource() }
    val switchInteractionSource = remember { MutableInteractionSource() }

    PreferenceTemplate(
        modifier =
            modifier.combinedClickable(
                enabled = enabled,
                indication = ripple(),
                onLongClick = {
                    if (onLongClick != null) {
                        onLongClick()
                    }
                },
                interactionSource = interactionSource,
                onClick = {
                    if (onClick != null) {
                        onClick()
                    } else {
                        onCheckedChange(!checked)
                    }
                }
            ),
        title = { Text(text = label) },
        description = { description?.let { Text(text = it) } },
        endWidget = {
            OmniSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                interactionSource = switchInteractionSource,
            )
        },
        enabled = enabled,
    )
}
