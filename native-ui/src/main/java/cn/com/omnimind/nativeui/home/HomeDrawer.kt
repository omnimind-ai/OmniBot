package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.visibleConversations
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun HomeDrawer(
    state: NativeHomeState,
    onSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onOpen: (LegacyDestination) -> Unit,
    onRetry: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    val conversations = remember(state.conversations, query) { visibleConversations(state.conversations, query) }
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
                { onOpen(LegacyDestination.Page.Archive) }, size = 18.dp)
            OmniIconButton(R.drawable.omni_plus, stringResource(R.string.omni_home_drawer_new_chat), onNewConversation, size = 20.dp)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
            if (state.error != null) item(key = "error") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text(state.error, fontSize = 12.sp, color = palette.secondaryText)
                    Text(stringResource(R.string.omni_retry), color = palette.accent,
                        modifier = Modifier.clickable(role = Role.Button, onClick = onRetry).padding(vertical = 12.dp))
                }
            }
            if (!state.loading && conversations.isEmpty()) item(key = "empty") {
                Text(stringResource(if (query.isBlank()) R.string.omni_no_conversations else R.string.omni_no_search_results),
                    fontSize = 13.sp, color = palette.secondaryText, modifier = Modifier.padding(vertical = 24.dp))
            }
            val pinned = conversations.filter { it.pinned }
            val recent = conversations.filterNot { it.pinned }
            if (pinned.isNotEmpty()) item(key = "pinned-header") { DrawerHeading(stringResource(R.string.omni_pinned)) }
            items(pinned, key = { it.id }) { ConversationRow(it, onOpen) }
            if (recent.isNotEmpty()) item(key = "recent-header") { DrawerHeading(stringResource(R.string.omni_recent)) }
            items(recent, key = { it.id }) { ConversationRow(it, onOpen) }
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(CircleShape)
            .background(if (palette.dark) palette.secondarySurface else palette.surface), verticalAlignment = Alignment.CenterVertically) {
            val shortcuts = listOf(
                Triple(R.drawable.omni_settings, R.string.omni_settings_title, onSettings),
                Triple(R.drawable.omni_brain, R.string.omni_memory_center_title, { onOpen(LegacyDestination.Page.Memory) }),
                Triple(R.drawable.omni_puzzle, R.string.omni_plugin_market_title, { onOpen(LegacyDestination.Page.Plugins) }),
                Triple(R.drawable.omni_blocks, R.string.omni_skill_store_title, { onOpen(LegacyDestination.Page.Skills) }),
                Triple(R.drawable.omni_history, R.string.omni_history, { onOpen(LegacyDestination.Page.ExecutionHistory) }),
                Triple(R.drawable.omni_calendar_clock, R.string.omni_home_drawer_scheduled, { onOpen(LegacyDestination.Page.ScheduledTasks) }),
            )
            shortcuts.forEach { (icon, label, action) ->
                OmniIconButton(icon, stringResource(label), action, Modifier.weight(1f), size = 17.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerHeading(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LocalOmniPalette.current.secondaryText,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp))
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onOpen: (LegacyDestination) -> Unit) {
    Text(conversation.title, fontSize = 14.sp, lineHeight = 21.sp, color = LocalOmniPalette.current.text,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
            onOpen(LegacyDestination.Conversation(conversation.id, conversation.mode))
        }.padding(horizontal = 4.dp, vertical = 12.dp))
}
