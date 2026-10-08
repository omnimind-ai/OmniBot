package cn.com.omnimind.nativeui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.overlay.OverlayListPopup

/**
 * The Compose chat composer (batch 5d-1b): text field, pending attachments,
 * the primary send/stop button, the Agent permission menu and the context
 * ring. Ported from the large `ChatInputArea` layout; it renders
 * [ChatComposerState] and emits [ChatComposerActions] only.
 *
 * The draft lives here (saveable), not in the ViewModel: the composer is the
 * only writer, and [ChatComposerActions.onSend] returns the next draft. A
 * leading slash opens the command panel above the field (5d-1c).
 */
@Composable
fun ChatComposer(
    state: ChatComposerState,
    actions: ChatComposerActions,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOmniPalette.current
    var draft by rememberSaveable { mutableStateOf("") }
    var adoptedDraftKey by rememberSaveable { mutableStateOf(0L) }
    // Filling the field does not admit a turn; only the send button does.
    state.injectedDraft?.let { injected ->
        if (injected.key != adoptedDraftKey) {
            adoptedDraftKey = injected.key
            draft = injected.text
        }
    }
    if (!state.available) {
        if (state.handoffToChat) {
            Row(
                modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.omni_composer_history_only),
                    Modifier.weight(1f),
                    color = palette.tertiaryText,
                    fontSize = 12.sp,
                )
                TextButton(text = stringResource(R.string.omni_open_in_chat), onClick = actions.onOpenInChat)
            }
        }
        return
    }
    val primary = chatComposerPrimaryAction(state.isProcessing, draft, state.attachments.isNotEmpty())
    fun submit(text: String) {
        actions.onSend(text)?.let { draft = it }
    }
    // The panel follows the draft: a leading slash lists the commands.
    val slashEntries = remember(draft, state.slash) { state.slash.entries(draft) }
    Column(modifier.fillMaxWidth()) {
    if (slashEntries.isNotEmpty()) {
        ChatSlashPanel(slashEntries, onPick = { entry ->
            entry.fillText?.let { draft = it }
            entry.submitText?.let(::submit)
        })
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(22.dp))
            .animateContentSize(tween(220))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (state.editingMessageId != null) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OmniIcon(R.drawable.omni_pencil, tint = palette.accent, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.omni_message_editing), Modifier.weight(1f), color = palette.secondaryText, fontSize = 12.sp)
                OmniIconButton(R.drawable.omni_x, stringResource(R.string.omni_message_cancel_edit), {
                    draft = ""
                    actions.onCancelEdit()
                }, size = 14.dp, tint = palette.tertiaryText)
            }
        }
        if (state.attachments.isNotEmpty()) {
            ComposerAttachments(state.attachments, actions.onRemoveAttachment)
            Spacer(Modifier.height(6.dp))
        }
        TextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(if (state.awaitingAnswer) R.string.omni_composer_answer_hint else R.string.omni_chat_composer_hint),
            useLabelAsPlaceholder = true,
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = Color.Transparent,
                borderColor = Color.Transparent,
            ),
            // The field draws no border of its own; keep the caret visible.
            cursorBrush = SolidColor(palette.accent),
            // Three lines then scroll, like the Flutter composer.
            maxLines = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Default,
            ),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OmniIconButton(
                R.drawable.omni_plus,
                stringResource(R.string.omni_composer_add_attachment),
                actions.onPickAttachment,
                tint = palette.secondaryText,
            )
            state.permission?.let { current ->
                PermissionMenu(current, state.permissionChoices, actions.onSelectPermission)
            }
            Spacer(Modifier.weight(1f))
            state.contextUsage?.let {
                ContextRing(it, state.contextUsageLabel, state.contextThreshold, actions.onSaveContextThreshold)
            }
            PrimaryButton(primary, state.cancelling, onSend = { submit(draft.trim()) }, onCancel = actions.onCancel)
        }
    }
    }
}

@Composable
private fun ComposerAttachments(attachments: List<ChatComposerAttachment>, onRemove: (String) -> Unit) {
    val palette = LocalOmniPalette.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        items(attachments, key = { it.id }) { attachment ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.secondarySurface)
                    .padding(start = 10.dp)
                    .width(160.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(
                    if (attachment.isImage) R.drawable.omni_image else R.drawable.omni_file,
                    tint = palette.secondaryText,
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(attachment.name, fontSize = 12.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    attachment.size?.let {
                        Text(formatAttachmentSize(it), fontSize = 10.sp, color = palette.tertiaryText, maxLines = 1)
                    }
                }
                OmniIconButton(
                    R.drawable.omni_x,
                    stringResource(R.string.omni_composer_remove_attachment, attachment.name),
                    { onRemove(attachment.id) },
                    size = 14.dp,
                    tint = palette.tertiaryText,
                )
            }
        }
    }
}

@Composable
private fun PermissionMenu(
    current: ChatComposerPermission,
    choices: List<ChatComposerPermission>,
    onSelect: (ChatComposerPermission) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var show by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { show = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OmniIcon(permissionIcon(current), tint = palette.secondaryText, size = 16.dp)
            Spacer(Modifier.width(4.dp))
            Text(permissionLabel(current), fontSize = 12.sp, color = palette.secondaryText, maxLines = 1)
        }
        val labels = choices.map { permissionLabel(it) }
        OverlayListPopup(show = show, minWidth = 180.dp, onDismissRequest = { show = false }) {
            ListPopupColumn {
                choices.forEachIndexed { index, choice ->
                    DropdownImpl(labels[index], choices.size, choice == current, index, onSelectedIndexChange = {
                        show = false
                        if (choice != current) onSelect(choice)
                    })
                }
            }
        }
    }
}

