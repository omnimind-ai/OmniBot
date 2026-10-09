package cn.com.omnimind.bot.ui.workspace

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.ui.channel.SharedFileIntents
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.workspace.WORKSPACE_TEXT_PREVIEW_LIMIT
import cn.com.omnimind.nativeui.workspace.WorkspaceFileActions
import cn.com.omnimind.nativeui.workspace.WorkspaceFileKind
import cn.com.omnimind.nativeui.workspace.WorkspaceFileScreen
import cn.com.omnimind.nativeui.workspace.WorkspaceFileState
import cn.com.omnimind.nativeui.workspace.editable
import cn.com.omnimind.nativeui.workspace.workspaceEntryName
import cn.com.omnimind.nativeui.workspace.workspaceFileKind
import cn.com.omnimind.nativeui.workspace.workspaceMimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The native file preview (batch 5e-8a), replacing the Flutter
 * `OmnibotArtifactPreviewPage` for workspace files: text/Markdown/code with an
 * editor, images, and system hand-offs (open with, browser, share, save to
 * device) for everything else. The unsaved draft lives in [SavedStateHandle],
 * so rotation or process death keeps it.
 */
internal class NativeWorkspaceFileViewModel(
    context: Context,
    path: String,
    private val startInEdit: Boolean,
    private val savedState: SavedStateHandle,
    private val repository: WorkspaceFileRepository,
    paths: WorkspaceResourcePaths,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val kind = workspaceFileKind(path)
    private val mutableState = MutableStateFlow(
        WorkspaceFileState(
            path = path,
            name = workspaceEntryName(path),
            shellPath = paths.shellPathForAndroidPath(path) ?: path,
            kind = kind,
            mimeType = workspaceMimeType(path),
        ),
    )
    val state = mutableState.asStateFlow()

    val actions = WorkspaceFileActions(
        edit = ::edit,
        updateDraft = { draft ->
            savedState[KEY_DRAFT] = draft
            mutableState.update { it.copy(draft = draft) }
        },
        cancelEdit = ::stopEditing,
        save = ::save,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val exists = withContext(Dispatchers.IO) { File(state.value.path).isFile }
                if (!exists) {
                    mutableState.update { it.copy(exists = false, loading = false) }
                    return@launch
                }
                when {
                    kind.editable -> {
                        val content = withContext(Dispatchers.IO) {
                            repository.readText(state.value.path, WORKSPACE_TEXT_PREVIEW_LIMIT)
                        }
                        val restoredDraft = savedState.get<String>(KEY_DRAFT)
                        val editing = !content.truncated && (restoredDraft != null || startInEdit && savedState.get<Boolean>(KEY_EDIT_STARTED) != true)
                        savedState[KEY_EDIT_STARTED] = true
                        mutableState.update {
                            it.copy(
                                loading = false, text = content.text, truncated = content.truncated,
                                editing = editing, draft = restoredDraft ?: content.text,
                            )
                        }
                        if (editing) savedState[KEY_DRAFT] = restoredDraft ?: content.text
                    }
                    kind == WorkspaceFileKind.Image -> {
                        val image = withContext(Dispatchers.IO) { decodeSampled(state.value.path) }
                        mutableState.update { it.copy(loading = false, image = image, failed = image == null) }
                    }
                    else -> mutableState.update { it.copy(loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to read workspace file: ${error.message}")
                mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** Decodes at most ~2048px on the long side so a camera photo cannot exhaust memory. */
    private fun decodeSampled(path: String): androidx.compose.ui.graphics.ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    private fun edit() {
        val current = state.value
        if (!current.canEdit) return
        savedState[KEY_DRAFT] = current.text.orEmpty()
        mutableState.update { it.copy(editing = true, draft = it.text.orEmpty()) }
    }

    private fun stopEditing() {
        savedState.remove<String>(KEY_DRAFT)
        mutableState.update { it.copy(editing = false, draft = it.text.orEmpty()) }
    }

    private fun save() {
        val current = state.value
        if (!current.canEdit || current.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.writeText(current.path, current.draft) }
                savedState.remove<String>(KEY_DRAFT)
                mutableState.update {
                    it.copy(saving = false, editing = false, text = current.draft,
                        notice = appContext.getString(R.string.omni_ws_file_saved))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.w(TAG, "Unable to save workspace file: ${error.message}")
                mutableState.update {
                    it.copy(saving = false, notice = appContext.getString(R.string.omni_ws_save_failed, error.message.orEmpty()))
                }
            }
        }
    }

    /** Reports a failed system hand-off (open with, browser, share). */
    fun reportHandOff(outcome: SharedFileIntents.Outcome) {
        val resource = when (outcome) {
            SharedFileIntents.Outcome.Started -> return
            SharedFileIntents.Outcome.Missing -> R.string.omni_ws_missing
            SharedFileIntents.Outcome.NoHandler -> R.string.omni_ws_no_handler
            is SharedFileIntents.Outcome.Failed -> R.string.omni_ws_handoff_failed
        }
        mutableState.update { it.copy(notice = appContext.getString(resource)) }
    }

    /** Copies the file to the document the system picker returned. */
    fun saveToDevice(target: Uri?) {
        target ?: return
        val source = state.value.path
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.contentResolver.openOutputStream(target, "w")?.use { output ->
                        File(source).inputStream().use { it.copyTo(output) }
                    } ?: error("No output stream")
                }.isSuccess
            }
            mutableState.update {
                it.copy(notice = appContext.getString(if (saved) R.string.omni_ws_saved_to_device else R.string.omni_ws_save_to_device_failed))
            }
        }
    }

    class Factory(context: Context, private val path: String, private val edit: Boolean) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val paths = WorkspaceResourcePaths.from(appContext)
            return NativeWorkspaceFileViewModel(
                appContext, path, edit, extras.createSavedStateHandle(),
                WorkspaceFileRepository(paths.rootPath), paths,
            ) as T
        }
    }

    private companion object {
        const val TAG = "NativeWorkspaceFile"
        const val KEY_DRAFT = "draft"
        const val KEY_EDIT_STARTED = "editStarted"
        const val MAX_IMAGE_SIDE = 2048
    }
}

@Composable
internal fun NativeWorkspaceFileRoute(
    viewModel: NativeWorkspaceFileViewModel,
    activity: Activity,
    onLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(state.mimeType),
        viewModel::saveToDevice,
    )
    val actions = remember(viewModel, state.path, state.mimeType, state.name) {
        WorkspaceFileActions(
            edit = viewModel.actions.edit,
            updateDraft = viewModel.actions.updateDraft,
            cancelEdit = viewModel.actions.cancelEdit,
            save = viewModel.actions.save,
            openWithSystem = { viewModel.reportHandOff(SharedFileIntents.open(activity, state.path, state.mimeType)) },
            openInBrowser = { viewModel.reportHandOff(SharedFileIntents.openInBrowser(activity, state.path, state.mimeType)) },
            share = { viewModel.reportHandOff(SharedFileIntents.share(activity, state.path, state.name, state.mimeType)) },
            saveToDevice = { savePicker.launch(state.name) },
            dismissNotice = viewModel.actions.dismissNotice,
        )
    }
    WorkspaceFileScreen(state, actions, onLink, onBack)
}
