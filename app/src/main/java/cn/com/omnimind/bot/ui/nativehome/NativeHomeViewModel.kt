package cn.com.omnimind.bot.ui.nativehome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NativeHomeViewModel(private val repository: NativeHomeRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(repository.readPreferences())
    val state = mutableState.asStateFlow()
    private var historyJob: Job? = null
    private var serviceJob: Job? = null

    init { observeHistory() }

    private fun observeHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            try {
                repository.snapshots.collect { snapshot ->
                    mutableState.update {
                        snapshot.copy(localServiceEnabled = it.localServiceEnabled, localServiceBusy = it.localServiceBusy)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    fun refresh() {
        if (historyJob?.isActive != true) observeHistory()
        if (serviceJob?.isActive == true) return
        serviceJob = viewModelScope.launch {
            try {
                val enabled = repository.localServiceEnabled()
                mutableState.update { it.copy(localServiceEnabled = enabled, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    fun setLocalServiceEnabled(enabled: Boolean) {
        if (mutableState.value.localServiceBusy) return
        serviceJob?.cancel()
        mutableState.update { it.copy(localServiceBusy = true) }
        serviceJob = viewModelScope.launch {
            try {
                val actual = repository.setLocalServiceEnabled(enabled)
                mutableState.update { it.copy(localServiceEnabled = actual, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(localServiceBusy = false) }
            }
        }
    }

    private fun reportError(error: Exception) {
        OmniLog.e("NativeHome", "Failed to load home data", error)
        mutableState.update { it.copy(loading = false, error = error.localizedMessage ?: "Unable to load home data") }
    }

    class Factory(private val repository: NativeHomeRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NativeHomeViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return NativeHomeViewModel(repository) as T
        }
    }
}
