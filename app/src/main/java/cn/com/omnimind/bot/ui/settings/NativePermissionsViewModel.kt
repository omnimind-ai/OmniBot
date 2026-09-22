package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.nativeui.settings.PermissionNotice
import cn.com.omnimind.nativeui.settings.PermissionPrompt
import cn.com.omnimind.nativeui.settings.PermissionSetting
import cn.com.omnimind.nativeui.settings.PermissionsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads grants on resume; opening Settings is never treated as a successful grant. */
internal class NativePermissionsViewModel(private val repository: NativePermissionsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PermissionsState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var actionJob: Job? = null

    fun refresh() {
        if (refreshJob?.isActive == true || actionJob?.isActive == true) return
        mutableState.update { it.copy(refreshing = true, notice = null) }
        refreshJob = viewModelScope.launch {
            try {
                if (state.value.waitingForAccessibility) repository.awaitAccessibilityReady()
                val snapshot = repository.read()
                mutableState.update { previous ->
                    val wasWaiting = previous.waitingForAccessibility
                    snapshot.copy(
                        prompt = if (wasWaiting && snapshot.accessibilityReady) null else previous.prompt,
                        pendingSetting = previous.pendingSetting,
                        waitingForAccessibility = wasWaiting && !snapshot.accessibilityReady,
                        notice = if (wasWaiting && !snapshot.accessibilityReady) PermissionNotice.AccessibilityDisabled else null,
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error, PermissionNotice.ReadFailed) }
            finally { mutableState.update { it.copy(refreshing = false) } }
        }
    }

    fun select(setting: PermissionSetting) {
        if (state.value.busy) return
        when (setting) {
            PermissionSetting.Accessibility -> if (!state.value.accessibilityReady) {
                mutableState.update { it.copy(prompt = PermissionPrompt.Accessibility, notice = null) }
            }
            PermissionSetting.Shizuku -> if (!state.value.shizuku.granted) {
                mutableState.update { it.copy(prompt = PermissionPrompt.Shizuku, notice = null) }
            }
            else -> mutableState.update { it.copy(pendingSetting = setting, notice = null) }
        }
    }

    fun dismissPrompt() {
        mutableState.update { it.copy(prompt = null, waitingForAccessibility = false) }
    }

    fun confirmPrompt() {
        when (state.value.prompt) {
            PermissionPrompt.Accessibility -> mutableState.update {
                it.copy(pendingSetting = PermissionSetting.Accessibility, waitingForAccessibility = true, notice = null)
            }
            PermissionPrompt.Shizuku -> {
                mutableState.update { it.copy(prompt = null) }
                perform(PermissionNotice.OpenFailed) {
                    val previouslyRunning = state.value.shizuku.running
                    if (!repository.confirmShizuku() && previouslyRunning) {
                        mutableState.update { it.copy(notice = PermissionNotice.ShizukuDenied) }
                    }
                }
            }
            null -> Unit
        }
    }

    fun setNotifications(enabled: Boolean) = perform(PermissionNotice.SaveFailed) {
        repository.setNotifications(enabled)
    }

    fun consumeSetting() { mutableState.update { it.copy(pendingSetting = null) } }
    fun settingFailed(error: Exception) {
        mutableState.update { it.copy(waitingForAccessibility = false) }
        report(error, PermissionNotice.OpenFailed)
    }

    private fun perform(failure: PermissionNotice, action: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        refreshJob?.cancel()
        mutableState.update { it.copy(busy = true, refreshing = false, notice = null) }
        actionJob = viewModelScope.launch {
            try {
                action()
                val snapshot = repository.read()
                mutableState.update { snapshot.copy(prompt = it.prompt, notice = it.notice) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error, failure) }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }

    private fun report(error: Exception, notice: PermissionNotice) {
        OmniLog.e("NativePermissions", "Permission operation failed", error)
        mutableState.update { it.copy(notice = notice) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativePermissionsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return NativePermissionsViewModel(NativePermissionsRepository(context.applicationContext)) as T
        }
    }
}
