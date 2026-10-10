package cn.com.omnimind.nativeui.home

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.components.OmniIcon
import cn.com.omnimind.nativeui.theme.LocalOmniPalette
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** The native Home controls the first-use tour points at (5f-1c). */
enum class HomeTourAnchor { Menu, Island, Pet, Agents, Composer }

/** One tour step: what it points at and what it says. */
internal data class HomeTourStep(
    val anchor: HomeTourAnchor,
    @DrawableRes val icon: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
)

/**
 * Dart `ChatSpotlightTour` described the Flutter chat page (mode island,
 * tool island, model picker). Native Home has different controls, so each
 * step names one that is on screen.
 */
internal val HOME_TOUR_STEPS = listOf(
    HomeTourStep(HomeTourAnchor.Menu, R.drawable.omni_menu, R.string.omni_tour_menu_title, R.string.omni_tour_menu_body),
    HomeTourStep(HomeTourAnchor.Island, R.drawable.omni_folders, R.string.omni_tour_island_title, R.string.omni_tour_island_body),
    HomeTourStep(HomeTourAnchor.Pet, R.drawable.omni_paw_print, R.string.omni_tour_pet_title, R.string.omni_tour_pet_body),
    HomeTourStep(HomeTourAnchor.Agents, R.drawable.omni_bot, R.string.omni_tour_agents_title, R.string.omni_tour_agents_body),
    HomeTourStep(HomeTourAnchor.Composer, R.drawable.omni_message_circle, R.string.omni_tour_composer_title, R.string.omni_tour_composer_body),
)

/** Root-relative bounds of the tour's anchors, written by [homeTourAnchor]. */
@Stable
class HomeTourAnchors {
    internal val bounds = mutableStateMapOf<HomeTourAnchor, Rect>()
}

val LocalHomeTourAnchors = staticCompositionLocalOf<HomeTourAnchors?> { null }

/** Reports this element's bounds when a tour is listening; free otherwise. */
@Composable
internal fun Modifier.homeTourAnchor(anchor: HomeTourAnchor): Modifier {
    val anchors = LocalHomeTourAnchors.current ?: return this
    return onGloballyPositioned { anchors.bounds[anchor] = it.boundsInRoot() }
}

/**
 * Where the step card goes: below the spotlight when it fits, otherwise
 * above it, always inside the safe area. Dart placed the card by step
 * number, so a moved control could be covered by its own card.
 */
fun spotlightCardTop(hole: Rect, cardHeight: Float, containerHeight: Float, topInset: Float, bottomInset: Float, gap: Float): Float {
    val minTop = topInset + gap
    val maxTop = (containerHeight - bottomInset - gap - cardHeight).coerceAtLeast(minTop)
    val below = hole.bottom + gap
    val above = hole.top - gap - cardHeight
    return when {
        below + cardHeight <= containerHeight - bottomInset - gap -> below
        above >= minTop -> above
        else -> below.coerceIn(minTop, maxTop)
    }
}

/**
 * The spotlight tour over native Home: a scrim with a rounded hole that
 * glides between controls, a breathing accent ring, and a card with the
 * step, Skip and Next. Tapping anywhere advances, back goes to the previous
 * step. Dart offered no way out except tapping through all six steps.
 */
