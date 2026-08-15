package cn.com.omnimind.assists.recording

import cn.com.omnimind.assists.task.recording.canonicalManualScreenAction
import cn.com.omnimind.assists.task.recording.isManualSystemBackGesture
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualGestureRecognizerTest {
    private val overlayThresholds = ManualGestureThresholds.overlay(
        touchSlopPx = 20f,
        longPressTimeoutMs = 500L,
    )

    @Test
    fun shortStationaryTraceIsClick() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 120L, endX = 108f, endY = 104f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_CLICK, gesture?.actionName)
    }

    @Test
    fun longStationaryTraceIsLongPress() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 700L, endX = 102f, endY = 101f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_LONG_PRESS, gesture?.actionName)
    }

    @Test
    fun displacedTraceIsDirectionalSwipe() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 320L, endX = 100f, endY = 20f),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_SWIPE, gesture?.actionName)
        assertEquals("up", gesture?.direction)
    }

    @Test
    fun horizontalTracesKeepLeftAndRightDirection() {
        val left = ManualGestureRecognizer.recognize(
            trace(durationMs = 240L, endX = 20f, endY = 104f),
            overlayThresholds,
        )
        val right = ManualGestureRecognizer.recognize(
            trace(durationMs = 240L, endX = 260f, endY = 96f),
            overlayThresholds,
        )

        assertEquals("left", left?.direction)
        assertEquals("right", right?.direction)
    }

    @Test
    fun cancelledEdgeSwipeIsKept() {
        val gesture = ManualGestureRecognizer.recognizeCancelledSwipe(
            ManualPointerTrace(
                startX = 1f,
                startY = 600f,
                endX = 280f,
                endY = 604f,
                startedAtMs = 1_000L,
                finishedAtMs = 1_240L,
            ),
            overlayThresholds,
        )

        assertEquals(OobActionSchema.TOOL_SWIPE, gesture?.actionName)
        assertEquals("right", gesture?.direction)
    }

    @Test
    fun edgeBackSwipeSurvivesCanonicalCoordinateRoundTrip() {
        val canonical = canonicalManualScreenAction(
            tool = OobActionSchema.TOOL_SWIPE,
            args = linkedMapOf(
                OobActionSchema.ARG_X1 to 1f,
                OobActionSchema.ARG_Y1 to 1_200f,
                OobActionSchema.ARG_X2 to 320f,
                OobActionSchema.ARG_Y2 to 1_204f,
                OobActionSchema.ARG_DURATION_MS to 240L,
                OobActionSchema.ARG_DIRECTION to "right",
            ),
            displayWidth = 1080,
            displayHeight = 2400,
        )
        val replay = ActionCoordinateCodec.toScreenPixels(
            canonical.args,
            ActionCoordinateCodec.DisplaySize(1080.0, 2400.0),
        )

        assertEquals(1.0, (replay[OobActionSchema.ARG_X1] as Number).toDouble(), 0.001)
        assertEquals(1_200.0, (replay[OobActionSchema.ARG_Y1] as Number).toDouble(), 0.001)
        assertEquals(320.0, (replay[OobActionSchema.ARG_X2] as Number).toDouble(), 0.001)
        assertEquals(1_204.0, (replay[OobActionSchema.ARG_Y2] as Number).toDouble(), 0.001)
        assertEquals("right", replay[OobActionSchema.ARG_DIRECTION])
    }

    @Test
    fun onlyInwardEdgeSwipesBecomeSystemBack() {
        assertEquals(true, isManualSystemBackGesture(overlayGesture(1f, 320f, "right")))
        assertEquals(true, isManualSystemBackGesture(overlayGesture(1_079f, 760f, "left")))
        assertEquals(false, isManualSystemBackGesture(overlayGesture(300f, 700f, "right")))
        assertEquals(false, isManualSystemBackGesture(overlayGesture(1f, 0f, "left")))
    }

    @Test
    fun cancelledShortTouchIsNotRecorded() {
        val gesture = ManualGestureRecognizer.recognizeCancelledSwipe(
            trace(durationMs = 120L, endX = 108f, endY = 104f),
            overlayThresholds,
        )

        assertNull(gesture)
    }

    @Test
    fun rawThresholdGapRejectsAmbiguousMotion() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 300L, endX = 145f, endY = 100f),
            ManualGestureThresholds.rawTouch(),
        )

        assertNull(gesture)
    }

    @Test
    fun rawLongStationaryTracePastMaximumIsRejected() {
        val gesture = ManualGestureRecognizer.recognize(
            trace(durationMs = 2_600L, endX = 100f, endY = 100f),
            ManualGestureThresholds.rawTouch(),
        )

        assertNull(gesture)
    }

    private fun trace(
        durationMs: Long,
        endX: Float,
        endY: Float,
    ): ManualPointerTrace = ManualPointerTrace(
        startX = 100f,
        startY = 100f,
        endX = endX,
        endY = endY,
        startedAtMs = 1_000L,
        finishedAtMs = 1_000L + durationMs,
    )

    private fun overlayGesture(
        startX: Float,
        endX: Float,
        direction: String,
    ) = cn.com.omnimind.assists.ManualOverlayTouchGesture(
        actionName = OobActionSchema.TOOL_SWIPE,
        startX = startX,
        startY = 1_200f,
        endX = endX,
        endY = 1_204f,
        durationMs = 240L,
        distancePx = kotlin.math.abs(endX - startX),
        direction = direction,
        startedAtMs = 1_000L,
        finishedAtMs = 1_240L,
        displayWidth = 1080,
        displayHeight = 2400,
    )
}
