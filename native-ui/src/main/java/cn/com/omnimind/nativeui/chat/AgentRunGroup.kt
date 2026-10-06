package cn.com.omnimind.nativeui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.AgentBrandIcon
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.hasKnownAgentBrand
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.max

private const val RunFoldMillis = 320
private val RunFoldEasing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * One Agent run (Flutter `AgentRunGroupMessage`, ACP presentation): a single
 * header, then the turn in arrival order. Process cards and every prose entry
 * except the final reply fold behind the header once the run finishes; plans
 * and failure messages stay visible. A running turn is always open.
 */
@Composable
internal fun AgentRunGroupBlock(
    group: AgentRunTimelineGroup,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    agentAvatar: ImageBitmap?,
    renderMessage: @Composable (message: ChatMessageUi, hideThinkingAvatar: Boolean) -> Unit,
) {
    val effectiveExpanded = group.isRunning || expanded
    val layout = remember(group) { AgentRunGroupLayout.of(group) }
    // Keyed by the tool-run's message ids, like Flutter `_toolGroupKey`.
    val expandedToolGroups = remember(group.taskId) { mutableStateMapOf<String, Boolean>() }

    Column(Modifier.fillMaxWidth()) {
        AgentRunHeader(
            group = group,
            agentAvatar = agentAvatar,
            activeToolLabel = layout.activeToolLabel,
            expanded = effectiveExpanded,
            onToggleExpanded = if (group.isRunning || !layout.hasFoldableHistory) null else onToggleExpanded,
        )
        for (segment in group.segmentsOldestFirst) {
            val message = segment.message
            when {
                segment.isProcess -> RunFold(effectiveExpanded, Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                    Column {
                        for (block in processBlocks(segment.messages)) {
                            if (block.size > 1) {
                                val key = block.joinToString("-") { it.id }
                                AgentToolCallGroup(
                                    messages = block,
                                    expanded = expandedToolGroups[key] == true,
                                    onToggle = { expandedToolGroups[key] = expandedToolGroups[key] != true },
                                ) { renderMessage(it, false) }
                            } else {
                                renderMessage(block.single(), block.single().id == layout.firstThinkingId)
                            }
                        }
                    }
                }
                isAgentTurnFailureMessage(message) -> renderMessage(message, false)
                // ACP plans are mutable snapshots: the current plan stays out of the fold.
                isAgentPlanMessage(message) -> Box(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
                    renderMessage(message, false)
                }
                message.id != layout.primaryVisibleMessageId -> RunFold(effectiveExpanded) {
                    renderMessage(message, false)
                }
                else -> renderMessage(message, false)
            }
        }
    }
}

/** Run-level decisions derived once per group instance. */
private class AgentRunGroupLayout(
    val primaryVisibleMessageId: String?,
    val hasFoldableHistory: Boolean,
    val firstThinkingId: String?,
    val activeToolLabel: String?,
) {
    companion object {
        fun of(group: AgentRunTimelineGroup): AgentRunGroupLayout {
            val primary = group.visibleMessagesOldestFirst.lastOrNull { !isAgentTurnFailureMessage(it) }?.id
            return AgentRunGroupLayout(
                primaryVisibleMessageId = primary,
                hasFoldableHistory = group.segmentsOldestFirst.any { it.isProcess || it.message.id != primary },
                firstThinkingId = group.processMessagesOldestFirst.firstOrNull { it.thinkingCard != null }?.id,
                // Flutter `_activeToolLabel`: the newest live tool names the running header.
                activeToolLabel = group.processMessagesNewestFirst
                    .firstNotNullOfOrNull { message ->
                        message.toolCard?.takeIf { it.status == "running" || it.status == "pending" }?.title
                    },
            )
        }
    }
}

/** Consecutive tool cards inside a process segment collapse into one group row. */
private fun processBlocks(messages: List<ChatMessageUi>): List<List<ChatMessageUi>> {
    val blocks = ArrayList<List<ChatMessageUi>>()
    var index = 0
    while (index < messages.size) {
        if (messages[index].toolCard == null) {
            blocks.add(listOf(messages[index]))
            index += 1
            continue
        }
        var next = index + 1
        while (next < messages.size && messages[next].toolCard != null) next += 1
        blocks.add(messages.subList(index, next))
        index = next
    }
    return blocks
}

