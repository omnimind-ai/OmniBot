package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.RadioButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField

/**
 * Alarm ringtone settings. Presentation only; the MMKV record, validation and
 * playback stay with the alarm service behind the host ViewModel.
 */
@Composable
fun AlarmSettingsScreen(
    state: AlarmSettingsState,
    actions: AlarmSettingsActions,
    onPickMp3: () -> Unit,
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
        topBar = { OmniTopBar(stringResource(R.string.omni_alarm_page_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                    .imePadding()
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 24.dp),
            ) {
                SectionTitle(stringResource(R.string.omni_alarm_ringtone_source),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
                SourceRow(AlarmSoundSource.Default, R.string.omni_alarm_source_default,
                    R.string.omni_alarm_source_default_desc, state, actions)
                SourceDivider()
                SourceRow(AlarmSoundSource.LocalMp3, R.string.omni_alarm_source_local,
                    R.string.omni_alarm_source_local_desc, state, actions)
                SourceDivider()
                SourceRow(AlarmSoundSource.RemoteMp3, R.string.omni_alarm_source_remote,
                    R.string.omni_alarm_source_remote_desc, state, actions)
                if (state.source == AlarmSoundSource.LocalMp3) {
                    Spacer(Modifier.height(18.dp))
                    LocalFileSection(state, onPickMp3)
                }
                if (state.source == AlarmSoundSource.RemoteMp3) {
                    Spacer(Modifier.height(18.dp))
                    RemoteUrlSection(state, actions)
                }
                Spacer(Modifier.weight(1f))
                Button(actions.save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (state.saving) R.string.omni_workspace_saving
                        else R.string.omni_agent_save))
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: AlarmSoundSource,
    titleRes: Int,
    subtitleRes: Int,
    state: AlarmSettingsState,
    actions: AlarmSettingsActions,
) {
    val palette = LocalOmniPalette.current
    val title = stringResource(titleRes)
    val selected = state.source == source
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !state.saving, role = Role.Button) { actions.selectSource(source) }
            .padding(vertical = 12.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.radioButtonColors(selectedColor = palette.accent))
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(subtitleRes), fontSize = 12.sp, color = palette.secondaryText)
        }
        if (selected) {
            OmniIcon(R.drawable.omni_check, modifier = Modifier.padding(top = 8.dp),
                size = 16.dp, tint = palette.accent)
        }
    }
}

@Composable
private fun SourceDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp)
        .background(LocalOmniPalette.current.border))
}

@Composable
private fun LocalFileSection(state: AlarmSettingsState, onPickMp3: () -> Unit) {
    val palette = LocalOmniPalette.current
    SectionTitle(stringResource(R.string.omni_alarm_local_file),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    Text(stringResource(R.string.omni_alarm_local_file), fontSize = 14.sp,
        fontWeight = FontWeight.Medium, color = palette.text)
    Spacer(Modifier.height(8.dp))
    Text(
        state.localLabel.ifEmpty { stringResource(R.string.omni_alarm_no_file) },
        fontSize = 12.sp, color = palette.secondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(10.dp))
    val pickLabel = stringResource(R.string.omni_alarm_select_mp3)
    Text(
        pickLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.accent,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
            .clickable(enabled = !state.saving, role = Role.Button, onClick = onPickMp3)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = pickLabel },
    )
}

@Composable
private fun RemoteUrlSection(state: AlarmSettingsState, actions: AlarmSettingsActions) {
    SectionTitle(stringResource(R.string.omni_alarm_remote_url),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp))
    TextField(
        state.remoteUrl, actions.editRemoteUrl, singleLine = true, enabled = !state.saving,
        label = "https://example.com/alarm.mp3", useLabelAsPlaceholder = true,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "https://example.com/alarm.mp3"
        },
    )
}
