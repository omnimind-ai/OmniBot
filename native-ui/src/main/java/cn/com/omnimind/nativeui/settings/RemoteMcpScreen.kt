package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun RemoteMcpScreen(state: RemoteMcpSettingsState, actions: RemoteMcpSettingsActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(it) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.dismissNotice()
        }
    }
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_settings_mcp_tools_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PreferenceSectionHeader(stringResource(R.string.omni_mcp_remote_services))
                    }
                    Spacer(Modifier.weight(1f))
                    McpIcon(R.drawable.omni_refresh_cw, stringResource(R.string.omni_mcp_refresh_list),
                        state.loaded && !state.loading && state.busyIds.isEmpty(), actions.refresh)
                    McpIcon(R.drawable.omni_plus, stringResource(R.string.omni_mcp_add),
                        state.loaded && !state.loading && state.busyIds.isEmpty(), { actions.openEditor(null) })
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!state.loaded) item {
                Text(stringResource(if (state.loadFailed) R.string.omni_mcp_load_failed else R.string.omni_mcp_loading),
                    color = palette.secondaryText, fontSize = 13.sp)
                if (state.loadFailed) TextButton(stringResource(R.string.omni_log_retry), actions.refresh)
            } else if (state.servers.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    OmniIcon(R.drawable.omni_puzzle, size = 44.dp, tint = palette.tertiaryText)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.omni_mcp_empty), color = palette.secondaryText, fontSize = 16.sp)
                }
            } else items(state.servers, key = { it.id }) { server ->
                RemoteMcpServerRow(server, server.id in state.busyIds || state.loading ||
                    state.savingEditor, actions)
                PreferenceDivider(withIcon = false)
            }
        }
        val draft = state.editor
        OverlayBottomSheet(show = draft != null,
            title = stringResource(if (draft?.id == null) R.string.omni_mcp_add else R.string.omni_mcp_edit),
            backgroundColor = palette.page,
            onDismissRequest = actions.dismissEditor) {
            if (draft != null) key(draft.id) {
                RemoteMcpEditor(draft, state.savingEditor, actions)
            }
        }
        val deleting = state.servers.firstOrNull { it.id == state.deletingId }
        OverlayDialog(show = deleting != null, title = stringResource(R.string.omni_mcp_delete),
            summary = stringResource(R.string.omni_mcp_delete_confirm, deleting?.name.orEmpty()),
            backgroundColor = palette.page,
            onDismissRequest = { actions.confirmDelete(null) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(stringResource(R.string.omni_cancel), { actions.confirmDelete(null) },
                    modifier = Modifier.weight(1f))
                TextButton(stringResource(R.string.omni_mcp_delete), actions.deleteConfirmed,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RemoteMcpServerRow(server: RemoteMcpServerItem, busy: Boolean, actions: RemoteMcpSettingsActions) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(server.endpointUrl, color = palette.secondaryText, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Switch(server.enabled, { actions.toggle(server.id, it) }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = server.name })
        }
        Spacer(Modifier.height(8.dp))
        val health = when (server.health) {
            "healthy" -> R.string.omni_mcp_connected
            "error" -> R.string.omni_mcp_connection_error
            else -> R.string.omni_mcp_unknown
        }
        Text(stringResource(health) + "  ·  " + stringResource(R.string.omni_mcp_tool_count, server.toolCount),
            color = palette.secondaryText, fontSize = 12.sp)
        if (!server.lastError.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(server.lastError, color = MiuixTheme.colorScheme.error, fontSize = 12.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            McpIcon(R.drawable.omni_refresh_cw, stringResource(R.string.omni_mcp_refresh_tools), !busy) {
                actions.refreshTools(server.id)
            }
            McpIcon(R.drawable.omni_pencil, stringResource(R.string.omni_mcp_edit), !busy) {
                actions.openEditor(server.id)
            }
            Spacer(Modifier.weight(1f))
            McpIcon(R.drawable.omni_trash_2, stringResource(R.string.omni_mcp_delete), !busy) {
                actions.confirmDelete(server.id)
            }
        }
    }
}

@Composable
private fun RemoteMcpEditor(draft: RemoteMcpEditorDraft, saving: Boolean, actions: RemoteMcpSettingsActions) {
    val palette = LocalOmniPalette.current
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(draft.name, actions.editName, singleLine = true, enabled = !saving,
            label = stringResource(R.string.omni_mcp_name), modifier = Modifier.fillMaxWidth())
        TextField(draft.endpointUrl, actions.editEndpoint, singleLine = true, enabled = !saving,
            label = stringResource(R.string.omni_mcp_endpoint), modifier = Modifier.fillMaxWidth())
        TextField(draft.bearerToken, actions.editToken, singleLine = true, enabled = !saving,
            label = stringResource(R.string.omni_mcp_bearer_token),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                McpIcon(if (reveal) R.drawable.omni_eye else R.drawable.omni_eye_off,
                    stringResource(if (reveal) R.string.omni_mcp_hide_token else R.string.omni_mcp_show_token),
                    !saving) { reveal = !reveal }
            }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.omni_mcp_enabled), color = palette.text, fontSize = 14.sp,
                modifier = Modifier.weight(1f))
            Switch(draft.enabled, actions.editEnabled, enabled = !saving)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(stringResource(R.string.omni_cancel), actions.dismissEditor, enabled = !saving,
                modifier = Modifier.weight(1f))
            Button(actions.saveEditor, enabled = !saving, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (saving) R.string.omni_workspace_saving else R.string.omni_provider_save))
            }
        }
    }
}

@Composable
private fun McpIcon(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    TooltipBox(label) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button,
            onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            OmniIcon(icon, size = 18.dp, tint = palette.text.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}