@Composable
private fun RunFold(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(tween(RunFoldMillis, easing = RunFoldEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(RunFoldMillis, delayMillis = RunFoldMillis / 10)),
        exit = shrinkVertically(tween(RunFoldMillis, easing = RunFoldEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(RunFoldMillis * 9 / 10)),
    ) { content() }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

/**
 * The single header above every Agent turn (Flutter `AgentRunHeader`):
 * avatar, "正在处理 Ns" (or the live tool) while running, "已处理 1m 5s"
 * once finished, and the fold chevron when there is history to fold.
 */
@Composable
private fun AgentRunHeader(
    group: AgentRunTimelineGroup,
    agentAvatar: ImageBitmap?,
    activeToolLabel: String?,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
) {
    val palette = LocalOmniPalette.current
    val running = group.isRunning
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val end = if (running) now else group.finishedAtMillis
    val elapsedSeconds = if (end == null) 0L else max(0L, (end - group.startedAtMillis) / 1000)
    val label = if (running) {
        if (!activeToolLabel.isNullOrBlank()) {
            stringResource(R.string.omni_run_active_tool, activeToolLabel.trim(), elapsedSeconds)
        } else {
            stringResource(R.string.omni_run_processing, elapsedSeconds)
        }
    } else {
        val base = stringResource(
            when (group.status) {
                AgentRunStatus.failed -> R.string.omni_run_failed
                AgentRunStatus.cancelled -> R.string.omni_run_cancelled
                else -> R.string.omni_run_processed
            },
        )
        val elapsed = formatRunElapsed(elapsedSeconds)
        if (elapsed.isEmpty()) base else "$base  $elapsed"
    }
    val labelColor = if (!running && expanded) palette.secondaryText else palette.tertiaryText
    val style = TextStyle(color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 13.sp)
    val chevronRotation by animateFloatAsState(
        if (expanded) 0f else -90f,
        tween(RunFoldMillis, easing = RunFoldEasing),
        label = "run-chevron",
    )
    Row(
        Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (onToggleExpanded != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onToggleExpanded,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentRunAvatar(group.agentId, agentAvatar)
        Spacer(Modifier.width(8.dp))
        BoxWithConstraints(Modifier.weight(1f, fill = false)) {
            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "run-label",
            ) { isRunning ->
                Text(
                    label,
                    Modifier.widthIn(max = maxWidth),
                    style = if (isRunning) style.copy(brush = rememberShimmerBrush(labelColor)) else style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Box(Modifier.size(18.dp)) {
            if (!running && onToggleExpanded != null) {
                OmniIcon(R.drawable.omni_chevron_down, size = 18.dp, tint = labelColor, modifier = Modifier.rotate(chevronRotation))
            }
        }
    }
}

/** Flutter `AgentRunAvatar`: bare brand marks, a soft badge for custom Agents. */
@Composable
private fun AgentRunAvatar(agentId: String, agentAvatar: ImageBitmap?) {
    if (hasKnownAgentBrand(agentId)) {
        AgentBrandIcon(agentId, agentAvatar, size = 30.dp)
        return
    }
    val palette = LocalOmniPalette.current
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (palette.dark) palette.secondarySurface.copy(alpha = .66f)
                else palette.elevatedSurface.copy(alpha = .92f),
            )
            .border(.5.dp, palette.border.copy(alpha = if (palette.dark) .48f else .72f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AgentBrandIcon(agentId, agentAvatar, size = 18.dp, tint = palette.tertiaryText)
    }
}

/** "47s" / "1m 23s" / "1h 5m"; empty for sub-second runs. */
internal fun formatRunElapsed(totalSeconds: Long): String {
    if (totalSeconds < 1) return ""
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    if (minutes < 60) {
        val seconds = totalSeconds % 60
        return if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
    }
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return if (remainingMinutes == 0L) "${hours}h" else "${hours}h ${remainingMinutes}m"
}

// ---------------------------------------------------------------------------
// Tool call group
// ---------------------------------------------------------------------------

/**
 * Several consecutive tool calls (Flutter `_AgentToolCallGroup`): one row
 * with the live tool's title (or "已处理"), a count and a chevron; the
 * individual cards expand underneath.
 */
@Composable
private fun AgentToolCallGroup(
    messages: List<ChatMessageUi>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderMessage: @Composable (ChatMessageUi) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val cards = messages.mapNotNull { it.toolCard }
    val primary = cards.firstOrNull { it.status == "running" } ?: cards.first()
    val live = primary.status == "running" || primary.status == "pending"
    val title = if (live) primary.title else stringResource(R.string.omni_run_processed)
    val muted = palette.secondaryText.copy(alpha = if (palette.dark) .78f else .68f)
    val titleColor = palette.secondaryText.copy(alpha = if (palette.dark) .94f else .88f)
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "tool-group-chevron")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 6.dp, bottom = 4.dp).widthIn(max = maxWidth * 0.9f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(start = 2.dp, top = 5.dp, end = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(agentToolStatusIcon(primary.status, primary.toolType), size = 16.dp, tint = muted)
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    Modifier.weight(1f),
                    color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text("${messages.size}", color = muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                OmniIcon(R.drawable.omni_chevron_down, size = 18.dp, tint = muted, modifier = Modifier.rotate(chevronRotation))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(260), expandFrom = Alignment.Top) + fadeIn(tween(260)),
                exit = shrinkVertically(tween(260), shrinkTowards = Alignment.Top) + fadeOut(tween(200)),
            ) {
                Column(Modifier.padding(top = 2.dp)) { messages.forEach { renderMessage(it) } }
            }
        }
    }
}
