package cn.com.omnimind.nativeui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/* System, development setup, tools and the install progress (batch 5f-1b). */

@Composable
internal fun SystemPage(environment: EnvironmentSetup, actions: OnboardingActions) {
    PageColumn(R.drawable.omni_box, stringResource(R.string.omni_onb_system_title), stringResource(R.string.omni_onb_system_desc)) {
        OptionCard(environment.distribution == "alpine", { actions.selectDistribution("alpine") },
            { IconTile(R.drawable.omni_distro_alpine, Color.Unspecified) }, "Alpine",
            badge = stringResource(R.string.omni_onb_alpine_badge),
            description = stringResource(R.string.omni_onb_alpine_desc),
            detail = stringResource(R.string.omni_onb_alpine_detail), enabled = !environment.busy)
        OptionCard(environment.distribution == "ubuntu", { actions.selectDistribution("ubuntu") },
            { IconTile(R.drawable.omni_distro_ubuntu, Color.Unspecified) }, "Ubuntu",
            badge = stringResource(R.string.omni_onb_ubuntu_badge),
            description = stringResource(R.string.omni_onb_ubuntu_desc),
            detail = "Ubuntu Base 24.04 · apt", enabled = !environment.busy)
    }
}

private val EnvironmentPreset.icon: Int
    get() = when (id) {
        "node" -> R.drawable.omni_globe
        "python" -> R.drawable.omni_square_terminal
        else -> R.drawable.omni_message_circle
    }

@Composable
internal fun DevelopmentPage(environment: EnvironmentSetup, actions: OnboardingActions) {
    val english = isEnglish()
    PageColumn(R.drawable.omni_hammer, stringResource(R.string.omni_onb_dev_title), stringResource(R.string.omni_onb_dev_desc)) {
        ENVIRONMENT_PRESETS.forEach { preset ->
            val selected = environment.presetId == preset.id
            val palette = LocalOmniPalette.current
            OptionCard(selected, { actions.selectPreset(preset.id) },
                { IconTile(preset.icon, palette.accent, palette.accent.copy(alpha = .1f)) },
                if (english) preset.titleEn else preset.titleZh,
                description = if (english) preset.descriptionEn else preset.descriptionZh,
                detail = preset.contents, enabled = !environment.busy)
        }
    }
}

private val OptionalTool.icon: Int
    get() = when (id) {
        "codex" -> R.drawable.omni_brand_codex
        "claude_code" -> R.drawable.omni_brand_claude
        "opencode" -> R.drawable.omni_brand_opencode
        else -> R.drawable.omni_network
    }

@Composable
internal fun ToolsPage(environment: EnvironmentSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    val english = isEnglish()
    PageColumn(R.drawable.omni_blocks, stringResource(R.string.omni_onb_tools_title), stringResource(R.string.omni_onb_tools_desc)) {
        OPTIONAL_TOOLS.forEach { tool ->
            OptionCard(tool.id in environment.toolIds, { actions.toggleTool(tool.id) },
                { IconTile(tool.icon, if (tool.id == "claude_code") Color(0xFFD97757) else palette.text) },
                tool.label, description = if (english) tool.descriptionEn else tool.descriptionZh,
                multiple = true, enabled = !environment.busy)
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle(stringResource(R.string.omni_onb_tools_summary))
        Spacer(Modifier.height(10.dp))
        SetupSummary(environment)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.omni_onb_tools_note), fontSize = 12.sp, lineHeight = 18.sp, color = palette.tertiaryText)
    }
}



