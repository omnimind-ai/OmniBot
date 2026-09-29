package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.NativeRemoteBridgeConfig
import cn.com.omnimind.bot.agent.NativeRemoteBridgeRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.RemoteBridgeActions
import cn.com.omnimind.nativeui.settings.RemoteBridgeSaveStatus
import cn.com.omnimind.nativeui.settings.RemoteBridgeState
import cn.com.omnimind.nativeui.settings.RemoteDirectoryPickerEntry
import cn.com.omnimind.nativeui.settings.RemoteDirectoryPickerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remote PC Bridge settings. Autosave is debounced by 700 ms and deduplicated
 * by a trimmed field signature, as on the Flutter page. Two jobs keep the
 * semantics safe: the debounce job may be cancelled by further edits, while
 * the in-flight write job always completes and re-arms a pending edit instead
 * of dropping it. The ViewModel is Activity-scoped, so leaving the page never
 * discards a committed write; a process death drops the pending debounce like
 * the Flutter page's dispose.
 */
internal class NativeRemoteBridgeViewModel(context: Context) : ViewModel() {
    private val repository = NativeRemoteBridgeRepository(context)
    private val mutableState = MutableStateFlow(RemoteBridgeState())
    private var lastSavedSignature: String? = null
    private var debounceJob: Job? = null
    private var saveJob: Job? = null
    private var pickerConfig = NativeRemoteBridgeConfig(enabled = false, bridgeUrl = "", token = "", cwd = "")
    private var pickerGeneration = 0
    val state = mutableState.asStateFlow()

