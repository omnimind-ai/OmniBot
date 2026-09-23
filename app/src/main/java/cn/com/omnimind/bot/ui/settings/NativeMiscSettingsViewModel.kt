package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.preferences.MiscPreferencesRepository
import cn.com.omnimind.bot.preferences.MiscPreferencesSnapshot
import cn.com.omnimind.bot.preferences.MiscPreferenceKey
import cn.com.omnimind.nativeui.settings.HabitualHandChoice
import cn.com.omnimind.nativeui.settings.MiscNotice
import cn.com.omnimind.nativeui.settings.MiscSettingsActions
import cn.com.omnimind.nativeui.settings.MiscSettingsState
import cn.com.omnimind.nativeui.settings.StartupBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presentation state only; the existing platform and storage owners perform every mutation. */
internal class NativeMiscSettingsViewModel(private val repository: MiscPreferencesRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MiscSettingsState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null

    init { observe() }

    val actions = MiscSettingsActions(
        refresh = ::refresh,
        setStartup = { mutate(MiscPreferenceKey.Startup, it.stored) },
        setRecentOnly = { mutate(MiscPreferenceKey.RecentOnly, it) },
        setHideFromRecents = { mutate(MiscPreferenceKey.HideFromRecents, it) },
        setVibration = { mutate(MiscPreferenceKey.Vibration, it) },
        setIndependentSend = { mutate(MiscPreferenceKey.IndependentSend, it) },
        setPredictiveBack = { mutate(MiscPreferenceKey.PredictiveBack, it) },
        setPreventSleep = { mutate(MiscPreferenceKey.PreventSleep, it) },
        setCompletionNotification = { mutate(MiscPreferenceKey.CompletionNotification, it) },
        setHabitualHand = { mutate(MiscPreferenceKey.HabitualHand, it.stored) },
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun notificationPermissionDenied() {
        mutableState.update { it.copy(notice = MiscNotice.NotificationPermissionRequired) }
    }

    fun refresh() {
        observe()
        if (state.value.busy) return
        viewModelScope.launch {
            try { applySnapshot(withContext(Dispatchers.IO) { repository.read() }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = MiscNotice.SaveFailed) } }
        }
    }

    private fun observe() {
        if (observation?.isActive == true) return
        observation = viewModelScope.launch {
            try { repository.snapshots.collect(::applySnapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = MiscNotice.SaveFailed) } }
        }
    }

    private fun mutate(operation: MiscPreferenceKey, value: Any) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { applySnapshot(repository.update(operation, value)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = MiscNotice.SaveFailed) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun applySnapshot(saved: MiscPreferencesSnapshot) {
        mutableState.update { it.copy(
            loaded = true,
            startup = StartupBehavior.entries.firstOrNull { item -> item.stored == saved.startup }
                ?: StartupBehavior.ResumeLast,
            recentOnly = saved.recentOnly,
            hideFromRecents = saved.hideFromRecents,
            vibration = saved.vibration,
            independentSend = saved.independentSend,
            predictiveBack = saved.predictiveBack,
            preventSleep = saved.preventSleep,
            completionNotification = saved.completionNotification,
            habitualHand = HabitualHandChoice.entries.firstOrNull { item -> item.stored == saved.habitualHand }
                ?: HabitualHandChoice.Right,
        ) }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val repository = MiscPreferencesRepository.get(context)
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeMiscSettingsViewModel::class.java))
            return NativeMiscSettingsViewModel(repository) as T
        }
    }
}
