package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Presentation-only snapshot; the shared-open preference store stays the owner. */
@Immutable
data class OpenWithSettingsState(
    val loaded: Boolean = false,
    val imageMode: String = "default",
    val fileMode: String = "default",
    @StringRes val notice: Int? = null,
)

data class OpenWithSettingsActions(
    val setMode: (target: String, mode: String) -> Unit,
    val dismissNotice: () -> Unit,
)
