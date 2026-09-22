package cn.com.omnimind.nativeui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniTopBar
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Composable
fun AboutScreen(state: AboutState, actions: AboutActions, onBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val secondary = if (palette.dark) palette.secondaryText else palette.text.copy(alpha = .7f)
    val tertiary = if (palette.dark) palette.tertiaryText else palette.text.copy(alpha = .5f)
    var showSource by rememberSaveable { mutableStateOf(false) }
    val busy = state.operation != null
    Scaffold(containerColor = palette.page, topBar = { OmniTopBar(stringResource(R.string.omni_settings_about_title), onBack) }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            val compact = maxHeight < 760.dp
            Column(Modifier.widthIn(max = 468.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = if (compact) 12.dp else 30.dp, bottom = if (compact) 20.dp else 38.dp)) {
                AboutHero(compact)
                Spacer(Modifier.height(if (compact) 16.dp else 20.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Version ${state.currentVersion.ifBlank { "-" }}", fontSize = if (compact) 11.sp else 12.sp,
                        lineHeight = 18.sp, letterSpacing = .33.sp, color = secondary)
                    if (state.hasUpdate) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.omni_update_available_version, state.latestVersion),
                            fontSize = 11.sp, color = tertiary)
                    }
                    Spacer(Modifier.height(12.dp))
                    AboutPrimaryButton(stringResource(when {
                        state.operation == AboutOperation.Check -> R.string.omni_checking_update
                        state.hasUpdate -> R.string.omni_view_update
                        else -> R.string.omni_check_update
                    }), compact, !busy, actions.primary)
                    Spacer(Modifier.height(12.dp))
                    AboutLinkButton(R.drawable.omni_receipt_text, stringResource(R.string.omni_request_logs), compact, actions.openRequestLogs)
                    Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
                    AboutLinkButton(R.drawable.omni_bug, stringResource(R.string.omni_runtime_logs), compact, actions.openRuntimeLogs)
                    Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
                    AboutLinkButton(R.drawable.omni_book_open, stringResource(R.string.omni_user_guide), compact, actions.openUserGuide)
                }
                if (state.operation == AboutOperation.Install) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator()
                    Text(stringResource(R.string.omni_update_installing), fontSize = 12.sp, color = palette.secondaryText)
                }
                state.notice?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(it.messageResource()), fontSize = 12.sp, lineHeight = 18.sp, color = palette.secondaryText)
                }
                Spacer(Modifier.height(if (compact) 18.dp else 24.dp))
                PreferenceSectionHeader(stringResource(R.string.omni_about_preferences_section_title), compact = compact)
                val betaLabel = stringResource(R.string.omni_about_beta_program_title)
                PreferenceRow(betaLabel, stringResource(R.string.omni_about_beta_program_description),
                    compact = compact, enabled = !busy, summaryColor = secondary, onClick = { actions.setBeta(!state.betaEnabled) }) {
                    Switch(state.betaEnabled, actions.setBeta, enabled = !busy,
                        colors = SwitchDefaults.switchColors(checkedTrackColor = palette.accent, uncheckedTrackColor = palette.strongBorder,
                            checkedThumbColor = Color.White, uncheckedThumbColor = Color.White),
                        modifier = Modifier.semantics { contentDescription = betaLabel })
                }
                PreferenceDivider(withIcon = false)
                PreferenceRow(stringResource(R.string.omni_about_apk_source_title), stringResource(R.string.omni_about_apk_source_description),
                    compact = compact, isLast = true, enabled = !busy, onClick = { showSource = true },
                    summaryColor = secondary, noteColor = tertiary, bottomNote = stringResource(R.string.omni_about_apk_source_disclaimer)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(state.downloadSource.labelResource()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
                        OmniIcon(R.drawable.omni_circle_chevron_down, tint = palette.tertiaryText)
                    }
                }
            }
        }
        OverlayBottomSheet(show = showSource, title = stringResource(R.string.omni_about_apk_source_title),
            backgroundColor = palette.page, onDismissRequest = { showSource = false }) {
            UpdateDownloadSource.entries.forEach { source ->
                PreferenceRow(stringResource(source.labelResource()), stringResource(source.descriptionResource()), enabled = !busy, summaryColor = secondary,
                    onClick = { showSource = false; actions.setDownloadSource(source) }) {
                    RadioButton(selected = source == state.downloadSource, enabled = !busy,
                        onClick = { showSource = false; actions.setDownloadSource(source) })
                }
            }
        }
        UpdateConfirmation(state, actions)
    }
}

