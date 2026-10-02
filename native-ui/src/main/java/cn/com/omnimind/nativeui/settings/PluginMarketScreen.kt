package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

/**
 * Plugin market list. Presentation only; the catalog stays with OmniPluginHost
 * behind the host ViewModel.
 */
@Composable
fun PluginMarketScreen(
    state: PluginMarketState,
    actions: PluginMarketActions,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let { stringResource(it) }
    OmniPage(stringResource(R.string.omni_plugin_market_title), onBack,
        notice = notice, onNoticeShown = actions.dismissNotice) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            PluginSearchField(state.query, actions.setQuery,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp))
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val query = state.query.trim().lowercase()
                val visible = state.plugins.filter { plugin ->
                    query.isEmpty() || plugin.name.lowercase().contains(query) ||
                        plugin.description.lowercase().contains(query) ||
                        plugin.publisher.lowercase().contains(query) ||
                        plugin.capabilities.any { it.lowercase().contains(query) }
                }
                when {
                    state.plugins.isEmpty() -> PluginEmptyState(
                        stringResource(R.string.omni_plugin_market_empty),
                        stringResource(R.string.omni_plugin_market_empty_desc),
                    )
                    visible.isEmpty() -> PluginEmptyState(
                        stringResource(R.string.omni_plugin_search_empty), null,
                        search = true,
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp),
                    ) {
                        items(visible.size, key = { visible[it].id }) { index ->
                            PluginRow(visible[index], onOpen)
                            if (index < visible.lastIndex) {
                                Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp)
                                    .background(palette.border.copy(alpha = if (palette.dark) .5f else .78f)))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalOmniPalette.current
    val hint = stringResource(R.string.omni_plugin_search_hint)
    BasicTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = palette.text),
        cursorBrush = SolidColor(palette.accent),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = hint },
        decorationBox = { field ->
            Row(
                Modifier.height(36.dp).clip(CircleShape).background(palette.secondarySurface)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(R.drawable.omni_search, tint = palette.secondaryText)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(hint, fontSize = 13.sp, color = palette.tertiaryText,
                        maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
private fun PluginEmptyState(title: String, description: String?, search: Boolean = false) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        OmniIcon(if (search) R.drawable.omni_search else R.drawable.omni_puzzle, size = 48.dp,
            tint = palette.tertiaryText)
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = palette.text)
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(description, fontSize = 12.sp, color = palette.secondaryText)
        }
    }
}

@Composable
private fun PluginRow(plugin: PluginItem, onOpen: (String) -> Unit) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { onOpen(plugin.id) }
            .padding(start = 4.dp, top = 14.dp, end = 2.dp, bottom = 14.dp)
            .semantics { contentDescription = plugin.name },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                .background(palette.accent.copy(alpha = .1f)),
            contentAlignment = Alignment.Center,
        ) {
            OmniIcon(R.drawable.omni_puzzle, size = 23.dp, tint = palette.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(plugin.name, fontSize = 14.sp, lineHeight = 20.3.sp, fontWeight = FontWeight.Medium,
                color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                plugin.description.trim().ifEmpty { stringResource(R.string.omni_plugin_no_description) },
                fontSize = 11.sp, lineHeight = 16.5.sp, color = palette.secondaryText,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(
                    plugin.publisher,
                    stringResource(kindLabelRes(plugin.kind)),
                    formatPluginSize(plugin.downloadSizeBytes),
                ).filter(String::isNotEmpty).joinToString(" · "),
                fontSize = 11.sp, lineHeight = 15.4.sp, color = palette.tertiaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                stringResource(pluginStatusRes(plugin.compatible, plugin.installed, plugin.enabled)),
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (plugin.installed && plugin.enabled) palette.accent else palette.tertiaryText,
            )
            Spacer(Modifier.height(8.dp))
            OmniIcon(R.drawable.omni_chevron_right, size = 20.dp, tint = palette.tertiaryText)
        }
    }
}

internal fun kindLabelRes(kind: String): Int = when (kind) {
    "bundled_module" -> R.string.omni_plugin_kind_bundled_module
    "companion_app" -> R.string.omni_plugin_kind_companion_app
    else -> R.string.omni_plugin_kind_runtime_bundle
}

internal fun pluginStatusRes(compatible: Boolean, installed: Boolean, enabled: Boolean): Int = when {
    !compatible -> R.string.omni_plugin_incompatible
    !installed -> R.string.omni_plugin_status_not_installed
    enabled -> R.string.omni_plugin_status_enabled
    else -> R.string.omni_plugin_status_installed
}

internal fun formatPluginSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val megabyte = 1024 * 1024L
    if (bytes >= megabyte) {
        val value = bytes.toDouble() / megabyte
        return if (bytes >= 10 * megabyte) "${value.toInt()} MB" else "%.1f MB".format(value)
    }
    return "%.0f KB".format(bytes / 1024.0)
}
