// SPDX-License-Identifier: GPL-3.0-only
package com.rk.components.compose.preferences.switch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.coroutines.coroutineContext

/** Controlled settings toggle with a solid track and a lifted, stretching thumb. */
@Composable
internal fun OmniSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val position = remember { Animatable(if (checked) 1f else 0f) }
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentChecked by rememberUpdatedState(checked)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(position.value) }
    var tapLift by remember { mutableStateOf(false) }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = with(LocalDensity.current) { 20.dp.toPx() }
    val toggleEasing = remember { CubicBezierEasing(0.175f, 0.885f, 0.32f, 1.275f) }
    val pressEasing = remember { CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f) }

    // Compose's animation clock observes the system duration scale, including zero.
    LaunchedEffect(checked, dragging, enabled) {
        if (!dragging || !enabled) {
            position.animateTo(if (checked) 1f else 0f, tween(300, easing = toggleEasing))
        }
    }
    LaunchedEffect(checked, enabled) {
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (enabled && !dragging && durationScale > 0 &&
            abs(position.value - (if (checked) 1f else 0f)) > 0.5f
        ) {
            tapLift = true
            try {
                delay((200 * durationScale).toLong())
            } finally {
                tapLift = false
            }
        }
    }
    LaunchedEffect(pressed, dragging, tapLift, enabled) {
        lift.animateTo(
            if (enabled && (pressed || dragging || tapLift)) 1f else 0f,
            tween(150, easing = pressEasing),
        )
    }
    LaunchedEffect(enabled) {
        if (!enabled) dragging = false
    }

    Canvas(
        modifier = modifier
            .size(60.dp, 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .pointerInput(enabled, rtl, travel) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragPosition = position.value.coerceIn(0f, 1f)
                        dragging = true
                        scope.launch { position.stop() }
                    },
                    onDragEnd = {
                        val releasedPosition = dragPosition
                        scope.launch {
                            position.snapTo(releasedPosition)
                            dragging = false
                            if (!currentEnabled) {
                                position.snapTo(if (currentChecked) 1f else 0f)
                                return@launch
                            }
                            val next = releasedPosition >= 0.5f
                            if (next != currentChecked) currentOnCheckedChange(next)
                        }
                    },
                    onDragCancel = {
                        // A cancelled pointer restores the owner's value without toggling.
                        scope.launch {
                            position.snapTo(if (currentChecked) 1f else 0f)
                            dragging = false
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragPosition = (dragPosition + (if (rtl) -delta else delta) / travel)
                            .coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val along = if (dragging) dragPosition else position.value
        val on = along.coerceIn(0f, 1f)
        val stretch = sin(PI.toFloat() * on)
        val pressedFraction = lift.value
        val trackWidth = 52.dp.toPx()
        val trackHeight = 32.dp.toPx()
        val trackTopLeft = Offset((size.width - trackWidth) / 2, (size.height - trackHeight) / 2)
        val trackSize = Size(trackWidth, trackHeight)
        val trackColor = if (enabled) {
            lerp(colors.surfaceContainerHighest, colors.primary, on)
        } else {
            lerp(colors.surfaceContainerHighest.copy(alpha = 0.12f), colors.onSurface.copy(alpha = 0.12f), on)
        }
        val restingColor = if (enabled) {
            lerp(colors.outline, colors.onPrimary, on)
        } else {
            lerp(colors.onSurface.copy(alpha = 0.38f), colors.surface, on)
        }
        drawRoundRect(trackColor, trackTopLeft, trackSize, CornerRadius(trackHeight / 2))

        val restingWidth = 24f + 10f * stretch
        val restingHeight = 24f - 2f * stretch
        val thumbWidth = (restingWidth + (40f + 10f * stretch - restingWidth) * pressedFraction).dp.toPx()
        val thumbHeight = (restingHeight + (40f - 2f * stretch - restingHeight) * pressedFraction).dp.toPx()
        val thumbX = trackTopLeft.x + trackHeight / 2 +
            (trackWidth - trackHeight) * if (rtl) 1 - along else along
        val thumbTopLeft = Offset(thumbX - thumbWidth / 2, size.height / 2 - thumbHeight / 2)
        val thumbSize = Size(thumbWidth, thumbHeight)
        if (pressedFraction > 0) {
            // Soft lift shadow stays outside the track without changing its hit target.
            for (band in 3 downTo 1) {
                val spread = band.dp.toPx()
                drawRoundRect(
                    Color.Black.copy(alpha = 0.035f * pressedFraction),
                    thumbTopLeft + Offset(-spread, 2.dp.toPx() - spread),
                    Size(thumbWidth + spread * 2, thumbHeight + spread * 2),
                    CornerRadius(thumbHeight / 2 + spread),
                )
            }
        }
        drawRoundRect(
            lerp(restingColor, Color.White, pressedFraction),
            thumbTopLeft,
            thumbSize,
            CornerRadius(thumbHeight / 2),
        )
        if (focused && enabled) {
            val outset = 3.dp.toPx()
            drawRoundRect(
                colors.primary,
                trackTopLeft - Offset(outset, outset),
                Size(trackWidth + outset * 2, trackHeight + outset * 2),
                CornerRadius(trackHeight / 2 + outset),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}
