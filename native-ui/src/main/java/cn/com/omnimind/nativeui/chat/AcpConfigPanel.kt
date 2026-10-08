package cn.com.omnimind.nativeui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * The ACP config panel (batch 5e-3, Flutter `AcpConfigPanel`): every option
 * the Agent declares; a select opens its choices, a boolean toggles, an
 * unknown type is shown read-only. Writes go through [onSet]; the panel
 * re-renders from the complete response.
 */
@Composable
internal fun AcpConfigSheet(
    state: AcpConfigPanelState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSet: (configId: String, value: Any) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = state.options.firstOrNull { it.id == expandedId && it.isSelect }
    val editable = !state.loading && !state.saving && !state.readOnly
    OverlayBottomSheet(
        show = state.visible,
        title = expanded?.label ?: stringResource(R.string.omni_acp_config_title),
        onDismissRequest = {
            if (expanded != null) expandedId = null else onDismiss()
        },
        onDismissFinished = { expandedId = null },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            when {
                expanded != null -> expanded.choices.forEach { choice ->
                    ConfigRow(
                        label = choice.label,
                        value = "",
                        selected = choice.value == expanded.currentValue?.toString(),
                        enabled = editable,
                    ) {
                        onSet(expanded.id, choice.value)
                        expandedId = null
                    }
                }
                state.loading && state.options.isEmpty() -> Hint(stringResource(R.string.omni_acp_config_loading))
                state.options.isEmpty() -> Hint(state.error ?: stringResource(R.string.omni_acp_config_empty))
                else -> state.options.forEach { option ->
                    if (option.isBoolean) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option.label, Modifier.weight(1f), color = palette.text, fontSize = 14.sp)
                            OmniSwitch(
                                checked = option.currentValue == true,
                                onCheckedChange = if (editable) ({ onSet(option.id, it) }) else null,
                                contentDescription = option.label,
                            )
                        }
                    } else {
                        ConfigRow(
                            label = option.label,
                            value = option.currentLabel,
                            selected = false,
                            enabled = option.isSelect && !state.saving,
                            chevron = option.isSelect,
                        ) { expandedId = option.id }
                    }
                }
            }
            if (state.readOnly) Hint(stringResource(R.string.omni_slash_busy))
            if (state.error != null && state.options.isNotEmpty()) Hint(state.error)
            if (expanded == null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.omni_acp_config_refresh), onRefresh, enabled = !state.loading)
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(
    label: String,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = palette.text, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) {
            Text(value, color = palette.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            OmniIcon(R.drawable.omni_check, tint = palette.accent, size = 16.dp)
        }
        if (chevron) OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText, size = 14.dp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalOmniPalette.current.tertiaryText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
}
