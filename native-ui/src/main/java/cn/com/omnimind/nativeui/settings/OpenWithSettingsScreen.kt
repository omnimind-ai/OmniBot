package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Shared-open (Open with Omnibot) preferences. Presentation only; the
 * preference store stays the owner behind the host ViewModel.
 */
@Composable
fun OpenWithSettingsScreen(
    state: OpenWithSettingsState,
    actions: OpenWithSettingsActions,
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
        topBar = { OmniTopBar(stringResource(R.string.omni_open_with_page_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 28.dp),
        ) {
            item(key = "open-with") {
                Column {
                    PreferenceSectionHeader(stringResource(R.string.omni_open_with_page_title))
                    ModeRow(
                        icon = R.drawable.omni_image,
                        title = stringResource(R.string.omni_open_with_image),
                        subtitle = stringResource(modeSubtitleRes("image",
                            state.imageMode)),
                        target = "image",
                        mode = state.imageMode,
                        loaded = state.loaded,
                        actions = actions,
                    )
                    PreferenceDivider()
                    ModeRow(
                        icon = R.drawable.omni_file,
                        title = stringResource(R.string.omni_open_with_file),
                        subtitle = stringResource(modeSubtitleRes("file", state.fileMode)),
                        target = "file",
                        mode = state.fileMode,
                        loaded = state.loaded,
                        actions = actions,
                    )
                }
            }
        }
    }
}

private fun modeSubtitleRes(target: String, mode: String): Int = when {
    mode == "workspace" -> R.string.omni_open_with_subtitle_workspace
    target == "image" -> R.string.omni_open_with_subtitle_default_image
    else -> R.string.omni_open_with_subtitle_default_file
}

@Composable
private fun ModeRow(
    icon: Int,
    title: String,
    subtitle: String,
    target: String,
    mode: String,
    loaded: Boolean,
    actions: OpenWithSettingsActions,
) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, end = 2.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OmniIcon(icon, tint = palette.text)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.sp, lineHeight = 17.05.sp, color = palette.secondaryText)
        }
        if (!loaded) {
            Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            }
        } else {
            ModeDropdown(title, target, mode, actions)
        }
    }
}

@Composable
private fun ModeDropdown(title: String, target: String, mode: String, actions: OpenWithSettingsActions) {
    val palette = LocalOmniPalette.current
    var show by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(if (target == "image")
        R.string.omni_open_with_default_image else R.string.omni_open_with_default_file)
    val workspaceLabel = stringResource(R.string.omni_open_with_workspace)
    Row(
        Modifier.padding(start = 12.dp).widthIn(max = 132.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.DropdownList) { show = true }
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (mode == "workspace") workspaceLabel else defaultLabel,
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        OmniIcon(R.drawable.omni_chevron_right, modifier = Modifier.rotate(90f),
            size = 18.dp, tint = palette.tertiaryText)
    }
    OverlayDialog(show = show, title = title, backgroundColor = palette.page,
        onDismissRequest = { show = false }) {
        Column {
            ModeChoiceRow(defaultLabel, selected = mode != "workspace") {
                show = false
                actions.setMode(target, "default")
            }
            ModeChoiceRow(workspaceLabel, selected = mode == "workspace") {
                show = false
                actions.setMode(target, "workspace")
            }
        }
    }
}

@Composable
private fun ModeChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = palette.text, fontSize = 14.sp)
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.radioButtonColors(selectedColor = palette.accent))
    }
}
