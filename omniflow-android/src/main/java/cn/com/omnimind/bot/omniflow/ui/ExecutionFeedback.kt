package cn.com.omnimind.bot.omniflow.ui

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.RequiresApi
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import com.tencent.mmkv.MMKV
import kotlin.math.min

internal sealed interface ExecutionActionFeedback {
    val haptic: ExecutionHapticType

    data class Press(
        val x: Float,
        val y: Float,
        val longPress: Boolean,
        val holdDurationMs: Long,
        override val haptic: ExecutionHapticType,
    ) : ExecutionActionFeedback

    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long,
        override val haptic: ExecutionHapticType = ExecutionHapticType.SWIPE,
    ) : ExecutionActionFeedback
}

internal enum class ExecutionHapticType {
    TAP,
    LONG_PRESS,
    SWIPE,
}

internal fun executionActionFeedback(
    action: Action,
    displayWidth: Int,
    displayHeight: Int,
): ExecutionActionFeedback? {
    if (displayWidth <= 0 || displayHeight <= 0) return null

    fun coordinate(name: String, dimension: Int): Float? {
        val value = (action.args[name] as? Number)?.toDouble()
            ?.takeIf(Double::isFinite)
            ?.takeIf { it in 0.0..CANONICAL_COORDINATE_MAX }
            ?: return null
        return (value / CANONICAL_COORDINATE_MAX * dimension).toFloat()
    }

    fun duration(defaultValue: Long): Long =
        (action.args[OobActionSchema.ARG_DURATION_MS] as? Number)?.toLong()
            ?.takeIf { it > 0L }
            ?: defaultValue

    return when (action.tool) {
        OobActionSchema.TOOL_CLICK -> ExecutionActionFeedback.Press(
            x = coordinate(OobActionSchema.ARG_X, displayWidth) ?: return null,
            y = coordinate(OobActionSchema.ARG_Y, displayHeight) ?: return null,
            longPress = false,
            holdDurationMs = TAP_HOLD_DURATION_MS,
            haptic = ExecutionHapticType.TAP,
        )

        OobActionSchema.TOOL_LONG_PRESS -> {
            val actionDuration = duration(DEFAULT_LONG_PRESS_DURATION_MS)
            val holdDuration = (
                actionDuration - PRESS_POP_IN_DURATION_MS - PRESS_FADE_OUT_DURATION_MS
            ).coerceIn(MIN_LONG_PRESS_HOLD_MS, MAX_LONG_PRESS_HOLD_MS)
            ExecutionActionFeedback.Press(
                x = coordinate(OobActionSchema.ARG_X, displayWidth) ?: return null,
                y = coordinate(OobActionSchema.ARG_Y, displayHeight) ?: return null,
                longPress = true,
                holdDurationMs = holdDuration,
                haptic = ExecutionHapticType.LONG_PRESS,
            )
        }

        OobActionSchema.TOOL_SWIPE -> ExecutionActionFeedback.Swipe(
            x1 = coordinate(OobActionSchema.ARG_X1, displayWidth) ?: return null,
            y1 = coordinate(OobActionSchema.ARG_Y1, displayHeight) ?: return null,
            x2 = coordinate(OobActionSchema.ARG_X2, displayWidth) ?: return null,
            y2 = coordinate(OobActionSchema.ARG_Y2, displayHeight) ?: return null,
            durationMs = duration(DEFAULT_SWIPE_DURATION_MS).coerceIn(
                MIN_SWIPE_FEEDBACK_DURATION_MS,
                MAX_SWIPE_FEEDBACK_DURATION_MS,
            ),
        )

        else -> null
    }
}

