package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniConfirmDialog
import cn.com.omnimind.nativeui.components.OmniChoiceRow

/**
 * Per-Agent configuration editor. Presentation only; reads, writes, revision
 * checks, binding side effects and teardown stay in the host ViewModel.
 */
@Composable
fun AgentConfigScreen(
    state: AgentConfigState,
    actions: AgentConfigActions,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let { stringResource(it) }
    OmniPage(
        state.agentName.ifEmpty { stringResource(R.string.omni_agent_config_title_fallback) },
        onBack,
        notice = notice,
        onNoticeShown = actions.dismissNotice,
        actions = {
            if (!state.builtIn) {
                OmniIconButton(R.drawable.omni_trash_2, stringResource(R.string.omni_agent_delete_agent),
                    { actions.showDeleteConfirm(true) })
            }
        },
    ) { insets ->
        when {
            !state.loaded && state.loadErrorRes == null -> {
                Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.loadErrorRes != null && !state.loaded -> {
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
            else -> AgentConfigContent(state, actions,
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding())
        }
        OmniConfirmDialog(
            show = state.confirmDelete,
            title = stringResource(R.string.omni_agent_delete_title),
            summary = stringResource(R.string.omni_agent_delete_confirm, state.agentName),
            confirmText = stringResource(R.string.omni_agent_delete),
            onConfirm = actions.deleteAgent,
            onDismiss = { actions.showDeleteConfirm(false) },
        )
    }
}

@Composable
private fun AgentConfigContent(state: AgentConfigState, actions: AgentConfigActions, modifier: Modifier) {
    val palette = LocalOmniPalette.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
    ) {
        item(key = "header") {
            SectionTitle(stringResource(configTitleRes(state.kind)),
                Modifier.padding(start = 4.dp, end = 4.dp))
            Text(configSubtitle(state), fontSize = 12.sp, lineHeight = 18.sp,
                color = palette.secondaryText,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
        }
        item(key = "editor") {
            when (state.kind) {
                "codex" -> CodexEditor(state, actions)
                "json", "jsonc" -> RawFileEditor(state, actions)
                "deepseek-harness" -> DeepSeekHarnessEditor(state, actions)
                "profile" -> ProfileEditor(state, actions)
            }
        }
        if (state.kind.isNotEmpty()) {
            item(key = "save") {
                Spacer(Modifier.height(18.dp))
                Button(actions.save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    if (state.saving) {
                        CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.saving) R.string.omni_workspace_saving
                        else R.string.omni_agent_config_save))
                }
            }
        }
    }
}

@Composable
private fun CodexEditor(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    Column {
        SharedModelSelector(state, actions)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.omni_agent_codex_files, state.configPath, state.authPath),
            fontSize = 12.sp, lineHeight = 18.sp, color = palette.secondaryText)
    }
}

@Composable
private fun RawFileEditor(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    Column {
        SharedModelSelector(state, actions)
        Spacer(Modifier.height(14.dp))
        TextField(
            state.draft.content, actions.editContent,
            minLines = 16, maxLines = 28, enabled = !state.saving,
            label = stringResource(R.string.omni_agent_raw_config_label, state.configPath),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = palette.text,
            ),
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = state.configPath
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.omni_agent_raw_config_hint), fontSize = 12.sp,
            lineHeight = 18.sp, color = palette.tertiaryText)
    }
}

@Composable
private fun DeepSeekHarnessEditor(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    Column {
        SharedModelSelector(state, actions)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.omni_agent_dsh_config_file, state.configPath),
            fontSize = 12.sp, lineHeight = 18.sp, color = palette.secondaryText)
        Spacer(Modifier.height(14.dp))
        DropdownField(
            label = stringResource(R.string.omni_agent_reasoning_effort),
            value = state.draft.reasoningEffort?.replaceFirstChar(Char::uppercase).orEmpty(),
            enabled = !state.saving,
            options = listOf("off" to "Off", "high" to "High", "max" to "Max"),
            selected = state.draft.reasoningEffort,
            onSelect = actions.setReasoningEffort,
        )
        Spacer(Modifier.height(14.dp))
        DropdownField(
            label = stringResource(R.string.omni_agent_permission_mode),
            value = permissionModeLabel(state.draft.permissionMode),
            enabled = !state.saving,
            options = listOf(
                "read-only" to stringResource(R.string.omni_agent_permission_read_only),
                "workspace-write" to stringResource(R.string.omni_agent_permission_workspace_write),
                "danger-full-access" to stringResource(R.string.omni_agent_permission_full_access),
            ),
            selected = state.draft.permissionMode,
            onSelect = actions.setPermissionMode,
        )
    }
}

