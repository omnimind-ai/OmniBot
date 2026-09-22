package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.WebProcessStatus
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
internal fun WebQuickActions(state: NativeHomeState, actions: NativeHomeActions) {
    val palette = LocalOmniPalette.current
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = state.webActions.firstOrNull { it.key == selectedKey }
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.webActions.forEach { action ->
            val busy = state.busyWebAction == action.key
            val status = when {
                busy -> stringResource(R.string.omni_web_busy)
                action.status == WebProcessStatus.Starting -> stringResource(R.string.omni_web_starting)
                action.status == WebProcessStatus.Running -> stringResource(R.string.omni_web_running)
                else -> ""
            }
            val surface = if (palette.dark) palette.secondarySurface else palette.surface
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp))
                    .background(surface).semantics { stateDescription = status }
                    .combinedClickable(
                        enabled = state.busyWebAction == null,
                        role = Role.Button,
                        onClick = { actions.invokeWebAction(action, false) },
                        onLongClick = if (action.active && action.canStop) ({ selectedKey = action.key }) else null,
                    ).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (busy) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                else Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                    val (icon, tint) = when (action.agentId) {
                        "kimi-code-acp" -> R.drawable.omni_brand_moonshot to Color(0xFF1783FF)
                        "deepseek-harness-acp" -> R.drawable.omni_brand_deepseek to Color(0xFF4D6BFE)
                        else -> R.drawable.omni_bot to palette.accent
                    }
                    OmniIcon(icon, size = 20.dp, tint = tint)
                    if (action.active) Box(Modifier.align(Alignment.TopEnd).size(8.dp)
                        .background(if (action.status == WebProcessStatus.Running) Color(0xFF2EAF67) else Color(0xFFE3A52B), CircleShape)
                        .border(1.5.dp, surface, CircleShape))
                }
                Spacer(Modifier.width(8.dp))
                Text(action.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    OverlayBottomSheet(show = selected != null, title = selected?.label,
        backgroundColor = palette.page, onDismissRequest = { selectedKey = null }) {
        selected?.let { action ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(R.string.omni_web_open), {
                    selectedKey = null
                    actions.invokeWebAction(action, false)
                }, enabled = state.busyWebAction == null, modifier = Modifier.fillMaxWidth())
                if (action.active && action.canStop) TextButton(stringResource(R.string.omni_web_stop), {
                    selectedKey = null
                    actions.invokeWebAction(action, true)
                }, enabled = state.busyWebAction == null, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
