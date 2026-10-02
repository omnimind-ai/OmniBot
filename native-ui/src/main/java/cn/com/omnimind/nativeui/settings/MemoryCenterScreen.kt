package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniTabRow
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * Memory center. Presentation only; the workspace memory files stay with
 * `WorkspaceMemoryService` behind the host ViewModel. The LLM greeting
 * generation is disabled upstream, so the greeting is the static text.
 */
@Composable
fun MemoryCenterScreen(
    state: MemoryCenterState,
    actions: MemoryCenterActions,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let {
        if (state.noticeArg != null) stringResource(it, state.noticeArg) else stringResource(it)
    }
    OmniPage(
        title = if (state.selectionMode) {
            stringResource(R.string.omni_memory_selected_count, state.selectedIds.size)
        } else {
            stringResource(R.string.omni_memory_center_title)
        },
        onBack = if (state.selectionMode) actions.exitSelection else onBack,
        notice = notice,
        onNoticeShown = actions.dismissNotice,
        actions = {
            if (state.selectionMode) {
                val allSelected = state.selectedIds.size == state.shortMemories.size &&
                    state.shortMemories.isNotEmpty()
                TextButton(
                    stringResource(if (allSelected) R.string.omni_memory_deselect_all
                        else R.string.omni_memory_select_all),
                    onClick = actions.toggleSelectAll,
                )
            }
        },
        bottomBar = {
            if (state.selectionMode) {
                Column(
                    Modifier.fillMaxWidth().background(palette.surface)
                        .clickable(
                            enabled = state.selectedIds.isNotEmpty() && !state.mutating,
                            role = Role.Button,
                        ) { actions.showDeleteSelection(true) }
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OmniIcon(R.drawable.omni_trash_2, size = 20.dp,
                        tint = Color(0xFFFF6464).copy(alpha = if (state.selectedIds.isEmpty()) .4f else 1f))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.omni_agent_delete), fontSize = 12.sp,
                        color = Color(0xFFFF6464).copy(alpha = if (state.selectedIds.isEmpty()) .4f else 1f),
                    )
                }
            }
        },
    ) { insets ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val hasLongSection = !state.longFailed
            if (state.shortMemories.isEmpty() && !hasLongSection) {
                Column(
                    Modifier.fillMaxSize().padding(insets),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    OmniIcon(R.drawable.omni_brain, size = 72.dp, tint = palette.tertiaryText)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.omni_memory_empty_title), fontSize = 17.sp,
                        fontWeight = FontWeight.Medium, color = palette.accent)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.omni_memory_empty_desc), fontSize = 14.sp,
                        color = palette.secondaryText)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                    Spacer(Modifier.height(12.dp))
                    // The LLM greeting flow is disabled upstream; static text only.
                    val greeting = stringResource(R.string.omni_memory_greeting)
                    if (palette.dark) {
                        Text(greeting, fontSize = 16.sp, lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium, color = palette.text,
                            modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        Text(
                            greeting, fontSize = 16.sp, lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                            style = TextStyle(
                                brush = Brush.linearGradient(
                                    listOf(Color(0xFF2DA5F0), Color(0xFF1930D9)),
                                ),
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OmniTabRow(
                        listOf(stringResource(R.string.omni_memory_tab_local),
                            stringResource(R.string.omni_memory_tab_cloud)),
                        if (state.tab == MemoryTab.Local) 0 else 1,
                        { index ->
                            actions.setTab(if (index == 0) MemoryTab.Local else MemoryTab.Cloud)
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.weight(1f)) {
                        Column(
                            Modifier.fillMaxSize()
                                .then(if (state.selectionMode) Modifier.blur(10.dp) else Modifier),
                        ) {
                            when (state.tab) {
                                MemoryTab.Local -> LocalMemoryTab(state, actions)
                                MemoryTab.Cloud -> CloudMemoryTab(state, actions)
                            }
                        }
                    }
                }
            }
        }
    }

    // Selection delete confirmation.
    val selectedCount = state.selectedIds.size
    val singleSelected = state.shortMemories.firstOrNull { it.id == state.selectedIds.firstOrNull() }
    OmniConfirmDialog(
        show = state.confirmDeleteSelection,
        title = stringResource(R.string.omni_memory_delete_short_title),
        summary = stringResource(R.string.omni_memory_delete_short_scope) + "\n\n" +
            if (selectedCount == 1 && singleSelected != null) {
                clipMemoryText(singleSelected.description ?: singleSelected.title, 120)
            } else {
                stringResource(R.string.omni_memory_selected_count, selectedCount)
            },
        confirmText = stringResource(R.string.omni_agent_delete),
        onConfirm = actions.deleteSelectionConfirmed,
        onDismiss = { actions.showDeleteSelection(false) },
    )

    // Long-term memory detail sheet.
    val detailItem = state.longMemories.firstOrNull { it.id == state.detailItemId }
    OverlayBottomSheet(
        show = detailItem != null,
        title = stringResource(R.string.omni_memory_tab_cloud),
        backgroundColor = palette.page,
        onDismissRequest = actions.closeDetail,
    ) {
        if (detailItem != null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(detailItem.memory, fontSize = 17.sp, lineHeight = 25.5.sp,
                    fontWeight = FontWeight.SemiBold, color = palette.text)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(stringResource(R.string.omni_memory_edit_title),
                        { actions.openEditor(detailItem.id) }, enabled = !state.mutating)
                    TextButton(stringResource(R.string.omni_agent_delete),
                        { actions.showDeleteLong(detailItem.id) }, enabled = !state.mutating)
                }
                Spacer(Modifier.height(14.dp))
                DetailRow(stringResource(R.string.omni_memory_id_label), detailItem.id)
                DetailRow(stringResource(R.string.omni_memory_user), "workspace")
                DetailRow("Agent", "omnibot-workspace-memory")
            }
        }
    }

    // Long-term memory editor sheet (create and edit share it; the MEMORY.md
    // bullet format stores only the text, as in the Flutter service).
    if (state.editorOpen) {
        val editing = state.editorItemId?.let { id ->
            state.longMemories.firstOrNull { it.id == id }
        }
        OverlayBottomSheet(
            show = true,
            title = stringResource(if (editing == null) R.string.omni_memory_add_title
                else R.string.omni_memory_edit_long_title),
            backgroundColor = palette.page,
            onDismissRequest = actions.closeEditor,
        ) {
            key(state.editorItemId) {
                LongMemoryEditor(
                    initialMemory = editing?.memory.orEmpty(),
                    mutating = state.mutating,
                    submitLabel = stringResource(if (editing == null) R.string.omni_memory_save_to_long
                        else R.string.omni_memory_save_changes),
                    onSubmit = actions.saveEditor,
                )
            }
        }
    }

    // Long-term delete confirmation.
    val deletingLong = state.longMemories.firstOrNull { it.id == state.confirmDeleteLongId }
    OmniConfirmDialog(
        show = deletingLong != null,
        title = stringResource(R.string.omni_memory_delete_long_title),
        summary = stringResource(R.string.omni_memory_delete_warning) + ":\n" +
            clipMemoryText(deletingLong?.memory.orEmpty(), 36),
        confirmText = stringResource(R.string.omni_agent_delete),
        onConfirm = actions.deleteLongConfirmed,
        onDismiss = { actions.showDeleteLong(null) },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val palette = LocalOmniPalette.current
    Row(Modifier.padding(bottom = 10.dp)) {
        Text(label, Modifier.width(72.dp), fontSize = 12.sp, lineHeight = 18.sp,
            color = palette.secondaryText)
        Text(value, fontSize = 14.sp, lineHeight = 21.sp, color = palette.text)
    }
}

