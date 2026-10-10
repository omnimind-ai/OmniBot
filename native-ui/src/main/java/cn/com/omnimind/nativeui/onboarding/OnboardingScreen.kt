package cn.com.omnimind.nativeui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.components.OmniIconButton
import cn.com.omnimind.nativeui.components.OmniSwitch
import cn.com.omnimind.nativeui.components.SectionTitle
import cn.com.omnimind.nativeui.settings.PermissionDialogs
import cn.com.omnimind.nativeui.settings.PermissionSetting
import cn.com.omnimind.nativeui.settings.PermissionsActions
import cn.com.omnimind.nativeui.settings.PermissionsState
import cn.com.omnimind.nativeui.settings.PreferenceDivider
import cn.com.omnimind.nativeui.settings.PreferenceRow
import cn.com.omnimind.nativeui.settings.labelResource
import cn.com.omnimind.nativeui.settings.guideResource
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.utils.PressFeedbackType

private val SUCCESS = Color(0xFF2F8F6B)
private val ERROR = Color(0xFFE5484D)

/**
 * Native first-use onboarding (batch 5f-1b), replacing the Flutter
 * `OnboardingChoicePage`. One page at a time slides in the direction of
 * travel; a step rail on top replaces the dotted footer and jumps back to
 * visited steps. [onExit] leaves a replay opened from settings.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingState,
    permissions: PermissionsState,
    permissionActions: PermissionsActions,
    actions: OnboardingActions,
    onExit: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val goBack = {
        if (!state.locked) {
            if (state.flow.hasHistory) actions.back() else if (state.replay) onExit()
        }
    }
    // A running install, connection or save owns back until it settles.
    BackHandler(enabled = state.locked || state.flow.hasHistory || state.replay, onBack = goBack)
    Scaffold(containerColor = palette.page) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val wide = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            AnimatedVisibility(state.flow.onNavigationPage, wide, enter = fadeIn(), exit = fadeOut()) {
                StepRail(state.flow, enabled = !state.locked, onJump = actions.jumpTo)
            }
            AnimatedContent(
                targetState = state.flow,
                contentKey = { it.page },
                transitionSpec = {
                    val direction = targetState.direction
                    (slideInHorizontally(tween(320)) { it * direction / 6 } + fadeIn(tween(220, 60))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it * direction / 8 } + fadeOut(tween(140))) using
                        SizeTransform(clip = false)
                },
                modifier = wide.weight(1f),
                label = "onboarding-page",
            ) { flow ->
                Box(Modifier.fillMaxSize()) {
                    OnboardingPageContent(flow.page, state, permissions, permissionActions, actions, goBack)
                }
            }
            if (state.flow.page != OnboardingPage.EnvironmentProgress) {
                Footer(state, actions, goBack, wide)
            }
        }
        // Dialogs render in this Scaffold's popup host.
        PermissionDialogs(permissions, permissionActions)
    }
}

/** Visited steps are reachable; the current one is a longer accent segment. */
@Composable
private fun StepRail(flow: OnboardingFlow, enabled: Boolean, onJump: (OnboardingPage) -> Unit) {
    val palette = LocalOmniPalette.current
    val steps = ONBOARDING_NAVIGATION_PAGES
    val stepLabel = stringResource(R.string.omni_onb_step, flow.navigationIndex + 1, steps.size)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(36.dp)
            .semantics { contentDescription = stepLabel },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, page ->
            val current = page == flow.page
            val visited = page in flow.visited
            val weight by animateFloatAsState(if (current) 2.4f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "step-width")
            val color by animateColorAsState(
                when {
                    current -> palette.accent
                    visited -> palette.accent.copy(alpha = .32f)
                    else -> palette.border
                },
                label = "step-color",
            )
            Box(
                Modifier.weight(weight).fillMaxHeight()
                    .clickable(enabled = enabled && visited && !current, role = Role.Tab,
                        onClickLabel = stringResource(R.string.omni_onb_step, index + 1, steps.size)) { onJump(page) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(color))
            }
        }
    }
}

