package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.WorkspaceMountManager
import cn.com.omnimind.bot.terminal.NativeTerminalSettingsRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.TerminalAutoStartTaskItem
import cn.com.omnimind.nativeui.settings.TerminalEnvironmentDefinition
import cn.com.omnimind.nativeui.settings.TerminalEnvironmentItem
import cn.com.omnimind.nativeui.settings.TerminalMountItem
import cn.com.omnimind.nativeui.settings.TerminalSettingsActions
import cn.com.omnimind.nativeui.settings.TerminalSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Terminal settings page. The setup inventory, rootfs preparation, boot tasks
 * and workspace mounts stay with their existing owners; this ViewModel triggers
 * and observes. Distribution switching runs the channel's exact sequence.
 */
internal class NativeTerminalSettingsViewModel(
    context: Context,
    private val focusPackageId: String? = null,
) : ViewModel() {
    private val repository = NativeTerminalSettingsRepository(context)
    private val mutableState = MutableStateFlow(TerminalSettingsState())
    private var inventoryRequest = 0
    private var distributionRequest = 0
    private var selectionInitialized = false
    private val initListener: (Map<String, Any?>) -> Unit = { payload -> onInitProgress(payload) }
    val state = mutableState.asStateFlow()

    private val definitions = listOf(
        TerminalEnvironmentDefinition("nodejs", "nodejs", R.string.omni_terminal_env_nodejs, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("npm", "npm", R.string.omni_terminal_env_npm, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("git", "git", R.string.omni_terminal_env_git, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("python", "python", R.string.omni_terminal_env_python, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("uv", "uv", R.string.omni_terminal_env_uv, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("pip", "pip", R.string.omni_terminal_env_pip, R.string.omni_terminal_group_dev),
        TerminalEnvironmentDefinition("codex", "codex", R.string.omni_terminal_env_codex, R.string.omni_terminal_group_agent),
        TerminalEnvironmentDefinition("claude_code", "Claude Code", R.string.omni_terminal_env_claude, R.string.omni_terminal_group_agent),
        TerminalEnvironmentDefinition("opencode", "OpenCode", R.string.omni_terminal_env_opencode, R.string.omni_terminal_group_agent),
        TerminalEnvironmentDefinition("deepseek_harness", "DeepSeek Harness", R.string.omni_terminal_env_dsh, R.string.omni_terminal_group_agent),
        TerminalEnvironmentDefinition("kimi", "Kimi Code", R.string.omni_terminal_env_kimi, R.string.omni_terminal_group_ssh),
        TerminalEnvironmentDefinition("ssh_client", "ssh", R.string.omni_terminal_env_ssh_client, R.string.omni_terminal_group_ssh),
        TerminalEnvironmentDefinition("sshpass", "sshpass", R.string.omni_terminal_env_sshpass, R.string.omni_terminal_group_ssh),
        TerminalEnvironmentDefinition("openssh_server", "sshd", R.string.omni_terminal_env_sshd, R.string.omni_terminal_group_ssh),
    )

    val actions = TerminalSettingsActions(
        refresh = ::refreshAll,
        switchDistribution = ::switchDistribution,
        cancelSwitch = ::cancelSwitch,
        togglePackage = { id, selected ->
            mutableState.update {
                it.copy(selectedPackageIds = if (selected) it.selectedPackageIds + id else it.selectedPackageIds - id)
            }
        },
        openTaskEditor = { taskId ->
            if (!state.value.autoStartBusy) {
                mutableState.update { it.copy(taskEditorOpen = true, editTaskId = taskId) }
            }
        },
        closeTaskEditor = { mutableState.update { it.copy(taskEditorOpen = false, editTaskId = null) } },
        saveTaskEditor = ::saveTask,
        toggleTask = ::toggleTask,
        runTask = ::runTask,
        confirmDeleteTask = { id ->
            if (!state.value.autoStartBusy) mutableState.update { it.copy(deletingTaskId = id) }
        },
        deleteTaskConfirmed = ::deleteTaskConfirmed,
        confirmUnmount = { linkPath ->
            if (!state.value.mountsBusy) mutableState.update { it.copy(unmountLinkPath = linkPath) }
        },
        unmountConfirmed = ::unmountConfirmed,
        mountConfirmed = ::mountConfirmed,
        dismissMountPicker = { mutableState.update { it.copy(mountAliasPickerPath = null, mountAliasError = false) } },
        dismissNotice = { mutableState.update { it.copy(notice = null, noticeArg = null) } },
    )

    init {
        repository.addInitProgressListener(initListener)
    }

    override fun onCleared() {
        repository.removeInitProgressListener(initListener)
    }

    fun load() {
        if (state.value.loaded) return
        refreshAll()
    }

    /** Entry lifecycle resume: re-detect unless a switch is in flight. */
    fun resume() {
        if (state.value.switchingDistributionId == null) refreshInventory(selectMissingByDefault = false)
        refreshAutoStartTasks()
        refreshMounts()
    }

    fun refreshAll() {
        loadDistribution()
        refreshAutoStartTasks()
        refreshMounts()
    }

    private fun loadDistribution() {
        val request = ++distributionRequest
        viewModelScope.launch {
            try {
                val distribution = withContext(Dispatchers.IO) { repository.selectedDistribution() }
                if (request != distributionRequest) return@launch
                val changed = distribution.id != state.value.distributionId
                mutableState.update {
                    it.copy(
                        distributionId = distribution.id,
                        distributionLoading = false,
                        items = if (changed) emptyList() else it.items,
                        selectedPackageIds = if (changed) emptySet() else it.selectedPackageIds,
                    )
                }
                if (changed) selectionInitialized = false
                refreshInventory(selectMissingByDefault = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Distribution read failed", error)
                if (request == distributionRequest) {
                    mutableState.update { it.copy(distributionLoading = false) }
                    refreshInventory(selectMissingByDefault = true)
                }
            }
        }
    }

    private fun refreshInventory(selectMissingByDefault: Boolean) {
        val request = ++inventoryRequest
        val distribution = state.value.distributionId
        mutableState.update { it.copy(detecting = true, detectFailed = false) }
        viewModelScope.launch {
            try {
                val inventory = withContext(Dispatchers.IO) { repository.packageInventory() }
                if (request != inventoryRequest || distribution != state.value.distributionId) return@launch
                val shouldSelectMissing = selectMissingByDefault || !selectionInitialized
                val selected = mutableSetOf<String>()
                definitions.forEach { definition ->
                    val item = inventory[definition.id]
                    if (item?.ready == false &&
                        (definition.id in state.value.selectedPackageIds || shouldSelectMissing ||
                            definition.id == focusPackageId)
                    ) {
                        selected += definition.id
                    }
                }
                selectionInitialized = true
                mutableState.update {
                    it.copy(
                        detecting = false,
                        detectFailed = false,
                        selectedPackageIds = selected,
                        items = definitions.map { definition ->
                            TerminalEnvironmentItem(
                                definition = definition,
                                ready = inventory[definition.id]?.ready,
                                version = inventory[definition.id]?.version,
                            )
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Inventory read failed", error)
                if (request != inventoryRequest) return@launch
                selectionInitialized = true
                mutableState.update {
                    it.copy(
                        detecting = false,
                        detectFailed = true,
                        items = definitions.map { definition ->
                            TerminalEnvironmentItem(definition, ready = null, version = null)
                        },
                        notice = R.string.omni_terminal_detect_failed,
                    )
                }
            }
        }
    }

    private fun switchDistribution(distributionId: String) {
        val current = state.value
        if (current.distributionLoading || current.switchingDistributionId != null ||
            distributionId == current.distributionId) return
        val previous = current.distributionId
        ++distributionRequest
        ++inventoryRequest
        selectionInitialized = false
        val name = repository.supportedDistributions()
            .firstOrNull { it.id == distributionId }?.displayName ?: distributionId
        mutableState.update {
            it.copy(
                distributionId = distributionId,
                switchingDistributionId = distributionId,
                switchCancelling = false,
                switchProgress = null,
                switchStage = null,
                items = emptyList(),
                selectedPackageIds = emptySet(),
            )
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { repository.switchDistribution(distributionId) }
                mutableState.update { it.copy(distributionId = saved.id) }
                refreshInventory(selectMissingByDefault = true)
                refreshAutoStartTasks()
                notice(R.string.omni_terminal_switched, name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Distribution switch failed", error)
                mutableState.update { it.copy(distributionId = previous) }
                notice(R.string.omni_terminal_switch_failed)
                refreshInventory(selectMissingByDefault = false)
            } finally {
                mutableState.update {
                    it.copy(switchingDistributionId = null, switchCancelling = false,
                        switchProgress = null, switchStage = null)
                }
            }
        }
    }

    private fun cancelSwitch() {
        if (state.value.switchingDistributionId == null || state.value.switchCancelling) return
        mutableState.update { it.copy(switchCancelling = true) }
        repository.cancelDistributionSwitch()
    }

    private fun onInitProgress(payload: Map<String, Any?>) {
        val switching = state.value.switchingDistributionId ?: return
        val distribution = (payload["distribution"] as? String)?.trim()
        if (!distribution.isNullOrEmpty() && distribution != switching) return
        val progress = (payload["progress"] as? Number)?.toFloat()
        val message = (payload["message"] as? String)?.takeIf(String::isNotBlank)
        mutableState.update {
            it.copy(
                switchProgress = progress ?: it.switchProgress,
                switchStage = message ?: it.switchStage,
            )
        }
    }

    private fun refreshAutoStartTasks() {
        mutableState.update { it.copy(autoStartLoading = true, autoStartFailed = false) }
        viewModelScope.launch {
            try {
                val tasks = withContext(Dispatchers.IO) { repository.listAutoStartTasks() }
                mutableState.update {
                    it.copy(
                        autoStartLoading = false,
                        autoStartTasks = tasks.map { task ->
                            TerminalAutoStartTaskItem(
                                id = task.id,
                                name = task.name,
                                command = task.command,
                                workingDirectory = task.workingDirectory,
                                enabled = task.enabled,
                                running = task.running,
                            )
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Auto-start tasks load failed", error)
                mutableState.update {
                    it.copy(autoStartLoading = false, autoStartFailed = true,
                        notice = R.string.omni_terminal_tasks_load_failed)
                }
            }
        }
    }

    private fun saveTask(name: String, command: String, workingDirectory: String, enabled: Boolean) {
        if (state.value.autoStartBusy) return
        val trimmedName = name.trim()
        val trimmedCommand = command.trim()
        if (trimmedName.isEmpty()) {
            notice(R.string.omni_terminal_task_name_required)
            return
        }
        if (trimmedCommand.isEmpty()) {
            notice(R.string.omni_terminal_command_required)
            return
        }
        val editId = state.value.editTaskId
        mutableState.update {
            it.copy(taskEditorOpen = false, editTaskId = null, autoStartBusy = true, notice = null)
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveAutoStartTask(editId, trimmedName, trimmedCommand,
                        workingDirectory.trim().ifEmpty { null }, enabled)
                }
                refreshAutoStartTasks()
                notice(if (editId == null) R.string.omni_terminal_task_added else R.string.omni_terminal_task_updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Auto-start task save failed", error)
                notice(R.string.omni_terminal_task_save_failed)
            } finally {
                mutableState.update { it.copy(autoStartBusy = false) }
            }
        }
    }

    private fun toggleTask(taskId: String, enabled: Boolean) {
        val task = state.value.autoStartTasks.firstOrNull { it.id == taskId } ?: return
        if (state.value.autoStartBusy) return
        mutableState.update { it.copy(autoStartBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveAutoStartTask(task.id, task.name, task.command,
                        task.workingDirectory, enabled)
                }
                refreshAutoStartTasks()
                notice(if (enabled) R.string.omni_terminal_boot_enabled else R.string.omni_terminal_boot_disabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Auto-start task toggle failed", error)
                notice(R.string.omni_terminal_task_update_failed)
            } finally {
                mutableState.update { it.copy(autoStartBusy = false) }
            }
        }
    }

    private fun runTask(taskId: String) {
        if (state.value.autoStartBusy) return
        mutableState.update { it.copy(autoStartBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.runAutoStartTask(taskId) }
                refreshAutoStartTasks()
                notice(R.string.omni_terminal_command_sent,
                    result.message.ifEmpty { null })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Auto-start task run failed", error)
                notice(R.string.omni_terminal_start_failed)
            } finally {
                mutableState.update { it.copy(autoStartBusy = false) }
            }
        }
    }

    private fun deleteTaskConfirmed() {
        val taskId = state.value.deletingTaskId ?: return
        if (state.value.autoStartBusy) return
        mutableState.update { it.copy(deletingTaskId = null, autoStartBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.deleteAutoStartTask(taskId) }
                refreshAutoStartTasks()
                notice(R.string.omni_terminal_task_deleted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Auto-start task delete failed", error)
                notice(R.string.omni_terminal_task_delete_failed)
            } finally {
                mutableState.update { it.copy(autoStartBusy = false) }
            }
        }
    }

    /** Called by the route after the system picker resolves a host path. */
    fun onMountDirectoryPicked(sourcePath: String) {
        if (state.value.mountsBusy) return
        mutableState.update {
            it.copy(
                mountAliasPickerPath = sourcePath,
                mountAliasSuggestion = repository.suggestMountAlias(sourcePath),
                mountAliasError = false,
            )
        }
    }

    fun mountDirectoryPickFailed() {
        notice(R.string.omni_terminal_mount_invalid_dir)
    }

    private fun mountConfirmed(alias: String) {
        val sourcePath = state.value.mountAliasPickerPath ?: return
        val validation = repository.validateMountAlias(alias)
        if (validation != WorkspaceMountManager.AliasValidation.OK) {
            mutableState.update {
                it.copy(mountAliasError = true, notice = aliasErrorRes(validation))
            }
            return
        }
        mutableState.update {
            it.copy(mountAliasPickerPath = null, mountAliasError = false, mountsBusy = true, notice = null)
        }
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { repository.mount(sourcePath, alias) }
                refreshMounts()
                notice(R.string.omni_terminal_mounted, entry.alias)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Mount failed", error)
                notice(R.string.omni_terminal_mount_failed, error.message)
            } finally {
                mutableState.update { it.copy(mountsBusy = false) }
            }
        }
    }

    private fun unmountConfirmed() {
        val linkPath = state.value.unmountLinkPath ?: return
        if (state.value.mountsBusy) return
        val alias = state.value.mounts.firstOrNull { it.linkPath == linkPath }?.alias.orEmpty()
        mutableState.update { it.copy(unmountLinkPath = null, mountsBusy = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.unmount(linkPath) }
                refreshMounts()
                notice(R.string.omni_terminal_unmounted, alias)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Unmount failed", error)
                notice(R.string.omni_terminal_unmount_failed, error.message)
            } finally {
                mutableState.update { it.copy(mountsBusy = false) }
            }
        }
    }

    private fun refreshMounts() {
        mutableState.update { it.copy(mountsLoading = true, mountsFailed = false) }
        viewModelScope.launch {
            try {
                val mounts = withContext(Dispatchers.IO) { repository.listMounts() }
                mutableState.update {
                    it.copy(
                        mountsLoading = false,
                        mounts = mounts.map { entry ->
                            TerminalMountItem(
                                alias = entry.alias,
                                linkPath = entry.linkPath,
                                sourcePath = entry.sourcePath,
                                shellPath = entry.shellPath,
                                broken = entry.broken,
                            )
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeTerminalSettings", "Mounts load failed", error)
                mutableState.update { it.copy(mountsLoading = false, mountsFailed = true) }
            }
        }
    }

    private fun aliasErrorRes(validation: WorkspaceMountManager.AliasValidation): Int = when (validation) {
        WorkspaceMountManager.AliasValidation.EMPTY -> R.string.omni_terminal_mount_alias_empty
        WorkspaceMountManager.AliasValidation.DOT -> R.string.omni_terminal_mount_alias_dot
        WorkspaceMountManager.AliasValidation.SLASH -> R.string.omni_terminal_mount_alias_slash
        WorkspaceMountManager.AliasValidation.BACKSLASH -> R.string.omni_terminal_mount_alias_backslash
        WorkspaceMountManager.AliasValidation.ILLEGAL -> R.string.omni_terminal_mount_alias_illegal
        WorkspaceMountManager.AliasValidation.INTERNAL -> R.string.omni_terminal_mount_alias_internal
        WorkspaceMountManager.AliasValidation.OK -> R.string.omni_terminal_mount_alias_empty
    }

    private fun notice(resource: Int, arg: String? = null) {
        mutableState.update { it.copy(notice = resource, noticeArg = arg) }
    }

    class Factory(context: Context, private val focusPackageId: String?) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeTerminalSettingsViewModel(appContext, focusPackageId) as T
    }
}
