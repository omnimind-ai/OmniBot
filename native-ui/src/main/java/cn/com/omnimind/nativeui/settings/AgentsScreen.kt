package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.WebProcessStatus
import cn.com.omnimind.nativeui.WebQuickAction
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val StatusGreen = Color(0xFF2EAF67)
private val StatusGray = Color(0xFF98A2B3)
private val StatusRed = Color(0xFFE05252)
private val StatusYellow = Color(0xFFE3A52B)

/**
 * Agent mode list. Presentation only: catalog reads, installs, probes and Web
 * process ownership stay in the app-side ViewModel and the existing runtime.
 */
@Composable
fun AgentsScreen(
    state: AgentsState,
    actions: AgentsActions,
    openLegacy: (LegacyDestination) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.dismissNotice()
        }
    }
    Scaffold(
        containerColor = palette.page,
        topBar = {
            OmniTopBar(stringResource(R.string.omni_agents_title), onBack) {
                if (state.refreshing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                    }
                } else {
                    OmniIconButton(R.drawable.omni_refresh_cw, stringResource(R.string.omni_agents_refresh),
                        actions.refresh)
                }
                OmniIconButton(R.drawable.omni_plus, stringResource(R.string.omni_agents_add_custom),
                    actions.openEditor)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        when {
            !state.loaded && state.loadErrorRes == null -> {
                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.loadErrorRes != null && state.agents.isEmpty() -> {
                Column(
                    Modifier.fillMaxSize().padding(insets).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(state.loadErrorRes), color = palette.text, fontSize = 14.sp,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(actions.retry) { Text(stringResource(R.string.omni_retry)) }
                }
            }
            else -> AgentsList(state, actions, openLegacy,
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets))
        }
        val result = state.actionResult
        OverlayBottomSheet(
            show = result != null,
            title = result?.let { stringResource(it.titleRes) },
            backgroundColor = palette.page,
            onDismissRequest = actions.dismissActionResult,
        ) {
            if (result != null) {
                SelectionContainer {
                    Text(stringResource(result.messageRes), fontSize = 13.sp, lineHeight = 20.sp,
                        color = palette.secondaryText,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                }
            }
        }
        OverlayBottomSheet(
            show = state.editor != null,
            title = stringResource(R.string.omni_agents_add_custom),
            backgroundColor = palette.page,
            onDismissRequest = actions.dismissEditor,
        ) {
            state.editor?.let { draft -> AgentEditor(draft, state.savingEditor, actions) }
        }
    }
}

@Composable
private fun AgentsList(
    state: AgentsState,
    actions: AgentsActions,
    openLegacy: (LegacyDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalOmniPalette.current
    val query = state.query.trim().lowercase()
    val visible = state.agents.filter { agent ->
        (query.isEmpty() || agent.searchText.contains(query)) && when (state.filter) {
            AgentFilter.All -> true
            AgentFilter.Available -> agent.status == "online"
            AgentFilter.Unavailable -> agent.status != "online"
        }
    }
    val managed = visible.filter { it.builtIn }
    val custom = visible.filter { !it.builtIn }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            SectionTitle(stringResource(R.string.omni_agents_managed_section),
                Modifier.padding(start = 4.dp, end = 4.dp))
            Text(stringResource(R.string.omni_agents_managed_summary), fontSize = 12.sp,
                lineHeight = 18.sp, color = palette.secondaryText,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
            SharedModelSummary(state.sharedModelLabel)
            Spacer(Modifier.height(12.dp))
            AgentSearchField(state.query, actions.setQuery)
            Spacer(Modifier.height(12.dp))
            AgentFilterTabs(state, actions.setFilter)
        }
        if (managed.isNotEmpty()) {
            item(key = "managed-label") {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.omni_agents_builtin_section),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
            }
            items(managed.size, key = { "managed:${managed[it].id}" }) { index ->
                AgentRow(managed[index], state, actions, openLegacy)
                if (index < managed.lastIndex) PreferenceDivider()
            }
        }
        if (custom.isNotEmpty()) {
            item(key = "custom-label") {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.omni_agents_custom_section),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
            }
            items(custom.size, key = { "custom:${custom[it].id}" }) { index ->
                AgentRow(custom[index], state, actions, openLegacy)
                if (index < custom.lastIndex) PreferenceDivider()
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    OmniIcon(R.drawable.omni_search, size = 26.dp, tint = palette.tertiaryText)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.omni_agents_empty_title), fontSize = 13.sp,
                        fontWeight = FontWeight.Medium, color = palette.secondaryText)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.omni_agents_empty_hint), fontSize = 12.sp,
                        color = palette.tertiaryText)
                }
            }
        }
        if (state.webActions.isNotEmpty()) {
            item(key = "web-label") {
                Spacer(Modifier.height(24.dp))
                SectionTitle(stringResource(R.string.omni_agents_web_section),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
            }
            items(state.webActions.size, key = { "web:${state.webActions[it].key}" }) { index ->
                WebActionRow(state.webActions[index], state.busyWebActionKey, actions.invokeWebAction)
                if (index < state.webActions.lastIndex) PreferenceDivider()
            }
        }
        item(key = "remote") {
            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.omni_agents_remote_section),
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
            FlatTile(
                leading = { OmniIcon(R.drawable.omni_monitor_smartphone, tint = palette.accent) },
                title = stringResource(R.string.omni_agents_remote_bridge),
                statusColor = if (state.remoteBridgeEnabled) StatusGreen else StatusGray,
                statusLabel = stringResource(if (state.remoteBridgeEnabled)
                    R.string.omni_agents_remote_enabled else R.string.omni_agents_remote_disabled),
                subtitle = stringResource(if (state.remoteBridgeEnabled)
                    R.string.omni_agents_remote_enabled_summary else R.string.omni_agents_remote_disabled_summary),
                onTap = { openLegacy(LegacyDestination.Page.RemoteBridge) },
            )
        }
    }
}

