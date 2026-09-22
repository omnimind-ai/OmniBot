package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun HomeDrawer(
    state: NativeHomeState,
    onSettings: () -> Unit,
    onArchive: () -> Unit,
    onNewConversation: () -> Unit,
    actions: NativeHomeActions,
) {
    val palette = LocalOmniPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(palette.drawer).safeDrawingPadding().imePadding().padding(top = 16.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val searchLabel = stringResource(R.string.omni_home_drawer_search_hint)
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = palette.text),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.weight(1f).semantics { contentDescription = searchLabel },
                decorationBox = { field ->
                    Row(Modifier.height(36.dp).clip(CircleShape).background(palette.secondarySurface).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        OmniIcon(R.drawable.omni_search, tint = palette.secondaryText)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text(searchLabel, fontSize = 13.sp, color = palette.tertiaryText, maxLines = 1)
                            field()
                        }
                    }
                },
            )
            OmniIconButton(R.drawable.omni_archive, stringResource(R.string.omni_home_drawer_archive),
                onArchive, size = 18.dp)
            OmniIconButton(R.drawable.omni_plus, stringResource(R.string.omni_home_drawer_new_chat), onNewConversation, size = 20.dp)
        }
        Spacer(Modifier.height(12.dp))
        DrawerConversationList(state, query, actions, Modifier.weight(1f))
        if (state.webActions.isNotEmpty()) WebQuickActions(state, actions)
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(CircleShape)
            .background(if (palette.dark) palette.secondarySurface else palette.surface), verticalAlignment = Alignment.CenterVertically) {
            val shortcuts = listOf(
                Triple(R.drawable.omni_settings, R.string.omni_settings_title, onSettings),
                Triple(R.drawable.omni_brain, R.string.omni_memory_center_title, { actions.open(LegacyDestination.Page.Memory) }),
                Triple(R.drawable.omni_puzzle, R.string.omni_plugin_market_title, { actions.open(LegacyDestination.Page.Plugins) }),
                Triple(R.drawable.omni_blocks, R.string.omni_skill_store_title, { actions.open(LegacyDestination.Page.Skills) }),
                Triple(R.drawable.omni_history, R.string.omni_history, { actions.open(LegacyDestination.Page.ExecutionHistory) }),
                Triple(R.drawable.omni_calendar_clock, R.string.omni_home_drawer_scheduled, { actions.open(LegacyDestination.Page.ScheduledTasks) }),
            )
            shortcuts.forEach { (icon, label, action) ->
                OmniIconButton(icon, stringResource(label), action, Modifier.weight(1f), size = 17.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
