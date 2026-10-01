package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.components.OmniPage

@Composable
fun SceneModelsScreen(state: SceneModelsState, actions: SceneModelsActions,
    onProviders: () -> Unit, onEditAvatar: () -> Unit, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val notice = state.notice?.let { stringResource(it) }
    val enabled = !state.loading && state.savingSceneId == null && !state.voiceBusy
    OmniPage(stringResource(R.string.omni_settings_scene_model_title), onBack, notice = notice, onNoticeShown = actions.dismissNotice) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp)) {
            if (!state.loaded) {
                item {
                    Text(stringResource(if (state.loadFailed) R.string.omni_scene_load_failed else R.string.omni_scene_loading),
                        color = palette.secondaryText, fontSize = 13.sp)
                    if (state.loadFailed) TextButton(stringResource(R.string.omni_log_retry), actions.refresh)
                }
            } else {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.omni_scene_mapping), Modifier.weight(1f),
                            color = palette.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val refreshLabel = stringResource(R.string.omni_scene_refresh)
                        TooltipBox(refreshLabel) {
                            Box(Modifier.size(48.dp).clip(CircleShape)
                                .clickable(enabled = enabled, role = Role.Button, onClick = actions.refresh),
                                contentAlignment = Alignment.Center) {
                                OmniIcon(R.drawable.omni_refresh_cw, refreshLabel, size = 18.dp,
                                    tint = palette.tertiaryText.copy(alpha = if (enabled) 1f else .5f))
                            }
                        }
                    }
                    Text(stringResource(R.string.omni_scene_mapping_summary), color = palette.secondaryText,
                        fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(12.dp))
                    if (state.scenes.isEmpty()) Text(stringResource(R.string.omni_scene_no_scenes),
                        color = palette.secondaryText, fontSize = 12.sp)
                }
                items(state.scenes.size, key = { state.scenes[it].id }) { index ->
                    val scene = state.scenes[index]
                    SceneBindingRow(scene, state, actions, enabled, onProviders, onEditAvatar)
                    if (index < state.scenes.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                        PreferenceDivider(withIcon = false)
                        Spacer(Modifier.height(10.dp))
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    PreferenceSectionHeader(stringResource(R.string.omni_scene_voice_capability))
                    VoiceReplySettings(state, actions, enabled)
                }
            }
        }
    }
}

@Composable
private fun SceneBindingRow(scene: SceneModelRow, state: SceneModelsState, actions: SceneModelsActions,
    enabled: Boolean, onProviders: () -> Unit, onEditAvatar: () -> Unit) {
    val palette = LocalOmniPalette.current
    val title = sceneTitle(scene.id)
    val tooltip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(.4f), verticalAlignment = Alignment.CenterVertically) {
            TooltipBox(text = scene.description.ifBlank { scene.id }, state = tooltip,
                modifier = Modifier.weight(1f)) {
                Row(Modifier.clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                    .heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), color = palette.text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    OmniIcon(R.drawable.omni_info, scene.description, size = 15.dp, tint = palette.tertiaryText)
                }
            }
            if (scene.id == "scene.dispatch.model") {
                val description = stringResource(R.string.omni_scene_edit_avatar)
                TooltipBox(description) {
                    Box(Modifier.size(38.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onEditAvatar)
                        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                        if (state.avatar != null) Image(state.avatar, null, Modifier.size(30.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop)
                        else OmniIcon(R.drawable.omni_bot, size = 26.dp)
                        OmniIcon(R.drawable.omni_pencil, modifier = Modifier.align(Alignment.BottomEnd)
                            .background(palette.surface, CircleShape), size = 11.dp, tint = palette.accent)
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        SceneSelector(scene, state.providers, actions, enabled, state.savingSceneId == scene.id,
            onProviders, Modifier.weight(.6f))
    }
}

@Composable
private fun SceneSelector(scene: SceneModelRow, providers: List<SceneProviderGroup>, actions: SceneModelsActions,
    enabled: Boolean, saving: Boolean, onProviders: () -> Unit, modifier: Modifier) {
    val palette = LocalOmniPalette.current
    var show by rememberSaveable(scene.id) { mutableStateOf(false) }
    val label = if (scene.modelId != null)
        "${scene.providerName ?: stringResource(R.string.omni_scene_provider_missing)} / ${scene.modelId}"
        else if (scene.defaultModel.isBlank()) stringResource(R.string.omni_scene_unbound)
        else stringResource(R.string.omni_scene_default_model, scene.defaultModel)
    BoxWithConstraints(modifier) {
        val width = maxWidth.coerceAtLeast(160.dp)
        ModelIdTooltip(label) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.surface)
                .clickable(enabled = enabled, role = Role.Button) { show = true }
                .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (saving) stringResource(R.string.omni_workspace_saving) else label,
                    Modifier.weight(1f), color = palette.text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(90f), tint = palette.tertiaryText)
            }
        }
        OverlayListPopup(show = show && enabled, popupModifier = Modifier.width(width), minWidth = width,
            maxHeight = 420.dp, alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { show = false }) {
            // Search and group state are local to each opening; business selection is hoisted.
            if (show) key(scene.id) {
                SceneModelPicker(scene, providers,
                    onRestore = { show = false; actions.restoreDefault(scene.id) },
                    onSelect = { providerId, modelId -> show = false; actions.selectModel(scene.id, providerId, modelId) },
                    onProviders = { show = false; onProviders() },
                    onRetry = { show = false; actions.refresh() })
            }
        }
    }
}

