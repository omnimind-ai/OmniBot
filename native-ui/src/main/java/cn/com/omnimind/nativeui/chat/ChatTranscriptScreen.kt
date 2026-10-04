package cn.com.omnimind.nativeui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text

private val UserBubbleLight = Color(0xE6F1F8FF)
private val ChatErrorText = Color(0xFFE05252)

/** Immutable input of the read-only native transcript (batch 5c preview). */
@Immutable
data class ChatTranscriptState(
    val title: String = "",
    /** Newest first, as the runtime publishes them. */
    val messages: List<ChatMessageUi> = emptyList(),
    /** Runs still producing output (runtime `activeAgentTurnIds`). */
    val activeTaskIds: Set<String> = emptySet(),
    val conversationAgentId: String? = null,
    /** True while the transcript mirrors a live runtime instead of stored history. */
    val isLive: Boolean = false,
    val loading: Boolean = true,
)

/**
 * Read-only Compose rendering of one conversation, fed by native runtime
 * snapshots. Used to compare the migrated message surfaces with the Flutter
 * chat on a device until the chat page itself moves (batch 5e).
 */
@Composable
fun ChatTranscriptScreen(
    state: ChatTranscriptState,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit = {},
) {
    val palette = LocalOmniPalette.current
    val subtitle = stringResource(
        if (state.isLive) R.string.omni_transcript_live else R.string.omni_transcript_history,
    )
    OmniPage(title = state.title.ifBlank { stringResource(R.string.omni_transcript_title) }, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                subtitle,
                color = palette.tertiaryText,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
            if (!state.loading && state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.omni_transcript_empty), color = palette.secondaryText)
                }
            } else {
                ChatMessageList(state, onOpenLink, Modifier.fillMaxSize())
            }
        }
    }
}

/** The message list: newest at the bottom, grouped into Agent runs. */
@Composable
fun ChatMessageList(
    state: ChatTranscriptState,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(state.messages, state.activeTaskIds, state.conversationAgentId) {
        buildAgentRunTimelineEntries(
            state.messages,
            activeTaskIds = state.activeTaskIds,
            conversationAgentId = state.conversationAgentId,
        )
    }
    val expandedRuns = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(
        modifier = modifier,
        reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            val message = entry.message
            val group = entry.group
            when {
                message != null -> ChatMessageItem(message, onOpenLink)
                group != null -> AgentRunGroupItem(
                    group = group,
                    expanded = expandedRuns[group.taskId] ?: group.isRunning,
                    onToggle = { expandedRuns[group.taskId] = !(expandedRuns[group.taskId] ?: group.isRunning) },
                    onOpenLink = onOpenLink,
                )
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessageUi, onOpenLink: (String) -> Unit) {
    when {
        message.type == 2 -> ChatCardPlaceholder(message)
        message.user == 1 -> UserBubble(message)
        else -> AssistantText(message, onOpenLink)
    }
}

/** User text: right-aligned bubble, at most 78% of the row (Flutter parity). */
@Composable
private fun UserBubble(message: ChatMessageUi) {
    val palette = LocalOmniPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp)) {
        val bubbleMaxWidth = maxWidth * 0.78f
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .background(if (palette.dark) palette.secondarySurface else UserBubbleLight, RoundedCornerShape(4.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(message.text.orEmpty(), color = palette.text, fontSize = 15.sp)
            }
        }
    }
}

/** Assistant text: Markdown without a bubble; failed replies use the error tone. */
@Composable
private fun AssistantText(message: ChatMessageUi, onOpenLink: (String) -> Unit) {
    val palette = LocalOmniPalette.current
    val text = message.text.orEmpty()
    if (text.isBlank()) return
    ChatMarkdownText(
        markdown = text,
        textColor = if (message.isError) ChatErrorText else palette.text,
        linkColor = palette.accent,
        codeBackground = palette.secondarySurface,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 18.dp),
        onOpenLink = onOpenLink,
    )
}

/** Card kinds migrate in 5c-2/5c-3; until then show which card sits here. */
@Composable
private fun ChatCardPlaceholder(message: ChatMessageUi) {
    val palette = LocalOmniPalette.current
    val card = message.cardData
    val type = card?.get("type")?.toString().orEmpty()
    val title = (card?.get("toolTitle") ?: card?.get("title") ?: card?.get("summary"))?.toString()
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(type.ifBlank { "card" }, color = palette.tertiaryText, fontSize = 11.sp)
            if (!title.isNullOrBlank()) {
                Text(title, color = palette.text, fontSize = 13.sp, maxLines = 2)
            }
        }
    }
}

/**
 * One Agent run: a header with status and counts, its process cards folded
 * behind it, and the run's visible messages always shown (5c-4 refines it).
 */
@Composable
private fun AgentRunGroupItem(
    group: AgentRunTimelineGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val statusText = stringResource(
        when (group.status) {
            AgentRunStatus.running -> R.string.omni_run_running
            AgentRunStatus.finished -> R.string.omni_run_finished
            AgentRunStatus.failed -> R.string.omni_run_failed
            AgentRunStatus.cancelled -> R.string.omni_run_cancelled
        },
    )
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = group.hasProcessMessages, onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(group.agentId, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "  $statusText" + if (group.hasProcessMessages) {
                    " · " + stringResource(R.string.omni_run_counts, group.thinkingCount, group.toolCount)
                } else {
                    ""
                },
                color = palette.secondaryText,
                fontSize = 12.sp,
            )
        }
        for (segment in group.segmentsOldestFirst) {
            if (segment.isProcess) {
                if (expanded) segment.messages.forEach { ChatMessageItem(it, onOpenLink) }
            } else {
                ChatMessageItem(segment.message, onOpenLink)
            }
        }
    }
}