@Composable
private fun Footer(state: OnboardingState, actions: OnboardingActions, goBack: () -> Unit, modifier: Modifier) {
    val page = state.flow.page
    Column(modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        PrimaryAction(state, actions)
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            val showBack = state.flow.hasHistory || state.replay
            if (showBack) {
                RoundButton(R.drawable.omni_arrow_left, stringResource(R.string.omni_onb_back), state.canGoBack,
                    filled = false, onClick = goBack)
            }
            Spacer(Modifier.weight(1f))
            if (page != OnboardingPage.Completion) {
                val next = state.nextPage
                RoundButton(R.drawable.omni_arrow_right,
                    stringResource(if (next == null) R.string.omni_onb_next_blocked else R.string.omni_onb_next),
                    next != null && !state.locked, filled = true, onClick = actions.next)
            }
        }
    }
}

@Composable
private fun RoundButton(@DrawableRes icon: Int, description: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
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

/** The page's main call to action, above the arrows. */
@Composable
private fun PrimaryAction(state: OnboardingState, actions: OnboardingActions) {
    val provider = state.provider
    when (state.flow.page) {
        OnboardingPage.Tools -> PrimaryButton(stringResource(R.string.omni_onb_start_setup), R.drawable.omni_download,
            enabled = !state.environment.distributionLoading, onClick = actions.startEnvironment)
        OnboardingPage.Provider -> if (provider.connected) {
            PrimaryButton(stringResource(R.string.omni_onb_provider_continue, provider.profileName), R.drawable.omni_arrow_right,
                enabled = !provider.loading) { actions.goTo(OnboardingPage.ModelInventory) }
        } else {
            SecondaryButton(stringResource(R.string.omni_onb_provider_skip), enabled = !provider.loading) {
                actions.goTo(OnboardingPage.Completion)
            }
        }
        OnboardingPage.ProviderConnection -> PrimaryButton(
            stringResource(when {
                provider.busy -> R.string.omni_onb_connecting
                provider.connected -> R.string.omni_onb_reconnect
                else -> R.string.omni_onb_connect
            }),
            if (provider.connected) R.drawable.omni_refresh_cw else R.drawable.omni_plug_zap,
            enabled = !provider.busy, busy = provider.busy, onClick = actions.connect,
        )
        OnboardingPage.MemoryScenes -> PrimaryButton(
            stringResource(if (provider.savingScenes) R.string.omni_onb_saving_scenes else R.string.omni_onb_save_scenes),
            R.drawable.omni_check, enabled = !provider.savingScenes && provider.connected && provider.models.isNotEmpty(),
            busy = provider.savingScenes, onClick = actions.saveScenes,
        )
        OnboardingPage.Completion -> PrimaryButton(
            stringResource(if (state.completing) R.string.omni_onb_opening else R.string.omni_onb_start_exploring),
            R.drawable.omni_rocket, enabled = !state.completing, busy = state.completing, onClick = actions.complete,
        )
        else -> Unit
    }
}

@Composable
private fun PrimaryButton(text: String, @DrawableRes icon: Int, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
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
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text, onClick, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = enabled, minHeight = 52.dp)
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    state: OnboardingState,
    permissions: PermissionsState,
    permissionActions: PermissionsActions,
    actions: OnboardingActions,
    goBack: () -> Unit,
) {
    when (page) {
        OnboardingPage.System -> SystemPage(state.environment, actions)
        OnboardingPage.Development -> DevelopmentPage(state.environment, actions)
        OnboardingPage.Tools -> ToolsPage(state.environment, actions)
        OnboardingPage.EnvironmentProgress -> EnvironmentProgressPage(state.environment, actions, goBack)
        OnboardingPage.Permissions -> PermissionsPage(permissions, permissionActions)
        OnboardingPage.Provider -> ProviderPage(state, actions)
        OnboardingPage.ProviderConnection -> ConnectionPage(state.provider, actions)
        OnboardingPage.ModelInventory -> ModelInventoryPage(state.provider, actions)
        OnboardingPage.PrimaryScenes -> ScenesPage(state.provider, actions, memory = false)
        OnboardingPage.MemoryScenes -> ScenesPage(state.provider, actions, memory = true)
        OnboardingPage.Completion -> CompletionPage(state)
    }
}