@Composable
internal fun EnvironmentProgressPage(environment: EnvironmentSetup, actions: OnboardingActions, goBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val accent by animateColorAsState(when {
        environment.cancelled -> palette.tertiaryText
        environment.failed -> ERROR
        environment.ready -> SUCCESS
        else -> palette.accent
    }, label = "environment-accent")
    // The ViewModel ticks every 350 ms; tweening across a tick keeps the ring moving continuously.
    val progress by animateFloatAsState(environment.progress.coerceIn(0f, 1f), tween(380), label = "environment-progress")
    val percent = "${(progress * 100).toInt()}%"
    val progressLabel = stringResource(R.string.omni_onb_env_progress)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
        .padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Box(Modifier.size(176.dp).semantics { contentDescription = "$progressLabel $percent" }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = progress, size = 176.dp, strokeWidth = 11.dp,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = accent, backgroundColor = palette.border))
            AnimatedContent(environment.ready, label = "environment-center") { ready ->
                if (ready) OmniIcon(R.drawable.omni_check, size = 48.dp, tint = SUCCESS)
                else Text(percent, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = palette.text,
                    fontFamily = FontFamily.Default)
            }
        }
        Spacer(Modifier.height(26.dp))
        val title = when {
            environment.cancelled -> stringResource(R.string.omni_onb_env_cancelled_title)
            environment.failed -> stringResource(R.string.omni_onb_env_failed_title)
            environment.ready -> stringResource(R.string.omni_onb_env_ready_title)
            else -> when (environment.phase) {
                0 -> stringResource(R.string.omni_onb_env_phase_0)
                1 -> stringResource(R.string.omni_onb_env_phase_1, environment.distributionName)
                2 -> stringResource(R.string.omni_onb_env_phase_2)
                3 -> stringResource(R.string.omni_onb_env_phase_3)
                else -> stringResource(R.string.omni_onb_env_phase_4)
            }
        }
        CrossFadeText(title, 22.sp, FontWeight.Bold, palette.text)
        Spacer(Modifier.height(10.dp))
        val stage = environmentStageText(environment)
        CrossFadeText(stage, 14.sp, FontWeight.Normal, if (environment.failed && !environment.cancelled) ERROR else palette.secondaryText)
        Spacer(Modifier.height(24.dp))
        Milestones(environment, accent)
        Spacer(Modifier.height(22.dp))
        SetupSummary(environment)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(when {
            environment.cancelled -> R.string.omni_onb_env_hint_cancelled
            environment.failed -> R.string.omni_onb_env_hint_failed
            environment.ready -> R.string.omni_onb_env_hint_ready
            else -> R.string.omni_onb_env_hint_running
        }), fontSize = 12.sp, lineHeight = 18.sp, color = palette.tertiaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        when {
            environment.failed -> PrimaryButton(stringResource(
                if (environment.cancelled) R.string.omni_onb_env_resume else R.string.omni_onb_env_retry), R.drawable.omni_refresh_ccw,
                onClick = actions.startEnvironment)
            environment.busy -> SecondaryButton(stringResource(R.string.omni_onb_env_cancel), onClick = actions.cancelEnvironment)
            environment.ready -> PrimaryButton(stringResource(R.string.omni_onb_env_continue), R.drawable.omni_arrow_right) {
                actions.goTo(OnboardingPage.Permissions)
            }
        }
        if (!environment.busy) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(R.drawable.omni_arrow_left, stringResource(R.string.omni_onb_back), true, filled = false, onClick = goBack)
                Spacer(Modifier.weight(1f))
                if (environment.failed) {
                    TextButton(stringResource(R.string.omni_onb_skip_environment), { actions.goTo(OnboardingPage.Permissions) })
                }
            }
        }
    }
}


/** Save, system, tools, verify: done steps fill, the running one pulses its outline. */
@Composable
private fun Milestones(environment: EnvironmentSetup, accent: Color) {
    val palette = LocalOmniPalette.current
    val labels = listOf(R.string.omni_onb_env_milestone_save, R.string.omni_onb_env_milestone_system,
        R.string.omni_onb_env_milestone_tools, R.string.omni_onb_env_milestone_verify)
    val phase = if (environment.ready) 4 else environment.phase
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { index, label ->
            val done = index < phase
            val current = index == phase && !environment.ready
            val dot by animateColorAsState(if (done || current) accent else palette.border, label = "milestone-dot")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(22.dp).clip(CircleShape)
                    .background(if (done) dot else Color.Transparent).border(2.dp, dot, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (done) OmniIcon(R.drawable.omni_check, size = 13.dp, tint = Color.White)
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(label), fontSize = 11.sp, color = if (done || current) palette.text else palette.tertiaryText,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** The installer's stage when it has one, otherwise onboarding's own step. */
@Composable
private fun environmentStageText(environment: EnvironmentSetup): String {
    if (environment.stage.isNotBlank()) {
        return localizedEnvironmentStage(environment.stage, isEnglish()) ?: stringResource(R.string.omni_onb_env_tools_next)
    }
    return when (environment.step) {
        EnvironmentStep.SavingChoices -> stringResource(R.string.omni_onb_env_saving)
        EnvironmentStep.PreparingSystem -> stringResource(R.string.omni_onb_env_preparing_system, environment.distributionName)
        EnvironmentStep.Failed -> stringResource(R.string.omni_onb_env_failed)
        EnvironmentStep.Cancelled -> stringResource(R.string.omni_onb_env_cancelled)
        null -> stringResource(R.string.omni_onb_env_preparing)
    }
}
