package cn.com.omnimind.nativeui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.util.Locale

private data class ColorPreset(val label: Int, val hex: String)
private val colorPresets = listOf(
    ColorPreset(R.string.omni_background_color_white, "#FFFFFF"),
    ColorPreset(R.string.omni_background_color_dark_gray, "#353E53"),
    ColorPreset(R.string.omni_background_color_light_blue, "#DCEBFF"),
    ColorPreset(R.string.omni_background_color_navy, "#1D3E7B"),
    ColorPreset(R.string.omni_background_color_teal, "#2F7A4A"),
    ColorPreset(R.string.omni_background_color_warm_yellow, "#F59E0B"),
)
private val hexPattern = Regex("^#(?:[A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")

@Composable
fun BackgroundSettingsScreen(
    state: BackgroundSettingsState,
    actions: BackgroundSettingsActions,
    onPickImage: () -> Unit,
    onPet: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val config = state.config
    var previewKind by rememberSaveable { mutableStateOf(BackgroundPreviewKind.Chat) }
    var colorPickerOpen by rememberSaveable { mutableStateOf(false) }
    var hexDraft by rememberSaveable { mutableStateOf(config.chatTextHexColor) }
    LaunchedEffect(config.chatTextHexColor) {
        if (hexDraft != config.chatTextHexColor) hexDraft = config.chatTextHexColor
    }
    val enabled = state.loaded && !state.importing
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_background_page_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "save-status") {
                Text(stringResource(if (state.saving || state.importing) R.string.omni_background_auto_saving
                    else R.string.omni_background_autosave_hint),
                    modifier = Modifier.padding(start = 4.dp), color = palette.secondaryText,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            item(key = "source") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_background_background_source))
                    val title = stringResource(R.string.omni_background_enable_background)
                    PreferenceRow(title, stringResource(R.string.omni_background_enable_background_subtitle),
                        icon = R.drawable.omni_image, isLast = true,
                        enabled = enabled, onClick = { actions.setEnabled(!config.enabled) }) {
                        Switch(config.enabled, actions.setEnabled, enabled = enabled,
                            colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent,
                                uncheckedTrackColor = palette.strongBorder,
                                checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                            modifier = Modifier.semantics { contentDescription = title })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceButton(stringResource(R.string.omni_background_source_local),
                            config.sourceType == BackgroundSource.Local, enabled,
                            { actions.setSource(BackgroundSource.Local) })
                        SourceButton(stringResource(R.string.omni_background_source_remote),
                            config.sourceType == BackgroundSource.Remote, enabled,
                            { actions.setSource(BackgroundSource.Remote) })
                    }
                    if (config.sourceType == BackgroundSource.Local) {
                        Spacer(Modifier.height(12.dp))
                        Text(config.localImagePath.ifBlank { stringResource(R.string.omni_background_no_local_image) },
                            color = palette.secondaryText, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        TextButton(stringResource(if (config.localImagePath.isBlank())
                            R.string.omni_background_pick_image else R.string.omni_background_repick_image),
                            onPickImage, enabled = !state.saving)
                        if (config.localImagePath.isBlank()) Text(stringResource(R.string.omni_background_pick_local_image_first),
                            color = palette.secondaryText, fontSize = 11.sp)
                    }
                    if (config.sourceType == BackgroundSource.Remote) {
                        Spacer(Modifier.height(12.dp))
                        TextField(config.remoteImageUrl, actions.setRemoteUrl,
                            label = stringResource(R.string.omni_background_remote_image_url),
                            useLabelAsPlaceholder = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                        if (config.remoteImageUrl.isBlank()) Text(
                            stringResource(R.string.omni_background_remote_image_url_hint),
                            color = palette.tertiaryText, fontSize = 11.sp)
                        if ((config.remoteImageUrl.isNotEmpty() && !validRemote(config.remoteImageUrl)) ||
                            (config.enabled && config.remoteImageUrl.isEmpty())) {
                            Text(stringResource(R.string.omni_background_invalid_http_url),
                                color = Color(0xFFFF6464), fontSize = 11.sp)
                        }
                    }
                }
            }
            item(key = "preview") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_background_preview))
                    TabRowWithContour(
                        tabs = listOf(stringResource(R.string.omni_background_preview_chat),
                            stringResource(R.string.omni_background_preview_workspace)),
                        selectedTabIndex = previewKind.ordinal, onTabSelected = {
                            previewKind = BackgroundPreviewKind.entries[it]
                        })
                    Spacer(Modifier.height(12.dp))
                    BackgroundPreview(state, previewKind, actions.setViewport)
                }
            }
            item(key = "adjustments") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_background_adjustments))
                    AdjustmentSlider(R.string.omni_background_background_blur,
                        R.string.omni_background_background_blur_subtitle,
                        config.blurSigma, 0f..24f, actions.setBlur, actions.flush, enabled)
                    AdjustmentSlider(R.string.omni_background_overlay_intensity,
                        R.string.omni_background_overlay_intensity_subtitle,
                        config.frostOpacity, 0f...55f, actions.setFrost, actions.flush, enabled)
                    AdjustmentSlider(R.string.omni_background_overlay_brightness,
                        R.string.omni_background_overlay_brightness_subtitle,
                        config.brightness, .5f..1.5f, actions.setBrightness, actions.flush, enabled)
                    AdjustmentSlider(R.string.omni_background_chat_text_size,
                        R.string.omni_background_chat_text_size_subtitle,
                        config.chatTextSize, 12f..22f, actions.setChatTextSize, actions.flush, enabled,
                        valueLabel = String.format(Locale.ROOT, "%.1fsp", config.chatTextSize))
                    TextColorSetting(config, actions, hexDraft, enabled, { next ->
                        hexDraft = next
                        if (hexPattern.matches(next.trim()))
                            actions.setTextColor(BackgroundTextColorMode.Custom, next.trim().uppercase(Locale.ROOT))
                    }, onMoreColors = { colorPickerOpen = true })
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.omni_background_preview_tip),
                        color = palette.secondaryText, fontSize = 12.sp)
                }
            }
            item(key = "pet-compatibility") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_background_pet_entry))
                    PreferenceRow(stringResource(R.string.omni_background_pet_entry),
                        stringResource(R.string.omni_background_pet_summary),
                        icon = R.drawable.omni_paw_print, isLast = true, onClick = onPet) {
                        OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText)
                    }
                }
            }
        }
        OverlayBottomSheet(show = colorPickerOpen, title = stringResource(R.string.omni_background_more_colors),
            backgroundColor = palette.page, onDismissRequest = { colorPickerOpen = false }) {
            Column {
                val current = runCatching { Color(AndroidColor.parseColor(config.chatTextHexColor)) }
                    .getOrDefault(Color.White)
                ColorPicker(color = current, onColorChanged = { color ->
                    // Preserve RGB as a stable shared preference string.
                    val red = (color.red * 255).toInt().coerceIn(0, 255)
                    val green = (color.green * 255).toInt().coerceIn(0, 255)
                    val blue = (color.blue * 255).toInt().coerceIn(0, 255)
                    val hex = String.format(Locale.ROOT, "#%02X%02X%02X", red, green, blue)
                    hexDraft = hex
                    actions.setTextColor(BackgroundTextColorMode.Custom, hex)
                }, modifier = Modifier.fillMaxWidth().height(240.dp))
                TextButton(stringResource(R.string.omni_background_confirm),
                    { colorPickerOpen = false }, modifier = Modifier.fillMaxWidth())
            }
        }
        OverlayDialog(show = state.notice != null,
            title = stringResource(when (state.notice) {
                BackgroundNotice.ImportFailed -> R.string.omni_background_import_failed
                BackgroundNotice.ChangedElsewhere -> R.string.omni_background_changed_elsewhere
                else -> R.string.omni_background_save_failed
            }), backgroundColor = palette.page, onDismissRequest = actions.clearNotice) {
            TextButton(stringResource(R.string.omni_background_confirm), actions.clearNotice,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SourceButton(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    TextButton(title, onClick, enabled = enabled, cornerRadius = 99.dp, minHeight = 34.dp,
        colors = ButtonDefaults.textButtonColors(
            color = if (selected) palette.accent.copy(alpha = .14f) else palette.secondarySurface,
            textColor = if (selected) palette.accent else palette.text),
        modifier = Modifier.semantics { this.selected = selected; contentDescription = title })
}

@Composable
private fun AdjustmentSlider(label: Int, summary: Int, value: Float,
    range: ClosedFloatingPointRange<Float>, onChanged: (Float) -> Unit, onFinished: () -> Unit,
    enabled: Boolean,
    valueLabel: String = String.format(Locale.ROOT, "%.2f", value)) {
    val palette = LocalOmniPalette.current
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), color = palette.text, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(valueLabel, color = palette.secondaryText, fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(stringResource(summary), color = palette.secondaryText, fontSize = 12.sp)
        Slider(value, onChanged, enabled = enabled, valueRange = range, onValueChangeFinished = onFinished,
            modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun TextColorSetting(config: BackgroundConfig, actions: BackgroundSettingsActions,
    draft: String, enabled: Boolean, onDraftChanged: (String) -> Unit, onMoreColors: () -> Unit) {
    val palette = LocalOmniPalette.current
    Text(stringResource(R.string.omni_background_text_color_title), color = palette.text,
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(2.dp))
    Text(stringResource(R.string.omni_background_text_color_subtitle),
        color = palette.secondaryText, fontSize = 12.sp)
    Spacer(Modifier.height(10.dp))
    SourceButton(stringResource(R.string.omni_background_text_color_auto),
        config.chatTextColorMode == BackgroundTextColorMode.Auto, enabled,
        { actions.setTextColor(BackgroundTextColorMode.Auto, "") })
    Spacer(Modifier.height(10.dp))
    FlowRow {
        colorPresets.forEach { preset ->
            val color = Color(AndroidColor.parseColor(preset.hex))
            val selected = config.chatTextColorMode == BackgroundTextColorMode.Custom &&
                config.chatTextHexColor.equals(preset.hex, ignoreCase = true)
            val label = stringResource(preset.label)
            Box(Modifier.size(48.dp)
                .clickable(enabled = enabled) { actions.setTextColor(BackgroundTextColorMode.Custom, preset.hex) }
                .semantics { this.selected = selected; contentDescription = label },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).background(color, CircleShape)
                    .border(if (selected) 3.dp else 1.5.dp,
                        if (selected) palette.accent else palette.strongBorder, CircleShape))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    TextField(draft, onDraftChanged, label = stringResource(R.string.omni_background_custom_color_label),
        useLabelAsPlaceholder = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    if (config.chatTextColorMode == BackgroundTextColorMode.Custom && !hexPattern.matches(draft.trim())) {
        Text(stringResource(if (draft.isBlank()) R.string.omni_background_invalid_hex_color
            else R.string.omni_background_invalid_hex_color_format),
            color = Color(0xFFFF6464), fontSize = 11.sp)
    }
    Spacer(Modifier.height(8.dp))
    TextButton(stringResource(R.string.omni_background_more_colors), onMoreColors, enabled = enabled)
}

private fun validRemote(value: String): Boolean = runCatching {
    val uri = android.net.Uri.parse(value.trim())
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)