internal fun executionFeedbackInView(
    feedback: ExecutionActionFeedback,
    viewLeftOnScreen: Int,
    viewTopOnScreen: Int,
): ExecutionActionFeedback {
    val offsetX = viewLeftOnScreen.toFloat()
    val offsetY = viewTopOnScreen.toFloat()
    return when (feedback) {
        is ExecutionActionFeedback.Press -> feedback.copy(
            x = feedback.x - offsetX,
            y = feedback.y - offsetY,
        )

        is ExecutionActionFeedback.Swipe -> feedback.copy(
            x1 = feedback.x1 - offsetX,
            y1 = feedback.y1 - offsetY,
            x2 = feedback.x2 - offsetX,
            y2 = feedback.y2 - offsetY,
        )
    }
}

internal data class ExecutionDisplayShapeTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float,
)

internal fun executionDisplayShapeTransform(
    sourceLeft: Float,
    sourceTop: Float,
    sourceRight: Float,
    sourceBottom: Float,
    targetWidth: Int,
    targetHeight: Int,
): ExecutionDisplayShapeTransform? {
    val sourceWidth = sourceRight - sourceLeft
    val sourceHeight = sourceBottom - sourceTop
    if (
        !sourceWidth.isFinite() || !sourceHeight.isFinite() ||
        sourceWidth <= 0f || sourceHeight <= 0f ||
        targetWidth <= 0 || targetHeight <= 0
    ) {
        return null
    }
    val scaleX = targetWidth / sourceWidth
    val scaleY = targetHeight / sourceHeight
    return ExecutionDisplayShapeTransform(
        scaleX = scaleX,
        scaleY = scaleY,
        translateX = -sourceLeft * scaleX,
        translateY = -sourceTop * scaleY,
    )
}