@Composable
private fun SceneModelPicker(scene: SceneModelRow, providers: List<SceneProviderGroup>, onRestore: () -> Unit,
    onSelect: (String, String) -> Unit, onProviders: () -> Unit, onRetry: () -> Unit) {
    val palette = LocalOmniPalette.current
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOfNotNull(scene.providerId ?: providers.firstOrNull()?.id)) }
    val modelIds = providers.associate { provider ->
        // An unavailable catalog may still confirm the committed binding.
        val bound = if (provider.id == scene.providerId) listOfNotNull(scene.modelId) else emptyList()
        provider.id to (provider.modelsByCapability[scene.capability].orEmpty() + bound).distinct()
            .filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val searching = query.isNotBlank()
    val visible = providers.filter { !searching || modelIds[it.id].orEmpty().isNotEmpty() }
    Column(Modifier.fillMaxWidth()) {
        TextField(query, { query = it }, singleLine = true,
            label = stringResource(R.string.omni_scene_search), useLabelAsPlaceholder = true,
            cornerRadius = 8.dp, modifier = Modifier.fillMaxWidth().padding(10.dp),
            leadingIcon = { OmniIcon(R.drawable.omni_search, tint = palette.tertiaryText) })
        PickerRow(stringResource(R.string.omni_scene_restore), scene.modelId == null, onRestore)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (providers.isEmpty()) item { PickerSummary(stringResource(R.string.omni_scene_no_providers)) }
            else if (visible.isEmpty()) item { PickerSummary(stringResource(R.string.omni_scene_no_matches)) }
            visible.forEach { provider ->
                val models = modelIds[provider.id].orEmpty()
                val isExpanded = searching || provider.id in expanded
                item(key = "provider:${provider.id}") {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (scene.providerId == provider.id) palette.accent.copy(alpha = .1f) else Color.Transparent)
                        .clickable(role = Role.Button) {
                        if (!searching) expanded = if (provider.id in expanded) expanded - provider.id else expanded + provider.id
                    }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(provider.name, color = palette.secondaryText,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (!provider.configured) Text(stringResource(R.string.omni_scene_not_configured),
                                color = palette.tertiaryText, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(6.dp))
                        if (provider.configured) Text(models.size.toString(), color = palette.tertiaryText, fontSize = 11.sp)
                        if (scene.providerId == provider.id) RadioButton(selected = true, onClick = null,
                            colors = RadioButtonDefaults.radioButtonColors(selectedColor = palette.accent))
                        OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(if (isExpanded) -90f else 90f),
                            size = 16.dp, tint = palette.tertiaryText)
                    }
                    if (isExpanded) {
                        if (scene.capability in provider.loadingCapabilities) PickerSummary(stringResource(R.string.omni_scene_loading))
                        if (scene.capability in provider.failedCapabilities) {
                            PickerSummary(stringResource(R.string.omni_scene_models_failed))
                            TextButton(stringResource(R.string.omni_log_retry), onRetry)
                        }
                        if (!provider.configured) PickerSummary(stringResource(R.string.omni_scene_configure_provider))
                        else if (models.isEmpty() && scene.capability !in provider.loadingCapabilities)
                            PickerSummary(stringResource(R.string.omni_scene_no_models))
                    }
                }
                if (isExpanded && provider.configured) items(models.size, key = { "${provider.id}:${models[it]}" }) { index ->
                    val model = models[index]
                    ModelIdTooltip(model) {
                        PickerRow(model, scene.providerId == provider.id && scene.modelId == model,
                            { onSelect(provider.id, model) })
                    }
                }
            }
            item { TextButton(stringResource(R.string.omni_settings_model_provider_title), onProviders) }
        }
    }
}

@Composable
private fun ModelIdTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        state = rememberTooltipState(), tooltip = {
            PlainTooltip(maxWidth = 320.dp) { Text(text, fontSize = 12.sp) }
        }, content = content)
}

@Composable
private fun PickerRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (selected) palette.accent.copy(alpha = .1f) else Color.Transparent)
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 44.dp)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = palette.text, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) {
            Spacer(Modifier.width(6.dp))
            RadioButton(selected = true, onClick = null, modifier = Modifier.size(26.dp),
                colors = RadioButtonDefaults.radioButtonColors(selectedColor = palette.accent))
        }
    }
}

@Composable
private fun PickerSummary(text: String) {
    Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        color = LocalOmniPalette.current.tertiaryText, fontSize = 12.sp)
}

@Composable
private fun VoiceReplySettings(state: SceneModelsState, actions: SceneModelsActions, enabled: Boolean) {
    val palette = LocalOmniPalette.current
    val voiceLabel = stringResource(R.string.omni_scene_voice_reply)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(voiceLabel, Modifier.weight(1f), color = palette.text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            OmniSwitch(state.autoPlay, actions.setAutoPlay, enabled = enabled && state.voiceAvailable, contentDescription = voiceLabel)
        }
        Text(stringResource(if (state.voiceAvailable) R.string.omni_scene_voice_connected else R.string.omni_scene_voice_unavailable),
            color = palette.secondaryText, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(if (state.voiceAvailable) R.string.omni_scene_voice_summary else R.string.omni_scene_voice_prerequisite),
            color = palette.secondaryText, fontSize = 12.sp, lineHeight = 17.4.sp)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.omni_scene_voice_details, state.voiceId, state.stylePreset),
            color = palette.secondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun sceneTitle(id: String): String = when (id) {
    "scene.dispatch.model" -> "Agent"
    "scene.voice" -> stringResource(R.string.omni_scene_speech)
    "scene.vlm.operation.primary" -> "GUI"
    "scene.compactor.context.chat" -> "Chat Compactor"
    "scene.memory.embedding" -> "Memory Embed"
    "scene.memory.rollup" -> "Memory Rollup"
    else -> id
}
