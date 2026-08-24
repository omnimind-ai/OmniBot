package cn.com.omnimind.bot.omniflow.ui

import android.view.WindowManager
import cn.com.omnimind.baselib.runlog.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionFeedbackTest {
    @Test
    fun `click maps canonical coordinates to screen pixels`() {
        val feedback = executionActionFeedback(
            action = action("click", "x" to 500, "y" to 250),
            displayWidth = 1080,
            displayHeight = 2400,
        ) as ExecutionActionFeedback.Press

        assertEquals(540f, feedback.x, 0.001f)
        assertEquals(600f, feedback.y, 0.001f)
        assertEquals(false, feedback.longPress)
        assertEquals(ExecutionHapticType.TAP, feedback.haptic)
    }

    @Test
    fun `long press aligns its visible hold with action duration`() {
        val feedback = executionActionFeedback(
            action = action("long_press", "x" to 0, "y" to 1000, "duration_ms" to 800),
            displayWidth = 1080,
            displayHeight = 2400,
        ) as ExecutionActionFeedback.Press

        assertEquals(0f, feedback.x, 0.001f)
        assertEquals(2400f, feedback.y, 0.001f)
        assertEquals(true, feedback.longPress)
        assertEquals(420L, feedback.holdDurationMs)
        assertEquals(ExecutionHapticType.LONG_PRESS, feedback.haptic)
    }

    @Test
    fun `swipe maps both endpoints and keeps the gesture duration`() {
        val feedback = executionActionFeedback(
            action = action(
                "swipe",
                "x1" to 100,
                "y1" to 900,
                "x2" to 700,
                "y2" to 200,
                "duration_ms" to 560,
            ),
            displayWidth = 1000,
            displayHeight = 2000,
        ) as ExecutionActionFeedback.Swipe

        assertEquals(100f, feedback.x1, 0.001f)
        assertEquals(1800f, feedback.y1, 0.001f)
        assertEquals(700f, feedback.x2, 0.001f)
        assertEquals(400f, feedback.y2, 0.001f)
        assertEquals(560L, feedback.durationMs)
        assertEquals(ExecutionHapticType.SWIPE, feedback.haptic)
    }

    @Test
    fun `swipe feedback duration is bounded`() {
        val short = executionActionFeedback(
            action = action(
                "swipe",
                "x1" to 0,
                "y1" to 0,
                "x2" to 1000,
                "y2" to 1000,
                "duration_ms" to 1,
            ),
            displayWidth = 100,
            displayHeight = 200,
        ) as ExecutionActionFeedback.Swipe
        val long = executionActionFeedback(
            action = action(
                "swipe",
                "x1" to 0,
                "y1" to 0,
                "x2" to 1000,
                "y2" to 1000,
                "duration_ms" to 60_000,
            ),
            displayWidth = 100,
            displayHeight = 200,
        ) as ExecutionActionFeedback.Swipe

        assertEquals(300L, short.durationMs)
        assertEquals(10_000L, long.durationMs)
    }

    @Test
    fun `invalid or non gesture actions do not create feedback`() {
        assertNull(
            executionActionFeedback(
                action = action("click", "x" to -1, "y" to 500),
                displayWidth = 1080,
                displayHeight = 2400,
            ),
        )
        assertNull(
            executionActionFeedback(
                action = action("click", "x" to Double.NaN, "y" to 500),
                displayWidth = 1080,
                displayHeight = 2400,
            ),
        )
        assertNull(
            executionActionFeedback(
                action = action("input_text", "text" to "hello", "x" to 500, "y" to 500),
                displayWidth = 1080,
                displayHeight = 2400,
            ),
        )
        assertNull(
            executionActionFeedback(
                action = action("click", "x" to 500, "y" to 500),
                displayWidth = 0,
                displayHeight = 2400,
            ),
        )
    }

    @Test
    fun `feedback window is full touch through`() {
        val flags = executionFeedbackWindowFlags()

        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
    }

    @Test
    fun `feedback window extends through system bars and display cutouts`() {
        val androidTen = executionFeedbackWindowPolicy(29)
        val androidEleven = executionFeedbackWindowPolicy(30)

        assertNull(androidTen.fitInsetsTypes)
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
            androidTen.layoutInDisplayCutoutMode,
        )
        assertEquals(0, androidEleven.fitInsetsTypes)
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            androidEleven.layoutInDisplayCutoutMode,
        )
    }

    @Test
    fun `screen feedback coordinates are translated into view coordinates`() {
        val press = executionFeedbackInView(
            feedback = ExecutionActionFeedback.Press(
                x = 720f,
                y = 1568f,
                longPress = false,
                holdDurationMs = 100L,
                haptic = ExecutionHapticType.TAP,
            ),
            viewLeftOnScreen = 0,
            viewTopOnScreen = 137,
        ) as ExecutionActionFeedback.Press
        val swipe = executionFeedbackInView(
            feedback = ExecutionActionFeedback.Swipe(
                x1 = 100f,
                y1 = 200f,
                x2 = 900f,
                y2 = 1800f,
                durationMs = 500L,
            ),
            viewLeftOnScreen = 24,
            viewTopOnScreen = 137,
        ) as ExecutionActionFeedback.Swipe

        assertEquals(720f, press.x, 0.001f)
        assertEquals(1431f, press.y, 0.001f)
        assertEquals(76f, swipe.x1, 0.001f)
        assertEquals(63f, swipe.y1, 0.001f)
        assertEquals(876f, swipe.x2, 0.001f)
        assertEquals(1663f, swipe.y2, 0.001f)
    }

    @Test
    fun `display shape is normalized from its own coordinate space`() {
        val transform = requireNotNull(
            executionDisplayShapeTransform(
                sourceLeft = 0f,
                sourceTop = 0f,
                sourceRight = 1080f,
                sourceBottom = 2400f,
                targetWidth = 1440,
                targetHeight = 3136,
            ),
        )

        assertEquals(4f / 3f, transform.scaleX, 0.001f)
        assertEquals(3136f / 2400f, transform.scaleY, 0.001f)
        assertEquals(0f, transform.translateX, 0.001f)
        assertEquals(0f, transform.translateY, 0.001f)
    }

    @Test
    fun `invalid display shape bounds are rejected`() {
        assertNull(
            executionDisplayShapeTransform(
                sourceLeft = 0f,
                sourceTop = 0f,
                sourceRight = 0f,
                sourceBottom = 2400f,
                targetWidth = 1440,
                targetHeight = 3136,
            ),
        )
    }

    private fun action(tool: String, vararg args: Pair<String, Any?>): Action =
        Action(tool = tool, args = linkedMapOf(*args))
}
