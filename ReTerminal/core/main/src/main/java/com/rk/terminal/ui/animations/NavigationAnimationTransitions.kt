package com.rk.terminal.ui.animations

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Navigation transitions matching the main OmnibotApp page style.
 *
 * When the predictive-back toggle is ON (default): the incoming page slides the
 * full width, the covered page moves 25% of the width (parallax) and dims to
 * 90%. All specs are linear tweens on purpose: the Flutter
 * PredictiveBackPageTransition drives the page position strictly 1:1 with the
 * finger (no easing while the gesture is tracked), and since navigation-compose
 * seeks these same specs with the back-gesture progress, any eased/spring spec
 * would make the page travel faster than the finger and feel twitchy.
 * Programmatic (non-gesture) navigations use the same 250ms linear slide.
 *
 * When the toggle is OFF: legacy behavior — plain 250ms linear fade, same as
 * the Flutter fallback.
 */
object NavigationAnimationTransitions {
    private const val SLIDE_MS = 250
    private const val LEGACY_FADE_MS = 250

    fun enterTransition(predictiveBack: Boolean): EnterTransition =
        if (predictiveBack) {
            slideInHorizontally(tween(SLIDE_MS, easing = LinearEasing)) { it }
        } else {
            fadeIn(tween(LEGACY_FADE_MS, easing = LinearEasing))
        }

    fun exitTransition(predictiveBack: Boolean): ExitTransition =
        if (predictiveBack) {
            slideOutHorizontally(tween(SLIDE_MS, easing = LinearEasing)) { -it / 4 } +
                fadeOut(tween(SLIDE_MS, easing = LinearEasing), 0.9f)
        } else {
            fadeOut(tween(LEGACY_FADE_MS, easing = LinearEasing))
        }

    fun popEnterTransition(predictiveBack: Boolean): EnterTransition =
        if (predictiveBack) {
            slideInHorizontally(tween(SLIDE_MS, easing = LinearEasing)) { -it / 4 } +
                fadeIn(tween(SLIDE_MS, easing = LinearEasing), 0.9f)
        } else {
            fadeIn(tween(LEGACY_FADE_MS, easing = LinearEasing))
        }

    fun popExitTransition(predictiveBack: Boolean): ExitTransition =
        if (predictiveBack) {
            slideOutHorizontally(tween(SLIDE_MS, easing = LinearEasing)) { it }
        } else {
            fadeOut(tween(LEGACY_FADE_MS, easing = LinearEasing))
        }
}