@Composable
private fun SharedModelSummary(label: String) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(R.drawable.omni_bot, tint = palette.accent)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.omni_agents_default_model,
                label.ifEmpty { stringResource(R.string.omni_agents_no_shared_model) }),
            fontSize = 13.sp, lineHeight = 18.2.sp, color = palette.secondaryText,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentSearchField(query: String, onQuery: (String) -> Unit) {
    val palette = LocalOmniPalette.current
    val hint = stringResource(R.string.omni_agents_search_hint)
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        interactionSource = interactionSource,
        textStyle = TextStyle(fontSize = 14.sp, color = palette.text),
        cursorBrush = SolidColor(palette.accent),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(palette.surface)
                    .border(1.dp, if (focused) palette.accent.copy(alpha = .6f) else palette.border,
                        RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OmniIcon(R.drawable.omni_search, tint = palette.tertiaryText)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).padding(vertical = 13.dp)) {
                    if (query.isEmpty()) Text(hint, fontSize = 13.5.sp, color = palette.tertiaryText,
                        maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
private fun AgentFilterTabs(state: AgentsState, onSelect: (AgentFilter) -> Unit) {
    val palette = LocalOmniPalette.current
    val available = state.agents.count { it.status == "online" }
    val labels = listOf(
        stringResource(R.string.omni_agents_filter_all) + " " + state.agents.size,
        stringResource(R.string.omni_agents_filter_available) + " " + available,
        stringResource(R.string.omni_agents_filter_unavailable) + " " + (state.agents.size - available),
    )
    val filters = AgentFilter.entries
    // Miuix owns the indicator, click semantics and horizontal layout.
    TabRowWithContour(labels, filters.indexOf(state.filter), { onSelect(filters[it]) },
        colors = TabRowDefaults.tabRowColors(backgroundColor = palette.segmentTrack,
            contentColor = palette.secondaryText, selectedBackgroundColor = palette.segmentThumb,
            selectedContentColor = palette.accent))
}

@Composable
private fun AgentRow(
    agent: AgentItem,
    state: AgentsState,
    actions: AgentsActions,
    openLegacy: (LegacyDestination) -> Unit,
) {
    val preparing = agent.id in state.preparingAgentIds
    val busy = state.busyAgentId == agent.id || preparing
    val statusColor = when {
        preparing -> StatusYellow
        !agent.enabled -> StatusGray
        else -> when (agent.status) {
            "online" -> StatusGreen
            "missing" -> StatusGray
            "offline" -> StatusRed
            else -> StatusYellow
        }
    }
    val statusLabel = stringResource(when {
        preparing -> R.string.omni_agent_status_preparing
        !agent.enabled -> R.string.omni_agent_status_disabled
        else -> when (agent.status) {
            "online" -> R.string.omni_agent_status_available
            "missing" -> R.string.omni_agent_status_missing
            "offline" -> R.string.omni_agent_status_offline
            else -> R.string.omni_agent_status_unchecked
        }
    })
    val subtitle = agent.subtitleRes?.let { stringResource(it) } ?: agent.subtitleText
    val errorRes = if (preparing) null else agent.errorRes
    val canTest = agent.enabled && (agent.status != "missing" || agent.managedAdapter)
    val actionLabel = when {
        !canTest -> null
        agent.managedAdapter -> stringResource(if (agent.installed == true)
            R.string.omni_agent_reinstall else R.string.omni_agent_install)
        else -> stringResource(R.string.omni_agent_recheck)
    }
    val openConfig = { openLegacy(LegacyDestination.AgentConfig(agent.id)) }
    FlatTile(
        leading = { AgentBrandIcon(agent.id, state.xiaowanAvatar) },
        title = agent.name,
        statusColor = statusColor,
        statusLabel = statusLabel,
        subtitle = subtitle,
        subtitleMonospace = agent.subtitleMonospace,
        errorText = errorRes?.let { stringResource(it) },
        actionLabel = actionLabel,
        onAction = if (canTest && !busy) ({
            if (agent.managedAdapter) actions.prepareAgent(agent.id) else actions.testAgent(agent.id)
        }) else null,
        busy = busy,
        navigationLabel = stringResource(R.string.omni_agent_configure),
        onTap = openConfig,
        tapEnabled = !preparing,
        modifier = Modifier.semantics { contentDescription = agent.name },
    )
}

@Composable
private fun WebActionRow(
    action: WebQuickAction,
    busyKey: String?,
    invoke: (WebQuickAction, Boolean) -> Unit,
) {
    val palette = LocalOmniPalette.current
    val busy = busyKey == action.key
    val disabled = busyKey != null
    val active = action.active
    FlatTile(
        leading = { OmniIcon(R.drawable.omni_globe, tint = palette.accent) },
        title = action.label,
        statusColor = when (action.status) {
            WebProcessStatus.Running -> StatusGreen
            WebProcessStatus.Starting -> StatusYellow
            else -> null
        },
        statusLabel = when (action.status) {
            WebProcessStatus.Running -> stringResource(R.string.omni_web_running)
            WebProcessStatus.Starting -> stringResource(R.string.omni_web_starting)
            else -> null
        },
        subtitle = action.description.ifBlank { null },
        actionLabel = stringResource(if (active) R.string.omni_web_stop else R.string.omni_web_open),
        actionColor = if (active) StatusRed else null,
        onAction = if (disabled) null else ({ invoke(action, active) }),
        busy = busy,
        onTap = { if (!disabled) invoke(action, false) },
        tapEnabled = !disabled,
        modifier = Modifier.semantics { contentDescription = action.label },
    )
}

/** The flat settings row shared by Agent, Web action and remote bridge entries. */
@Composable
private fun FlatTile(
    leading: @Composable () -> Unit,
    title: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    statusLabel: String? = null,
    subtitle: String? = null,
    subtitleMonospace: Boolean = false,
    errorText: String? = null,
    actionLabel: String? = null,
    actionColor: Color? = null,
    onAction: (() -> Unit)? = null,
    busy: Boolean = false,
    navigationLabel: String? = null,
    tapEnabled: Boolean = true,
) {
    val palette = LocalOmniPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(enabled = tapEnabled, role = Role.Button, onClick = onTap)
            .padding(start = 4.dp, top = 13.dp, end = 2.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (statusLabel != null && statusColor != null || !subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusLabel != null && statusColor != null) {
                        Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(statusLabel, fontSize = 11.sp, lineHeight = 17.05.sp,
                            fontWeight = FontWeight.Medium, color = statusColor)
                    }
                    if (!subtitle.isNullOrEmpty()) {
                        if (statusLabel != null && statusColor != null) {
                            Text("  ·  ", fontSize = 11.sp, lineHeight = 17.05.sp,
                                color = palette.tertiaryText)
                        }
                        Text(subtitle, fontSize = 11.sp, lineHeight = 17.05.sp,
                            color = palette.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontFamily = if (subtitleMonospace) FontFamily.Monospace else null,
                            modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            if (!errorText.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(errorText, fontSize = 11.sp, lineHeight = 17.05.sp,
                    color = MiuixTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.padding(start = 10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (busy) {
                Box(Modifier.padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                }
            } else if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = actionColor ?: palette.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                )
            }
            if ((busy || actionLabel != null) && navigationLabel != null) Spacer(Modifier.height(3.dp))
            if (navigationLabel != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = tapEnabled, role = Role.Button, onClick = onTap)
                        .padding(start = 4.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(navigationLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = palette.secondaryText)
                    Spacer(Modifier.width(3.dp))
                    OmniIcon(R.drawable.omni_chevron_right, size = 16.dp, tint = palette.tertiaryText)
                }
            } else if (!busy && actionLabel == null) {
                OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText)
            }
        }
    }
}

@Composable
private fun AgentEditor(draft: AgentEditorDraft, saving: Boolean, actions: AgentsActions) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextField(draft.name, actions.editName, singleLine = true, enabled = !saving,
            label = stringResource(R.string.omni_agent_field_name), modifier = Modifier.fillMaxWidth())
        TextField(draft.command, actions.editCommand, singleLine = true, enabled = !saving,
            label = stringResource(R.string.omni_agent_field_command), modifier = Modifier.fillMaxWidth())
        TextField(draft.arguments, actions.editArguments, minLines = 2, maxLines = 4, enabled = !saving,
            label = stringResource(R.string.omni_agent_field_arguments), modifier = Modifier.fillMaxWidth())
        TextField(draft.environment, actions.editEnvironment, minLines = 3, maxLines = 6, enabled = !saving,
            label = stringResource(R.string.omni_agent_field_environment), modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.omni_agent_field_environment_helper), fontSize = 11.sp,
            color = palette.tertiaryText)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.omni_agent_field_enabled), color = palette.text,
                fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(draft.enabled, actions.editEnabled, enabled = !saving)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(stringResource(R.string.omni_cancel), actions.dismissEditor, enabled = !saving,
                modifier = Modifier.weight(1f))
            Button(actions.saveEditor, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.omni_agent_save))
            }
        }
    }
}

