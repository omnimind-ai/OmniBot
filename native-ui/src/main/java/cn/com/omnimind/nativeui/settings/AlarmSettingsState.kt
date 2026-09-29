package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

enum class AlarmSoundSource { Default, LocalMp3, RemoteMp3 }

/** Presentation-only snapshot; MMKV storage and playback stay with the alarm service. */
@Immutable
data class AlarmSettingsState(
    val loaded: Boolean = false,
    val source: AlarmSoundSource = AlarmSoundSource.Default,
    val localLabel: String = "",
    val remoteUrl: String = "",
    val saving: Boolean = false,
    @StringRes val notice: Int? = null,
)

data class AlarmSettingsActions(
    val selectSource: (AlarmSoundSource) -> Unit,
    val editRemoteUrl: (String) -> Unit,
    val save: () -> Unit,
    val dismissNotice: () -> Unit,
)