    val actions = RemoteBridgeActions(
        setEnabled = ::setEnabled,
        editUrl = { value -> edit { it.copy(url = value) } },
        editToken = { value -> edit { it.copy(token = value) } },
        editCwd = { value -> edit { it.copy(cwd = value) } },
        toggleTokenVisible = { mutableState.update { it.copy(tokenVisible = !it.tokenVisible) } },
        test = ::test,
        openPicker = ::openPicker,
        closePicker = { mutableState.update { it.copy(picker = null) } },
        pickerOpen = ::loadPickerDirectory,
        pickerUp = {
            state.value.picker?.parent?.let(::loadPickerDirectory)
        },
        pickerHome = {
            state.value.picker?.home?.let(::loadPickerDirectory)
        },
        pickerRefresh = {
            state.value.picker?.let { picker -> loadPickerDirectory(picker.currentPath) }
        },
        pickerSelect = ::selectPickerDirectory,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load() {
        if (state.value.loaded) return
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { repository.read() }
                lastSavedSignature = signatureOf(config)
                mutableState.update {
                    it.copy(
                        loaded = true,
                        enabled = config.enabled,
                        url = config.bridgeUrl,
                        token = config.token,
                        cwd = config.cwd,
                        status = RemoteBridgeSaveStatus.None,
                        errorRes = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeRemoteBridge", "Remote bridge config load failed", error)
                mutableState.update { it.copy(loaded = true, errorRes = R.string.omni_bridge_load_failed) }
            }
        }
    }

    /**
     * Returning from the Flutter QR-scan hand-off re-reads the stored config.
     * An unsaved local draft wins over the reload; a clean form adopts it.
     */
    fun resume() {
        if (!state.value.loaded) {
            load()
            return
        }
        if (signatureOf(state.value) != lastSavedSignature) return
        viewModelScope.launch {
            try {
                val config = withContext(Dispatchers.IO) { repository.read() }
                if (signatureOf(state.value) != lastSavedSignature) return@launch
                lastSavedSignature = signatureOf(config)
                mutableState.update {
                    it.copy(
                        enabled = config.enabled,
                        url = config.bridgeUrl,
                        token = config.token,
                        cwd = config.cwd,
                        status = RemoteBridgeSaveStatus.None,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OmniLog.w("NativeRemoteBridge", "Remote bridge refresh failed; keeping current form")
            }
        }
    }

    private fun setEnabled(enabled: Boolean) {
        if (state.value.status == RemoteBridgeSaveStatus.Saving) return
        edit { it.copy(enabled = enabled) }
    }

    private fun edit(change: (RemoteBridgeState) -> RemoteBridgeState) {
        mutableState.update(change)
        onEdited()
    }

    private fun onEdited() {
        debounceJob?.cancel()
        val current = state.value
        val complete = isComplete(current)
        val signature = signatureOf(current)
        mutableState.update {
            it.copy(
                errorRes = null,
                status = when {
                    !complete -> RemoteBridgeSaveStatus.Incomplete
                    signature == lastSavedSignature -> RemoteBridgeSaveStatus.Saved
                    else -> RemoteBridgeSaveStatus.Pending
                },
            )
        }
        if (complete && signature != lastSavedSignature) {
            debounceJob = viewModelScope.launch {
                delay(AUTO_SAVE_DELAY_MS)
                startSave()
            }
        }
    }

    /** The write job is never cancelled by edits; its completion re-arms them. */
    private fun startSave() {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            try {
                while (true) {
                    val current = state.value
                    if (!isComplete(current)) break
                    val signature = signatureOf(current)
                    if (signature == lastSavedSignature) break
                    mutableState.update { it.copy(status = RemoteBridgeSaveStatus.Saving) }
                    val saved = withContext(Dispatchers.IO) {
                        repository.write(
                            NativeRemoteBridgeConfig(
                                enabled = current.enabled,
                                bridgeUrl = current.url,
                                token = current.token,
                                cwd = current.cwd,
                            ),
                        )
                    }
                    lastSavedSignature = signatureOf(saved)
                    // Adopt the server-trimmed form only while the user has not typed.
                    mutableState.update {
                        val adopt = signatureOf(it) == signature
                        it.copy(
                            url = if (adopt) saved.bridgeUrl else it.url,
                            token = if (adopt) saved.token else it.token,
                            cwd = if (adopt) saved.cwd else it.cwd,
                            status = if (signatureOf(it) == lastSavedSignature) {
                                RemoteBridgeSaveStatus.Saved
                            } else {
                                RemoteBridgeSaveStatus.Pending
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeRemoteBridge", "Remote bridge autosave failed", error)
                mutableState.update {
                    it.copy(status = RemoteBridgeSaveStatus.None, errorRes = R.string.omni_bridge_save_failed)
                }
            }
            // Edits that arrived during the write get a fresh debounce window.
            val current = state.value
            if (isComplete(current) && signatureOf(current) != lastSavedSignature) {
                onEdited()
            }
        }
    }

    private fun test() {
        val current = state.value
        if (current.testing) return
        if (current.url.trim().isEmpty() || current.cwd.trim().isEmpty()) {
            mutableState.update { it.copy(notice = R.string.omni_bridge_url_cwd_required) }
            return
        }
        mutableState.update { it.copy(testing = true, notice = null) }
        viewModelScope.launch {
            try {
                val probe = withContext(Dispatchers.IO) {
                    repository.test(
                        NativeRemoteBridgeConfig(
                            enabled = true,
                            bridgeUrl = current.url,
                            token = current.token,
                            cwd = current.cwd,
                        ),
                    )
                }
                mutableState.update {
                    it.copy(notice = if (probe.ok) R.string.omni_bridge_test_ready else R.string.omni_bridge_test_failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeRemoteBridge", "Remote bridge test failed", error)
                mutableState.update { it.copy(notice = R.string.omni_bridge_test_failed) }
            } finally {
                mutableState.update { it.copy(testing = false) }
            }
        }
    }

    private fun openPicker() {
        val current = state.value
        if (current.url.trim().isEmpty()) {
            mutableState.update { it.copy(notice = R.string.omni_bridge_url_required) }
            return
        }
        // The listing uses the form values captured when the sheet opens.
        pickerConfig = NativeRemoteBridgeConfig(
            enabled = true,
            bridgeUrl = current.url.trim(),
            token = current.token.trim(),
            cwd = current.cwd.trim(),
        )
        mutableState.update { it.copy(picker = RemoteDirectoryPickerState(currentPath = current.cwd.trim())) }
        loadPickerDirectory(current.cwd.trim())
    }

    private fun loadPickerDirectory(path: String) {
        if (state.value.picker == null) return
        val generation = ++pickerGeneration
        mutableState.update { it.copy(picker = it.picker?.copy(loading = true, failed = false)) }
        viewModelScope.launch {
            try {
                val listing = withContext(Dispatchers.IO) {
                    repository.listDirectories(pickerConfig, path)
                }
                if (generation != pickerGeneration) return@launch
                mutableState.update {
                    it.copy(
                        picker = it.picker?.copy(
                            currentPath = listing.path.ifEmpty { path },
                            parent = listing.parent,
                            home = listing.home,
                            entries = listing.entries.map { entry ->
                                RemoteDirectoryPickerEntry(entry.name, entry.path)
                            },
                            loading = false,
                            failed = !listing.ok,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativeRemoteBridge", "Remote directory list failed", error)
                if (generation != pickerGeneration) return@launch
                mutableState.update { it.copy(picker = it.picker?.copy(loading = false, failed = true)) }
            }
        }
    }

    private fun selectPickerDirectory() {
        val picker = state.value.picker ?: return
        val path = picker.currentPath.trim()
        if (path.isEmpty() || picker.loading || picker.failed) return
        mutableState.update { it.copy(picker = null, cwd = path) }
        // A picker selection is an edit: it joins the same debounced autosave.
        onEdited()
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeRemoteBridgeViewModel(appContext) as T
    }

    private companion object {
        const val AUTO_SAVE_DELAY_MS = 700L

        fun isComplete(state: RemoteBridgeState): Boolean =
            !state.enabled || (state.url.trim().isNotEmpty() && state.cwd.trim().isNotEmpty())

        fun signatureOf(state: RemoteBridgeState): String = listOf(
            if (state.enabled) "enabled" else "disabled",
            state.url.trim(),
            state.token.trim(),
            state.cwd.trim(),
        ).joinToString("\n")

        fun signatureOf(config: NativeRemoteBridgeConfig): String = listOf(
            if (config.enabled) "enabled" else "disabled",
            config.bridgeUrl,
            config.token,
            config.cwd,
        ).joinToString("\n")
    }
}
