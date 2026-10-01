package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import cn.com.omnimind.nativeui.components.OmniPage
import cn.com.omnimind.nativeui.components.OmniDialogActions
import cn.com.omnimind.nativeui.components.OmniConfirmDialog

private data class ProviderPreset(val label: String, val source: String, val protocol: String,
    val wire: String = "chat_completions", val baseUrl: String? = null, val name: String? = null)

private val presets = listOf(
    ProviderPreset("DeepSeek", "deepseek", "deepseek", baseUrl = "https://api.deepseek.com", name = "DeepSeek"),
    ProviderPreset("Mimo", "mimo", "openai_compatible", baseUrl = "https://api.xiaomimimo.com/v1", name = "Mimo"),
    ProviderPreset("Kimi", "moonshot", "openai_compatible", baseUrl = "https://api.moonshot.cn/v1", name = "Kimi"),
    ProviderPreset("MiniMax", "minimax", "openai_compatible", baseUrl = "https://api.minimaxi.com/v1", name = "MiniMax"),
    ProviderPreset("阿里百炼", "bailian", "openai_compatible",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", name = "阿里百炼"),
    ProviderPreset("OpenAI", "custom", "openai_compatible"),
    ProviderPreset("Anthropic", "custom", "anthropic"),
)

@Composable
fun ModelProviderScreen(state: ModelProviderState, actions: ModelProviderActions,
    onFieldBlur: () -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val message = state.notice?.let { stringResource(it) }
    var addProvider by rememberSaveable { mutableStateOf(false) }
    var newProviderName by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var addModel by rememberSaveable { mutableStateOf(false) }
    var newModelId by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf(false) }
    val available = state.loaded && !state.busy && !state.fetching
    val canManageProfiles = state.loaded && !state.busy
    val editable = available && state.current?.readOnly != true
    OmniPage(stringResource(R.string.omni_settings_model_provider_title), { actions.save(); onBack() },
        notice = message, onNoticeShown = actions.dismissNotice) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            if (!state.loaded) item {
                Text(stringResource(if (state.loadFailed) R.string.omni_provider_load_failed else R.string.omni_provider_loading),
                    color = palette.secondaryText, fontSize = 13.sp)
                if (state.loadFailed) TextButton(stringResource(R.string.omni_log_retry), actions.refresh)
            }
            else {
                item {
                    PreferenceSectionHeader(stringResource(R.string.omni_provider_config))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ProviderChoice(state, actions, Modifier.weight(1f))
                        ActionIcon(R.drawable.omni_trash_2, stringResource(R.string.omni_provider_delete),
                            enabled = canManageProfiles && state.current?.readOnly != true && state.profiles.size > 1) { confirmDelete = true }
                        ActionIcon(R.drawable.omni_plus, stringResource(R.string.omni_provider_add),
                            enabled = canManageProfiles) { newProviderName = ""; addProvider = true }
                    }
                    Spacer(Modifier.height(12.dp))
                    ProviderInput(stringResource(R.string.omni_provider_name), state.name, actions.setName,
                        editable, onFieldBlur)
                    Spacer(Modifier.height(12.dp))
                    ProviderInput("Base URL", state.baseUrl, actions.setBaseUrl, editable, onFieldBlur)
                    if (state.requestUrlHint.isNotBlank()) Text(state.requestUrlHint,
                        color = palette.tertiaryText, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp))
                    Text(stringResource(R.string.omni_provider_url_hint), color = palette.tertiaryText,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    Spacer(Modifier.height(12.dp))
                    PresetChoice(state, actions, editable)
                    if (state.sourceType == "custom" && state.protocolType == "openai_compatible") {
                        Spacer(Modifier.height(12.dp))
                        WireApiChoice(state, actions, editable)
                    }
                    Spacer(Modifier.height(12.dp))
                    SecretInput(state.apiKey, actions.setApiKey, editable, onFieldBlur)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.omni_provider_key_hint), color = palette.tertiaryText, fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    HeaderEditor(state, actions, editable, onFieldBlur)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(stringResource(R.string.omni_provider_save), actions.save,
                            enabled = editable && state.dirty)
                    }
                    Spacer(Modifier.height(18.dp))
                    PreferenceSectionHeader(stringResource(R.string.omni_provider_models))
                    ModelToolbar(state, actions, editable,
                        onAdd = { newModelId = ""; addModel = true },
                        onVisibility = { visibility = true })
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "models") {
                    ModelList(state, actions, editable)
                }
            }
        }
        OverlayBottomSheet(show = addProvider, title = stringResource(R.string.omni_provider_add), onDismissRequest = { addProvider = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(newProviderName, { newProviderName = it }, singleLine = true,
                    label = stringResource(R.string.omni_provider_name), modifier = Modifier.fillMaxWidth())
                DialogButtons({ addProvider = false }, enabled = newProviderName.isNotBlank() && canManageProfiles) {
                    actions.addProfile(newProviderName); addProvider = false
                }
            }
        }
        OmniConfirmDialog(
            show = confirmDelete,
            title = stringResource(R.string.omni_provider_delete),
            summary = stringResource(R.string.omni_provider_delete_summary, state.current?.name.orEmpty()),
            confirmText = stringResource(R.string.omni_provider_confirm),
            onConfirm = { actions.deleteProfile(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
            confirmEnabled = canManageProfiles,
        )
        OverlayBottomSheet(show = addModel, title = stringResource(R.string.omni_provider_add_model), onDismissRequest = { addModel = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(newModelId, { newModelId = it }, singleLine = true,
                    label = stringResource(R.string.omni_provider_model_id), modifier = Modifier.fillMaxWidth())
                DialogButtons({ addModel = false }, enabled = newModelId.isNotBlank() && editable) {
                    actions.addModel(newModelId); addModel = false
                }
            }
        }
        OverlayBottomSheet(show = visibility, title = stringResource(R.string.omni_provider_chat_models), onDismissRequest = { visibility = false }) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(stringResource(R.string.omni_provider_hide_all), actions.hideAllRemote,
                        enabled = available && state.models.any { !it.manual })
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.models, key = { it.id }) { model ->
                        val title = model.id
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), color = palette.text, fontSize = 13.sp)
                            Switch(model.visibleInChat, { actions.setModelVisible(model.id, it) },
                                enabled = available, modifier = Modifier.semantics { contentDescription = title })
                        }
                        PreferenceDivider(withIcon = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButtons(cancel: () -> Unit, enabled: Boolean, confirm: () -> Unit) =
    OmniDialogActions(cancel, stringResource(R.string.omni_provider_confirm), confirm, confirmEnabled = enabled)

@Composable
private fun ActionIcon(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(label) { OmniIconButton(icon, label, onClick, size = 18.dp, enabled = enabled) }
}

@Composable
private fun ProviderChoice(state: ModelProviderState, actions: ModelProviderActions, modifier: Modifier) {
    val palette = LocalOmniPalette.current
    var show by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        val profile = state.current
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(enabled = state.profiles.isNotEmpty() && !state.busy, role = Role.Button) { show = true }
            .heightIn(min = 44.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile?.name ?: stringResource(R.string.omni_provider_empty), color = palette.text,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                profile?.status?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = palette.tertiaryText, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            OmniIcon(R.drawable.omni_circle_chevron_down, tint = palette.tertiaryText)
        }
        OverlayListPopup(show = show, onDismissRequest = { show = false },
            minWidth = 220.dp, maxHeight = 400.dp) {
            ListPopupColumn {
                state.profiles.forEachIndexed { index, profile ->
                    DropdownImpl(profile.name, state.profiles.size, profile.id == state.editingId,
                        index, onSelectedIndexChange = { selected ->
                            show = false; actions.selectProfile(state.profiles[selected].id)
                        })
                }
            }
        }
    }
}