@Composable
internal fun HomeSpotlightTour(anchors: HomeTourAnchors, onFinish: () -> Unit) {
    val palette = LocalOmniPalette.current
    val density = LocalDensity.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = HOME_TOUR_STEPS[index.coerceIn(0, HOME_TOUR_STEPS.lastIndex)]
    val last = index == HOME_TOUR_STEPS.lastIndex
    val advance = { if (last) onFinish() else index += 1 }
    BackHandler { if (index > 0) index -= 1 else onFinish() }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val padding = with(density) { 6.dp.toPx() }
    val target = anchors.bounds[step.anchor]?.translate(-origin)?.inflate(padding)
    // Snaps to the first measured control, then glides between controls.
    val hole = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(target) {
        val next = target ?: return@LaunchedEffect
        if (hole.value == Rect.Zero) hole.snapTo(next)
        else hole.animateTo(next, spring(dampingRatio = .82f, stiffness = Spring.StiffnessMediumLow))
    }
    val pulse by rememberInfiniteTransition(label = "tour-ring")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "tour-pulse")
    val title = stringResource(step.title)
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    Layout(
        modifier = Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .semantics { paneTitle = title }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = advance),
        content = {
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                drawRect(Color.Black.copy(alpha = .62f))
                if (target != null) {
                    val hole = hole.value
                    val radius = CornerRadius(minOf(hole.height, hole.width) / 2f)
                    drawRoundRect(Color.Transparent, hole.topLeft, hole.size, radius, blendMode = BlendMode.Clear)
                    val spread = 2.dp.toPx() + pulse * 4.dp.toPx()
                    drawRoundRect(
                        palette.accent.copy(alpha = .9f - pulse * .45f),
                        Offset(hole.left - spread, hole.top - spread),
                        Size(hole.width + spread * 2, hole.height + spread * 2),
                        CornerRadius(radius.x + spread),
                        style = Stroke(2.5.dp.toPx()),
                    )
                }
            }
            TourCard(index, step, last, onSkip = onFinish, onNext = advance)
        },
    ) { measurables, constraints ->
        val scrim = measurables[0].measure(constraints)
        val margin = 18.dp.roundToPx()
        val cardWidth = minOf(constraints.maxWidth - margin * 2, 440.dp.roundToPx())
        val card = measurables[1].measure(constraints.copy(minWidth = cardWidth, maxWidth = cardWidth, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            scrim.place(0, 0)
            val top = spotlightCardTop(hole.value, card.height.toFloat(), constraints.maxHeight.toFloat(),
                safeTop.toFloat(), safeBottom.toFloat(), 16.dp.toPx())
            card.place((constraints.maxWidth - cardWidth) / 2, top.toInt())
        }
    }
}

@Composable
private fun TourCard(index: Int, step: HomeTourStep, last: Boolean, onSkip: () -> Unit, onNext: () -> Unit) {
    val palette = LocalOmniPalette.current
    Column(
        Modifier.shadow(24.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(palette.surface)
            .border(1.dp, palette.border, RoundedCornerShape(22.dp))
            // The card itself does not advance; only its buttons and the scrim do.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
    ) {
        AnimatedContent(step, transitionSpec = {
            val forward = HOME_TOUR_STEPS.indexOf(targetState) > HOME_TOUR_STEPS.indexOf(initialState)
            (fadeIn(tween(220, 80)) + slideInVertically(tween(260)) { if (forward) it / 6 else -it / 6 }) togetherWith fadeOut(tween(120))
        }, label = "tour-card") { current ->
            Row(Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(palette.accent.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center) {
                    OmniIcon(current.icon, size = 20.dp, tint = palette.accent)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(current.title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text)
                    Spacer(Modifier.height(5.dp))
                    Text(stringResource(current.body), fontSize = 13.sp, lineHeight = 20.sp, color = palette.secondaryText)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                HOME_TOUR_STEPS.indices.forEach { dot ->
                    val width by animateDpAsState(if (dot == index) 16.dp else 6.dp, label = "tour-dot")
                    Box(Modifier.height(6.dp).width(width).clip(CircleShape)
                        .background(if (dot == index) palette.accent else palette.border))
                }
            }
            Spacer(Modifier.weight(1f))
            if (!last) TextButton(stringResource(R.string.omni_tour_skip), onSkip, minHeight = 40.dp)
            Spacer(Modifier.width(6.dp))
            TextButton(stringResource(if (last) R.string.omni_tour_done else R.string.omni_tour_next), onNext, minHeight = 40.dp,
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}