private fun clipMemoryText(text: String, maxLength: Int): String {
    val trimmed = text.trim()
    return if (trimmed.length <= maxLength) trimmed else trimmed.substring(0, maxLength) + "..."
}

@Composable
private fun LocalMemoryTab(state: MemoryCenterState, actions: MemoryCenterActions) {
    val palette = LocalOmniPalette.current
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
    ) {
        item(key = "note") {
            Column {
                Text(stringResource(R.string.omni_memory_short_retention), fontSize = 14.sp,
                    lineHeight = 21.sp, color = palette.secondaryText,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 12.dp))
                if (state.shortMemories.isNotEmpty()) {
                    Row(Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp)) {
                        Text(stringResource(R.string.omni_memory_tab_local), fontSize = 14.sp,
                            lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                        Spacer(Modifier.width(8.dp))
                        Text("${state.shortMemories.size}", fontSize = 12.sp, lineHeight = 21.sp,
                            color = palette.secondaryText)
                    }
                }
            }
        }
        if (state.shortMemories.isEmpty()) {
            item(key = "empty") { PlaceholderCard(
                stringResource(R.string.omni_memory_no_short),
                stringResource(R.string.omni_memory_no_short_desc),
            ) }
        } else {
            items(state.shortMemories, key = { it.id }) { item ->
                ShortMemoryCard(item, state, actions)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ShortMemoryCard(item: ShortMemoryItem, state: MemoryCenterState, actions: MemoryCenterActions) {
    val palette = LocalOmniPalette.current
    val selected = item.id in state.selectedIds
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(8.dp))
            .combinedClickable(
                role = Role.Button,
                onClick = {
                    // Outside selection mode a tap does nothing; long-press enters selection.
                    if (state.selectionMode) actions.toggleSelection(item.id)
                },
                onLongClick = { actions.enterSelection(item.id) },
            )
            .padding(16.dp)
            .semantics { contentDescription = item.title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = 17.sp, lineHeight = 19.1.sp, fontWeight = FontWeight.Medium,
                color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.description != null) {
                Spacer(Modifier.height(8.dp))
                Text(item.description, fontSize = 14.sp, lineHeight = 21.sp,
                    color = palette.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OmniIcon(R.drawable.omni_memory_context, size = 18.dp, tint = Color.Unspecified)
                Spacer(Modifier.width(8.dp))
                Text(item.timeLabel, fontSize = 12.sp, lineHeight = 18.sp,
                    color = palette.secondaryText)
            }
        }
        if (state.selectionMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .background(if (selected) palette.accent else Color.Transparent)
                    .border(1.5.dp, if (selected) palette.accent else palette.strongBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) OmniIcon(R.drawable.omni_check, size = 12.dp, tint = Color.White)
            }
        }
    }
}

