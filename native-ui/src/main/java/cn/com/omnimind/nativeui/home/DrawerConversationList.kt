package cn.com.omnimind.nativeui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.NativeHomeActions
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun DrawerConversationList(
    state: NativeHomeState,
    query: String,
    actions: NativeHomeActions,
    modifier: Modifier = Modifier,
    archivedOnly: Boolean = false,
) {
    val palette = LocalOmniPalette.current
    // Re-evaluate local dates when the drawer is revisited/refreshed; no per-row timer.
    val rows = remember(state.conversations, state.scheduledTasks, state.expandedSections, query, archivedOnly, state.recentConversationsOnly) {
        projectDrawerRows(state.conversations, state.scheduledTasks, state.expandedSections, query, archivedOnly, state.recentConversationsOnly)
    }
    var menuKey by remember { mutableStateOf<String?>(null) }
    val menuConversation = state.conversations.firstOrNull { it.key == menuKey }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
        if (state.error != null) item(key = "error") {
            Text(state.error, fontSize = 12.sp, color = palette.secondaryText)
            TextButton(stringResource(R.string.omni_retry), actions.refresh)
        }
        if (!state.loading && rows.isEmpty()) item(key = "empty") {
            Text(stringResource(if (query.isBlank()) R.string.omni_no_conversations else R.string.omni_no_search_results),
                fontSize = 13.sp, color = palette.secondaryText, modifier = Modifier.padding(vertical = 24.dp))
        }
        items(rows, key = { it.key }, contentType = { if (it is DrawerRow.Heading) "heading" else "thread" }) { row ->
            when (row) {
                is DrawerRow.Heading -> DrawerSectionHeading(row) { actions.setSectionExpanded(row.key, !row.expanded) }
                is DrawerRow.Thread -> ConversationRow(row, state, actions, if (archivedOnly) palette.page else palette.drawer) { menuKey = row.conversation.key }
            }
        }
    }
    OverlayBottomSheet(
        show = menuConversation != null,
        title = menuConversation?.title,
        backgroundColor = palette.page,
        onDismissRequest = { menuKey = null },
    ) {
        menuConversation?.let { conversation ->
            TextButton(
                text = stringResource(if (conversation.archived) R.string.omni_restore_conversation else R.string.omni_archive_conversation),
                enabled = conversation.id !in state.busyConversationIds,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    menuKey = null
                    actions.setArchived(conversation, !conversation.archived)
                },
            )
        }
    }
}

@Composable
private fun DrawerSectionHeading(row: DrawerRow.Heading, onToggle: () -> Unit) {
    val palette = LocalOmniPalette.current
    val label = when (row.kind) {
        DrawerHeadingKind.Scheduled -> stringResource(R.string.omni_scheduled_section)
        DrawerHeadingKind.Pinned -> stringResource(R.string.omni_pinned)
        DrawerHeadingKind.ChatOnly -> stringResource(R.string.omni_chat_only)
        DrawerHeadingKind.Date -> {
            val date = requireNotNull(row.date)
            val locale = LocalConfiguration.current.locales[0]
            when (ChronoUnit.DAYS.between(date, LocalDate.now())) {
                0L -> stringResource(R.string.omni_today)
                1L -> stringResource(R.string.omni_yesterday)
                in 2L..6L -> date.format(DateTimeFormatter.ofPattern("EEE", locale))
                else -> "${date.monthValue}-${date.dayOfMonth}"
            }
        }
    }
    val expansionState = stringResource(if (row.expanded) R.string.omni_collapse else R.string.omni_expand)
    Row(
        Modifier.fillMaxWidth().semantics { stateDescription = expansionState }
            .combinedClickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = 28.dp).padding(start = (4 + row.depth * 20).dp, top = 5.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(if (row.kind == DrawerHeadingKind.Scheduled) R.drawable.omni_calendar_clock else R.drawable.omni_history,
            size = 14.dp, tint = palette.tertiaryText)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.tertiaryText)
        Spacer(Modifier.width(8.dp))
        Text(row.count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.tertiaryText.copy(alpha = .82f))
    }
}

@Composable
private fun ConversationRow(row: DrawerRow.Thread, state: NativeHomeState, actions: NativeHomeActions, background: Color, onMenu: () -> Unit) {
    val palette = LocalOmniPalette.current
    val conversation = row.conversation
    val busy = conversation.id in state.busyConversationIds
    val archiveLabel = stringResource(if (conversation.archived) R.string.omni_restore_conversation else R.string.omni_archive_conversation)
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = state.leftHanded,
        enableDismissFromEndToStart = !state.leftHanded,
        gesturesEnabled = !busy,
        onDismiss = { direction ->
            if (direction != SwipeToDismissBoxValue.Settled) {
                actions.setArchived(conversation, !conversation.archived)
                // Room/domain state decides removal. Reset the visual if persistence fails.
                scope.launch { swipe.reset() }
            }
        },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(palette.secondarySurface).padding(horizontal = 12.dp),
                contentAlignment = if (state.leftHanded) Alignment.CenterStart else Alignment.CenterEnd) {
                Text(archiveLabel, color = palette.accent, fontSize = 12.sp)
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().background(background)
            .semantics { customActions = listOf(CustomAccessibilityAction(archiveLabel) {
                if (!busy) actions.setArchived(conversation, !conversation.archived)
                !busy
            }) }
            .combinedClickable(enabled = !busy, role = Role.Button,
                onClick = { actions.open(LegacyDestination.Conversation(conversation.id, conversation.mode, conversation.agentId)) },
                onLongClick = onMenu)
            .padding(start = (4 + row.depth * 20).dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(conversation.title, fontSize = 14.sp, lineHeight = 21.sp, color = palette.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.taskCount > 0 || row.childCount > 0) {
                    Text(stringResource(R.string.omni_scheduled_counts, row.taskCount, row.childCount), fontSize = 10.sp, color = palette.tertiaryText)
                }
            }
            if (row.expansionKey != null) {
                OmniIconButton(R.drawable.omni_circle_chevron_down,
                    stringResource(if (row.expanded) R.string.omni_collapse else R.string.omni_expand),
                    { actions.setSectionExpanded(row.expansionKey, !row.expanded) }, size = 18.dp)
            }
        }
    }
}