@Composable
private fun PresetChoice(state: ModelProviderState, actions: ModelProviderActions, enabled: Boolean) {
    val selected = presets.firstOrNull { it.source == state.sourceType && it.protocol == state.protocolType }
        ?: presets.first { it.source == "custom" && it.protocol == "openai_compatible" }
    SelectorMenu(stringResource(R.string.omni_provider_type), selected.label,
        presets.map { it.label }, enabled) { index ->
        val option = presets[index]
        actions.setSourceType(option.source, option.protocol,
            if (option.source == "custom" && option.protocol == "openai_compatible") state.wireApi else option.wire,
            option.baseUrl, option.name)
    }
}

@Composable
private fun WireApiChoice(state: ModelProviderState, actions: ModelProviderActions, enabled: Boolean) {
    val values = listOf("chat_completions", "responses")
    SelectorMenu(stringResource(R.string.omni_provider_wire_api),
        if (state.wireApi == "responses") "Responses" else "Chat Completions",
        listOf("Chat Completions", "Responses"), enabled) { actions.setWireApi(values[it]) }
}

@Composable
private fun SelectorMenu(label: String, selected: String, options: List<String>, enabled: Boolean,
    choose: (Int) -> Unit) {
    val palette = LocalOmniPalette.current
    var show by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(label, color = palette.secondaryText, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)
                .clickable(enabled = enabled, role = Role.Button) { show = true }
                .heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected, Modifier.weight(1f), color = palette.text, fontSize = 13.sp)
                OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(90f), tint = palette.tertiaryText)
            }
            OverlayListPopup(show = show, minWidth = 200.dp, onDismissRequest = { show = false }) {
                ListPopupColumn {
                    options.forEachIndexed { index, option ->
                        DropdownImpl(option, options.size, option == selected, index,
                            onSelectedIndexChange = { chosen -> show = false; choose(chosen) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderInput(label: String, value: String, onChange: (String) -> Unit,
    enabled: Boolean, onBlur: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val palette = LocalOmniPalette.current
    Column {
        Text(label, color = palette.secondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        TextField(value, onChange, singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (focused && !focus.isFocused) onBlur()
                focused = focus.isFocused
            })
    }
}

@Composable
private fun SecretInput(value: String, onChange: (String) -> Unit, enabled: Boolean, onBlur: () -> Unit) {
    var obscure by rememberSaveable { mutableStateOf(true) }
    var focused by remember { mutableStateOf(false) }
    val palette = LocalOmniPalette.current
    val reveal = stringResource(if (obscure) R.string.omni_provider_show_key else R.string.omni_provider_hide_key)
    Column {
        Text("API Key", color = palette.secondaryText, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        TextField(value, onChange, singleLine = true, enabled = enabled,
            visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                if (focused && !focus.isFocused) onBlur()
                focused = focus.isFocused
            }, trailingIcon = {
                ActionIcon(if (obscure) R.drawable.omni_eye_off else R.drawable.omni_eye,
                    reveal, enabled) { obscure = !obscure }
            })
    }
}

@Composable
private fun HeaderEditor(state: ModelProviderState, actions: ModelProviderActions,
    enabled: Boolean, onBlur: () -> Unit) {
    val palette = LocalOmniPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { expanded = !expanded }
        .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.omni_provider_headers), Modifier.weight(1f), color = palette.text,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(state.headers.count { it.name.isNotBlank() }.toString(), color = palette.tertiaryText, fontSize = 12.sp)
        OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(if (expanded) -90f else 90f),
            tint = palette.tertiaryText)
    }
    if (expanded) {
        state.headers.forEach { header ->
            key(header.id) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        ProviderInput(stringResource(R.string.omni_provider_header_name), header.name,
                            { actions.setHeader(header.id, it, null) }, enabled, onBlur)
                        ProviderInput(stringResource(R.string.omni_provider_header_value), header.value,
                            { actions.setHeader(header.id, null, it) }, enabled, onBlur)
                    }
                    ActionIcon(R.drawable.omni_trash_2, stringResource(R.string.omni_provider_remove_header),
                        enabled) { actions.removeHeader(header.id) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        TextButton(stringResource(R.string.omni_provider_add_header), actions.addHeader, enabled = enabled)
        Text(stringResource(R.string.omni_provider_header_hint), color = palette.tertiaryText, fontSize = 12.sp)
    }
}

@Composable
private fun ModelToolbar(state: ModelProviderState, actions: ModelProviderActions,
    editable: Boolean, onAdd: () -> Unit, onVisibility: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.omni_provider_model_count, state.models.size), Modifier.weight(1f),
            color = palette.secondaryText, fontSize = 12.sp)
        ActionIcon(R.drawable.omni_plus, stringResource(R.string.omni_provider_add_model), editable, onAdd)
        ActionIcon(R.drawable.omni_refresh_cw, stringResource(R.string.omni_provider_fetch),
            state.loaded && !state.fetching && !state.busy, actions.fetchModels)
        ActionIcon(R.drawable.omni_eye_off, stringResource(R.string.omni_provider_chat_models),
            state.loaded && !state.busy, onVisibility)
    }
    if (state.fetching) Text(stringResource(R.string.omni_provider_fetching),
        color = palette.secondaryText, fontSize = 12.sp)
}

