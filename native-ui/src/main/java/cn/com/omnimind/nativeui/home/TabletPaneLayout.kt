package cn.com.omnimind.nativeui.home

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/*
 * Tablet landscape panes (batch 5e-7d), ported from `isHdPadLandscapeViewport`
 * and `HdPadPaneLayoutResolver` in `chat_page_models.dart` and the collapse
 * threshold of `chat_page_ui.dart`. Widths are dp, which match Flutter's
 * logical pixels, so the saved widths are shared with the Flutter chat.
 */

const val TABLET_MIN_SHORTEST_SIDE = 600f
const val TABLET_MIN_LANDSCAPE_WIDTH = 960f

/** Dart `isHdPadLandscapeViewport`: a landscape window at least 960 x 600. */
fun isTabletLandscape(width: Float, height: Float): Boolean =
    width > height && min(width, height) >= TABLET_MIN_SHORTEST_SIDE && width >= TABLET_MIN_LANDSCAPE_WIDTH

@Immutable
data class TabletPaneLayout(val left: Float, val center: Float, val right: Float)

object TabletPanes {
    const val DIVIDER = 12f
    const val DEFAULT_LEFT = 260f
    const val MIN_LEFT = 220f
    const val MAX_LEFT = 360f
    const val DEFAULT_RIGHT = 300f
    const val MIN_RIGHT = 240f
    const val MAX_RIGHT = 420f
    const val MIN_CENTER = 320f
    private const val COLLAPSE_WIDTH_RATIO = 0.12f
    private const val COLLAPSE_MIN_WIDTH_FACTOR = 0.72f

    /**
     * Dart `HdPadPaneLayoutResolver.resolve`. Unlike Flutter, a collapsed
     * pane keeps its divider (a rail that reopens it), so [total] always
     * loses two dividers.
     */
    fun resolve(
        total: Float,
        preferredLeft: Float? = null,
        preferredRight: Float? = null,
        collapseLeft: Boolean = false,
        collapseRight: Boolean = false,
    ): TabletPaneLayout {
        val available = max(0f, total - DIVIDER * 2)
        var left = if (collapseLeft) 0f else (preferredLeft ?: DEFAULT_LEFT).coerceIn(MIN_LEFT, MAX_LEFT)
        var right = if (collapseRight) 0f else (preferredRight ?: DEFAULT_RIGHT).coerceIn(MIN_RIGHT, MAX_RIGHT)
        if (!collapseLeft) left = left.coerceIn(MIN_LEFT, max(MIN_LEFT, available - right - MIN_CENTER))
        if (!collapseRight) right = right.coerceIn(MIN_RIGHT, max(MIN_RIGHT, available - left - MIN_CENTER))
        var center = available - left - right
        if (!collapseRight && center < MIN_CENTER) {
            val delta = min(MIN_CENTER - center, max(0f, right - MIN_RIGHT))
            right -= delta
            center += delta
        }
        if (!collapseLeft && center < MIN_CENTER) {
            val delta = min(MIN_CENTER - center, max(0f, left - MIN_LEFT))
            left -= delta
            center += delta
        }
        return TabletPaneLayout(left, max(0f, center), if (center < 0f) 0f else right)
    }

    /** Dart `_resolveHdPadPaneCollapseThreshold`: dragging a pane below this collapses it. */
    fun collapseThreshold(available: Float, minPane: Float): Float =
        min(minPane - 1f, max(available * COLLAPSE_WIDTH_RATIO, minPane * COLLAPSE_MIN_WIDTH_FACTOR))
}

/** Saved pane widths; null keeps the default. */
@Immutable
data class TabletPaneWidths(val left: Float? = null, val right: Float? = null)
