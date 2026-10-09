package cn.com.omnimind.nativeui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from `chat_hd_pad_layout_test.dart`; dividers always count twice (a collapsed pane keeps its rail). */
class TabletPaneLayoutTest {
    private val d = TabletPanes.DIVIDER

    @Test fun requiresATabletSizedLandscapeWindow() {
        assertFalse(isTabletLandscape(932f, 430f))
        assertFalse(isTabletLandscape(844f, 390f))
        assertFalse(isTabletLandscape(959f, 600f))
        assertFalse(isTabletLandscape(768f, 1024f))
        assertTrue(isTabletLandscape(960f, 600f))
        assertTrue(isTabletLandscape(1024f, 768f))
    }

    @Test fun usesDefaultsWithinSupportedWidth() {
        val layout = TabletPanes.resolve(1200f)
        assertEquals(TabletPanes.DEFAULT_LEFT, layout.left)
        assertEquals(TabletPanes.DEFAULT_RIGHT, layout.right)
        assertEquals(1200f - d * 2 - TabletPanes.DEFAULT_LEFT - TabletPanes.DEFAULT_RIGHT, layout.center)
    }

    @Test fun clampsOversizedPreferencesToKeepTheCenterUsable() {
        val layout = TabletPanes.resolve(960f, preferredLeft = 360f, preferredRight = 420f)
        assertTrue(layout.left >= TabletPanes.MIN_LEFT)
        assertTrue(layout.right >= TabletPanes.MIN_RIGHT)
        assertTrue(layout.center >= TabletPanes.MIN_CENTER)
        assertEquals(960f - d * 2, layout.left + layout.center + layout.right, 0.01f)
    }

    @Test fun clampsSavedWidthsToPaneBounds() {
        val layout = TabletPanes.resolve(1400f, preferredLeft = 120f, preferredRight = 1000f)
        assertEquals(TabletPanes.MIN_LEFT, layout.left)
        assertTrue(layout.right <= TabletPanes.MAX_RIGHT)
        assertTrue(layout.center >= TabletPanes.MIN_CENTER)
    }

    @Test fun collapsesEitherOrBothPanes() {
        val noLeft = TabletPanes.resolve(1200f, preferredLeft = 320f, preferredRight = 300f, collapseLeft = true)
        assertEquals(0f, noLeft.left)
        assertEquals(TabletPanes.DEFAULT_RIGHT, noLeft.right)
        assertEquals(1200f - d * 2 - TabletPanes.DEFAULT_RIGHT, noLeft.center)

        val noRight = TabletPanes.resolve(1200f, preferredLeft = 260f, preferredRight = 360f, collapseRight = true)
        assertEquals(TabletPanes.DEFAULT_LEFT, noRight.left)
        assertEquals(0f, noRight.right)

        val neither = TabletPanes.resolve(1200f, collapseLeft = true, collapseRight = true)
        assertEquals(1200f - d * 2, neither.center)
    }

    @Test fun dragsBelowTheThresholdCollapseButNeverAboveThePaneMinimum() {
        // The larger of 12% of the window and 72% of the pane minimum, capped below the minimum.
        assertEquals(TabletPanes.MIN_LEFT * 0.72f, TabletPanes.collapseThreshold(1176f, TabletPanes.MIN_LEFT), 0.01f)
        assertEquals(1600f * 0.12f, TabletPanes.collapseThreshold(1600f, TabletPanes.MIN_LEFT), 0.01f)
        assertEquals(TabletPanes.MIN_LEFT - 1f, TabletPanes.collapseThreshold(4000f, TabletPanes.MIN_LEFT), 0.01f)
    }
}
