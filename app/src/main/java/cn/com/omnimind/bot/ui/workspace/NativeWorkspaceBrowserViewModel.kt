package cn.com.omnimind.bot.ui.workspace

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.workspace.EntryNameError
import cn.com.omnimind.nativeui.workspace.MoveError
import cn.com.omnimind.nativeui.workspace.WORKSPACE_INLINE_EXPANSION_DEPTH
import cn.com.omnimind.nativeui.workspace.WorkspaceBrowserActions
import cn.com.omnimind.nativeui.workspace.WorkspaceBrowserScreen
import cn.com.omnimind.nativeui.workspace.WorkspaceBrowserState
import cn.com.omnimind.nativeui.workspace.WorkspaceEntryUi
import cn.com.omnimind.nativeui.workspace.WorkspaceMoveTarget
import cn.com.omnimind.nativeui.workspace.WorkspaceSelection
import cn.com.omnimind.nativeui.workspace.flattenWorkspaceRows
import cn.com.omnimind.nativeui.workspace.isSelfOrDescendant
import cn.com.omnimind.nativeui.workspace.normalizeWorkspacePath
import cn.com.omnimind.nativeui.workspace.validateEntryName
import cn.com.omnimind.nativeui.workspace.workspaceMoveError
import cn.com.omnimind.nativeui.workspace.workspaceParentPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The native workspace browser (batch 5e-8a), replacing
 * `OmnibotWorkspacePage`/`OmnibotWorkspaceBrowser`. Every filesystem call
 * runs on IO through [WorkspaceFileRepository]; a refresh is fenced by a
 * generation so a slow listing of a folder the user already left never
 * overwrites the current one.
 */