internal class ExecutionFeedbackView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val edgePath = Path()
    private val shaderMatrix = Matrix()
    private val shapeMatrix = Matrix()
    private val shapeBounds = RectF()
    private val viewLocation = IntArray(2)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EDGE_GLOW_WIDTH_PX
        maskFilter = BlurMaskFilter(EDGE_GLOW_WIDTH_PX, BlurMaskFilter.Blur.NORMAL)
    }
    private val gesturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GESTURE_COLOR
        strokeCap = Paint.Cap.ROUND
    }
    private val overshoot = OvershootInterpolator(2f)

    private var running = true
    private var edgeRotation = 0f
    private var edgeShader: SweepGradient? = null
    private var rotationAnimator: ValueAnimator? = null
    private var gestureAnimator: ValueAnimator? = null
    private var gesture: ExecutionActionFeedback? = null
    private var gestureProgress = 0f
    private var gestureAlpha = 1f
    private var gestureScale = 1f
    private var gestureGeneration = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        if (running) startRotation()
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        stopRotation()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateEdgePath(rootWindowInsets)
        edgeShader = if (width > 0 && height > 0) {
            SweepGradient(
                width / 2f,
                height / 2f,
                RAINBOW_COLORS,
                RAINBOW_POSITIONS,
            )
        } else {
            null
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateEdgePath(insets)
        return insets
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        canvas.drawColor(DIM_COLOR)
        drawEdgeGlow(canvas)
        when (val activeGesture = gesture) {
            is ExecutionActionFeedback.Press -> drawPress(canvas, activeGesture)
            is ExecutionActionFeedback.Swipe -> drawSwipe(canvas, activeGesture)
            null -> Unit
        }
    }

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (value) {
            startRotation()
        } else {
            cancelGesture()
            stopRotation()
        }
        invalidate()
    }

    fun show(feedback: ExecutionActionFeedback) {
        if (!running) return
        cancelGesture()
        getLocationOnScreen(viewLocation)
        val localFeedback = executionFeedbackInView(
            feedback = feedback,
            viewLeftOnScreen = viewLocation[0],
            viewTopOnScreen = viewLocation[1],
        )
        gesture = localFeedback
        gestureProgress = 0f
        gestureAlpha = 1f
        gestureScale = 0.5f
        ExecutionHapticFeedback.perform(context, feedback.haptic)

        val generation = ++gestureGeneration
        val gestureDurationMs = when (localFeedback) {
            is ExecutionActionFeedback.Press ->
                PRESS_POP_IN_DURATION_MS + localFeedback.holdDurationMs + PRESS_FADE_OUT_DURATION_MS
            is ExecutionActionFeedback.Swipe ->
                localFeedback.durationMs + SWIPE_FADE_OUT_DURATION_MS
        }
        gestureAnimator = ValueAnimator.ofFloat(0f, gestureDurationMs.toFloat()).apply {
            duration = gestureDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                updateGesture(localFeedback, animation.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (gestureGeneration == generation) {
                        gesture = null
                        gestureAnimator = null
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun drawEdgeGlow(canvas: Canvas) {
        val shader = edgeShader ?: return
        shaderMatrix.setRotate(edgeRotation, width / 2f, height / 2f)
        shader.setLocalMatrix(shaderMatrix)
        edgePaint.shader = shader
        canvas.drawPath(edgePath, edgePaint)
    }

    private fun updateEdgePath(insets: WindowInsets?) {
        edgePath.reset()
        if (width <= 0 || height <= 0) return

        getLocationInWindow(viewLocation)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            insets != null &&
            updateRoundedCornerPath(insets)
        ) {
            invalidate()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val displayPath = insets?.displayShape?.path
            if (displayPath != null && !displayPath.isEmpty) {
                displayPath.computeBounds(shapeBounds, true)
                val transform = executionDisplayShapeTransform(
                    sourceLeft = shapeBounds.left,
                    sourceTop = shapeBounds.top,
                    sourceRight = shapeBounds.right,
                    sourceBottom = shapeBounds.bottom,
                    targetWidth = width,
                    targetHeight = height,
                )
                if (transform != null) {
                    shapeMatrix.setValues(
                        floatArrayOf(
                            transform.scaleX,
                            0f,
                            transform.translateX,
                            0f,
                            transform.scaleY,
                            transform.translateY,
                            0f,
                            0f,
                            1f,
                        ),
                    )
                    edgePath.set(displayPath)
                    edgePath.transform(shapeMatrix)
                    invalidate()
                    return
                }
            }
        }

        edgePath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun updateRoundedCornerPath(insets: WindowInsets): Boolean {
        val topLeft = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        val bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        if (listOf(topLeft, topRight, bottomRight, bottomLeft).all { it == null }) {
            return false
        }

        val left = viewLocation[0].toFloat()
        val top = viewLocation[1].toFloat()
        val right = left + width
        val bottom = top + height

        edgePath.moveTo(topLeft?.center?.x?.toFloat() ?: left, top)
        edgePath.lineTo(topRight?.center?.x?.toFloat() ?: right, top)
        topRight?.let { edgePath.arcTo(it.bounds(), -90f, 90f) }
        edgePath.lineTo(right, bottomRight?.center?.y?.toFloat() ?: bottom)
        bottomRight?.let { edgePath.arcTo(it.bounds(), 0f, 90f) }
        edgePath.lineTo(bottomLeft?.center?.x?.toFloat() ?: left, bottom)
        bottomLeft?.let { edgePath.arcTo(it.bounds(), 90f, 90f) }
        edgePath.lineTo(left, topLeft?.center?.y?.toFloat() ?: top)
        topLeft?.let { edgePath.arcTo(it.bounds(), 180f, 90f) }
        edgePath.close()
        edgePath.offset(-left, -top)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun RoundedCorner.bounds(): RectF {
        val radius = radius.toFloat()
        return RectF(
            center.x - radius,
            center.y - radius,
            center.x + radius,
            center.y + radius,
        )
    }

    private fun drawPress(canvas: Canvas, press: ExecutionActionFeedback.Press) {
        val ringRadius = 20f * density * gestureScale

        gesturePaint.style = Paint.Style.FILL
        gesturePaint.alpha = (gestureAlpha * if (press.longPress) 0x38 else 0x2A).toInt()
        canvas.drawCircle(press.x, press.y, ringRadius, gesturePaint)

        gesturePaint.style = Paint.Style.STROKE
        gesturePaint.strokeWidth = (if (press.longPress) 2.5f else 2f) * density
        gesturePaint.alpha = (gestureAlpha * 255).toInt()
        canvas.drawCircle(press.x, press.y, ringRadius, gesturePaint)

        gesturePaint.style = Paint.Style.FILL
        gesturePaint.alpha = (gestureAlpha * 230).toInt()
        canvas.drawCircle(
            press.x,
            press.y,
            (if (press.longPress) 4f else 3f) * density * gestureScale,
            gesturePaint,
        )

        if (press.longPress) {
            gesturePaint.style = Paint.Style.STROKE
            gesturePaint.strokeWidth = density
            gesturePaint.alpha = (gestureAlpha * 110).toInt()
            canvas.drawCircle(press.x, press.y, 25f * density * gestureScale, gesturePaint)
        }
    }

    private fun drawSwipe(canvas: Canvas, swipe: ExecutionActionFeedback.Swipe) {
        val currentX = swipe.x1 + (swipe.x2 - swipe.x1) * gestureProgress
        val currentY = swipe.y1 + (swipe.y2 - swipe.y1) * gestureProgress

        gesturePaint.style = Paint.Style.STROKE
        gesturePaint.strokeWidth = 2f * density
        gesturePaint.alpha = (gestureAlpha * 0.12f * 255).toInt()
        canvas.drawLine(swipe.x1, swipe.y1, swipe.x2, swipe.y2, gesturePaint)

        gesturePaint.strokeWidth = 3f * density
        gesturePaint.alpha = (gestureAlpha * 0.62f * 255).toInt()
        canvas.drawLine(swipe.x1, swipe.y1, currentX, currentY, gesturePaint)

        gesturePaint.style = Paint.Style.FILL
        gesturePaint.alpha = (gestureAlpha * 0.9f * 255).toInt()
        canvas.drawCircle(currentX, currentY, 7f * density, gesturePaint)

        gesturePaint.style = Paint.Style.STROKE
        gesturePaint.strokeWidth = 1.5f * density
        gesturePaint.alpha = (gestureAlpha * 0.48f * 255).toInt()
        canvas.drawCircle(currentX, currentY, 13f * density, gesturePaint)

        gesturePaint.style = Paint.Style.FILL
        gesturePaint.alpha = (gestureAlpha * 0.5f * 255).toInt()
        canvas.drawCircle(
            swipe.x1,
            swipe.y1,
            min(4f * density, 4f * density * (1f - gestureProgress) + density),
            gesturePaint,
        )
    }

    private fun updateGesture(feedback: ExecutionActionFeedback, elapsedMs: Float) {
        when (feedback) {
            is ExecutionActionFeedback.Press -> {
                val fadeStartMs = PRESS_POP_IN_DURATION_MS + feedback.holdDurationMs
                when {
                    elapsedMs < PRESS_POP_IN_DURATION_MS -> {
                        val progress = elapsedMs / PRESS_POP_IN_DURATION_MS
                        gestureAlpha = progress.coerceIn(0f, 1f)
                        gestureScale = 0.5f + 0.5f * overshoot.getInterpolation(progress)
                    }
                    elapsedMs < fadeStartMs -> {
                        gestureAlpha = 1f
                        gestureScale = 1f
                    }
                    else -> {
                        val progress = ((elapsedMs - fadeStartMs) / PRESS_FADE_OUT_DURATION_MS)
                            .coerceIn(0f, 1f)
                        gestureAlpha = 1f - progress
                        gestureScale = 1f + progress * if (feedback.longPress) 0.12f else 0.06f
                    }
                }
            }

            is ExecutionActionFeedback.Swipe -> {
                gestureProgress = (elapsedMs / feedback.durationMs).coerceIn(0f, 1f)
                gestureAlpha = if (elapsedMs <= feedback.durationMs) {
                    1f
                } else {
                    1f - ((elapsedMs - feedback.durationMs) / SWIPE_FADE_OUT_DURATION_MS)
                        .coerceIn(0f, 1f)
                }
            }
        }
        invalidate()
    }

    private fun startRotation() {
        if (!isAttachedToWindow || rotationAnimator != null) return
        rotationAnimator = ValueAnimator.ofFloat(edgeRotation, edgeRotation + 360f).apply {
            duration = EDGE_ROTATION_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                edgeRotation = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
    }

    private fun cancelGesture() {
        gestureGeneration++
        gestureAnimator?.removeAllListeners()
        gestureAnimator?.cancel()
        gestureAnimator = null
        gesture = null
    }
}

private object ExecutionHapticFeedback {
    // VIBRATE is declared by the host app and baselib manifests. This library's
    // intentionally empty manifest makes standalone module lint unable to see it.
    @SuppressLint("MissingPermission")
    fun perform(context: Context, type: ExecutionHapticType) {
        if (!appHapticsEnabled() || !systemHapticsEnabled(context)) return
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return

        runCatching {
            vibrator.vibrate(effect(vibrator, type))
        }
    }

    private fun appHapticsEnabled(): Boolean =
        runCatching { MMKV.defaultMMKV().decodeBool(APP_VIBRATE_KEY, true) }.getOrDefault(true)

    private fun systemHapticsEnabled(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0
    }.getOrDefault(true)

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun effect(vibrator: Vibrator, type: ExecutionHapticType): VibrationEffect {
        val fallback = when (type) {
            ExecutionHapticType.TAP,
            ExecutionHapticType.SWIPE,
            -> VibrationEffect.EFFECT_TICK
            ExecutionHapticType.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return VibrationEffect.createPredefined(fallback)
        }

        val primitive = when (type) {
            ExecutionHapticType.TAP -> VibrationEffect.Composition.PRIMITIVE_CLICK to 0.45f
            ExecutionHapticType.LONG_PRESS ->
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK to 0.7f
            ExecutionHapticType.SWIPE -> VibrationEffect.Composition.PRIMITIVE_TICK to 0.32f
        }
        return if (vibrator.arePrimitivesSupported(primitive.first).firstOrNull() == true) {
            VibrationEffect.startComposition()
                .addPrimitive(primitive.first, primitive.second)
                .compose()
        } else {
            VibrationEffect.createPredefined(fallback)
        }
    }
}

private const val CANONICAL_COORDINATE_MAX = 1000.0
private const val DEFAULT_LONG_PRESS_DURATION_MS = 800L
private const val DEFAULT_SWIPE_DURATION_MS = 300L
private const val PRESS_POP_IN_DURATION_MS = 200L
private const val PRESS_FADE_OUT_DURATION_MS = 180L
private const val TAP_HOLD_DURATION_MS = 100L
private const val MIN_LONG_PRESS_HOLD_MS = 240L
private const val MAX_LONG_PRESS_HOLD_MS = 620L
private const val MIN_SWIPE_FEEDBACK_DURATION_MS = 300L
private const val MAX_SWIPE_FEEDBACK_DURATION_MS = 10_000L
private const val SWIPE_FADE_OUT_DURATION_MS = 160L
private const val EDGE_GLOW_WIDTH_PX = 40f
private const val EDGE_ROTATION_DURATION_MS = 5_000L
private const val APP_VIBRATE_KEY = "app_vibrate"
private const val GESTURE_COLOR = 0xFF2879FB.toInt()
private const val DIM_COLOR = 0x4F000000

private val RAINBOW_COLORS = intArrayOf(
    0xFFB0F2FF.toInt(),
    0xFFFAFAA3.toInt(),
    0xFFFFB472.toInt(),
    0xFFFB8DFF.toInt(),
    0xFFB0F2FF.toInt(),
    0xFFFB8DFF.toInt(),
    0xFFFFB472.toInt(),
    0xFFFAFAA3.toInt(),
    0xFFB0F2FF.toInt(),
)

private val RAINBOW_POSITIONS = floatArrayOf(
    0f,
    0.13f,
    0.257f,
    0.37f,
    0.505f,
    0.634f,
    0.744f,
    0.87f,
    1f,
)