@Composable
private fun AboutHero(compact: Boolean) {
    val palette = LocalOmniPalette.current
    val secondary = if (palette.dark) palette.secondaryText else palette.text.copy(alpha = .7f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.omni_about_logo), contentDescription = null,
            modifier = Modifier.size(if (compact) 112.dp else 144.dp, if (compact) 80.dp else 102.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        Text(stringResource(R.string.omni_brand_name), fontSize = if (compact) 26.sp else 32.sp,
            fontWeight = FontWeight.Bold, letterSpacing = if (compact) .2.sp else .4.sp, color = palette.text)
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        Text(stringResource(R.string.omni_about_description), textAlign = TextAlign.Center,
            fontSize = if (compact) 10.5.sp else 12.sp, lineHeight = if (compact) 14.175.sp else 18.sp,
            letterSpacing = if (compact) .16.sp else .22.sp, color = secondary,
            modifier = Modifier.widthIn(max = if (compact) 300.dp else 320.dp))
    }
}

@Composable
private fun AboutPrimaryButton(label: String, compact: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    val colors = remember(palette, enabled) {
        if (!enabled) listOf(Color(0xFFBDBDBD), Color(0xFFE0E0E0))
        else if (!palette.dark) listOf(Color(0xFF1930D9), Color(0xFF2DA5F0))
        else {
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(palette.accent.toArgb(), it) }
            listOf(
                Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1] * .72f, (hsl[2] - .08f).coerceIn(0f, 1f)))),
                Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], hsl[1] * .66f, (hsl[2] + .02f).coerceIn(0f, 1f)))),
            )
        }
    }
    val height = if (compact) 40.dp else 44.dp
    val density = LocalDensity.current
    // Preserve GradientButton's original alignment; Miuix owns the click/press behavior.
    val brush = with(density) { Brush.linearGradient(colors, Offset(180.dp.toPx() * .57f, height.toPx() * -.045f), Offset(180.dp.toPx() * 1.05f, height.toPx() * 1.13f)) }
    val luminance = colors.last().luminance() + .05f
    val foreground = if (palette.dark && luminance * luminance > .15f) Color(0xFF171916) else Color.White
    Button(onClick, modifier = Modifier.width(180.dp).heightIn(min = height).clip(RoundedCornerShape(8.dp)).background(brush),
        enabled = enabled, cornerRadius = 8.dp, minHeight = height,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(color = Color.Transparent, disabledColor = Color.Transparent)) {
        Text(label, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .5.sp, color = foreground)
    }
}

@Composable
private fun AboutLinkButton(icon: Int, label: String, compact: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    val height = if (compact) 40.dp else 44.dp
    Button(onClick, modifier = Modifier.width(180.dp).heightIn(min = height)
        .border(1.dp, if (palette.dark) Color(0xFF2B3444) else Color(0xFFD6E0EE), RoundedCornerShape(14.dp)),
        cornerRadius = 14.dp, minHeight = height,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(color = Color.Transparent)) {
        OmniIcon(icon)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = palette.text)
    }
}

private fun UpdateDownloadSource.labelResource() = if (this == UpdateDownloadSource.GitHub) R.string.omni_about_apk_source_option_github else R.string.omni_about_apk_source_option_cnb
private fun UpdateDownloadSource.descriptionResource() = if (this == UpdateDownloadSource.GitHub) R.string.omni_about_apk_source_option_github_description else R.string.omni_about_apk_source_option_cnb_description

private fun AboutNotice.messageResource() = when (this) {
    AboutNotice.CheckFailed -> R.string.omni_update_check_failed
    AboutNotice.UpToDate -> R.string.omni_update_latest
    AboutNotice.PreferenceFailed -> R.string.omni_update_preference_failed
    AboutNotice.InstallFailed -> R.string.omni_update_install_failed
    AboutNotice.DownloadFailed -> R.string.omni_update_download_failed
    AboutNotice.InstallPermissionRequired -> R.string.omni_update_install_permission_required
    AboutNotice.InstallerOpened -> R.string.omni_update_installer_opened
    AboutNotice.NotificationsDenied -> R.string.omni_update_notifications_denied
    AboutNotice.BrowserUnavailable -> R.string.omni_update_browser_failed
}
