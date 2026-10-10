package cn.com.omnimind.nativeui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/* Building blocks shared by the onboarding pages. */

internal val SUCCESS = Color(0xFF2F8F6B)
internal val ERROR = Color(0xFFE5484D)

/** Scrollable page body with the shared heading. */
@Composable
internal fun PageColumn(
    @DrawableRes icon: Int,
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalOmniPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(palette.accent.copy(alpha = .12f)),
            contentAlignment = Alignment.Center) {
            OmniIcon(icon, size = 22.dp, tint = palette.accent)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.height(8.dp))
        Text(description, fontSize = 14.sp, lineHeight = 21.sp, color = palette.secondaryText)
        Spacer(Modifier.height(22.dp))
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Selectable card: Miuix [Card] with sink press feedback, an accent outline
 * and tint when selected, and a radio or checkbox that only renders.
 */
@Composable
internal fun OptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    description: String? = null,
    detail: String? = null,
    multiple: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = LocalOmniPalette.current
    val haptic = LocalHapticFeedback.current
    val outline by animateColorAsState(if (selected) palette.accent else palette.border, label = "option-outline")
    val fill by animateColorAsState(if (selected) palette.accent.copy(alpha = if (palette.dark) .14f else .07f) else palette.surface,
        label = "option-fill")
    Card(
        modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, outline, RoundedCornerShape(18.dp))
            .semantics { stateDescription = if (selected) "✓" else "" },
        cornerRadius = 18.dp,
        insideMargin = PaddingValues(14.dp),
        colors = CardDefaults.defaultColors(color = fill),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = if (enabled) {
            {
                haptic.performHapticFeedback(if (selected && multiple) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                onClick()
            }
        } else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text,
                        modifier = Modifier.weight(1f, fill = false))
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.accent,
                            modifier = Modifier.clip(CircleShape).background(palette.accent.copy(alpha = .12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (description != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondaryText)
                }
                if (detail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, fontSize = 12.sp, color = palette.tertiaryText)
                }
            }
            Spacer(Modifier.width(10.dp))
            if (multiple) Checkbox(if (selected) ToggleableState.On else ToggleableState.Off, onClick = null, enabled = enabled)
            else RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
internal fun IconTile(@DrawableRes icon: Int, tint: Color, background: Color = LocalOmniPalette.current.secondarySurface) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(background), contentAlignment = Alignment.Center) {
        OmniIcon(icon, size = 22.dp, tint = tint)
    }
}

@Composable
internal fun RoundButton(@DrawableRes icon: Int, description: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    val background by animateColorAsState(
        when {
            !filled -> palette.secondarySurface
            enabled -> palette.accent
            else -> palette.border
        },
        label = "round-button",
    )
    Box(Modifier.size(48.dp).clip(CircleShape).background(background)) {
        OmniIconButton(icon, description, onClick, enabled = enabled,
            tint = if (filled) Color.White else palette.text)
    }
}


@Composable
internal fun PrimaryButton(text: String, @DrawableRes icon: Int, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val palette = LocalOmniPalette.current
    Button(onClick, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = enabled, minHeight = 52.dp,
        colors = ButtonDefaults.buttonColorsPrimary(color = palette.accent)) {
        if (busy) {
            CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = Color.White,
                    backgroundColor = Color.White.copy(alpha = .3f)))
        } else {
            OmniIcon(icon, size = 18.dp, tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text, onClick, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = enabled, minHeight = 52.dp)
}


@Composable
internal fun CrossFadeText(text: String, size: TextUnit, weight: FontWeight, color: Color) {
    AnimatedContent(text, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "cross-fade") { value ->
        Text(value, fontSize = size, lineHeight = size * 1.45f, fontWeight = weight, color = color, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}


@Composable
internal fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalOmniPalette.current.secondaryText,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
}


@Composable
internal fun NoticeText(notice: OnboardingNotice) {
    val palette = LocalOmniPalette.current
    val warning = notice.kind == OnboardingNotice.Kind.NoModels || notice.kind == OnboardingNotice.Kind.FetchFailed ||
        notice.kind == OnboardingNotice.Kind.LoadFailed
    Row(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(14.dp))
        .background((if (warning) palette.accent else ERROR).copy(alpha = .08f)).padding(12.dp)) {
        OmniIcon(if (warning) R.drawable.omni_info else R.drawable.omni_triangle_alert, size = 16.dp,
            tint = if (warning) palette.accent else ERROR)
        Spacer(Modifier.width(8.dp))
        Text(noticeText(notice), fontSize = 13.sp, lineHeight = 19.sp, color = palette.text)
    }
}


@Composable
internal fun SetupSummary(environment: EnvironmentSetup) {
    val palette = LocalOmniPalette.current
    val english = isEnglish()
    val tools = OPTIONAL_TOOLS.filter { it.id in environment.toolIds }.joinToString(" · ") { it.label }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.secondarySurface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryLine(stringResource(R.string.omni_onb_summary_system), environment.distributionName)
        SummaryLine(stringResource(R.string.omni_onb_summary_setup),
            if (english) environment.preset.titleEn else environment.preset.titleZh)
        SummaryLine(stringResource(R.string.omni_onb_summary_packages), environment.preset.contents)
        if (tools.isNotEmpty()) SummaryLine(stringResource(R.string.omni_onb_summary_tools), tools)
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val palette = LocalOmniPalette.current
    Row {
        Text(label, fontSize = 12.sp, color = palette.tertiaryText, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 12.sp, lineHeight = 17.sp, color = palette.text, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun isEnglish(): Boolean = LocalConfiguration.current.locales[0].language != "zh"

@Composable
private fun noticeText(notice: OnboardingNotice): String = when (notice.kind) {
    OnboardingNotice.Kind.LoadFailed -> stringResource(R.string.omni_onb_notice_load_failed)
    OnboardingNotice.Kind.MissingName -> stringResource(R.string.omni_onb_notice_missing_name)
    OnboardingNotice.Kind.InvalidBaseUrl -> stringResource(R.string.omni_onb_notice_invalid_url)
    OnboardingNotice.Kind.MissingApiKey -> stringResource(R.string.omni_onb_notice_missing_key)
    OnboardingNotice.Kind.SaveFailed -> stringResource(R.string.omni_onb_notice_save_failed, notice.detail)
    OnboardingNotice.Kind.NoModels -> stringResource(R.string.omni_onb_notice_no_models)
    OnboardingNotice.Kind.FetchFailed -> stringResource(R.string.omni_onb_notice_fetch_failed)
    OnboardingNotice.Kind.InvalidModelId -> stringResource(R.string.omni_onb_notice_invalid_model)
    OnboardingNotice.Kind.AddModelFailed -> stringResource(R.string.omni_onb_notice_add_failed, notice.detail)
    OnboardingNotice.Kind.MissingScene -> stringResource(R.string.omni_onb_notice_missing_scene)
    OnboardingNotice.Kind.SceneSaveFailed -> stringResource(R.string.omni_onb_notice_scene_failed, notice.detail)
}
