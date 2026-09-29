package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

enum class RemoteBridgeSaveStatus { None, Incomplete, Pending, Saving, Saved }

@Immutable
data class RemoteDirectoryPickerEntry(val name: String, val path: String)

/** Bottom-sheet listing state; owned by the page ViewModel, never by the sheet. */
@Immutable
data class RemoteDirectoryPickerState(
    val currentPath: String = "",
    val parent: String? = null,
    val home: String? = null,
    val entries: List<RemoteDirectoryPickerEntry> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

/** The token field is masked by default and redacted from the string form. */
@Immutable
data class RemoteBridgeState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
    val cwd: String = "",
    val status: RemoteBridgeSaveStatus = RemoteBridgeSaveStatus.None,
    val testing: Boolean = false,
    val tokenVisible: Boolean = false,
    @StringRes val errorRes: Int? = null,
    @StringRes val notice: Int? = null,
    val picker: RemoteDirectoryPickerState? = null,
) {
    override fun toString(): String =
        "RemoteBridgeState(loaded=$loaded, enabled=$enabled, url=$url, cwd=$cwd, status=$status)"
}

data class RemoteBridgeActions(
    val setEnabled: (Boolean) -> Unit,
    val editUrl: (String) -> Unit,
    val editToken: (String) -> Unit,
    val editCwd: (String) -> Unit,
    val toggleTokenVisible: () -> Unit,
    val test: () -> Unit,
    val openPicker: () -> Unit,
    val closePicker: () -> Unit,
    val pickerOpen: (String) -> Unit,
    val pickerUp: () -> Unit,
    val pickerHome: () -> Unit,
    val pickerRefresh: () -> Unit,
    val pickerSelect: () -> Unit,
    val dismissNotice: () -> Unit,
)