/** Scrollable page body with the shared heading. */
@Composable
private fun PageColumn(
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
private fun OptionCard(
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
private fun IconTile(@DrawableRes icon: Int, tint: Color, background: Color = LocalOmniPalette.current.secondarySurface) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(background), contentAlignment = Alignment.Center) {
        OmniIcon(icon, size = 22.dp, tint = tint)
    }
}

@Composable
private fun SystemPage(environment: EnvironmentSetup, actions: OnboardingActions) {
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
private fun DevelopmentPage(environment: EnvironmentSetup, actions: OnboardingActions) {
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
private fun ToolsPage(environment: EnvironmentSetup, actions: OnboardingActions) {
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
private fun SetupSummary(environment: EnvironmentSetup) {
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
private fun EnvironmentProgressPage(environment: EnvironmentSetup, actions: OnboardingActions, goBack: () -> Unit) {
    val palette = LocalOmniPalette.current
    val accent by animateColorAsState(when {
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
        val stage = if (environment.stage.isBlank()) stringResource(R.string.omni_onb_env_preparing)
            else localizedEnvironmentStage(environment.stage, isEnglish()) ?: stringResource(R.string.omni_onb_env_tools_next)
        CrossFadeText(stage, 14.sp, FontWeight.Normal, if (environment.failed) ERROR else palette.secondaryText)
        Spacer(Modifier.height(24.dp))
        Milestones(environment, accent)
        Spacer(Modifier.height(22.dp))
        SetupSummary(environment)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(when {
            environment.failed -> R.string.omni_onb_env_hint_failed
            environment.ready -> R.string.omni_onb_env_hint_ready
            else -> R.string.omni_onb_env_hint_running
        }), fontSize = 12.sp, lineHeight = 18.sp, color = palette.tertiaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        when {
            environment.failed -> PrimaryButton(stringResource(R.string.omni_onb_env_retry), R.drawable.omni_refresh_ccw,
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

@Composable
private fun CrossFadeText(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color) {
    AnimatedContent(text, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "cross-fade") { value ->
        Text(value, fontSize = size, lineHeight = size * 1.45f, fontWeight = weight, color = color, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
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

@Composable
private fun PermissionsPage(permissions: PermissionsState, actions: PermissionsActions) {
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_shield_check, stringResource(R.string.omni_onb_perm_title), stringResource(R.string.omni_onb_perm_desc)) {
        val ready = permissions.onboardingCoreReady
        val progress by animateFloatAsState(ready / ONBOARDING_CORE_PERMISSIONS.toFloat(), label = "permission-progress")
        CrossFadeLeft(stringResource(R.string.omni_onb_perm_ready, ready, ONBOARDING_CORE_PERMISSIONS))
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = if (permissions.loaded) progress else null, height = 6.dp,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = palette.accent, backgroundColor = palette.border))
        Spacer(Modifier.height(24.dp))
        SectionTitle(stringResource(R.string.omni_permissions_core))
        Spacer(Modifier.height(4.dp))
        PermissionItem(PermissionSetting.Background, R.drawable.omni_battery_charging, R.string.omni_permission_background,
            R.string.omni_permission_background_summary, permissions.backgroundAllowed, permissions, actions)
        PreferenceDivider()
        PermissionItem(PermissionSetting.Overlay, R.drawable.omni_picture_in_picture_2, R.string.omni_permission_overlay,
            R.string.omni_permission_overlay_summary, permissions.overlayAllowed, permissions, actions)
        PreferenceDivider()
        PermissionItem(PermissionSetting.InstalledApps, R.drawable.omni_layout_grid, R.string.omni_permission_apps,
            R.string.omni_permission_apps_summary, permissions.installedAppsAllowed, permissions, actions, isLast = true)
        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.omni_permissions_advanced))
        Spacer(Modifier.height(4.dp))
        PermissionItem(PermissionSetting.PublicStorage, R.drawable.omni_folder_open, R.string.omni_permission_storage,
            R.string.omni_permission_storage_summary, permissions.publicStorageAllowed, permissions, actions)
        PreferenceDivider()
        PreferenceRow(stringResource(R.string.omni_permission_shizuku), stringResource(permissions.shizuku.guideResource()),
            icon = R.drawable.omni_usb, isLast = true, enabled = permissions.loaded && !permissions.busy,
            onClick = { actions.select(PermissionSetting.Shizuku) }) {
            GrantTrailing(stringResource(permissions.shizuku.labelResource()), permissions.shizuku.granted)
        }
        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.omni_permissions_notifications))
        Spacer(Modifier.height(4.dp))
        val label = stringResource(R.string.omni_authorize_receive_notifications)
        PreferenceRow(label, stringResource(R.string.omni_authorize_notifications_desc), icon = R.drawable.omni_bell, isLast = true,
            enabled = permissions.loaded && !permissions.busy,
            onClick = { actions.setNotificationsEnabled(!permissions.notificationsEnabled) }) {
            OmniSwitch(permissions.notificationsEnabled, actions.setNotificationsEnabled,
                enabled = permissions.loaded && !permissions.busy, contentDescription = label)
        }
    }
}

@Composable
private fun CrossFadeLeft(text: String) {
    val palette = LocalOmniPalette.current
    AnimatedContent(text, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "permission-count") {
        Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
    }
}

@Composable
private fun PermissionItem(setting: PermissionSetting, icon: Int, title: Int, summary: Int, granted: Boolean,
    state: PermissionsState, actions: PermissionsActions, isLast: Boolean = false) {
    PreferenceRow(stringResource(title), stringResource(summary), icon = icon, isLast = isLast,
        enabled = state.loaded && !state.busy && !granted, onClick = { actions.select(setting) }) {
        GrantTrailing(stringResource(if (granted) R.string.omni_permission_enabled else R.string.omni_permission_enable), granted)
    }
}

/** A filled check pops in when the grant lands; otherwise the action label. */
@Composable
private fun GrantTrailing(label: String, granted: Boolean) {
    val palette = LocalOmniPalette.current
    AnimatedContent(granted, transitionSpec = {
        (scaleIn(spring(dampingRatio = .55f, stiffness = Spring.StiffnessMedium)) + fadeIn()) togetherWith fadeOut()
    }, label = "grant") { done ->
        if (done) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(palette.accent), contentAlignment = Alignment.Center) {
                OmniIcon(R.drawable.omni_check, label, size = 14.dp, tint = Color.White)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = palette.accent)
                OmniIcon(R.drawable.omni_chevron_right, size = 16.dp, tint = palette.tertiaryText)
            }
        }
    }
}

private val ProviderOption.icon: Int
    get() = when (id) {
        "deepseek" -> R.drawable.omni_brand_deepseek
        "moonshot" -> R.drawable.omni_brand_moonshot
        "mimo" -> R.drawable.omni_brand_xiaomi
        "openai" -> R.drawable.omni_brand_openai
        "anthropic" -> R.drawable.omni_brand_anthropic
        else -> R.drawable.omni_plug_zap
    }

@Composable
private fun ProviderOption.tint(): Color = when (id) {
    "deepseek" -> Color(0xFF4D6BFE)
    "mimo" -> Color(0xFFFF6900)
    "custom" -> LocalOmniPalette.current.secondaryText
    else -> LocalOmniPalette.current.text
}

@Composable
private fun ProviderPage(state: OnboardingState, actions: OnboardingActions) {
    val provider = state.provider
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_brain, stringResource(R.string.omni_onb_provider_title), stringResource(R.string.omni_onb_provider_desc)) {
        if (state.accountAvailable) {
            OptionCard(false, actions.openAccount, { IconTile(R.drawable.omni_log_in, palette.accent, palette.accent.copy(alpha = .1f)) },
                stringResource(R.string.omni_onb_provider_account),
                description = stringResource(R.string.omni_onb_provider_account_desc), enabled = !provider.busy)
            Spacer(Modifier.height(10.dp))
        }
        if (provider.loading) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.omni_onb_provider_loading), fontSize = 13.sp, color = palette.secondaryText)
            }
        } else {
            PROVIDER_OPTIONS.forEach { option ->
                val connectedHere = provider.connected && provider.optionId == option.id
                OptionCard(connectedHere, { actions.chooseProvider(option.id) },
                    { IconTile(option.icon, option.tint()) },
                    if (option.id == "custom") stringResource(R.string.omni_onb_provider_custom) else option.label,
                    description = when {
                        connectedHere -> stringResource(R.string.omni_onb_provider_connected, provider.models.size)
                        option.id == "custom" -> stringResource(R.string.omni_onb_provider_custom_desc)
                        else -> option.baseUrl.removePrefix("https://")
                    },
                    enabled = !provider.busy)
            }
        }
        provider.notice?.let { NoticeText(it) }
    }
}

