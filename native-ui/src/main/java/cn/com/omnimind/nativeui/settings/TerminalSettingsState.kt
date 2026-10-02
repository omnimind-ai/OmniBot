package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

@Immutable
data class TerminalEnvironmentDefinition(
    val id: String,
    val title: String,
    @StringRes val descriptionRes: Int,
    @StringRes val groupRes: Int,
)

@Immutable
data class TerminalEnvironmentItem(
    val definition: TerminalEnvironmentDefinition,
    val ready: Boolean?,
    val version: String?,
)

@Immutable
data class TerminalAutoStartTaskItem(
    val id: String,
    val name: String,
    val command: String,
    val workingDirectory: String?,
    val enabled: Boolean,
    val running: Boolean,
)

@Immutable
data class TerminalMountItem(
    val alias: String,
    val linkPath: String,
    val sourcePath: String,
    val shellPath: String,
    val broken: Boolean,
)

/** Editor draft for the boot-task sheet; local to the sheet. */
@Immutable
data class TerminalBootTaskDraft(
    val taskId: String?,
    val name: String,
    val command: String,
    val workingDirectory: String,
    val enabled: Boolean,
)

@Immutable
data class TerminalSettingsState(
    val loaded: Boolean = false,
    val detecting: Boolean = true,
    val detectFailed: Boolean = false,
    val distributionId: String = "alpine",
    val distributionLoading: Boolean = true,
    val switchingDistributionId: String? = null,
    val switchCancelling: Boolean = false,
    val switchProgress: Float? = null,
    val switchStage: String? = null,
    val items: List<TerminalEnvironmentItem> = emptyList(),
    val selectedPackageIds: Set<String> = emptySet(),
    val openingSetup: Boolean = false,
    val autoStartLoading: Boolean = true,
    val autoStartFailed: Boolean = false,
    val autoStartTasks: List<TerminalAutoStartTaskItem> = emptyList(),
    val autoStartBusy: Boolean = false,
    val mountsLoading: Boolean = true,
    val mountsFailed: Boolean = false,
    val mounts: List<TerminalMountItem> = emptyList(),
    val mountsBusy: Boolean = false,
    val editTaskId: String? = null,
    val taskEditorOpen: Boolean = false,
    val deletingTaskId: String? = null,
    val unmountLinkPath: String? = null,
    val mountAliasPickerPath: String? = null,
    val mountAliasSuggestion: String = "",
    val mountAliasError: Boolean = false,
    @StringRes val notice: Int? = null,
    val noticeArg: String? = null,
) {
    val selectedMissingCount: Int
        get() = items.count { it.ready == false && it.definition.id in selectedPackageIds }
}

data class TerminalSettingsActions(
    val refresh: () -> Unit,
    val switchDistribution: (String) -> Unit,
    val cancelSwitch: () -> Unit,
    val togglePackage: (String, Boolean) -> Unit,
    val openTaskEditor: (String?) -> Unit,
    val closeTaskEditor: () -> Unit,
    val saveTaskEditor: (name: String, command: String, workingDirectory: String, enabled: Boolean) -> Unit,
    val toggleTask: (String, Boolean) -> Unit,
    val runTask: (String) -> Unit,
    val confirmDeleteTask: (String?) -> Unit,
    val deleteTaskConfirmed: () -> Unit,
    val confirmUnmount: (String?) -> Unit,
    val unmountConfirmed: () -> Unit,
    val mountConfirmed: (String) -> Unit,
    val dismissMountPicker: () -> Unit,
    val dismissNotice: () -> Unit,
)
