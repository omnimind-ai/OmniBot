package cn.com.omnimind.nativeui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable

/** Presentation row; the plugin platform store stays with OmniPluginHost. */
@Immutable
data class PluginItem(
    val id: String,
    val name: String,
    val description: String,
    val publisher: String,
    val kind: String,
    val downloadSizeBytes: Long,
    val capabilities: List<String> = emptyList(),
    val installed: Boolean,
    val enabled: Boolean,
    val compatible: Boolean,
)

@Immutable
data class PluginMarketState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val plugins: List<PluginItem> = emptyList(),
    val query: String = "",
    @StringRes val notice: Int? = null,
)

data class PluginMarketActions(
    val refresh: () -> Unit,
    val setQuery: (String) -> Unit,
    val dismissNotice: () -> Unit,
)