@Composable
private fun permissionModeLabel(value: String?): String = when (value) {
    "read-only" -> stringResource(R.string.omni_agent_permission_read_only)
    "workspace-write" -> stringResource(R.string.omni_agent_permission_workspace_write)
    "danger-full-access" -> stringResource(R.string.omni_agent_permission_full_access)
    else -> ""
}

@Composable
private fun ProfileEditor(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextField(state.draft.command, actions.editCommand, singleLine = true, enabled = !state.saving,
            label = stringResource(R.string.omni_agent_field_command), modifier = Modifier.fillMaxWidth())
        TextField(state.draft.arguments, actions.editArguments, minLines = 3, maxLines = 6,
            enabled = !state.saving,
            label = stringResource(R.string.omni_agent_field_arguments), modifier = Modifier.fillMaxWidth())
        Column {
            TextField(state.draft.environment, actions.editEnvironment, minLines = 5, maxLines = 10,
                enabled = !state.saving,
                label = stringResource(R.string.omni_agent_config_environment),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("KEY=VALUE", fontSize = 11.sp, color = palette.tertiaryText)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.omni_agent_field_enabled), color = palette.text,
                fontSize = 14.sp, modifier = Modifier.weight(1f))
            OmniSwitch(state.draft.enabled, actions.editEnabled, enabled = !state.saving)
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    enabled: Boolean,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val palette = LocalOmniPalette.current
    var show by remember { mutableStateOf(false) }
    Column {
        Text(label, fontSize = 12.sp, color = palette.tertiaryText)
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)
                .clickable(enabled = enabled, role = Role.Button) { show = true }
                .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 11.dp)
                .semantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, Modifier.weight(1f), color = palette.text, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(90f),
                tint = palette.tertiaryText)
        }
    }
    OverlayDialog(show = show, title = label,
        onDismissRequest = { show = false }) {
        Column {
            options.forEach { (optionValue, optionLabel) ->
                OmniChoiceRow(optionLabel, selected = optionValue == selected) {
                    show = false
                    onSelect(optionValue)
                }
            }
        }
    }
}

@Composable
private fun SharedModelSelector(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    val boundName = state.providers.firstOrNull { it.id == state.boundProviderId }?.name
        ?: state.boundProviderId
    val label = stringResource(R.string.omni_agent_shared_model_label)
    val value = when {
        state.sharedLoading -> stringResource(R.string.omni_agent_shared_model_loading)
        state.boundProviderId == null || state.boundModelId == null ->
            stringResource(R.string.omni_agent_shared_model_empty)
        else -> "$boundName / ${state.boundModelId}"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        Column {
            Text(label, fontSize = 12.sp, color = palette.tertiaryText)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)
                    .clickable(
                        enabled = !state.sharedSaving && !state.sharedLoading && !state.saving,
                        role = Role.Button,
                        onClick = actions.openPicker,
                    )
                    .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 11.dp)
                    .semantics { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, Modifier.weight(1f), color = palette.text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                if (state.sharedSaving) {
                    CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                } else {
                    OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(90f),
                        tint = palette.tertiaryText)
                }
            }
        }
        OverlayListPopup(
            show = state.pickerOpen,
            popupModifier = Modifier.width(width),
            minWidth = width,
            maxHeight = 420.dp,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = actions.closePicker,
        ) {
            if (state.pickerOpen) SharedModelPicker(state, actions)
        }
    }
}

