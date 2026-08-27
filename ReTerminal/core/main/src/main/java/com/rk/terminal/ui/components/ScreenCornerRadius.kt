package com.rk.terminal.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The physical radius at each corner of the display, in Compose logical pixels.
 */
data class ScreenCornerRadii(
    val topLeft: Dp = 0.dp,
    val topRight: Dp = 0.dp,
    val bottomLeft: Dp = 0.dp,
    val bottomRight: Dp = 0.dp,
)

/**
 * Clips the moving destination itself, rather than the stationary NavHost.
 *
 * A clip on the NavHost is indistinguishable from the physical display cutout
 * because that clip never moves. Applying the leading-corner clip inside each
 * destination makes the rounded edge travel with the page during forward and
 * predictive-back transitions, matching the main Flutter page transition.
 */
@Composable
fun AnimatedContentScope.PhysicalScreenCornerClip(
    enabled: Boolean,
    screenCorners: ScreenCornerRadii,
    content: @Composable () -> Unit,
) {
    val transitioning = transition.currentState != transition.targetState
    val layoutDirection = LocalLayoutDirection.current
    val topLeading =
        if (layoutDirection == LayoutDirection.Ltr) screenCorners.topLeft else screenCorners.topRight
    val bottomLeading =
        if (layoutDirection == LayoutDirection.Ltr) screenCorners.bottomLeft else screenCorners.bottomRight
    val leadingCornerShape =
        remember(topLeading, bottomLeading) {
            RoundedCornerShape(
                topStart = topLeading,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
                bottomStart = bottomLeading,
            )
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = enabled && transitioning && (topLeading > 0.dp || bottomLeading > 0.dp)
                    shape = leadingCornerShape
                },
    ) {
        content()
    }
}

/**
 * The device's physical screen corner radii, resolved the same way as the
 * main app's DisplayGeometryService: WindowInsets.getRoundedCorner on API 31+,
 * falling back to the framework `rounded_corner_radius_*` dimens, else 0.
 *
 * Note: `View.getRootWindowInsets()` returns null before the first layout
 * pass, which typically happens *after* initial composition, so the value is
 * resolved again once the view has been laid out.
 */
@Composable
fun rememberScreenCornerRadii(): ScreenCornerRadii {
    val view = LocalView.current
    val density = LocalDensity.current
    var topLeftPx by remember { mutableFloatStateOf(0f) }
    var topRightPx by remember { mutableFloatStateOf(0f) }
    var bottomLeftPx by remember { mutableFloatStateOf(0f) }
    var bottomRightPx by remember { mutableFloatStateOf(0f) }
    DisposableEffect(view) {
        fun update() {
            val radii = resolveScreenCornerRadiiPx(view.context, view)
            topLeftPx = radii.topLeft
            topRightPx = radii.topRight
            bottomLeftPx = radii.bottomLeft
            bottomRightPx = radii.bottomRight
        }
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        view.addOnLayoutChangeListener(layoutListener)
        update()
        // rootWindowInsets is only populated after the first layout — retry
        // after it, otherwise the radius would stay 0 forever.
        view.post { update() }
        onDispose { view.removeOnLayoutChangeListener(layoutListener) }
    }
    return with(density) {
        ScreenCornerRadii(
            topLeft = topLeftPx.toDp(),
            topRight = topRightPx.toDp(),
            bottomLeft = bottomLeftPx.toDp(),
            bottomRight = bottomRightPx.toDp(),
        )
    }
}

private data class ScreenCornerRadiiPx(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomLeft: Float = 0f,
    val bottomRight: Float = 0f,
)

private fun resolveScreenCornerRadiiPx(context: Context, view: View): ScreenCornerRadiiPx {
    val fallbackGeneric = frameworkCornerRadius(context, "rounded_corner_radius")
    val fallbackTop =
        frameworkCornerRadius(context, "rounded_corner_radius_top").takeIf { it > 0f }
            ?: fallbackGeneric
    val fallbackBottom =
        frameworkCornerRadius(context, "rounded_corner_radius_bottom").takeIf { it > 0f }
            ?: fallbackGeneric

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val insets = view.rootWindowInsets
        if (insets != null) {
            return ScreenCornerRadiiPx(
                topLeft =
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
                        ?.takeIf { it > 0f } ?: fallbackTop,
                topRight =
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat()
                        ?.takeIf { it > 0f } ?: fallbackTop,
                bottomLeft =
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
                        ?.takeIf { it > 0f } ?: fallbackBottom,
                bottomRight =
                    insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat()
                        ?.takeIf { it > 0f } ?: fallbackBottom,
            )
        }
    }
    return ScreenCornerRadiiPx(
        topLeft = fallbackTop,
        topRight = fallbackTop,
        bottomLeft = fallbackBottom,
        bottomRight = fallbackBottom,
    )
}

@SuppressLint("DiscouragedApi")
private fun frameworkCornerRadius(context: Context, name: String): Float {
    val id = context.resources.getIdentifier(name, "dimen", "android")
    return if (id == 0) 0f else context.resources.getDimension(id)
}
