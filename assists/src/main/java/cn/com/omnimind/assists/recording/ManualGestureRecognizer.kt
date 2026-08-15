package cn.com.omnimind.assists.recording

import cn.com.omnimind.baselib.runlog.OobActionSchema
import kotlin.math.abs
import kotlin.math.hypot

data class ManualPointerTrace(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val startedAtMs: Long,
    val finishedAtMs: Long,
)

data class ManualGestureThresholds(
    val stationarySlopPx: Float,
    val swipeMinDistancePx: Float,
    val clickMaxDurationMs: Long,
    val longPressMinDurationMs: Long,
    val longPressMaxDurationMs: Long? = null,
) {
    companion object {
        fun overlay(
            touchSlopPx: Float,
            longPressTimeoutMs: Long,
        ): ManualGestureThresholds = ManualGestureThresholds(
            stationarySlopPx = touchSlopPx,
            swipeMinDistancePx = touchSlopPx,
            clickMaxDurationMs = (longPressTimeoutMs - 1L).coerceAtLeast(0L),
            longPressMinDurationMs = longPressTimeoutMs.coerceAtLeast(0L),
        )

        fun rawTouch(): ManualGestureThresholds = ManualGestureThresholds(
            stationarySlopPx = 28f,
            swipeMinDistancePx = 80f,
            clickMaxDurationMs = 800L,
            longPressMinDurationMs = 801L,
            longPressMaxDurationMs = 2_500L,
        )
    }
}

data class ManualRecognizedGesture(
    val actionName: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val durationMs: Long,
    val distancePx: Float,
    val direction: String?,
)

object ManualGestureRecognizer {
    fun recognizeCancelledSwipe(
        trace: ManualPointerTrace,
        thresholds: ManualGestureThresholds,
    ): ManualRecognizedGesture? = recognize(trace, thresholds)
        ?.takeIf { it.actionName == OobActionSchema.TOOL_SWIPE }

    fun recognize(
        trace: ManualPointerTrace,
        thresholds: ManualGestureThresholds,
    ): ManualRecognizedGesture? {
        val durationMs = (trace.finishedAtMs - trace.startedAtMs).coerceAtLeast(0L)
        val distancePx = hypot(
            (trace.endX - trace.startX).toDouble(),
            (trace.endY - trace.startY).toDouble(),
        ).toFloat()
        val actionName = when {
            distancePx >= thresholds.swipeMinDistancePx -> OobActionSchema.TOOL_SWIPE
            distancePx > thresholds.stationarySlopPx -> return null
            durationMs <= thresholds.clickMaxDurationMs -> OobActionSchema.TOOL_CLICK
            durationMs >= thresholds.longPressMinDurationMs &&
                (thresholds.longPressMaxDurationMs == null ||
                    durationMs <= thresholds.longPressMaxDurationMs) -> OobActionSchema.TOOL_LONG_PRESS
            else -> return null
        }
        return ManualRecognizedGesture(
            actionName = actionName,
            startX = trace.startX,
            startY = trace.startY,
            endX = trace.endX,
            endY = trace.endY,
            startedAtMs = trace.startedAtMs,
            finishedAtMs = trace.finishedAtMs,
            durationMs = durationMs,
            distancePx = distancePx,
            direction = directionName(trace).takeIf { actionName == OobActionSchema.TOOL_SWIPE },
        )
    }

    private fun directionName(trace: ManualPointerTrace): String {
        val deltaX = trace.endX - trace.startX
        val deltaY = trace.endY - trace.startY
        return if (abs(deltaX) > abs(deltaY)) {
            if (deltaX > 0f) "right" else "left"
        } else {
            if (deltaY > 0f) "down" else "up"
        }
    }
}
