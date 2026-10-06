package cn.com.omnimind.nativeui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.AgentBrandIcon
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Text

private val ActivityRowHeight = 32.dp
private val ActivityShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)

// ---------------------------------------------------------------------------
// Tool activity strip
// ---------------------------------------------------------------------------

/**
 * The bar above the composer that names the live tool (Flutter
 * `ChatToolActivityStrip`): the active row with a stop button while it runs,
 * and the run's other tools in a drawer above it.
 *
 * Browser/terminal preview thumbnails are not ported; tapping a row opens the
 * same detail sheet as the card.
 */
@Composable
fun ChatToolActivityStrip(
    messages: List<ChatMessageUi>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenDetail: (messageId: String) -> Unit,
    stopPending: Boolean,
    onStop: ((messageId: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val active = resolveActiveAgentToolMessage(messages) ?: return
    val activeCard = active.toolCard ?: return
    val history = messages.filter { it.id != active.id && it.toolCard != null }
    val canExpand = history.isNotEmpty()
    val isExpanded = expanded && canExpand
    val palette = LocalOmniPalette.current
    val divider = if (palette.dark) palette.border.copy(alpha = .52f) else Color(0x140F2034)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .shadow(10.dp, ActivityShape, clip = false, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
            .clip(ActivityShape)
            .background(if (palette.dark) palette.elevatedSurface.copy(alpha = .94f) else Color.White.copy(alpha = .92f))
            .border(1.dp, palette.border.copy(alpha = if (palette.dark) .5f else .7f), ActivityShape),
    ) {
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(tween(240), expandFrom = Alignment.Bottom) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(240), shrinkTowards = Alignment.Bottom) + fadeOut(tween(200)),
        ) {
            Column {
                // Newest at the bottom, next to the active row; up to five rows before scrolling.
                LazyColumn(Modifier.heightIn(max = 264.dp), reverseLayout = true) {
                    items(history, key = { it.id }) { message ->
                        Column {
                            ToolActivityRow(message.toolCard!!, onClick = { onOpenDetail(message.id) })
                            Box(Modifier.fillMaxWidth().height(1.dp).background(divider))
                        }
                    }
                }
                Box(Modifier.padding(start = 18.dp, end = 10.dp).fillMaxWidth().height(1.dp).background(divider))
            }
        }
        ToolActivityRow(
            activeCard,
            onClick = { if (canExpand) onExpandedChange(!isExpanded) else onOpenDetail(active.id) },
            trailing = {
                when {
                    activeCard.status == "running" && onStop != null ->
                        ToolStopButton(enabled = !stopPending) { onStop(active.id) }
                    canExpand -> {
                        val rotation by animateFloatAsState(if (isExpanded) 0f else 180f, tween(220), label = "strip-chevron")
                        OmniIcon(
                            R.drawable.omni_chevron_down,
                            size = 14.dp,
                            tint = if (palette.dark) palette.secondaryText else Color(0xFF657891),
                            modifier = Modifier.rotate(rotation + 180f),
                        )
                    }
                }
            },
        )
    }
}

/** Flutter `ToolActivityRow`: status dot, title, type label and status tag. */
@Composable
private fun ToolActivityRow(
    card: AgentToolCardUi,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalOmniPalette.current
    val primary = palette.text
    val secondary = if (palette.dark) palette.secondaryText else Color(0xFF7C8DA5)
    val statusColor = agentToolStatusColor(card.status)
    Row(
        Modifier
            .fillMaxWidth()
            .height(ActivityRowHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (palette.dark) lerp(palette.elevatedSurface, statusColor, .14f) else statusColor.copy(alpha = .16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(3.dp).background(statusColor, CircleShape))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            card.detail.title,
            Modifier.weight(1f),
            color = primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            card.detail.typeLabel,
            Modifier.widthIn(max = 34.dp),
            color = secondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            card.detail.statusLabel,
            Modifier
                .background(
                    if (palette.dark) lerp(palette.elevatedSurface, statusColor, .14f) else statusColor.copy(alpha = .11f),
                    CircleShape,
                )
                .padding(horizontal = 5.dp, vertical = 2.dp),
            color = if (palette.dark) lerp(palette.secondaryText, statusColor, .38f) else statusColor.copy(alpha = .9f),
            fontSize = 8.4.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) { trailing() }
        }
    }
}

