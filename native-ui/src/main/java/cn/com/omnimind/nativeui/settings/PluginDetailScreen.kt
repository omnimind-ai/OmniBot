package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * Plugin detail page. Presentation only; install/update/enable/uninstall stay
 * with OmniPluginHost behind the host ViewModel.
 */
@Composable
fun PluginDetailScreen(
    state: PluginDetailState,
    actions: PluginDetailActions,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val plugin = state.plugin
    val notice = state.notice?.let {
        if (state.noticeArg != null) stringResource(it, state.noticeArg) else stringResource(it)
    }
    OmniPage(
        stringResource(R.string.omni_plugin_detail_title), onBack,
        notice = notice, onNoticeShown = actions.dismissNotice,
        bottomBar = {
            if (plugin != null) PluginBottomBar(plugin, state.busy, actions)
        },
    ) { insets ->
        when {
            !state.loaded || state.loading -> Box(
                Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.notFound || plugin == null -> Column(
                Modifier.fillMaxSize().padding(insets),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                OmniIcon(R.drawable.omni_puzzle, size = 48.dp, tint = palette.tertiaryText)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.omni_plugin_market_empty), fontSize = 16.sp,
                    color = palette.text)
                Spacer(Modifier.height(8.dp))
                TextButton(stringResource(R.string.omni_retry), actions.retry)
            }
            else -> PluginDetailContent(plugin, state.busy, actions,
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets))
        }
    }
    OmniConfirmDialog(
        show = state.confirmUninstall,
        title = stringResource(R.string.omni_plugin_uninstall_title),
        summary = stringResource(R.string.omni_plugin_uninstall_confirm, plugin?.name.orEmpty()),
        confirmText = stringResource(R.string.omni_plugin_uninstall),
        onConfirm = actions.uninstallConfirmed,
        onDismiss = { actions.showUninstallConfirm(false) },
    )
}

@Composable
private fun PluginDetailContent(
    plugin: PluginDetailItem,
    busy: Boolean,
    actions: PluginDetailActions,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOmniPalette.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            Row {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(15.dp))
                        .background(palette.accent.copy(alpha = .1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    OmniIcon(R.drawable.omni_puzzle, size = 29.dp, tint = palette.accent)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(plugin.name, fontSize = 20.sp, lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold, color = palette.text)
                    Spacer(Modifier.height(4.dp))
                    Text("${plugin.publisher} · v${plugin.version}", fontSize = 12.sp,
                        color = palette.secondaryText)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        stringResource(pluginStatusRes(plugin.compatible, plugin.installed, plugin.enabled)),
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = if (plugin.enabled) palette.accent else palette.tertiaryText,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                plugin.description.ifEmpty { stringResource(R.string.omni_plugin_no_description) },
                fontSize = 14.sp, lineHeight = 23.8.sp, color = palette.secondaryText,
            )
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            Spacer(Modifier.height(26.dp))
        }
        item(key = "capabilities") {
            DetailHeading(stringResource(R.string.omni_plugin_core_capabilities))
            Spacer(Modifier.height(12.dp))
            if (plugin.capabilities.isEmpty()) {
                Text(stringResource(R.string.omni_plugin_no_capabilities), fontSize = 13.sp,
                    color = palette.tertiaryText)
            } else {
                plugin.capabilities.forEach { capability ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        OmniIcon(R.drawable.omni_circle_check, size = 18.dp, tint = palette.accent,
                            modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(capability, fontSize = 14.sp, lineHeight = 20.3.sp,
                            fontWeight = FontWeight.Medium, color = palette.text)
                    }
                }
            }
        }
        if (plugin.usage.isNotEmpty()) {
            item(key = "usage") {
                Spacer(Modifier.height(28.dp))
                DetailHeading(stringResource(R.string.omni_plugin_how_it_works))
                Spacer(Modifier.height(10.dp))
                plugin.usage.forEach { item ->
                    Row(Modifier.padding(vertical = 10.dp)) {
                        OmniIcon(presentationIconRes(item.icon), size = 18.dp, tint = palette.accent,
                            modifier = Modifier.padding(top = 2.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.text)
                            Spacer(Modifier.height(3.dp))
                            Text(item.description, fontSize = 13.sp, lineHeight = 20.15.sp,
                                color = palette.secondaryText)
                        }
                    }
                }
            }
            if (plugin.installed && plugin.enabled && plugin.readyGuide != null) {
                item(key = "ready") {
                    Spacer(Modifier.height(24.dp))
                    DetailHeading(stringResource(R.string.omni_plugin_get_started))
                    Spacer(Modifier.height(12.dp))
                    ReadyGuideCard(plugin, actions)
                }
            }
        }
        item(key = "info") {
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
            PluginInformation(plugin)
            if (!plugin.compatible || plugin.errorMessage != null) {
                Spacer(Modifier.height(18.dp))
                Text(
                    if (!plugin.compatible) stringResource(R.string.omni_plugin_incompatible)
                    else plugin.errorMessage.orEmpty(),
                    fontSize = 12.sp, lineHeight = 18.sp,
                    color = Color(0xFFE05252),
                )
            }
        }
    }
}

@Composable
private fun DetailHeading(label: String) {
    Text(label, fontSize = 18.sp, lineHeight = 24.3.sp, fontWeight = FontWeight.Bold,
        color = LocalOmniPalette.current.text)
}