@Composable
private fun SharedModelPicker(state: AgentConfigState, actions: AgentConfigActions) {
    val palette = LocalOmniPalette.current
    var query by remember { mutableStateOf("") }
    var expanded by remember {
        mutableStateOf(setOfNotNull(state.boundProviderId ?: state.providers.firstOrNull()?.id))
    }
    val searching = query.isNotBlank()
    val modelIds = state.providers.associate { provider ->
        val bound = if (provider.id == state.boundProviderId) {
            listOfNotNull(state.boundModelId)
        } else {
            emptyList()
        }
        provider.id to (provider.models + bound).distinct()
            .filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val visible = state.providers.filter { !searching || modelIds[it.id].orEmpty().isNotEmpty() }
    Column(Modifier.fillMaxWidth()) {
        TextField(query, { query = it }, singleLine = true,
            label = stringResource(R.string.omni_scene_search), useLabelAsPlaceholder = true,
            cornerRadius = 8.dp, modifier = Modifier.fillMaxWidth().padding(10.dp),
            leadingIcon = { OmniIcon(R.drawable.omni_search, tint = palette.tertiaryText) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 8.dp)) {
            if (state.providers.isEmpty()) {
                item { PickerNote(stringResource(R.string.omni_scene_no_providers)) }
            } else if (visible.isEmpty()) {
                item { PickerNote(stringResource(R.string.omni_scene_no_matches)) }
            }
            visible.forEach { provider ->
                val models = modelIds[provider.id].orEmpty()
                val isExpanded = searching || provider.id in expanded
                item(key = "provider:${provider.id}") {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (state.boundProviderId == provider.id) {
                                    palette.accent.copy(alpha = .1f)
                                } else {
                                    Color.Transparent
                                },
                            )
                            .clickable(enabled = provider.configured, role = Role.Button) {
                                if (!searching) {
                                    expanded = if (provider.id in expanded) {
                                        expanded - provider.id
                                    } else {
                                        expanded + provider.id
                                    }
                                }
                            }
                            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, color = palette.secondaryText, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            if (!provider.configured) {
                                Text(stringResource(R.string.omni_scene_not_configured),
                                    color = palette.tertiaryText, fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        if (provider.loading) {
                            CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                        } else if (provider.configured) {
                            Text(models.size.toString(), color = palette.tertiaryText, fontSize = 11.sp)
                        }
                        OmniIcon(R.drawable.omni_chevron_right,
                            modifier = Modifier.rotate(if (isExpanded) -90f else 90f),
                            size = 16.dp, tint = palette.tertiaryText)
                    }
                    if (isExpanded && provider.configured) {
                        when {
                            provider.failed -> {
                                PickerNote(stringResource(R.string.omni_scene_models_failed))
                                TextButton(stringResource(R.string.omni_log_retry),
                                    { actions.retryProvider(provider.id) })
                            }
                            models.isEmpty() && !provider.loading ->
                                PickerNote(stringResource(R.string.omni_scene_no_models))
                        }
                    }
                }
                if (isExpanded && provider.configured && !provider.failed) {
                    items(models.size, key = { "${provider.id}:${models[it]}" }) { index ->
                        val model = models[index]
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (state.boundProviderId == provider.id &&
                                        state.boundModelId == model
                                    ) {
                                        palette.accent.copy(alpha = .1f)
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable(role = Role.Button) {
                                    actions.selectSharedModel(provider.id, model)
                                }
                                .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(model, Modifier.weight(1f), color = palette.text, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.boundProviderId == provider.id && state.boundModelId == model) {
                                Spacer(Modifier.width(6.dp))
                                RadioButton(selected = true, onClick = null,
                                    modifier = Modifier.size(26.dp),
                                    colors = RadioButtonDefaults.radioButtonColors(
                                        selectedColor = palette.accent))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerNote(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        color = LocalOmniPalette.current.tertiaryText, fontSize = 12.sp)
}

private fun configTitleRes(kind: String): Int = when (kind) {
    "codex" -> R.string.omni_agent_config_title_codex
    "json" -> R.string.omni_agent_config_title_json
    "jsonc" -> R.string.omni_agent_config_title_jsonc
    "deepseek-harness" -> R.string.omni_agent_config_title_dsh
    "profile" -> R.string.omni_agent_config_title_profile
    else -> R.string.omni_agent_config_title_fallback
}

@Composable
private fun configSubtitle(state: AgentConfigState): String = when (state.kind) {
    "codex" -> stringResource(R.string.omni_agent_config_subtitle_codex)
    "json" -> stringResource(R.string.omni_agent_config_subtitle_json, state.configPath)
    "jsonc" -> stringResource(R.string.omni_agent_config_subtitle_jsonc, state.configPath)
    "deepseek-harness" -> stringResource(R.string.omni_agent_config_subtitle_dsh)
    "profile" -> stringResource(R.string.omni_agent_config_subtitle_profile)
    else -> ""
}