@Composable
private fun ConnectionPage(provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    val custom = provider.option.id == "custom"
    val label = if (custom) stringResource(R.string.omni_onb_provider_custom) else provider.option.label
    PageColumn(provider.option.icon, stringResource(R.string.omni_onb_connect_title, label), stringResource(R.string.omni_onb_connect_desc)) {
        if (custom) {
            FieldLabel(stringResource(R.string.omni_onb_connect_name))
            TextField(provider.name, actions.updateName, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
                label = stringResource(R.string.omni_onb_connect_name_hint), useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(16.dp))
        }
        FieldLabel(stringResource(R.string.omni_onb_connect_base_url))
        TextField(provider.baseUrl, actions.updateBaseUrl, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
            label = "https://api.example.com/v1", useLabelAsPlaceholder = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (custom) R.string.omni_onb_connect_base_url_custom else R.string.omni_onb_connect_base_url_preset),
            fontSize = 12.sp, lineHeight = 17.sp, color = palette.tertiaryText)
        Spacer(Modifier.height(16.dp))
        FieldLabel("API Key")
        var reveal by rememberSaveable { mutableStateOf(false) }
        TextField(provider.apiKey, actions.updateApiKey, Modifier.fillMaxWidth(), singleLine = true, enabled = !provider.busy,
            label = if (custom) stringResource(R.string.omni_onb_connect_key_optional) else "sk-…", useLabelAsPlaceholder = true,
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                OmniIconButton(if (reveal) R.drawable.omni_eye else R.drawable.omni_eye_off,
                    stringResource(if (reveal) R.string.omni_onb_key_hide else R.string.omni_onb_key_show),
                    { reveal = !reveal }, size = 18.dp, tint = palette.tertiaryText)
            })
        provider.notice?.let { NoticeText(it) }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalOmniPalette.current.secondaryText,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
}

