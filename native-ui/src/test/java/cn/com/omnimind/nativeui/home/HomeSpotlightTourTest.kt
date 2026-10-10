package cn.com.omnimind.nativeui.home

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSpotlightTourTest {
    private fun top(hole: Rect, card: Float = 300f) =
        spotlightCardTop(hole, card, containerHeight = 2000f, topInset = 100f, bottomInset = 80f, gap = 16f)

    @Test fun cardSitsBelowATopControl() {
        assertEquals(176f, top(Rect(10f, 110f, 90f, 160f)))
    }

    /** Fix (5f-1c): Dart placed the card by step number, so it could cover a control that moved. */
    @Test fun cardFlipsAboveABottomControl() {
        val composer = Rect(20f, 1700f, 1000f, 1900f)
        val cardTop = top(composer)
        assertEquals(1384f, cardTop)
        assertTrue(cardTop + 300f <= composer.top)
    }

    @Test fun anOversizedCardStaysInsideTheSafeArea() {
        val cardTop = top(Rect(0f, 900f, 100f, 1000f), card = 1800f)
        assertEquals(116f, cardTop)
    }

    @Test fun everyStepNamesADistinctControl() {
        assertEquals(HomeTourAnchor.entries.toSet(), HOME_TOUR_STEPS.map { it.anchor }.toSet())
    }
}