@Composable
private fun ModelList(state: ModelProviderState, actions: ModelProviderActions, editable: Boolean) {
    val palette = LocalOmniPalette.current
    var expanded by rememberSaveable(state.editingId) { mutableStateOf(setOf<String>()) }
    val other = stringResource(R.string.omni_provider_group_other)
    val grouped = state.models.groupBy { it.group.ifBlank { other } }
    if (state.modelFetchFailed) {
        Text(stringResource(R.string.omni_provider_models_failed), color = palette.secondaryText, fontSize = 12.sp)
        TextButton(stringResource(R.string.omni_log_retry), actions.fetchModels)
    }
    if (grouped.isEmpty()) {
        Text(stringResource(R.string.omni_provider_no_models), color = palette.secondaryText, fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 20.dp))
    } else {
        Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            grouped.forEach { (group, rows) ->
                val collapsed = group in expanded
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        expanded = if (collapsed) expanded - group else expanded + group
                    }.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group, Modifier.weight(1f), color = palette.secondaryText,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(rows.size.toString(), color = palette.tertiaryText, fontSize = 11.sp)
                    OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(if (collapsed) 90f else -90f),
                        tint = palette.tertiaryText)
                }
                if (!collapsed) rows.forEach { model ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.displayName, color = palette.text, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (model.displayName != model.id) Text(model.id, color = palette.tertiaryText,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val manualLabel = stringResource(R.string.omni_provider_manual)
                            val reasoningLabel = stringResource(R.string.omni_provider_reasoning)
                            val toolsLabel = stringResource(R.string.omni_provider_tools)
                            val attachmentLabel = stringResource(R.string.omni_provider_attachment)
                            val details = buildList {
                                if (model.manual) add(manualLabel)
                                model.contextLimit?.let { add("${it / 1000}k") }
                                if (model.reasoning == true) add(reasoningLabel)
                                if (model.toolCall == true) add(toolsLabel)
                                if (model.attachment == true) add(attachmentLabel)
                                addAll(model.inputModalities)
                            }
                            if (details.isNotEmpty()) Text(details.joinToString(" · "),
                                color = palette.tertiaryText, fontSize = 11.sp)
                        }
                        if (!model.visibleInChat) OmniIcon(R.drawable.omni_eye_off,
                            stringResource(R.string.omni_provider_hidden), size = 16.dp,
                            tint = palette.tertiaryText)
                        ActionIcon(R.drawable.omni_trash_2, stringResource(R.string.omni_provider_remove_model),
                            editable) { actions.removeModel(model.id) }
                    }
                    PreferenceDivider(withIcon = false)
                }
            }
        }
    }
}
