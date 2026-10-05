package cn.com.omnimind.nativeui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * One `agent_tool_summary` card (Flutter `AgentToolSummaryCard`): a status
 * capsule, or a flat inline row for file edits and agent-native tools. File
 * rows with a diff expand it in place; the rest open the detail sheet.
 */
@Composable
fun AgentToolCard(card: AgentToolCardUi, onOpenDetail: (AgentToolCardUi) -> Unit) {
    when (card.style) {
        AgentToolCardStyle.Capsule -> ToolCapsule(card) { onOpenDetail(card) }
        AgentToolCardStyle.Inline -> InlineToolRow(card, onOpenDetail)
    }
}

/** Flutter `resolveAgentToolStatusColor`. */
internal fun agentToolStatusColor(status: String): Color = when (status) {
    "success" -> Color(0xFF2F8F4E)
    "error" -> Color(0xFFFF6464)
    "timeout" -> Color(0xFFFF8A3D)
    "interrupted" -> Color(0xFFFFC04D)
    else -> Color(0xFF2C7FEB)
}

/** Flutter `resolveAgentToolStatusIcon` (Lucide). */
@DrawableRes
internal fun agentToolStatusIcon(status: String, toolType: String): Int = when {
    status == "timeout" -> R.drawable.omni_hourglass
    status == "interrupted" -> R.drawable.omni_circle_stop
    status == "error" -> R.drawable.omni_triangle_alert
    toolType == "terminal" -> R.drawable.omni_square_terminal
    toolType == "browser" -> R.drawable.omni_globe
    toolType == "search" -> R.drawable.omni_search
    toolType == "image" -> R.drawable.omni_image
    toolType == "file" -> R.drawable.omni_file_pen_line
    toolType == "calendar" -> R.drawable.omni_calendar_days
    toolType == "alarm" || toolType == "schedule" -> R.drawable.omni_alarm_clock
    toolType == "memory" -> R.drawable.omni_brain
    toolType == "workspace" -> R.drawable.omni_folder
    toolType == "subagent" -> R.drawable.omni_network
    toolType == "review" -> R.drawable.omni_message_square
    toolType == "mcp" -> R.drawable.omni_puzzle
    else -> R.drawable.omni_circle_check
}