internal class NativeWorkspaceBrowserViewModel(
    context: Context,
    private val repository: WorkspaceFileRepository,
    private val paths: WorkspaceResourcePaths,
    startPath: String?,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val root = repository.rootPath
    private val mutableState = MutableStateFlow(
        WorkspaceBrowserState(
            rootPath = root,
            rootLabel = paths.shellRootPath,
            directory = startPath?.let(::normalizeWorkspacePath)?.takeIf(repository::isInside) ?: root,
        ),
    )
    val state = mutableState.asStateFlow()

    private var entries: List<WorkspaceEntryUi> = emptyList()
    private val children = mutableMapOf<String, List<WorkspaceEntryUi>>()
    private val expanded = mutableSetOf<String>()
    private var generation = 0

    val actions = WorkspaceBrowserActions(
        refresh = ::refresh,
        openDirectory = ::openDirectory,
        toggleExpanded = ::toggleExpanded,
        goUp = ::goUp,
        toggleSelecting = {
            mutableState.update { it.copy(selecting = !it.selecting, selection = WorkspaceSelection()) }
        },
        toggleSelected = ::toggleSelected,
        deleteSelected = ::deleteSelected,
        rename = ::rename,
        delete = ::delete,
        unmount = ::unmount,
        startMove = ::startMove,
        moveTo = ::moveTo,
        cancelMove = { mutableState.update { it.copy(moveTargets = null, moveSource = null) } },
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    init {
        refresh()
    }

    fun refresh() {
        val current = ++generation
        val directory = state.value.directory
        val keepExpanded = expanded.filter { isSelfOrDescendant(it, directory) }
        mutableState.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            try {
                val (listing, loadedChildren) = withContext(Dispatchers.IO) {
                    val listing = repository.list(directory)
                    listing to keepExpanded.mapNotNull { path ->
                        repository.list(path).takeIf { it.exists }?.let { path to it.entries }
                    }.toMap()
                }
                if (current != generation) return@launch
                entries = listing.entries
                children.clear()
                children.putAll(loadedChildren)
                expanded.retainAll(loadedChildren.keys)
                val selection = withContext(Dispatchers.IO) { pruneSelection(state.value.selection) }
                if (current != generation) return@launch
                mutableState.update {
                    it.copy(loading = false, exists = listing.exists, selection = selection, rows = rows())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to list workspace folder: ${error.message}")
                if (current == generation) mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun pruneSelection(selection: WorkspaceSelection) = WorkspaceSelection(
        selection.selected.filter(repository::exists).toSet(),
        selection.excluded.filter(repository::exists).toSet(),
    )

    private fun rows() = flattenWorkspaceRows(entries, children, expanded)

    fun openDirectory(path: String) {
        val target = normalizeWorkspacePath(path)
        if (!repository.isInside(target)) return
        entries = emptyList()
        children.clear()
        expanded.clear()
        mutableState.update {
            it.copy(directory = target, rows = emptyList(), selecting = false, selection = WorkspaceSelection())
        }
        refresh()
    }

    private fun goUp() {
        val current = state.value
        when {
            current.selecting -> mutableState.update { it.copy(selecting = false, selection = WorkspaceSelection()) }
            current.directory != root -> openDirectory(workspaceParentPath(current.directory))
        }
    }

    private fun toggleExpanded(path: String) {
        val row = entryAt(path) ?: return
        if (!row.first.directory) return
        if (row.second >= WORKSPACE_INLINE_EXPANSION_DEPTH) {
            openDirectory(path)
            return
        }
        if (path in expanded) {
            expanded.removeAll { isSelfOrDescendant(it, path) }
            children.keys.removeAll { isSelfOrDescendant(it, path) }
            mutableState.update { it.copy(rows = rows()) }
            return
        }
        viewModelScope.launch {
            val listing = runCatching { withContext(Dispatchers.IO) { repository.list(path) } }.getOrNull()
            if (listing == null || !listing.exists) {
                notify(R.string.omni_ws_missing)
                refresh()
                return@launch
            }
            children[path] = listing.entries
            expanded += path
            mutableState.update { it.copy(rows = rows()) }
        }
    }

    /** The entry at [path] in the visible tree and its depth. */
    private fun entryAt(path: String): Pair<WorkspaceEntryUi, Int>? =
        state.value.rows.firstNotNullOfOrNull { row ->
            (row as? cn.com.omnimind.nativeui.workspace.WorkspaceRow.Entry)
                ?.takeIf { it.entry.path == path }?.let { it.entry to it.depth }
        }

    private fun toggleSelected(path: String) {
        mutableState.update {
            val selection = if (it.isSelected(path)) it.selection.deselect(path, it.directory) else it.selection.select(path)
            it.copy(selection = selection)
        }
    }

    private fun deleteSelected() = mutate {
        val current = state.value
        if (current.selection.isEmpty) {
            notify(R.string.omni_ws_none_selected)
            return@mutate
        }
        val result = withContext(Dispatchers.IO) { repository.deleteSelected(current.selection, current.directory) }
        mutableState.update { it.copy(selecting = false, selection = WorkspaceSelection()) }
        when {
            result.failed == 0 -> notify(R.string.omni_ws_deleted_count, result.deleted)
            result.deleted > 0 -> notify(R.string.omni_ws_deleted_partial, result.deleted, result.failed)
            else -> notify(R.string.omni_ws_delete_failed)
        }
    }

    private fun rename(path: String, rawName: String) = mutate {
        val name = rawName.trim()
        validateEntryName(name)?.let { error ->
            notify(when (error) {
                EntryNameError.Empty -> R.string.omni_ws_name_empty
                EntryNameError.Dot -> R.string.omni_ws_name_dot
                EntryNameError.Slash -> R.string.omni_ws_name_slash
                EntryNameError.Backslash -> R.string.omni_ws_name_backslash
                EntryNameError.Illegal -> R.string.omni_ws_name_illegal
            })
            return@mutate
        }
        if (repository.isMountRoot(path)) return@mutate
        notify(when (withContext(Dispatchers.IO) { repository.rename(path, name) }) {
            WorkspaceFileRepository.RenameResult.Renamed -> R.string.omni_ws_renamed
            WorkspaceFileRepository.RenameResult.Missing -> R.string.omni_ws_missing
            WorkspaceFileRepository.RenameResult.Unchanged -> R.string.omni_ws_name_unchanged
            WorkspaceFileRepository.RenameResult.Taken -> R.string.omni_ws_name_taken
            WorkspaceFileRepository.RenameResult.Outside -> R.string.omni_ws_rename_failed
        })
    }

    private fun delete(path: String) = mutate {
        if (repository.isMountRoot(path)) {
            withContext(Dispatchers.IO) { repository.unmount(path) }
            notify(R.string.omni_ws_unmounted)
            return@mutate
        }
        if (!withContext(Dispatchers.IO) { repository.exists(path) }) {
            notify(R.string.omni_ws_missing)
            return@mutate
        }
        val directory = withContext(Dispatchers.IO) { repository.isDirectory(path).also { repository.delete(path) } }
        notify(if (directory) R.string.omni_ws_folder_deleted else R.string.omni_ws_file_deleted)
    }

    private fun unmount(path: String) = mutate {
        withContext(Dispatchers.IO) { repository.unmount(path) }
        notify(R.string.omni_ws_unmounted)
    }

    /**
     * Opens the move sheet with every folder of the workspace except the
     * source, its parent and its subtree (the Dart page dragged a row onto a
     * folder; a picker replaces the drag so it works with TalkBack and
     * reaches folders that are not on screen).
     */
    private fun startMove(source: String) {
        if (repository.isMountRoot(source)) {
            notify(R.string.omni_ws_move_mount)
            return
        }
        viewModelScope.launch {
            val sourceIsDirectory = withContext(Dispatchers.IO) { repository.isDirectory(source) }
            val targets = withContext(Dispatchers.IO) { collectFolders(root, depth = 0) }
                .filter { folder ->
                    workspaceMoveError(source, folder, root, sourceIsDirectory,
                        sourceIsMountRoot = false, destinationExists = false) == null
                }
                .map { WorkspaceMoveTarget(it, paths.shellPathForAndroidPath(it) ?: it) }
            mutableState.update { it.copy(moveSource = source, moveTargets = targets) }
        }
    }

    /** Folders below [directory], breadth-limited so a huge tree cannot stall the sheet. */
    private fun collectFolders(directory: String, depth: Int): List<String> {
        if (depth > MOVE_TARGET_DEPTH) return listOf(directory)
        val listing = runCatching { repository.list(directory) }.getOrNull() ?: return listOf(directory)
        return listOf(directory) + listing.entries
            .filter { it.directory && !it.mount && !it.name.startsWith(".") }
            .take(MOVE_TARGETS_PER_FOLDER)
            .flatMap { collectFolders(it.path, depth + 1) }
    }

    private fun moveTo(target: String) {
        val source = state.value.moveSource ?: return
        mutableState.update { it.copy(moveTargets = null, moveSource = null) }
        mutate {
            val error = withContext(Dispatchers.IO) {
                workspaceMoveError(
                    source, target, root,
                    sourceIsDirectory = repository.isDirectory(source),
                    sourceIsMountRoot = repository.isMountRoot(source),
                    destinationExists = repository.destinationTaken(source, target),
                )
            }
            if (error != null) {
                notify(when (error) {
                    MoveError.OutsideWorkspace -> R.string.omni_ws_move_outside
                    MoveError.IntoSelf, MoveError.IntoDescendant -> R.string.omni_ws_move_into_self
                    MoveError.AlreadyThere -> R.string.omni_ws_move_already_there
                    MoveError.MountRoot -> R.string.omni_ws_move_mount
                    MoveError.NameTaken -> R.string.omni_ws_move_name_taken
                })
                return@mutate
            }
            if (!withContext(Dispatchers.IO) { repository.exists(source) }) {
                notify(R.string.omni_ws_missing)
                return@mutate
            }
            val directory = withContext(Dispatchers.IO) { repository.isDirectory(source).also { repository.move(source, target) } }
            notify(if (directory) R.string.omni_ws_folder_moved else R.string.omni_ws_file_moved)
        }
    }

    /** Runs one filesystem change, reports failures, then refreshes. */
    private fun mutate(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Workspace change failed: ${error.message}")
                notify(R.string.omni_ws_action_failed, error.message.orEmpty())
            } finally {
                mutableState.update { it.copy(busy = false) }
                refresh()
            }
        }
    }

    private fun notify(resource: Int, vararg args: Any) {
        mutableState.update { it.copy(notice = appContext.getString(resource, *args)) }
    }

    class Factory(context: Context, private val startPath: String?) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // Creates the internal folders on first use, like the Flutter path snapshot did.
            AgentWorkspaceManager(appContext).ensureRuntimeDirectories()
            val paths = WorkspaceResourcePaths.from(appContext)
            return NativeWorkspaceBrowserViewModel(
                appContext, WorkspaceFileRepository(File(paths.rootPath).absolutePath), paths, startPath,
            ) as T
        }
    }

    private companion object {
        const val TAG = "NativeWorkspaceBrowser"
        const val MOVE_TARGET_DEPTH = 3
        const val MOVE_TARGETS_PER_FOLDER = 40
    }
}

@Composable
internal fun NativeWorkspaceBrowserRoute(
    viewModel: NativeWorkspaceBrowserViewModel,
    onOpenFile: (path: String, edit: Boolean) -> Unit,
    onBack: () -> Unit,
    embedded: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkspaceBrowserScreen(state, viewModel.actions, onOpenFile, onBack, embedded)
}
