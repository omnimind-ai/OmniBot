package cn.com.omnimind.nativeui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

private val ThinkingTextLight = Color(0x80353E53)

// ---------------------------------------------------------------------------
// agent_request
// ---------------------------------------------------------------------------

/**
 * Compact ACP request card (Flutter `AgentRequestNotice`). Approvals carry
 * allow/deny actions while the request is live; user-input requests point
 * at the composer. Only the saved outcome is shown for history.
 */
@Composable
fun AgentRequestNotice(
    card: AgentRequestCardUi,
    canRespond: Boolean,
    responding: Boolean,
    onRespond: (accepted: Boolean) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val title = card.title.ifEmpty {
        stringResource(if (card.isApproval) R.string.omni_request_permission_title else R.string.omni_request_question_title)
    }
    val outcome = when (card.status) {
        "accepted" -> R.string.omni_request_accepted
        "declined" -> R.string.omni_request_declined
        "submitted" -> R.string.omni_request_submitted
        "ignored" -> R.string.omni_request_ignored
        "cancelled" -> R.string.omni_request_cancelled
        "failed" -> R.string.omni_request_failed
        "expired" -> R.string.omni_request_expired
        else -> null
    }
    // History rows cannot answer a live JSON-RPC request either.
    val unavailable = card.interactionUnavailable || !canRespond
    val statusLabel = outcome ?: if (unavailable) {
        if (card.sessionEnded) R.string.omni_request_session_ended else R.string.omni_request_unavailable
    } else {
        null
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .clip(shape)
            .background(if (palette.dark) palette.secondarySurface else palette.surface)
            .border(1.dp, palette.border, shape)
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
    ) {
        OmniIcon(
            if (card.isApproval) R.drawable.omni_shield else R.drawable.omni_circle_help,
            size = 17.dp,
            tint = palette.accent,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (card.detail.isNotEmpty() && card.detail != title) {
                Text(
                    card.detail,
                    Modifier.padding(top = 2.dp),
                    color = palette.secondaryText, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            if (card.kind == "user_input" && card.isPending && !unavailable) {
                Text(
                    stringResource(R.string.omni_request_reply_below),
                    Modifier.padding(top = 2.dp),
                    color = palette.secondaryText, fontSize = 12.sp,
                )
            }
            if (card.isApproval && card.isPending && !unavailable) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        stringResource(R.string.omni_request_deny),
                        { onRespond(false) },
                        enabled = !responding,
                    )
                    TextButton(
                        stringResource(R.string.omni_request_allow),
                        { onRespond(true) },
                        enabled = !responding,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
            if (statusLabel != null) {
                Text(
                    stringResource(statusLabel),
                    Modifier.padding(top = 2.dp),
                    color = palette.secondaryText, fontSize = 12.sp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// deep_thinking
// ---------------------------------------------------------------------------

/**
 * Streaming reasoning (Flutter `DeepThinkingCard`): a status row, then the
 * text behind a left rule in a 210dp window that follows the newest line
 * until the user scrolls up. Finished cards fold behind their status row.
 *
 * Flutter's paced character reveal is not ported: the text appears as the
 * runtime publishes it.
 */
@Composable
fun DeepThinkingCard(
    card: DeepThinkingCardUi,
    avatar: ImageBitmap?,
    showAvatar: Boolean = card.showAvatar,
    collapsible: Boolean = true,
    autoCollapseOnComplete: Boolean = true,
) {
    if (!card.hasContent && !card.isActivelyThinking) return
    val palette = LocalOmniPalette.current
    val textColor = if (palette.dark) palette.text else ThinkingTextLight
    val secondaryColor = if (palette.dark) palette.secondaryText else textColor.copy(alpha = textColor.alpha * .68f)
    val canCollapse = collapsible && card.stage == 4
    val shouldAutoCollapse = autoCollapseOnComplete && canCollapse && !card.isLoading
    var collapsed by rememberSaveable { mutableStateOf(shouldAutoCollapse) }
    // Collapse once when the card finishes; reopen if it starts thinking again.
    var autoCollapseHandled by rememberSaveable { mutableStateOf(shouldAutoCollapse) }
    LaunchedEffect(shouldAutoCollapse, card.isActivelyThinking) {
        if (shouldAutoCollapse && !autoCollapseHandled) {
            collapsed = true
            autoCollapseHandled = true
        } else if (card.isActivelyThinking) {
            collapsed = false
            autoCollapseHandled = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ThinkingStatusRow(
            card = card,
            avatar = avatar.takeIf { showAvatar },
            color = secondaryColor,
            collapsed = collapsed,
            onToggle = if (canCollapse && card.hasContent) ({ collapsed = !collapsed }) else null,
        )
        AnimatedVisibility(visible = card.hasContent && card.stage != 5 && !(canCollapse && collapsed)) {
            ThinkingText(card, textColor)
        }
        if (card.stage == 5) {
            Text(
                stringResource(R.string.omni_thinking_task_cancelled),
                Modifier.padding(top = 8.dp),
                color = secondaryColor, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Flutter `BotStatus`: avatar, hint or "thought for", and the fold chevron. */
@Composable
private fun ThinkingStatusRow(
    card: DeepThinkingCardUi,
    avatar: ImageBitmap?,
    color: Color,
    collapsed: Boolean,
    onToggle: (() -> Unit)?,
) {
    val elapsed = card.completedElapsedSeconds?.let { seconds ->
        if (seconds < 60) {
            stringResource(R.string.omni_elapsed_seconds, seconds.toInt())
        } else {
            stringResource(R.string.omni_elapsed_minutes, (seconds / 60).toInt(), (seconds % 60).toInt())
        }
    }
    val label = when {
        !card.isCompletedStage -> stringResource(R.string.omni_thinking_in_progress)
        elapsed != null -> stringResource(R.string.omni_thinking_done_elapsed, elapsed)
        else -> stringResource(R.string.omni_thinking_done)
    }
    val style = TextStyle(color = color, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = .33.sp)
    val chevronRotation by animateFloatAsState(if (collapsed) 0f else 180f, label = "thinking-chevron")
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) {
            Image(avatar, null, Modifier.size(30.dp).clip(CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = if (card.isCompletedStage) style else style.copy(brush = rememberShimmerBrush(color)),
        )
        if (onToggle != null) {
            Spacer(Modifier.width(2.dp))
            OmniIcon(R.drawable.omni_chevron_down, size = 16.dp, tint = color, modifier = Modifier.rotate(chevronRotation))
        }
    }
}

@Composable
private fun ThinkingText(card: DeepThinkingCardUi, textColor: Color) {
    val palette = LocalOmniPalette.current
    val scroll = rememberScrollState()
    var followLatest by remember { mutableStateOf(true) }
    // A drag that ends away from the bottom stops following; returning resumes it.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) followLatest = scroll.value >= scroll.maxValue - 1
        }
    }
    LaunchedEffect(card.text, scroll.maxValue) {
        if (followLatest && scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }
    val showFade = scroll.maxValue > 0 && scroll.value < scroll.maxValue - 1
    val rule = if (palette.dark) palette.border else Color(0x1A353E53)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .heightIn(max = 210.dp)
            .drawBehind { drawLine(rule, Offset(.5f, 0f), Offset(.5f, size.height), strokeWidth = 1.dp.toPx()) },
    ) {
        Text(
            card.text,
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 12.dp),
            color = textColor, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = .33.sp,
        )
        if (showFade) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Brush.verticalGradient(listOf(palette.page.copy(alpha = 0f), palette.page.copy(alpha = .8f), palette.page))),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Small markers
// ---------------------------------------------------------------------------

/** Flutter `ContextCompactionMarkerCard`: a centered chip between two rules. */
@Composable
fun ContextCompactionMarker(status: String, label: String) {
    val palette = LocalOmniPalette.current
    val color = when (status) {
        "compressing" -> Color(0xFF2C7FEB)
        "failed" -> Color(0xFFE45D5D)
        "noop" -> palette.secondaryText
        else -> if (palette.dark) palette.accent else Color(0xFF2F9D62)
    }
    val text = label.ifEmpty {
        stringResource(
            when (status) {
                "compressing" -> R.string.omni_compaction_running
                "noop" -> R.string.omni_compaction_noop
                "failed" -> R.string.omni_compaction_failed
                else -> R.string.omni_compaction_done
            },
        )
    }
    val line = color.copy(alpha = if (palette.dark) .5f else .28f)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Row(
            Modifier
                .padding(horizontal = 12.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (palette.dark) .16f else .1f))
                .border(1.dp, line, CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == "compressing") {
                CircularProgressIndicator(
                    size = 12.dp,
                    strokeWidth = 2.dp,
                    colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = color),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .1.sp)
        }
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

/** Flutter `_HistoryOmittedCard`: a folded process card from old history. */
@Composable
fun HistoryOmittedCard(summary: String, originalType: String) {
    val palette = LocalOmniPalette.current
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(top = 6.dp, bottom = 2.dp)
                .widthIn(max = maxWidth * 0.78f)
                .clip(shape)
                .background(palette.secondarySurface.copy(alpha = .55f))
                .border(1.dp, palette.border.copy(alpha = .6f), shape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OmniIcon(R.drawable.omni_history, size = 16.dp, tint = palette.secondaryText)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    summary.ifEmpty { stringResource(R.string.omni_history_omitted_title) },
                    color = palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    originalType.ifEmpty { stringResource(R.string.omni_history_omitted_subtitle) },
                    color = palette.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
