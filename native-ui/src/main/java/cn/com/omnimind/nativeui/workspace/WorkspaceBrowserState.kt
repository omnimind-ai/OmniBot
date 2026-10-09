package cn.com.omnimind.nativeui.workspace

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap

/** One listed file or folder. [directory] follows links, so a mounted folder is a directory. */
@Immutable
data class WorkspaceEntryUi(
    val path: String,
    val name: String,
    val directory: Boolean,
    /** A host folder mounted at the workspace root (a symlink); it can only be unmounted. */
    val mount: Boolean = false,
    val mountBroken: Boolean = false,
) {
    val kind: WorkspaceFileKind get() = if (directory) WorkspaceFileKind.Other else workspaceFileKind(path)
}

/** A row of the flattened tree: an entry, or the placeholder of an expanded empty folder. */
@Immutable
sealed interface WorkspaceRow {
    val key: String
    val depth: Int

    data class Entry(val entry: WorkspaceEntryUi, override val depth: Int, val expanded: Boolean, val expandable: Boolean) : WorkspaceRow {
        override val key: String get() = entry.path
    }

    data class EmptyFolder(val parentPath: String, override val depth: Int) : WorkspaceRow {
        override val key: String get() = "$parentPath/<empty>"
    }
}

/** Dart `_maxInlineExpansionDepth`: folders at this depth or deeper open as the current directory. */
const val WORKSPACE_INLINE_EXPANSION_DEPTH = 2

/**
 * Flattens [entries] and the loaded children of [expanded] folders into rows
 * (Dart `_buildEntryNode` / `_buildExpandedChildren`).
 */
fun flattenWorkspaceRows(
    entries: List<WorkspaceEntryUi>,
    children: Map<String, List<WorkspaceEntryUi>>,
    expanded: Set<String>,
    depth: Int = 0,
): List<WorkspaceRow> = buildList {
    entries.forEach { entry ->
        val expandable = entry.directory && depth < WORKSPACE_INLINE_EXPANSION_DEPTH
        val open = expandable && entry.path in expanded && entry.path in children
        add(WorkspaceRow.Entry(entry, depth, open, expandable))
        if (open) {
            val nested = children.getValue(entry.path)
            if (nested.isEmpty()) add(WorkspaceRow.EmptyFolder(entry.path, depth + 1))
            else addAll(flattenWorkspaceRows(nested, children, expanded, depth + 1))
        }
    }
}

/** A folder the move sheet offers, labelled by its shell path. */
@Immutable
data class WorkspaceMoveTarget(val path: String, val label: String)

@Immutable
data class WorkspaceBrowserState(
    val rootPath: String = "",
    /** The root breadcrumb label: the shell path (`/workspace`). */
    val rootLabel: String = "",
    val directory: String = "",
    val rows: List<WorkspaceRow> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val exists: Boolean = true,
    val selecting: Boolean = false,
    val selection: WorkspaceSelection = WorkspaceSelection(),
    val busy: Boolean = false,
    val notice: String? = null,
    /** Non-null while the move sheet is open for [moveSource]. */
    val moveTargets: List<WorkspaceMoveTarget>? = null,
    val moveSource: String? = null,
) {
    val canGoUp: Boolean get() = selecting || (directory.isNotEmpty() && directory != rootPath)
    fun isSelected(path: String): Boolean = selection.isSelected(path, directory)
}

class WorkspaceBrowserActions(
    val refresh: () -> Unit = {},
    val openDirectory: (String) -> Unit = {},
    val toggleExpanded: (String) -> Unit = {},
    /** Leaves selection mode first, then goes to the parent folder. */
    val goUp: () -> Unit = {},
    val toggleSelecting: () -> Unit = {},
    val toggleSelected: (String) -> Unit = {},
    val deleteSelected: () -> Unit = {},
    val rename: (path: String, newName: String) -> Unit = { _, _ -> },
    val delete: (String) -> Unit = {},
    val unmount: (String) -> Unit = {},
    val startMove: (String) -> Unit = {},
    val moveTo: (String) -> Unit = {},
    val cancelMove: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

/** How much of a text file the preview reads; larger files open read-only and truncated. */
const val WORKSPACE_TEXT_PREVIEW_LIMIT = 1024 * 1024

@Immutable
data class WorkspaceFileState(
    val path: String = "",
    val name: String = "",
    val shellPath: String = "",
    val kind: WorkspaceFileKind = WorkspaceFileKind.Other,
    val mimeType: String = "application/octet-stream",
    val exists: Boolean = true,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val text: String? = null,
    /** The file is larger than [WORKSPACE_TEXT_PREVIEW_LIMIT]; only its head is shown and it cannot be edited. */
    val truncated: Boolean = false,
    /** A line was longer than [WORKSPACE_PREVIEW_LINE_LIMIT]; the preview shows it cut and the file is read-only. */
    val longLines: Boolean = false,
    val image: ImageBitmap? = null,
    val editing: Boolean = false,
    val draft: String = "",
    val saving: Boolean = false,
    val notice: String? = null,
) {
    val canEdit: Boolean get() = exists && kind.editable && !truncated && !longLines && text != null
    val dirty: Boolean get() = editing && draft != text.orEmpty()
    val markdown: Boolean get() = mimeType == "text/markdown"
}

class WorkspaceFileActions(
    val edit: () -> Unit = {},
    val updateDraft: (String) -> Unit = {},
    val cancelEdit: () -> Unit = {},
    val save: () -> Unit = {},
    val openWithSystem: () -> Unit = {},
    val openInBrowser: () -> Unit = {},
    val share: () -> Unit = {},
    /** Starts the system "save as" picker; the route writes the copy. */
    val saveToDevice: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)
