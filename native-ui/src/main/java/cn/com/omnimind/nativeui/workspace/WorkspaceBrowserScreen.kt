package cn.com.omnimind.nativeui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniDialogActions
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog

private val Danger = androidx.compose.ui.graphics.Color(0xFFE53935)

/**
 * The native workspace browser (batch 5e-8a). Back leaves selection mode,
 * then climbs one folder, and only leaves the page at the root (Dart
 * `PopScope(canPop: !canGoUp)`).
 */
@Composable
fun WorkspaceBrowserScreen(
    state: WorkspaceBrowserState,
    actions: WorkspaceBrowserActions,
    onOpenFile: (path: String, edit: Boolean) -> Unit,
    onBack: () -> Unit,
    /** The tablet workspace pane (5e-7d): system back belongs to the chat page beside it. */
    embedded: Boolean = false,
) {
    val palette = LocalOmniPalette.current
    BackHandler(enabled = state.canGoUp && !embedded, onBack = actions.goUp)
    var sheetPath by rememberSaveable { mutableStateOf<String?>(null) }
    var renamePath by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmBulkDelete by rememberSaveable { mutableStateOf(false) }
    val entries = remember(state.rows) {
        state.rows.filterIsInstance<WorkspaceRow.Entry>().associateBy { it.entry.path }
    }

    OmniPage(
        title = stringResource(R.string.omni_workspace),
        onBack = { if (state.canGoUp) actions.goUp() else onBack() },
        notice = state.notice,
        onNoticeShown = actions.dismissNotice,
        actions = {
            OmniIconButton(
                if (state.selecting) R.drawable.omni_square_check else R.drawable.omni_check,
                stringResource(if (state.selecting) R.string.omni_ws_exit_select else R.string.omni_ws_select),
                actions.toggleSelecting,
            )
            OmniIconButton(R.drawable.omni_refresh_cw, stringResource(R.string.omni_ws_refresh), actions.refresh,
                enabled = !state.busy)
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            Breadcrumbs(state, actions, onDeleteSelected = { confirmBulkDelete = true })
            when {
                state.loading && state.rows.isEmpty() -> StatusText(stringResource(R.string.omni_ws_loading))
                state.failed -> Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.omni_ws_load_failed), color = palette.secondaryText)
                    TextButton(stringResource(R.string.omni_log_retry), actions.refresh)
                }
                !state.exists -> StatusText(stringResource(R.string.omni_ws_not_found))
                state.rows.isEmpty() -> StatusText(stringResource(R.string.omni_ws_empty))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                ) {
                    items(state.rows, key = { it.key }) { row ->
                        when (row) {
                            is WorkspaceRow.EmptyFolder -> Text(
                                stringResource(R.string.omni_ws_empty_folder), fontSize = 12.sp,
                                color = palette.secondaryText,
                                modifier = Modifier.fillMaxWidth().background(palette.surface)
                                    .padding(start = 12.dp + 16.dp * row.depth, top = 11.dp, bottom = 11.dp),
                            )
                            is WorkspaceRow.Entry -> EntryRow(
                                row = row,
                                selecting = state.selecting,
                                selected = state.selecting && state.isSelected(row.entry.path),
                                onClick = {
                                    val entry = row.entry
                                    when {
                                        state.selecting -> actions.toggleSelected(entry.path)
                                        entry.directory && row.expandable -> actions.toggleExpanded(entry.path)
                                        entry.directory -> actions.openDirectory(entry.path)
                                        else -> onOpenFile(entry.path, false)
                                    }
                                },
                                onLongClick = { if (!state.selecting) sheetPath = row.entry.path },
                            )
                        }
                    }
                }
            }
        }
    }

    val sheetEntry = sheetPath?.let(entries::get)?.entry
    OverlayBottomSheet(
        show = sheetEntry != null,
        title = sheetEntry?.name,
        backgroundColor = palette.page,
        onDismissRequest = { sheetPath = null },
    ) {
        if (sheetEntry != null) EntryActions(
            entry = sheetEntry,
            onEdit = { sheetPath = null; onOpenFile(sheetEntry.path, true) },
            onRename = { sheetPath = null; renamePath = sheetEntry.path },
            onMove = { sheetPath = null; actions.startMove(sheetEntry.path) },
            onDelete = { sheetPath = null; deletePath = sheetEntry.path },
        )
    }

    val renaming = renamePath?.let(entries::get)?.entry
    if (renaming != null) RenameDialog(renaming, onDismiss = { renamePath = null }) { name ->
        renamePath = null
        actions.rename(renaming.path, name)
    }

    val deleting = deletePath?.let(entries::get)?.entry
    OmniConfirmDialog(
        show = deleting != null,
        title = stringResource(when {
            deleting?.mount == true -> R.string.omni_ws_unmount
            deleting?.directory == true -> R.string.omni_ws_delete_folder
            else -> R.string.omni_ws_delete_file
        }),
        summary = deleting?.let {
            if (it.mount) stringResource(R.string.omni_ws_unmount_confirm, "/workspace/${it.name}")
            else stringResource(R.string.omni_ws_delete_confirm, it.name)
        },
        confirmText = stringResource(if (deleting?.mount == true) R.string.omni_ws_unmount_action else R.string.omni_ws_delete),
        onConfirm = {
            deleting?.let { if (it.mount) actions.unmount(it.path) else actions.delete(it.path) }
            deletePath = null
        },
        onDismiss = { deletePath = null },
    )

    val topLevel = state.selection.topLevelSelected()
    OmniConfirmDialog(
        show = confirmBulkDelete && topLevel.isNotEmpty(),
        title = stringResource(R.string.omni_ws_delete_selected_title),
        summary = if (topLevel.size == 1) stringResource(R.string.omni_ws_delete_confirm, workspaceEntryName(topLevel.first()))
            else stringResource(R.string.omni_ws_delete_selected_confirm, topLevel.size),
        confirmText = stringResource(R.string.omni_ws_delete),
        onConfirm = { confirmBulkDelete = false; actions.deleteSelected() },
        onDismiss = { confirmBulkDelete = false },
    )

    OverlayBottomSheet(
        show = state.moveTargets != null,
        title = stringResource(R.string.omni_ws_move_to),
        backgroundColor = palette.page,
        onDismissRequest = actions.cancelMove,
    ) {
        val targets = state.moveTargets.orEmpty()
        if (targets.isEmpty()) {
            Text(stringResource(R.string.omni_ws_move_no_targets), color = palette.secondaryText,
                modifier = Modifier.padding(vertical = 24.dp))
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(targets, key = { it.path }) { target ->
                BasicComponent(
                    title = target.label,
                    startAction = { OmniIcon(R.drawable.omni_folder, null, Modifier.padding(end = 12.dp), size = 20.dp) },
                    onClick = { actions.moveTo(target.path) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatusText(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalOmniPalette.current.secondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun Breadcrumbs(state: WorkspaceBrowserState, actions: WorkspaceBrowserActions, onDeleteSelected: () -> Unit) {
    val palette = LocalOmniPalette.current
    val crumbs = remember(state.rootPath, state.rootLabel, state.directory) {
        workspaceBreadcrumbs(state.rootPath, state.rootLabel, state.directory, targetIsFile = false)
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) OmniIcon(R.drawable.omni_chevron_right, null, size = 14.dp, tint = palette.tertiaryText)
                Text(
                    crumb.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp, fontWeight = if (crumb.current) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (crumb.current) palette.text else palette.secondaryText,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .then(if (crumb.current) Modifier else Modifier.combinedClickable(role = Role.Button) {
                            actions.openDirectory(crumb.path)
                        })
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (state.selecting && !state.selection.isEmpty) {
            val count = state.selection.selected.size
            TextButton(
                stringResource(R.string.omni_ws_delete_count, count), onDeleteSelected,
                enabled = !state.busy, minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColors(textColor = Danger),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    row: WorkspaceRow.Entry,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val entry = row.entry
    val icon = when {
        selecting -> if (selected) R.drawable.omni_square_check else R.drawable.omni_circle
        entry.directory -> if (row.expanded) R.drawable.omni_folder_open else R.drawable.omni_folder
        entry.kind == WorkspaceFileKind.Image -> R.drawable.omni_image
        entry.kind.editable -> R.drawable.omni_file_text
        else -> R.drawable.omni_file
    }
    val selectedLabel = stringResource(if (selected) R.string.omni_ws_selected else R.string.omni_ws_not_selected)
    val expandLabel = stringResource(if (row.expanded) R.string.omni_ws_expanded else R.string.omni_ws_collapsed)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).background(palette.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.omni_ws_more_actions))
            .semantics {
                if (selecting) stateDescription = selectedLabel
                else if (entry.directory && row.expandable) stateDescription = expandLabel
                if (entry.mountBroken) contentDescription = "${entry.name}, broken mount"
            }
            .padding(start = 12.dp + 16.dp * row.depth, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(icon, null, size = 20.dp, tint = if (selected) palette.accent else palette.text)
        Spacer(Modifier.width(8.dp))
        Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = if (entry.mountBroken) palette.tertiaryText else palette.text)
        if (entry.mount) Text(stringResource(R.string.omni_ws_mount_badge), fontSize = 11.sp, color = palette.tertiaryText,
            modifier = Modifier.padding(horizontal = 6.dp))
        if (!selecting && entry.directory) OmniIcon(
            when {
                !row.expandable -> R.drawable.omni_chevron_right
                row.expanded -> R.drawable.omni_chevron_down
                else -> R.drawable.omni_chevron_right
            }, null, size = 18.dp, tint = palette.secondaryText,
        )
    }
}

@Composable
private fun EntryActions(
    entry: WorkspaceEntryUi,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(if (entry.mount) R.string.omni_ws_mount_hint else R.string.omni_ws_move_hint),
            fontSize = 12.sp, color = palette.secondaryText, modifier = Modifier.padding(bottom = 8.dp))
        if (!entry.mount && !entry.directory && entry.kind.editable) {
            BasicComponent(title = stringResource(R.string.omni_ws_edit), onClick = onEdit,
                startAction = { OmniIcon(R.drawable.omni_pencil, null, Modifier.padding(end = 12.dp)) })
        }
        if (!entry.mount) {
            BasicComponent(title = stringResource(R.string.omni_ws_rename), onClick = onRename,
                startAction = { OmniIcon(R.drawable.omni_file_pen_line, null, Modifier.padding(end = 12.dp)) })
            BasicComponent(title = stringResource(R.string.omni_ws_move), onClick = onMove,
                startAction = { OmniIcon(R.drawable.omni_folders, null, Modifier.padding(end = 12.dp)) })
        }
        BasicComponent(
            title = stringResource(if (entry.mount) R.string.omni_ws_unmount else R.string.omni_ws_delete),
            titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(color = Danger),
            onClick = onDelete,
            startAction = { OmniIcon(R.drawable.omni_trash_2, null, Modifier.padding(end = 12.dp), tint = Danger) },
        )
    }
}

@Composable
private fun RenameDialog(entry: WorkspaceEntryUi, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(entry.path) { mutableStateOf(entry.name) }
    OverlayDialog(
        show = true,
        title = stringResource(if (entry.directory) R.string.omni_ws_rename_folder else R.string.omni_ws_rename_file),
        onDismissRequest = onDismiss,
    ) {
        TextField(name, { name = it }, singleLine = true, label = stringResource(R.string.omni_ws_rename_hint),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        OmniDialogActions(onDismiss, stringResource(R.string.omni_save), { onConfirm(name) },
            confirmEnabled = name.isNotBlank())
    }
}
