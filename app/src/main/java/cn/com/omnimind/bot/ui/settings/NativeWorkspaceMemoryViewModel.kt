package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupStatus
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.WorkspaceEditor
import cn.com.omnimind.nativeui.settings.WorkspaceMemorySettingsActions
import cn.com.omnimind.nativeui.settings.WorkspaceMemorySettingsState
import cn.com.omnimind.nativeui.settings.WorkspaceMemoryNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped drafts; existing workspace service and scheduler own every persisted change. */
internal class NativeWorkspaceMemoryViewModel(private val context: Context) : ViewModel() {
    private data class RollupSnapshot(
        val result: Map<String, Any?>,
        val status: WorkspaceMemoryRollupStatus?,
        val nextRun: Long?,
        val longMemory: String?,
    )

    private val service = WorkspaceMemoryService(context)
    private val scheduler = WorkspaceMemoryRollupScheduler(context)
    private val mutableState = MutableStateFlow(WorkspaceMemorySettingsState())
    private var lastLoadedLongMemory = ""
    private var capabilityRevision = 0
    val state = mutableState.asStateFlow()

    val actions = WorkspaceMemorySettingsActions(
        load = ::load,
        setDraft = ::setDraft,
        save = ::save,
        setEmbeddingEnabled = ::setEmbeddingEnabled,
        setRollupEnabled = ::setRollupEnabled,
        runRollupNow = ::runRollupNow,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load() {
        if (state.value.loading || state.value.loaded) return
        mutableState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val embedding = service.getEmbeddingConfigForUi()
                    val rollup = service.getRollupStatusForUi()
                    WorkspaceMemorySettingsState(
                        loaded = true,
                        soulDraft = service.readSoul(),
                        chatPromptDraft = service.readChatPrompt(),
                        longMemoryDraft = service.readLongTermMemory(),
                        embeddingEnabled = embedding.enabled,
                        embeddingConfigured = embedding.configured,
                        embeddingUsesPlatform = embedding.usesPlatform,
                        rollupEnabled = rollup.enabled,
                        lastRunAtMillis = rollup.lastRunAtMillis,
                        nextRunAtMillis = scheduler.getNextRunAtMillis(),
                    )
                }
                lastLoadedLongMemory = snapshot.longMemoryDraft
                mutableState.value = snapshot
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    /** Refresh status after a compatibility page without discarding unsaved text edits. */
    fun refreshCapabilities() {
        if (!state.value.loaded || state.value.embeddingBusy || state.value.rollupBusy || state.value.rollupRunning) return
        val revision = capabilityRevision
        viewModelScope.launch {
            try {
                val (embedding, rollup, nextRun) = withContext(Dispatchers.IO) {
                    Triple(service.getEmbeddingConfigForUi(), service.getRollupStatusForUi(), scheduler.getNextRunAtMillis())
                }
                if (revision == capabilityRevision && !state.value.embeddingBusy && !state.value.rollupBusy && !state.value.rollupRunning) {
                    mutableState.update { it.copy(
                        embeddingEnabled = embedding.enabled,
                        embeddingConfigured = embedding.configured,
                        embeddingUsesPlatform = embedding.usesPlatform,
                        rollupEnabled = rollup.enabled,
                        lastRunAtMillis = rollup.lastRunAtMillis,
                        nextRunAtMillis = nextRun,
                    ) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last readable status and any unsaved drafts. */ }
        }
    }

    private fun setDraft(editor: WorkspaceEditor, content: String) {
        mutableState.update { current -> when (editor) {
            WorkspaceEditor.Soul -> current.copy(soulDraft = content)
            WorkspaceEditor.ChatPrompt -> current.copy(chatPromptDraft = content)
            WorkspaceEditor.LongMemory -> current.copy(longMemoryDraft = content)
        } }
    }

    private fun save(editor: WorkspaceEditor) {
        val snapshot = state.value
        if (!snapshot.loaded || snapshot.saving != null || snapshot.rollupRunning) return
        val content = when (editor) {
            WorkspaceEditor.Soul -> snapshot.soulDraft
            WorkspaceEditor.ChatPrompt -> snapshot.chatPromptDraft
            WorkspaceEditor.LongMemory -> snapshot.longMemoryDraft
        }
        mutableState.update { it.copy(saving = editor, notice = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { when (editor) {
                    WorkspaceEditor.Soul -> { service.writeSoul(content); service.readSoul() }
                    WorkspaceEditor.ChatPrompt -> { service.writeChatPrompt(content); service.readChatPrompt() }
                    WorkspaceEditor.LongMemory -> { service.writeLongTermMemory(content); service.readLongTermMemory() }
                } }
                mutableState.update { current -> when (editor) {
                    WorkspaceEditor.Soul -> current.copy(soulDraft = saved)
                    WorkspaceEditor.ChatPrompt -> current.copy(chatPromptDraft = saved)
                    WorkspaceEditor.LongMemory -> current.copy(longMemoryDraft = saved)
                }.copy(notice = WorkspaceMemoryNotice.Resource(editor.savedMessage())) }
                if (editor == WorkspaceEditor.LongMemory) lastLoadedLongMemory = saved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = WorkspaceMemoryNotice.Resource(editor.failureMessage())) } }
            finally { mutableState.update { it.copy(saving = null) } }
        }
    }

    private fun setEmbeddingEnabled(enabled: Boolean) {
        if (!state.value.loaded || state.value.embeddingBusy || state.value.rollupRunning) return
        capabilityRevision++
        val previous = state.value.embeddingEnabled
        mutableState.update { it.copy(embeddingEnabled = enabled, embeddingBusy = true) }
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { service.saveEmbeddingConfigForUi(enabled) }
                mutableState.update { it.copy(embeddingEnabled = config.enabled,
                    embeddingConfigured = config.configured, embeddingUsesPlatform = config.usesPlatform) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(embeddingEnabled = previous,
                notice = WorkspaceMemoryNotice.Resource(R.string.omni_workspace_embedding_failed)) } }
            finally { mutableState.update { it.copy(embeddingBusy = false) } }
        }
    }

    private fun setRollupEnabled(enabled: Boolean) {
        if (!state.value.loaded || state.value.rollupBusy || state.value.rollupRunning) return
        capabilityRevision++
        val previous = state.value.rollupEnabled
        mutableState.update { it.copy(rollupEnabled = enabled, rollupBusy = true) }
        viewModelScope.launch {
            try {
                val (status, nextRun) = withContext(Dispatchers.IO) {
                    scheduler.setEnabled(enabled) to scheduler.getNextRunAtMillis()
                }
                mutableState.update { it.copy(rollupEnabled = status.enabled,
                    lastRunAtMillis = status.lastRunAtMillis, nextRunAtMillis = nextRun) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(rollupEnabled = previous,
                notice = WorkspaceMemoryNotice.Resource(R.string.omni_workspace_rollup_toggle_failed)) } }
            finally { mutableState.update { it.copy(rollupBusy = false) } }
        }
    }

    private fun runRollupNow() {
        if (!state.value.loaded || state.value.rollupRunning || state.value.rollupBusy ||
            state.value.embeddingBusy || state.value.saving != null) return
        capabilityRevision++
        mutableState.update { it.copy(rollupRunning = true, notice = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val result = scheduler.runNow()
                    RollupSnapshot(result, runCatching { service.getRollupStatusForUi() }.getOrNull(),
                        runCatching { scheduler.getNextRunAtMillis() }.getOrNull(),
                        runCatching { service.readLongTermMemory() }.getOrNull())
                }
                val previousMemory = lastLoadedLongMemory
                snapshot.longMemory?.let { lastLoadedLongMemory = it }
                mutableState.update { it.copy(
                    rollupEnabled = snapshot.status?.enabled ?: it.rollupEnabled,
                    lastRunAtMillis = snapshot.status?.lastRunAtMillis ?: it.lastRunAtMillis,
                    nextRunAtMillis = snapshot.nextRun,
                    longMemoryDraft = if (it.longMemoryDraft == previousMemory)
                        snapshot.longMemory ?: it.longMemoryDraft else it.longMemoryDraft,
                    notice = (snapshot.result["summary"] as? String)?.takeIf(String::isNotBlank)
                        ?.let { WorkspaceMemoryNotice.Message(it) }
                        ?: WorkspaceMemoryNotice.Resource(R.string.omni_workspace_rollup_done),
                ) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(
                notice = WorkspaceMemoryNotice.Resource(R.string.omni_workspace_rollup_failed)) } }
            finally { mutableState.update { it.copy(rollupRunning = false) } }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeWorkspaceMemoryViewModel(appContext) as T
    }
}

private fun WorkspaceEditor.savedMessage(): Int = when (this) {
    WorkspaceEditor.Soul -> R.string.omni_workspace_soul_saved
    WorkspaceEditor.ChatPrompt -> R.string.omni_workspace_chat_saved
    WorkspaceEditor.LongMemory -> R.string.omni_workspace_memory_saved
}

private fun WorkspaceEditor.failureMessage(): Int = when (this) {
    WorkspaceEditor.Soul -> R.string.omni_workspace_soul_save_failed
    WorkspaceEditor.ChatPrompt -> R.string.omni_workspace_chat_save_failed
    WorkspaceEditor.LongMemory -> R.string.omni_workspace_memory_save_failed
}
