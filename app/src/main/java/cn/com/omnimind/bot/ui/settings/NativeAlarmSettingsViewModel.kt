package cn.com.omnimind.bot.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.AlarmSettingsActions
import cn.com.omnimind.nativeui.settings.AlarmSettingsState
import cn.com.omnimind.nativeui.settings.AlarmSoundSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Alarm settings page. The MMKV record and its validation stay in
 * `AgentAlarmToolService`; this ViewModel only edits a draft and saves through
 * the repository. The stored local value is the picked document's content URI.
 */
internal class NativeAlarmSettingsViewModel(context: Context) : ViewModel() {
    private val repository = NativeAlarmSettingsRepository(context)
    private val mutableState = MutableStateFlow(AlarmSettingsState())
    private var localValue = ""
    val state = mutableState.asStateFlow()

    val actions = AlarmSettingsActions(
        selectSource = ::selectSource,
        editRemoteUrl = { url ->
            if (!state.value.saving) mutableState.update { it.copy(remoteUrl = url) }
        },
        save = ::save,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load() {
        if (state.value.loaded || state.value.saving) return
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { repository.read() }
                localValue = snapshot.localValue
                mutableState.update {
                    it.copy(
                        loaded = true,
                        source = parseSource(snapshot.source),
                        localLabel = snapshot.localLabel,
                        remoteUrl = snapshot.remoteUrl,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAlarmSettings", "Alarm settings load failed", error)
                mutableState.update { it.copy(notice = R.string.omni_alarm_save_failed) }
            }
        }
    }

    /** The picker grants a content URI; the repository persists the read grant. */
    fun onLocalPicked(uri: Uri) {
        if (state.value.saving) return
        val label = repository.persistLocalSelection(uri)
        localValue = uri.toString()
        mutableState.update {
            it.copy(source = AlarmSoundSource.LocalMp3, localLabel = label, notice = null)
        }
    }

    fun permissionDenied() {
        mutableState.update { it.copy(notice = R.string.omni_alarm_audio_permission_denied) }
    }

    private fun selectSource(source: AlarmSoundSource) {
        if (state.value.saving) return
        mutableState.update { it.copy(source = source, notice = null) }
    }

    private fun save() {
        val current = state.value
        if (current.saving || !current.loaded) return
        val localPath: String?
        val remoteUrl: String?
        when (current.source) {
            AlarmSoundSource.Default -> {
                localPath = null
                remoteUrl = null
            }
            AlarmSoundSource.LocalMp3 -> {
                if (localValue.isBlank()) {
                    mutableState.update { it.copy(notice = R.string.omni_alarm_select_local_first) }
                    return
                }
                localPath = localValue
                remoteUrl = null
            }
            AlarmSoundSource.RemoteMp3 -> {
                val url = current.remoteUrl.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    mutableState.update { it.copy(notice = R.string.omni_alarm_enter_https_url) }
                    return
                }
                localPath = null
                remoteUrl = url
            }
        }
        mutableState.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.save(wireSource(current.source), localPath, remoteUrl)
                }
                mutableState.update { it.copy(saving = false, notice = R.string.omni_alarm_saved) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeAlarmSettings", "Alarm settings save failed", error)
                mutableState.update { it.copy(saving = false, notice = R.string.omni_alarm_save_failed) }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeAlarmSettingsViewModel(appContext) as T
    }

    private companion object {
        fun parseSource(source: String): AlarmSoundSource = when (source) {
            "local_mp3" -> AlarmSoundSource.LocalMp3
            "remote_mp3_url" -> AlarmSoundSource.RemoteMp3
            else -> AlarmSoundSource.Default
        }

        fun wireSource(source: AlarmSoundSource): String = when (source) {
            AlarmSoundSource.Default -> "default"
            AlarmSoundSource.LocalMp3 -> "local_mp3"
            AlarmSoundSource.RemoteMp3 -> "remote_mp3_url"
        }
    }
}