@Composable
private fun NoticeText(notice: OnboardingNotice) {
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

private const val MODEL_PREVIEW = 8

@Composable
private fun ModelInventoryPage(provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    PageColumn(R.drawable.omni_layout_grid, stringResource(R.string.omni_onb_models_title), stringResource(R.string.omni_onb_models_desc)) {
        if (provider.models.isEmpty()) {
            Text(stringResource(R.string.omni_onb_models_empty), fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondaryText)
        } else {
            Text(stringResource(R.string.omni_onb_models_count, provider.models.size), fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, color = palette.text)
            Spacer(Modifier.height(10.dp))
            var expanded by rememberSaveable { mutableStateOf(false) }
            val shown = if (expanded) provider.models else provider.models.take(MODEL_PREVIEW)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface)) {
                shown.forEachIndexed { index, model ->
                    if (index > 0) Box(Modifier.padding(start = 14.dp).fillMaxWidth().height(1.dp).background(palette.border.copy(alpha = .6f)))
                    Text(model, fontSize = 13.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            if (provider.models.size > MODEL_PREVIEW) {
                TextButton(stringResource(if (expanded) R.string.omni_onb_models_less
                    else R.string.omni_onb_models_more, provider.models.size - MODEL_PREVIEW), { expanded = !expanded })
            }
        }
        Spacer(Modifier.height(18.dp))
        var manual by rememberSaveable { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true,
                label = stringResource(R.string.omni_onb_models_add_hint), useLabelAsPlaceholder = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            Spacer(Modifier.width(10.dp))
            TextButton(stringResource(R.string.omni_onb_models_add), {
                actions.addModel(manual)
                manual = ""
            }, enabled = manual.isNotBlank() && provider.connected, colors = ButtonDefaults.textButtonColorsPrimary())
        }
        provider.notice?.let { NoticeText(it) }
    }
}

@Composable
private fun ScenesPage(provider: ProviderSetup, actions: OnboardingActions, memory: Boolean) {
    val english = isEnglish()
    PageColumn(
        if (memory) R.drawable.omni_database else R.drawable.omni_route,
        stringResource(if (memory) R.string.omni_onb_memory_title else R.string.omni_onb_scenes_title),
        stringResource(if (memory) R.string.omni_onb_memory_desc else R.string.omni_onb_scenes_desc),
    ) {
        ONBOARDING_SCENES.filter { it.memory == memory }.forEach { scene ->
            SceneCard(scene, if (english) scene.descriptionEn else scene.descriptionZh, provider, actions)
        }
        if (memory) provider.notice?.let { NoticeText(it) }
    }
}

@Composable
private fun SceneCard(scene: OnboardingScene, description: String, provider: ProviderSetup, actions: OnboardingActions) {
    val palette = LocalOmniPalette.current
    var show by remember { mutableStateOf(false) }
    val selected = provider.sceneSelections[scene.id]
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(14.dp)) {
        Text(scene.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
        Spacer(Modifier.height(4.dp))
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = palette.secondaryText)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.secondarySurface)
                .clickable(enabled = provider.models.isNotEmpty() && !provider.savingScenes, role = Role.DropdownList) { show = true }
                .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.omni_onb_scene_model), fontSize = 11.sp, color = palette.tertiaryText)
                    Text(selected ?: stringResource(R.string.omni_onb_choose_model), fontSize = 14.sp,
                        color = if (selected == null) palette.tertiaryText else palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OmniIcon(R.drawable.omni_chevron_down, size = 18.dp, tint = palette.tertiaryText)
            }
            OverlayListPopup(show = show, popupModifier = Modifier.width(width), minWidth = width, maxHeight = 360.dp,
                onDismissRequest = { show = false }) {
                ListPopupColumn {
                    provider.models.forEachIndexed { index, model ->
                        DropdownImpl(model, provider.models.size, model == selected, index, onSelectedIndexChange = {
                            show = false
                            actions.selectSceneModel(scene.id, model)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionPage(state: OnboardingState) {
    val palette = LocalOmniPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else .6f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessLow), label = "done-scale")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).graphicsScale(scale).clip(CircleShape).background(SUCCESS.copy(alpha = .14f)),
            contentAlignment = Alignment.Center) {
            OmniIcon(R.drawable.omni_check, size = 44.dp, tint = SUCCESS)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.omni_onb_done_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.omni_onb_done_desc), fontSize = 14.sp, lineHeight = 21.sp, color = palette.secondaryText,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(palette.surface).padding(vertical = 4.dp)) {
            DoneRow(R.drawable.omni_square_terminal, stringResource(R.string.omni_onb_done_environment), state.environment.ready)
            DoneRow(R.drawable.omni_brain, stringResource(R.string.omni_onb_done_models), state.provider.connected)
            DoneRow(R.drawable.omni_message_circle, stringResource(R.string.omni_onb_done_chat), true)
        }
    }
}

private fun Modifier.graphicsScale(scale: Float) = graphicsLayer { scaleX = scale; scaleY = scale }

@Composable
private fun DoneRow(@DrawableRes icon: Int, label: String, done: Boolean) {
    val palette = LocalOmniPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OmniIcon(icon, size = 18.dp, tint = palette.secondaryText)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 14.sp, color = palette.text, modifier = Modifier.weight(1f))
        Text(stringResource(if (done) R.string.omni_onb_done_configured else R.string.omni_onb_done_later),
            fontSize = 12.sp, color = if (done) SUCCESS else palette.tertiaryText, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun isEnglish(): Boolean = LocalConfiguration.current.locales[0].language != "zh"