@Composable
private fun CloudMemoryTab(state: MemoryCenterState, actions: MemoryCenterActions) {
    val palette = LocalOmniPalette.current
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 20.dp),
    ) {
        item(key = "header") {
            Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.omni_memory_tab_cloud), fontSize = 14.sp,
                    lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                if (state.longMemories.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text("${state.longMemories.size}/${state.longMemories.size}", fontSize = 12.sp,
                        lineHeight = 21.sp, color = palette.secondaryText)
                }
                Spacer(Modifier.weight(1f))
                OmniIconButton(R.drawable.omni_plus,
                    stringResource(R.string.omni_memory_add_long), { actions.openEditor(null) },
                    size = 20.dp)
                OmniIconButton(R.drawable.omni_refresh_cw,
                    stringResource(R.string.omni_memory_refresh_long), actions.refreshLong,
                    size = 18.dp)
            }
            Spacer(Modifier.height(14.dp))
            }
        }
        when {
            state.longLoading && state.longMemories.isEmpty() -> {
                items(3, key = { "skeleton-$it" }) {
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp))
                            .background(palette.surface).border(1.dp, palette.border,
                                RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape)
                            .background(palette.accent.copy(alpha = .08f)))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(180.dp).height(12.dp).clip(CircleShape)
                            .background(palette.accent.copy(alpha = .06f)))
                    }
                }
            }
            state.longFailed -> item(key = "failed") {
                PlaceholderCard(stringResource(R.string.omni_memory_long_unavailable),
                    state.longErrorDetail?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.omni_memory_long_unavailable))
            }
            state.longMemories.isEmpty() -> item(key = "empty") {
                PlaceholderCard(stringResource(R.string.omni_memory_long_empty),
                    stringResource(R.string.omni_memory_long_empty_desc))
            }
            else -> items(state.longMemories, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp))
                        .background(palette.surface)
                        .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { actions.openDetail(item.id) }
                        .padding(14.dp)
                        .semantics { contentDescription = item.memory },
                ) {
                    Box(
                        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0x1F2DA5F0), Color(0x262C7FEB))),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        OmniIcon(R.drawable.omni_sparkles, size = 18.dp, tint = palette.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.memory, fontSize = 14.sp, lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium, color = palette.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.clip(CircleShape)
                                .background(palette.segmentThumb.copy(alpha = if (palette.dark) .72f else .9f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(item.timeLabel, fontSize = 11.sp, color = palette.secondaryText)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OmniIcon(R.drawable.omni_chevron_right, size = 18.dp, tint = palette.secondaryText,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@Composable
private fun PlaceholderCard(title: String, subtitle: String) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(16.dp)).padding(18.dp),
    ) {
        Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
            color = palette.text)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, fontSize = 12.sp, lineHeight = 21.sp, color = palette.secondaryText)
    }
}

/** Editor draft stays sheet-local; only the confirmed text leaves. */
@Composable
private fun LongMemoryEditor(
    initialMemory: String,
    mutating: Boolean,
    submitLabel: String,
    onSubmit: (String) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var memory by remember { mutableStateOf(initialMemory) }
    var tags by remember { mutableStateOf("") }
    val length = memory.trim().length
    val overLimit = length > 300
    Column(Modifier.fillMaxWidth().imePadding()) {
        TextField(
            memory, { memory = it }, minLines = 3, maxLines = 5, enabled = !mutating,
            label = stringResource(R.string.omni_memory_editor_hint), useLabelAsPlaceholder = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$length/300", fontSize = 12.sp,
            color = if (overLimit) Color(0xFFFF6464) else palette.tertiaryText,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.height(12.dp))
        TextField(
            tags, { tags = it }, maxLines = 2, enabled = !mutating,
            label = stringResource(R.string.omni_memory_editor_tags_label),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.omni_memory_editor_tags_hint), fontSize = 11.sp,
            color = palette.tertiaryText, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(22.dp))
        Button(
            { onSubmit(memory) },
            enabled = !mutating && memory.trim().isNotEmpty() && !overLimit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(submitLabel)
        }
        Spacer(Modifier.height(8.dp))
    }
}