@Composable
private fun ToolCapsule(card: AgentToolCardUi, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    val dark = palette.dark
    val statusColor = agentToolStatusColor(card.status)
    val live = card.status == "running" || card.status == "pending"
    val background = lerp(palette.surface, statusColor, if (dark) (if (live) .07f else .045f) else (if (live) .055f else .035f))
    val badgeText = if (dark) lerp(palette.secondaryText, statusColor, .38f) else statusColor
    val titleStyle = TextStyle(color = palette.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp)) {
        Row(
            Modifier
                .widthIn(max = maxWidth * 0.78f)
                .clip(CircleShape)
                .background(background)
                .border(1.dp, statusColor.copy(alpha = if (dark) .22f else .16f), CircleShape)
                .clickable(onClick = onClick)
                .padding(start = 10.dp, end = 9.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadgeIcon(card, statusColor)
            Spacer(Modifier.width(7.dp))
            ToolTitle(card.title, titleStyle, shimmer = card.isActive, modifier = Modifier.weight(1f, fill = false))
            card.diffStat?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it.label,
                    Modifier.background(Color.White.copy(alpha = .55f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
                    color = palette.secondaryText, fontSize = 9.5.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                card.badgeLabel,
                Modifier
                    .background(statusColor.copy(alpha = if (dark) .13f else .09f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                color = badgeText, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusBadgeIcon(card: AgentToolCardUi, statusColor: Color) {
    val palette = LocalOmniPalette.current
    val iconColor = if (palette.dark) lerp(palette.secondaryText, statusColor, .38f) else statusColor
    Box(
        Modifier.size(24.dp).background(statusColor.copy(alpha = if (palette.dark) .16f else .10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (card.status) {
            "running" -> CircularProgressIndicator(
                size = 14.dp,
                strokeWidth = 1.7.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = iconColor,
                    backgroundColor = Color.Transparent,
                ),
            )
            "pending" -> OmniIcon(R.drawable.omni_hourglass, size = 15.dp, tint = iconColor)
            else -> OmniIcon(agentToolStatusIcon(card.status, card.toolType), size = 15.dp, tint = iconColor)
        }
    }
}

@Composable
private fun InlineToolRow(card: AgentToolCardUi, onOpenDetail: (AgentToolCardUi) -> Unit) {
    val palette = LocalOmniPalette.current
    var expanded by rememberSaveable(card.cardId) { mutableStateOf(false) }
    val diff = card.diff?.takeIf { it.files.isNotEmpty() }
    val titleColor = palette.secondaryText.copy(alpha = .92f)
    val muted = palette.secondaryText.copy(alpha = .64f)
    val chevronTurn by animateFloatAsState(if (expanded) 180f else 0f, tween(220, easing = FastOutSlowInEasing))
    val onClick: (() -> Unit)? = when {
        diff != null -> ({ expanded = !expanded })
        card.opensDetail -> ({ onOpenDetail(card) })
        else -> null
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Column(Modifier.widthIn(max = maxWidth * 0.90f).animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                    .padding(start = 2.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(agentToolStatusIcon(card.status, card.toolType), size = 16.dp, tint = muted)
                Spacer(Modifier.width(6.dp))
                val baseStyle = TextStyle(color = titleColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (card.fileName.isNotEmpty() && card.title.contains(card.fileName)) {
                    InlineFileTitle(card.title, card.fileName, baseStyle, palette.accent, Modifier.weight(1f))
                } else {
                    ToolTitle(card.title, baseStyle, shimmer = card.isActive, modifier = Modifier.weight(1f))
                }
                card.diffStat?.let { stat ->
                    Spacer(Modifier.width(8.dp))
                    InlineDiffStat(stat, muted)
                }
                if (card.badgeLabel.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(card.badgeLabel, color = muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                if (diff != null) {
                    Spacer(Modifier.width(4.dp))
                    OmniIcon(R.drawable.omni_chevron_down, size = 18.dp, tint = muted, modifier = Modifier.rotate(chevronTurn))
                }
            }
            if (expanded && diff != null) {
                val colors = agentDiffColors()
                AgentDiffView(
                    diff,
                    Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.contextBackground),
                    showOverview = false,
                    showFileHeaders = false,
                )
            }
        }
    }
}

/** Title with its last path segment in the accent color (Flutter `_InlineFileTitleText`). */
@Composable
private fun InlineFileTitle(title: String, fileName: String, style: TextStyle, accent: Color, modifier: Modifier) {
    val start = title.lastIndexOf(fileName)
    val text = buildAnnotatedString {
        append(title.substring(0, start))
        withStyle(SpanStyle(color = accent)) { append(fileName) }
        append(title.substring(start + fileName.length))
    }
    Text(text, modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun InlineDiffStat(stat: AgentDiffStatUi, muted: Color) {
    val colors = agentDiffColors()
    val parts = stat.label.split(' ', limit = 2)
    val text = buildAnnotatedString {
        if (parts.size == 2) {
            withStyle(SpanStyle(color = if (stat.additions > 0) colors.addAccent else muted)) { append(parts[0]) }
            append(' ')
            withStyle(SpanStyle(color = if (stat.deletions > 0) colors.removeAccent else muted)) { append(parts[1]) }
        } else {
            append(stat.label)
        }
    }
    Text(text, color = muted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * Single-line tool title. While the tool runs, a soft highlight sweeps
 * across it (Flutter `_FlowingToolTitleText`, 1.8 s loop).
 */
@Composable
private fun ToolTitle(text: String, style: TextStyle, shimmer: Boolean, modifier: Modifier = Modifier) {
    if (!shimmer) {
        Text(text, modifier, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val base = style.color
    val highlight = lerp(base, if (LocalOmniPalette.current.dark) Color.White else Color(0xFF2C7FEB), .55f)
    val progress by rememberInfiniteTransition(label = "tool-title").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "tool-title-sweep",
    )
    val width = with(LocalDensity.current) { 240.dp.toPx() }
    val brush = Brush.linearGradient(
        0.08f to base, 0.5f to highlight, 0.92f to base,
        start = Offset(width * progress - width / 2, 0f),
        end = Offset(width * progress + width / 2, 0f),
    )
    Text(text, modifier, style = style.copy(brush = brush), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Tool detail (Flutter `showAgentToolDetailSheet`): type and status chips,
 * copy, then either the diff or the terminal-styled transcript, and the
 * card's follow-up actions.
 */
@Composable
fun AgentToolDetailSheet(
    detail: AgentToolDetailUi?,
    onDismiss: () -> Unit,
    onAction: (AgentToolActionUi) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.omni_tool_copied)
    OverlayBottomSheet(
        show = detail != null,
        title = detail?.title,
        backgroundColor = palette.page,
        onDismissRequest = onDismiss,
        endAction = {
            if (detail != null && detail.copyText.isNotEmpty()) {
                OmniIconButton(
                    R.drawable.omni_copy,
                    stringResource(R.string.omni_tool_copy_details),
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(detail.title, detail.copyText))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    size = 20.dp,
                )
            }
        },
    ) {
        if (detail == null) return@OverlayBottomSheet
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailChip(detail.typeLabel, palette.secondaryText, palette.secondarySurface)
                val statusColor = agentToolStatusColor(detail.status)
                DetailChip(detail.statusLabel, statusColor, statusColor.copy(alpha = .12f))
            }
            val diff = detail.diff
            if (diff != null) {
                AgentDiffView(diff, Modifier.fillMaxWidth().heightIn(max = 520.dp))
            } else {
                TerminalTranscript(detail)
            }
            if (detail.actions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.actions.forEach { action -> TextButton(action.label, onClick = { onAction(action) }) }
                }
            }
        }
    }
}

@Composable
private fun DetailChip(label: String, color: Color, background: Color) {
    if (label.isBlank()) return
    Text(
        label,
        Modifier.background(background, CircleShape).padding(horizontal = 9.dp, vertical = 4.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
    )
}

/** Dark terminal surface: bold prompt line, ANSI-colored output (Flutter `_buildDetailTextSpan`). */
@Composable
private fun TerminalTranscript(detail: AgentToolDetailUi) {
    val output = remember(detail.outputText) { ansiAnnotatedString(detail.outputText) }
    val text = remember(detail.promptLine, output) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFF4F7FB), fontWeight = FontWeight.SemiBold)) { append(detail.promptLine) }
            if (detail.outputText.isNotBlank()) {
                append('\n')
                append(output)
            }
        }
    }
    SelectionContainer {
        Text(
            text,
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0B0F14))
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            color = Color(0xFFB9F7C9),
            fontSize = 12.sp,
            lineHeight = 17.4.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
