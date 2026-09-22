package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.manager.ExternalApkInstaller
import cn.com.omnimind.nativeui.settings.AboutNotice
import cn.com.omnimind.nativeui.settings.AboutOperation
import cn.com.omnimind.nativeui.settings.AboutState
import cn.com.omnimind.nativeui.settings.UpdateDownloadSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity-scoped so navigation back to the page cannot start a second in-flight installation. */
internal class NativeAboutViewModel(private val repository: NativeAboutRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(AboutState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun refresh() = perform(AboutOperation.Refresh, AboutNotice.CheckFailed) {
        applySnapshot(repository.cached())
    }

    fun primary() {
        if (job?.isActive == true) return
        if (state.value.hasUpdate) {
            mutableState.update { it.copy(showUpdate = true, notice = null) }
            return
        }
        perform(AboutOperation.Check, AboutNotice.CheckFailed) {
            val snapshot = repository.check()
            applySnapshot(snapshot)
            mutableState.update {
                it.copy(showUpdate = snapshot.hasUpdate, notice = if (snapshot.hasUpdate) null else AboutNotice.UpToDate)
            }
        }
    }

    fun setBeta(enabled: Boolean) = perform(AboutOperation.Beta, AboutNotice.PreferenceFailed) {
        applySnapshot(repository.setBeta(enabled))
    }

    fun setSource(source: UpdateDownloadSource) = perform(AboutOperation.Source, AboutNotice.PreferenceFailed) {
        applySnapshot(repository.setSource(source))
    }

    fun dismissUpdate() { mutableState.update { it.copy(showUpdate = false) } }
    fun browserFailed() { mutableState.update { it.copy(notice = AboutNotice.BrowserUnavailable) } }

    /** Called only after the update dialog's confirmation; the installer owns its permission flow. */
    fun installConfirmed() = perform(AboutOperation.Install, AboutNotice.InstallFailed) {
        mutableState.update { it.copy(showUpdate = false) }
        if (!repository.requestDownloadNotifications()) {
            mutableState.update { it.copy(notice = AboutNotice.NotificationsDenied) }
        }
        val result = repository.install()
        val notice = when (result.status) {
            ExternalApkInstaller.STATUS_INSTALLER_LAUNCHED -> AboutNotice.InstallerOpened
            ExternalApkInstaller.STATUS_INSTALL_PERMISSION_REQUIRED -> AboutNotice.InstallPermissionRequired
            ExternalApkInstaller.STATUS_DOWNLOAD_FAILED -> AboutNotice.DownloadFailed
            else -> AboutNotice.InstallFailed
        }
        mutableState.update { it.copy(notice = notice) }
    }

    private fun applySnapshot(snapshot: AboutState) {
        mutableState.update { snapshot.copy(operation = it.operation, showUpdate = it.showUpdate && snapshot.hasUpdate, notice = it.notice) }
    }

    private fun perform(operation: AboutOperation, failure: AboutNotice, action: suspend () -> Unit) {
        if (job?.isActive == true) return
        mutableState.update { it.copy(operation = operation, notice = if (operation == AboutOperation.Refresh) it.notice else null) }
        job = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                OmniLog.e("NativeAbout", "About operation failed: $operation", error)
                mutableState.update { it.copy(notice = failure) }
            } finally { mutableState.update { it.copy(operation = null) } }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeAboutViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return NativeAboutViewModel(NativeAboutRepository(context.applicationContext)) as T
        }
    }
}