/** Same brand identity mapping as ui/lib/widgets/agent_brand_icon.dart. */
@Composable
private fun AgentBrandIcon(agentId: String, xiaowanAvatar: ImageBitmap?) {
    val palette = LocalOmniPalette.current
    when (normalizeAgentBrandId(agentId)) {
        "xiaowan-acp" -> if (xiaowanAvatar != null) {
            Image(xiaowanAvatar, stringResource(R.string.omni_agent_select),
                Modifier.size(18.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            OmniIcon(R.drawable.omni_bot, tint = palette.accent)
        }
        "kimi-code-acp" -> OmniIcon(R.drawable.omni_brand_moonshot, tint = Color(0xFF1783FF))
        "claude-code-acp" -> OmniIcon(R.drawable.omni_brand_claude, tint = Color(0xFFD97757))
        "codex-acp" -> OmniIcon(R.drawable.omni_brand_codex, tint = palette.text)
        "opencode-acp" -> OmniIcon(R.drawable.omni_brand_opencode, tint = palette.text)
        "deepseek-harness-acp" -> OmniIcon(R.drawable.omni_brand_deepseek, tint = Color(0xFF4D6BFE))
        else -> OmniIcon(R.drawable.omni_bot, tint = palette.accent)
    }
}

private fun normalizeAgentBrandId(agentId: String): String = when (agentId.trim().lowercase()) {
    "xiaowan", "xiaowan-acp" -> "xiaowan-acp"
    "codex", "codex-acp", "codex-remote" -> "codex-acp"
    "kimi", "kimi-code", "kimi-code-acp" -> "kimi-code-acp"
    "claude", "claude-code", "claude-code-acp" -> "claude-code-acp"
    "opencode", "open-code", "opencode-acp" -> "opencode-acp"
    "deepseek", "deepseek-acp", "deepseek-harness", "deepseek_harness", "deepseek-harness-acp" ->
        "deepseek-harness-acp"
    else -> agentId.trim().lowercase()
}