@Composable
private fun ReadyGuideCard(plugin: PluginDetailItem, actions: PluginDetailActions) {
    val palette = LocalOmniPalette.current
    val guide = plugin.readyGuide ?: return
    val vlm = plugin.vlm
    val providerReady = !plugin.usesVlmReadiness || vlm?.providerConfigured == true
    val providerLabel = (vlm?.providerName.orEmpty().trim().ifEmpty {
        stringResource(R.string.omni_plugin_vlm_default_provider)
    } + if (vlm?.model.orEmpty().isBlank()) "" else " · ${vlm?.model.orEmpty().trim()}")
    val message = when {
        !plugin.usesVlmReadiness -> guide.message
        providerReady && vlm?.debugBuild == true ->
            stringResource(R.string.omni_plugin_vlm_debug_ready, providerLabel)
        providerReady -> stringResource(R.string.omni_plugin_vlm_ready, providerLabel)
        else -> stringResource(R.string.omni_plugin_vlm_configure)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(palette.accent.copy(alpha = .08f))
            .border(1.dp, palette.accent.copy(alpha = .2f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmniIcon(
                if (providerReady) R.drawable.omni_circle_check else R.drawable.omni_info,
                size = 20.dp, tint = palette.accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(guide.title, Modifier.weight(1f), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, color = palette.text)
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 12.5.sp, lineHeight = 18.75.sp, color = palette.secondaryText)
            Spacer(Modifier.height(10.dp))
        }
        guide.steps.forEachIndexed { index, step ->
            Row(Modifier.padding(top = 6.dp)) {
                Text("${index + 1}", Modifier.width(20.dp), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = palette.accent)
                Text(step, fontSize = 12.5.sp, lineHeight = 18.1.sp, color = palette.secondaryText)
            }
        }
        if (guide.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                guide.actions.forEach { action ->
                    val enabled = !action.requiresReadiness || providerReady
                    GuideActionButton(action, enabled,
                        Modifier.weight(1f)) { actions.openAction(action) }
                }
            }
        }
    }
}

@Composable
private fun GuideActionButton(
    action: PluginPresentationAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    Row(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(palette.accent.copy(alpha = if (enabled) .14f else .06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = action.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        OmniIcon(presentationIconRes(action.icon), size = 18.dp,
            tint = palette.accent.copy(alpha = if (enabled) 1f else .4f))
        Spacer(Modifier.width(6.dp))
        Text(action.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = palette.accent.copy(alpha = if (enabled) 1f else .4f), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PluginInformation(plugin: PluginDetailItem) {
    val palette = LocalOmniPalette.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.omni_plugin_information_title), Modifier.weight(1f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            OmniIcon(R.drawable.omni_chevron_right,
                modifier = Modifier.padding(end = 4.dp).rotate(if (expanded) 90f else 0f),
                tint = palette.tertiaryText)
        }
        if (expanded) {
            InfoRow(stringResource(R.string.omni_plugin_publisher_label), plugin.publisher)
            InfoRow(stringResource(R.string.omni_plugin_version_label), plugin.version)
            InfoRow(stringResource(R.string.omni_plugin_type_label),
                stringResource(kindLabelRes(plugin.kind)))
            if (plugin.downloadSizeBytes > 0) {
                InfoRow(stringResource(R.string.omni_plugin_download_size_label),
                    formatPluginSize(plugin.downloadSizeBytes))
            }
            InfoRow(stringResource(R.string.omni_plugin_interface_version_label),
                plugin.interfaceVersion.toString())
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = LocalOmniPalette.current
    Row(Modifier.padding(vertical = 10.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = palette.secondaryText)
        Spacer(Modifier.width(20.dp))
        Text(value, fontSize = 13.sp, color = palette.text,
            textAlign = TextAlign.End)
    }
}

@Composable
private fun PluginBottomBar(plugin: PluginDetailItem, busy: Boolean, actions: PluginDetailActions) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxWidth().background(palette.surface)
            .navigationBarsPadding().imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
    ) {
        if (plugin.installed) {
            if (!plugin.required) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_plugin_enable_title), fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, color = palette.text)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.omni_plugin_enable_description), fontSize = 11.sp,
                            color = palette.tertiaryText)
                    }
                    OmniSwitch(plugin.enabled,
                        if (busy || !plugin.compatible) null else ({ actions.setEnabled(it) }),
                        contentDescription = stringResource(R.string.omni_plugin_enable_title))
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                plugin.installedAction?.let { action ->
                    Button(
                        { actions.openAction(action) },
                        enabled = !busy && plugin.enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        OmniIcon(presentationIconRes(action.icon), size = 18.dp,
                            tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(stringResource(R.string.omni_plugin_update), actions.update,
                    enabled = !busy)
                if (!plugin.required) {
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.omni_plugin_uninstall), fontSize = 13.sp,
                        color = Color(0xFFE05252),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !busy, role = Role.Button) {
                                actions.showUninstallConfirm(true)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
        } else {
            Button(actions.install, enabled = !busy && plugin.compatible,
                modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.omni_plugin_install))
                }
            }
        }
    }
}

internal fun presentationIconRes(icon: String): Int = when (icon) {
    "power" -> R.drawable.omni_power
    "touch" -> R.drawable.omni_hand
    "layers" -> R.drawable.omni_layout_grid
    "chat" -> R.drawable.omni_message_circle
    "route" -> R.drawable.omni_route
    "dashboard" -> R.drawable.omni_layout_grid
    "devices" -> R.drawable.omni_monitor_smartphone
    "battery" -> R.drawable.omni_battery_charging
    "notifications" -> R.drawable.omni_bell
    "send" -> R.drawable.omni_send
    "sync" -> R.drawable.omni_refresh_ccw
    else -> R.drawable.omni_puzzle
}
