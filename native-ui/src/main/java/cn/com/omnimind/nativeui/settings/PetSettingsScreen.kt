package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Miuix owns rows, buttons, dialogs and back. Discovery and import stay in the shared host owner. */
@Composable
fun PetSettingsScreen(
    state: PetSettingsState,
    actions: PetSettingsActions,
    onPickPackage: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val selected = state.options.firstOrNull { it.id == state.selectedId }?.name
        ?: stringResource(R.string.omni_brand_name)
    Scaffold(containerColor = palette.page,
        topBar = { OmniTopBar(stringResource(R.string.omni_pet_page_title), onBack) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            item(key = "summary") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_pet_page_title), color = palette.text,
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.omni_pet_selected, selected), color = palette.secondaryText,
                            fontSize = 13.sp)
                    }
                    OmniIconButton(R.drawable.omni_refresh_cw, stringResource(R.string.omni_pet_refresh),
                        actions.refresh)
                }
            }
            item(key = "import") {
                BasicComponent(onClick = onPickPackage, enabled = state.loaded && !state.busy,
                    insideMargin = PaddingValues(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
                    startAction = { OmniIcon(R.drawable.omni_archive, size = 22.dp,
                        tint = palette.secondaryText, modifier = Modifier.padding(end = 12.dp)) },
                    endActions = { OmniIcon(R.drawable.omni_chevron_right, tint = palette.tertiaryText) }) {
                    Text(stringResource(R.string.omni_pet_import), color = palette.text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(R.string.omni_pet_import_summary), color = palette.secondaryText,
                        fontSize = 12.sp)
                }
                PreferenceDivider(withIcon = false)
            }
            items(state.options, key = { it.id }) { item ->
                LaunchedEffect(item.id, item.previewPath, state.previewRevision) {
                    if (!item.builtIn) actions.loadPreview(item.id)
                }
                PetOptionRow(item, state.selectedId == item.id, state.previewImages[item.id],
                    state.loaded && !state.busy, { actions.select(item.id) })
                PreferenceDivider(withIcon = false)
            }
        }
        OverlayDialog(show = state.notice != null,
            title = stringResource(when (state.notice) {
                PetNotice.SelectionFailed -> R.string.omni_pet_selection_failed
                PetNotice.ImportFailed -> R.string.omni_pet_import_failed
                else -> R.string.omni_pet_read_failed
            }), backgroundColor = palette.page, onDismissRequest = actions.clearNotice) {
            TextButton(stringResource(R.string.omni_pet_confirm), actions.clearNotice,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PetOptionRow(item: PetAppearanceItem, selected: Boolean,
    preview: androidx.compose.ui.graphics.ImageBitmap?, enabled: Boolean, onSelect: () -> Unit) {
    val palette = LocalOmniPalette.current
    BasicComponent(onClick = if (selected) null else onSelect, enabled = enabled,
        insideMargin = PaddingValues(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        startAction = {
            Box(Modifier.size(58.dp).background(palette.secondarySurface, RoundedCornerShape(8.dp))
                .border(1.dp, palette.border, RoundedCornerShape(8.dp)).padding(6.dp),
                contentAlignment = Alignment.Center) {
                when {
                    item.builtIn -> Image(painterResource(R.drawable.omni_default_pet), null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    preview != null -> Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> OmniIcon(R.drawable.omni_paw_print, tint = palette.secondaryText)
                }
            }
            Spacer(Modifier.width(14.dp))
        },
        endActions = {
            if (selected) Text(stringResource(R.string.omni_pet_selected_button),
                color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            else TextButton(stringResource(R.string.omni_pet_select), onSelect, enabled = enabled)
        }) {
        Text(item.name, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(if (item.builtIn) stringResource(R.string.omni_pet_builtin_desc) else item.description,
            color = palette.secondaryText, fontSize = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (item.animationLabel.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(stringResource(R.string.omni_pet_animation_label),
                color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
