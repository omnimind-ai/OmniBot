package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.bot.share.SharedOpenPreferenceStore
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.OpenWithSettingsActions
import cn.com.omnimind.nativeui.settings.OpenWithSettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Open-with preferences. `SharedOpenPreferenceStore` stays the single owner;
 * the store's normalized return value is authoritative, and a rejected mode
 * rolls the optimistic update back, as on the Flutter page.
 */
internal class NativeOpenWithViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(OpenWithSettingsState())
    val state = mutableState.asStateFlow()

    val actions = OpenWithSettingsActions(
        setMode = ::setMode,
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load() {
        if (state.value.loaded) return
        viewModelScope.launch {
            val (image, file) = withContext(Dispatchers.IO) {
                SharedOpenPreferenceStore.getImageOpenMode(appContext) to
                    SharedOpenPreferenceStore.getFileOpenMode(appContext)
            }
            mutableState.update { it.copy(loaded = true, imageMode = image, fileMode = file) }
        }
    }

    private fun setMode(target: String, mode: String) {
        val current = state.value
        if (!current.loaded) return
        val isImage = target == "image"
        val previous = if (isImage) current.imageMode else current.fileMode
        if (previous == mode) return
        mutableState.update {
            if (isImage) it.copy(imageMode = mode, notice = null) else it.copy(fileMode = mode, notice = null)
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                if (isImage) {
                    SharedOpenPreferenceStore.setImageOpenMode(appContext, mode)
                } else {
                    SharedOpenPreferenceStore.setFileOpenMode(appContext, mode)
                }
            }
            mutableState.update {
                if (saved == mode) {
                    if (isImage) it.copy(imageMode = saved) else it.copy(fileMode = saved)
                } else {
                    if (isImage) {
                        it.copy(imageMode = previous, notice = R.string.omni_open_with_save_failed)
                    } else {
                        it.copy(fileMode = previous, notice = R.string.omni_open_with_save_failed)
                    }
                }
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativeOpenWithViewModel(appContext) as T
    }
}