@Composable
private fun ToolStopButton(enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    val base = if (palette.dark) palette.secondaryText else Color(0xFF657891)
    val foreground = if (enabled) base else base.copy(alpha = .42f)
    Box(
        Modifier
            .size(24.dp, ActivityRowHeight)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.omni_tool_stop), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(
                    if (palette.dark) palette.elevatedSurface.copy(alpha = if (enabled) .88f else .72f)
                    else Color.White.copy(alpha = if (enabled) .9f else .72f),
                    CircleShape,
                )
                .border(1.dp, foreground.copy(alpha = if (enabled) .48f else .3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(6.5.dp).background(foreground, RoundedCornerShape(1.8.dp)))
        }
    }
}

// ---------------------------------------------------------------------------
// Message anchors
// ---------------------------------------------------------------------------

/**
 * Jump navigation (Flutter `ChatMessageAnchorBar`): a round button that opens
 * a column of avatars with each message's first line; tapping one scrolls the
 * list to it.
 *
 * The Flutter fan layout, magnifier drag and system-bar spotlight are not
 * ported; this is a plain Miuix-style popup list.
 */
@Composable
fun ChatMessageAnchorBar(
    anchors: List<ChatMessageAnchor>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onJump: (entryKey: String) -> Unit,
    agentAvatar: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    if (anchors.isEmpty()) return
    val palette = LocalOmniPalette.current
    Column(modifier, horizontalAlignment = Alignment.End) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(200)) + scaleIn(tween(240), initialScale = .9f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = .9f, transformOrigin = TransformOrigin(1f, 1f)),
        ) {
            val shape = RoundedCornerShape(18.dp)
            LazyColumn(
                Modifier
                    .padding(bottom = 8.dp)
                    .widthIn(max = 240.dp)
                    .heightIn(max = 360.dp)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(if (palette.dark) palette.elevatedSurface else palette.surface),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(anchors.asReversed(), key = { it.entryKey }) { anchor ->
                    AnchorRow(anchor, agentAvatar) {
                        onExpandedChange(false)
                        onJump(anchor.entryKey)
                    }
                }
            }
        }
        val tint by animateFloatAsState(if (expanded) 1f else 0f, tween(220), label = "anchor-tint")
        Box(
            Modifier
                .size(34.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    lerp(
                        if (palette.dark) palette.elevatedSurface else Color.White,
                        lerp(palette.surface, palette.accent, .16f),
                        tint,
                    ),
                )
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.omni_anchor_open)) { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            OmniIcon(R.drawable.omni_gallery_vertical_end, size = 16.dp, tint = lerp(palette.tertiaryText, palette.accent, tint))
        }
    }
}

@Composable
private fun AnchorRow(anchor: ChatMessageAnchor, agentAvatar: ImageBitmap?, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            val agentId = anchor.agentId?.trim().orEmpty()
            when {
                anchor.isUser -> Box(
                    Modifier
                        .size(26.dp)
                        .background(if (palette.dark) palette.surface.copy(alpha = .58f) else palette.secondarySurface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { OmniIcon(R.drawable.omni_user, size = 15.dp, tint = palette.secondaryText) }
                // Plain assistant replies are Xiaowan's.
                else -> AgentBrandIcon(agentId.ifEmpty { "xiaowan-acp" }, agentAvatar, size = 26.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            anchor.preview.ifEmpty { stringResource(R.string.omni_anchor_working) },
            color = palette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
