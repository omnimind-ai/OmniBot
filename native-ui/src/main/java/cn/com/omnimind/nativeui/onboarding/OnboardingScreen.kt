package cn.com.omnimind.nativeui.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.settings.PermissionDialogs
import cn.com.omnimind.nativeui.settings.PermissionsActions
import cn.com.omnimind.nativeui.settings.PermissionsState
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.Scaffold



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
