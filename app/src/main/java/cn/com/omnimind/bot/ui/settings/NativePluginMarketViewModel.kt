package cn.com.omnimind.bot.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.plugin.NativePluginRepository
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.PluginItem
import cn.com.omnimind.nativeui.settings.PluginMarketActions
import cn.com.omnimind.nativeui.settings.PluginMarketState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plugin market list. `OmniPluginHost` stays the catalog/install owner. */
internal class NativePluginMarketViewModel(context: Context) : ViewModel() {
    private val repository = NativePluginRepository(context)
    private val mutableState = MutableStateFlow(PluginMarketState())
    val state = mutableState.asStateFlow()

    val actions = PluginMarketActions(
        refresh = { load(force = true) },
        setQuery = { query -> mutableState.update { it.copy(query = query) } },
        dismissNotice = { mutableState.update { it.copy(notice = null) } },
    )

    fun load(force: Boolean = false) {
        val current = state.value
        if (current.loading) return
        if (current.loaded && !force) return
        mutableState.update { it.copy(loading = true, notice = null) }
        viewModelScope.launch {
            try {
                // The Flutter page hides `visibility == "hidden"` plugins.
                val plugins = withContext(Dispatchers.IO) { repository.list() }
                    .filterNot(repository::isHidden)
                    .map { state ->
                        toItem(state).copy(
                            description = repository.localized(state.descriptor.presentation["description"])
                                .ifEmpty { state.descriptor.description.trim() },
                        )
                    }
                mutableState.update { it.copy(loaded = true, loading = false, plugins = plugins) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e("NativePluginMarket", "Plugin list load failed", error)
                mutableState.update {
                    it.copy(loading = false, notice = R.string.omni_plugin_load_failed)
                }
            }
        }
    }

    /** Entry lifecycle resume: the detail page may have changed install state. */
    fun resume() {
        load(force = true)
    }

    private fun toItem(state: cn.com.omnimind.bot.plugin.OmniPluginState): PluginItem {
        val descriptor = state.descriptor
        return PluginItem(
            id = descriptor.id,
            name = descriptor.name,
            description = descriptor.description,
            publisher = descriptor.publisher,
            kind = descriptor.kind.wireName,
            downloadSizeBytes = descriptor.downloadSizeBytes,
            capabilities = descriptor.capabilities,
            installed = state.installed,
            enabled = state.enabled,
            compatible = state.compatible,
        )
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NativePluginMarketViewModel(appContext) as T
    }
}