@Composable
private fun permissionLabel(permission: ChatComposerPermission): String = stringResource(
    when (permission) {
        ChatComposerPermission.ReadOnly -> R.string.omni_composer_permission_read_only
        ChatComposerPermission.Default -> R.string.omni_composer_permission_default
        ChatComposerPermission.AutoReview -> R.string.omni_composer_permission_auto_review
        ChatComposerPermission.FullAccess -> R.string.omni_composer_permission_full_access
    },
)

private fun permissionIcon(permission: ChatComposerPermission): Int = when (permission) {
    ChatComposerPermission.ReadOnly -> R.drawable.omni_eye
    ChatComposerPermission.Default -> R.drawable.omni_shield
    ChatComposerPermission.AutoReview -> R.drawable.omni_shield_check
    ChatComposerPermission.FullAccess -> R.drawable.omni_zap
}

@Composable
private fun ContextRing(
    ring: ContextUsageRing,
    label: String?,
    threshold: Int?,
    onSaveThreshold: (Int) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var showLabel by rememberSaveable { mutableStateOf(false) }
    var editThreshold by rememberSaveable { mutableStateOf(false) }
    val progress by animateFloatAsState(ring.progress, tween(220), label = "contextUsage")
    val color = when (ring.level) {
        ContextUsageLevel.Full -> if (palette.dark) Color(0xFFB97862) else Color(0xFFD65A3A)
        ContextUsageLevel.Warning -> if (palette.dark) Color(0xFFB39B6B) else Color(0xFFC69234)
        ContextUsageLevel.Normal -> if (palette.dark) palette.accent else Color(0xFF5A8DDE)
    }
    val track = if (palette.dark) palette.strongBorder else Color(0x18000000)
    val description = stringResource(R.string.omni_composer_context_usage)
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                // Long press edits the compression threshold (Dart `_handleContextUsageRingLongPress`).
                .combinedClickable(
                    onClick = { showLabel = !showLabel },
                    onLongClick = if (threshold != null) ({ editThreshold = true }) else null,
                )
                .semantics { contentDescription = label ?: description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, false, style = stroke)
                drawArc(color, -90f, 360f * progress, false, style = stroke)
            }
        }
        if (showLabel && label != null) {
            OverlayListPopup(show = true, minWidth = 160.dp, onDismissRequest = { showLabel = false }) {
                Text(label, fontSize = 12.sp, color = palette.text, modifier = Modifier.padding(12.dp))
            }
        }
    }
    if (threshold != null) {
        ContextThresholdSheet(
            show = editThreshold,
            threshold = threshold,
            onDismiss = { editThreshold = false },
            onSave = { value ->
                editThreshold = false
                if (value != threshold) onSaveThreshold(value)
            },
        )
    }
}

/**
 * The compression threshold editor (Dart `_ContextThresholdSheet`): preset
 * chips and a typed positive integer. Saved on confirm instead of the Dart
 * sheet's 320 ms autosave, so a half-typed number never reaches the store.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextThresholdSheet(show: Boolean, threshold: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val palette = LocalOmniPalette.current
    var text by rememberSaveable(threshold, show) { mutableStateOf(threshold.toString()) }
    var error by rememberSaveable(show) { mutableStateOf<ThresholdInputError?>(null) }
    OverlayBottomSheet(show = show, title = stringResource(R.string.omni_threshold_title), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.omni_threshold_summary), color = palette.secondaryText, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CONTEXT_THRESHOLD_PRESETS.forEach { preset ->
                    val selected = text.trim() == preset.toString()
                    Text(
                        if (preset >= 1_000_000) "${preset / 1_000_000}M" else "${preset / 1_000}K",
                        fontSize = 13.sp,
                        color = if (selected) palette.accent else palette.text,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) palette.accent.copy(alpha = .14f) else palette.secondarySurface)
                            .clickable(role = Role.Button) {
                                text = preset.toString()
                                error = null
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            TextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.omni_threshold_label),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            error?.let {
                Text(
                    stringResource(
                        when (it) {
                            ThresholdInputError.Empty -> R.string.omni_threshold_empty
                            ThresholdInputError.NotInteger -> R.string.omni_threshold_not_integer
                            ThresholdInputError.NotPositive -> R.string.omni_threshold_not_positive
                        },
                    ),
                    color = Color(0xFFE05252),
                    fontSize = 12.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(stringResource(R.string.omni_cancel), onDismiss, Modifier.weight(1f))
                TextButton(
                    stringResource(R.string.omni_save),
                    {
                        parseContextThreshold(text).fold(
                            onSuccess = onSave,
                            onFailure = { failure -> error = (failure as? ThresholdInputException)?.error },
                        )
                    },
                    Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun PrimaryButton(
    action: ChatComposerPrimaryAction,
    cancelling: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    when (action) {
        ChatComposerPrimaryAction.Cancel -> OmniIconButton(
            R.drawable.omni_circle_stop,
            stringResource(R.string.omni_composer_stop),
            onCancel,
            enabled = !cancelling,
            tint = palette.text,
        )
        ChatComposerPrimaryAction.Send, ChatComposerPrimaryAction.Disabled -> OmniIconButton(
            R.drawable.omni_arrow_up,
            stringResource(R.string.omni_composer_send),
            onSend,
            enabled = action == ChatComposerPrimaryAction.Send,
            tint = palette.accent,
        )
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
