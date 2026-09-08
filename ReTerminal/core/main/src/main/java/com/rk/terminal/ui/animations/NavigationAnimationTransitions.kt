package com.rk.terminal.ui.animations

import androidx.compose.animation.core.LinearEasing
import top.yukonga.miuix.kmp.nav.transition.NavMotion
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.math.abs

/** The enabled path uses Miuix's own MiuixDefault; only the legacy fade lives here. */
object NavigationAnimationTransitions {
    val LegacyFade = navGraphicsTransition(
        motion = NavMotion(
            programmatic = NavSettleSpec.Tween(250, LinearEasing),
        ),
    ) { scope ->
        alpha = 1f - abs(scope.relativeDepth).coerceIn(0f, 1f)
    }
}
