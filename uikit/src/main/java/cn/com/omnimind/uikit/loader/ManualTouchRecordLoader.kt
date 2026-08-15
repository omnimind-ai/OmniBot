package cn.com.omnimind.uikit.loader

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.AndroidGuiOverlayHost
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.recording.ManualGestureRecognizer
import cn.com.omnimind.assists.recording.ManualGestureThresholds
import cn.com.omnimind.assists.recording.ManualPointerTrace
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.UIKit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlin.math.max

object ManualTouchRecordLoader {
    private const val TAG = "ManualTouchRecordLoader"
    private const val MIN_SWIPE_DISTANCE_DP = 24f
    private const val OVERLAY_UNLOCK_REPLAY_DELAY_MS = 80L
    private const val OVERLAY_REPLAY_TOUCH_SUPPRESS_AFTER_MS = 120L
    private const val IME_RELIABLE_TOP_MIN_RATIO = 0.25f
    private const val IME_RELIABLE_TOP_MAX_RATIO = 0.92f
    private const val GESTURE_PROCESS_BASE_TIMEOUT_MS = 2_500L
    private const val GESTURE_PROCESS_MAX_EXTRA_DURATION_MS = 2_500L
    private const val FINISH_DRAIN_TIMEOUT_MS = 6_000L
    private const val FINISH_DRAIN_POLL_MS = 50L

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentTouchable: Boolean? = null
    private var currentOverlayHeight: Int = 0
    private var displayWidth: Int = 0
    private var displayHeight: Int = 0
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var downAtMs = 0L
    private var isTracking = false
    private var isProcessing = false
    private var isAcceptingGestures = false
    private var syntheticReplayInFlight = false
    private var syntheticReplaySuppressUntilMs = 0L
    private val pendingGestures = ArrayDeque<ManualOverlayTouchGesture>()

    fun show(context: Context? = UIKit.appContext): Boolean {
        val fallbackContext = context ?: UIKit.appContext ?: return false
        val overlayHandle = AndroidGuiOverlayHost.resolve(fallbackContext)
        var shouldEnsureControlsOnTop = false
        val shown = synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                isAcceptingGestures = true
                lockTouchLocked()
                shouldEnsureControlsOnTop = true
                return@synchronized true
            }
            if (tryShowLocked(
                    context = overlayHandle.context,
                    windowType = overlayHandle.windowType,
                    trusted = overlayHandle.trusted,
                )
            ) {
                isAcceptingGestures = true
                shouldEnsureControlsOnTop = true
                return@synchronized true
            }
            OmniLog.w(TAG, "manual touch recording overlay unavailable")
            false
        }
        if (shouldEnsureControlsOnTop) {
            ManualRecordingControlOverlay.ensureOnTop()
        }
        return shown
    }

    fun hide() {
        synchronized(this) {
            hideLocked()
        }
    }

    fun blockTouches(context: Context? = UIKit.appContext): Boolean {
        val shown = show(context)
        if (!shown) return false
        synchronized(this) {
            isAcceptingGestures = false
            isTracking = false
        }
        return true
    }

    fun beginFinishing() {
        synchronized(this) {
            isAcceptingGestures = false
            isTracking = false
        }
    }

    suspend fun awaitIdle(timeoutMs: Long = FINISH_DRAIN_TIMEOUT_MS): Boolean {
        val deadlineMs = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            val idle = synchronized(this) {
                !isTracking &&
                    !isProcessing &&
                    !syntheticReplayInFlight &&
                    pendingGestures.isEmpty()
            }
            if (idle) return true
            if (SystemClock.uptimeMillis() >= deadlineMs) {
                val snapshot = synchronized(this) {
                    "tracking=$isTracking processing=$isProcessing " +
                        "synthetic=$syntheticReplayInFlight pending=${pendingGestures.size}"
                }
                OmniLog.w(TAG, "manual touch drain timeout $snapshot")
                return false
            }
            delay(FINISH_DRAIN_POLL_MS)
        }
    }

    fun prepareForManualAction(): Boolean {
        return synchronized(this) {
            if (isTracking || isProcessing || syntheticReplayInFlight || pendingGestures.isNotEmpty()) {
                return@synchronized false
            }
            hideLocked()
            true
        }
    }

    private fun hideLocked() {
        isAcceptingGestures = false
        isTracking = false
        isProcessing = false
        pendingGestures.clear()
        val view = overlayView
        val manager = windowManager
        overlayView = null
        overlayParams = null
        windowManager = null
        currentTouchable = null
        currentOverlayHeight = 0
        displayWidth = 0
        displayHeight = 0
        syntheticReplayInFlight = false
        syntheticReplaySuppressUntilMs = 0L
        if (view != null && manager != null && view.isAttachedToWindow) {
            runCatching { manager.removeView(view) }
                .onFailure { OmniLog.w(TAG, "hide failed: ${it.message}") }
        }
    }

    private fun tryShowLocked(
        context: Context,
        windowType: Int,
        trusted: Boolean,
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildTouchView(context)
        val displaySize = realDisplaySize(context)
        val params = buildParams(context, windowType, touchable = true)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            currentTouchable = true
            currentOverlayHeight = params.height
            displayWidth = displaySize.x
            displayHeight = displaySize.y
            OmniLog.d(TAG, "manual touch recording overlay shown trusted=$trusted")
            true
        }.getOrElse { error ->
            OmniLog.e(TAG, "show failed trusted=$trusted: ${error.message}", error)
            false
        }
    }

    private fun buildTouchView(context: Context): View {
        val minSwipeDistance = MIN_SWIPE_DISTANCE_DP * context.resources.displayMetrics.density
        val touchSlop = max(
            minSwipeDistance,
            ViewConfiguration.get(context).scaledTouchSlop.toFloat() * 2f
        )
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        return View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (!shouldSuppressReplayTouch(event)) {
                    handleTouchEvent(event, touchSlop, longPressTimeout)
                }
                true
            }
        }
    }

    private fun shouldSuppressReplayTouch(event: MotionEvent): Boolean {
        val suppress = synchronized(this) {
            val now = SystemClock.uptimeMillis()
            syntheticReplayInFlight || now <= syntheticReplaySuppressUntilMs
        }
        if (suppress && (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                )
        ) {
            synchronized(this) { isTracking = false }
        }
        return suppress
    }

    private fun buildParams(
        context: Context,
        windowType: Int,
        touchable: Boolean
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = windowType
            val touchFlag = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                touchFlag
            // Android 12+ can drop pass-through touches below an untrusted app
            // overlay when the overlay window alpha is still opaque.
            alpha = if (touchable) 1f else 0f
            format = PixelFormat.TRANSLUCENT
            val displaySize = realDisplaySize(context)
            width = displaySize.x
            height = displaySize.y
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun handleTouchEvent(
        event: MotionEvent,
        touchSlop: Float,
        longPressTimeout: Long
    ) {
        if (!synchronized(this) { isAcceptingGestures }) {
            isTracking = false
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = clampRawPoint(event)
                startX = point.x
                startY = point.y
                endX = point.x
                endY = point.y
                downAtMs = System.currentTimeMillis()
                isTracking = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
            }
            MotionEvent.ACTION_UP -> {
                if (!isTracking) return
                val point = clampRawPoint(event)
                endX = point.x
                endY = point.y
                val finishedAtMs = System.currentTimeMillis()
                isTracking = false
                val recognized = ManualGestureRecognizer.recognize(
                    trace = ManualPointerTrace(
                        startX = startX,
                        startY = startY,
                        endX = endX,
                        endY = endY,
                        startedAtMs = downAtMs,
                        finishedAtMs = finishedAtMs,
                    ),
                    thresholds = ManualGestureThresholds.overlay(
                        touchSlopPx = touchSlop,
                        longPressTimeoutMs = longPressTimeout,
                    ),
                ) ?: return
                val gesture = ManualOverlayTouchGesture(
                    actionName = recognized.actionName,
                    startX = recognized.startX,
                    startY = recognized.startY,
                    endX = recognized.endX,
                    endY = recognized.endY,
                    durationMs = recognized.durationMs,
                    distancePx = recognized.distancePx,
                    direction = recognized.direction,
                    startedAtMs = recognized.startedAtMs,
                    finishedAtMs = recognized.finishedAtMs,
                    displayWidth = currentDisplaySize().x,
                    displayHeight = currentDisplaySize().y
                )
                enqueueGesture(gesture)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!isTracking) return
                val finishedAtMs = System.currentTimeMillis()
                isTracking = false
                val recognized = ManualGestureRecognizer.recognizeCancelledSwipe(
                    trace = ManualPointerTrace(
                        startX = startX,
                        startY = startY,
                        endX = endX,
                        endY = endY,
                        startedAtMs = downAtMs,
                        finishedAtMs = finishedAtMs,
                    ),
                    thresholds = ManualGestureThresholds.overlay(
                        touchSlopPx = touchSlop,
                        longPressTimeoutMs = longPressTimeout,
                    ),
                ) ?: return
                enqueueGesture(
                    ManualOverlayTouchGesture(
                        actionName = recognized.actionName,
                        startX = recognized.startX,
                        startY = recognized.startY,
                        endX = recognized.endX,
                        endY = recognized.endY,
                        durationMs = recognized.durationMs,
                        distancePx = recognized.distancePx,
                        direction = recognized.direction,
                        startedAtMs = recognized.startedAtMs,
                        finishedAtMs = recognized.finishedAtMs,
                        displayWidth = currentDisplaySize().x,
                        displayHeight = currentDisplaySize().y,
                    ),
                )
            }
        }
    }

    private fun enqueueGesture(gesture: ManualOverlayTouchGesture) {
        val shouldStartWorker = synchronized(this) {
            pendingGestures.addLast(gesture)
            if (isProcessing) {
                false
            } else {
                isProcessing = true
                true
            }
        }
        if (shouldStartWorker) {
            processGestureQueue()
        }
    }

    private fun processGestureQueue() {
        recordScope.launch {
            while (true) {
                val gesture = synchronized(this@ManualTouchRecordLoader) {
                    pendingGestures.pollFirst()
                }
                if (gesture == null) {
                    val shouldContinue = withContext(Dispatchers.Main) {
                        var continueProcessing = false
                        synchronized(this@ManualTouchRecordLoader) {
                            if (pendingGestures.isNotEmpty()) {
                                continueProcessing = true
                            } else {
                                isProcessing = false
                                if (HumanTrajectoryLearningSession.isActive() &&
                                    !HumanTrajectoryLearningSession.isPaused()) {
                                    lockTouchLocked()
                                } else {
                                    hide()
                                }
                            }
                        }
                        continueProcessing
                    }
                    if (shouldContinue) {
                        continue
                    }
                    return@launch
                }
                val keepRecording = withTimeoutOrNull(gestureProcessTimeoutMs(gesture)) {
                    processQueuedGesture(gesture)
                } ?: run {
                    OmniLog.w(
                        TAG,
                        "manual gesture processing timeout action=${gesture.actionName} " +
                            "x=${gesture.startX} y=${gesture.startY} pending=${pendingGestureCount()}"
                    )
                    recoverAfterGestureProcessingTimeout()
                    true
                }
                if (!keepRecording) {
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualTouchRecordLoader) {
                            pendingGestures.clear()
                            isProcessing = false
                            hide()
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private fun gestureProcessTimeoutMs(gesture: ManualOverlayTouchGesture): Long {
        val durationBudget = gesture.durationMs.coerceIn(0L, GESTURE_PROCESS_MAX_EXTRA_DURATION_MS)
        return GESTURE_PROCESS_BASE_TIMEOUT_MS + durationBudget
    }

    private fun pendingGestureCount(): Int = synchronized(this) {
        pendingGestures.size
    }

    private suspend fun recoverAfterGestureProcessingTimeout() {
        withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                endSyntheticReplaySuppressionLocked()
                if (HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()) {
                    lockTouchLocked()
                } else {
                    hide()
                }
            }
        }
    }

    private suspend fun processQueuedGesture(gesture: ManualOverlayTouchGesture): Boolean {
        var sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
            }
        }
        if (!sessionStillActive) return false

        val keyboardBlackBoxGesture = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                isKeyboardBlackBoxGestureLocked(gesture)
            }
        }
        if (keyboardBlackBoxGesture) {
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    lockTouchLocked()
                }
                ManualRecordingControlOverlay.showTransientStatus(
                    ManualRecordingControlOverlay.localizedText(
                        "请使用自动输入栏，或点「动作」补录",
                        "Use the assisted input bar or tap Action",
                    ),
                    1_800L,
                )
            }
            OmniLog.w(
                TAG,
                "manual keyboard touch blocked; use explicit input_text/press_key " +
                    "action=${gesture.actionName} x=${gesture.startX} y=${gesture.startY}"
            )
            return true
        }

        var executed = false
        var recorded = false
        runCatching {
            withContext(Dispatchers.Main) {
                synchronized(this@ManualTouchRecordLoader) {
                    if (overlayView?.isAttachedToWindow == true &&
                        HumanTrajectoryLearningSession.isActive() &&
                        !HumanTrajectoryLearningSession.isPaused()) {
                        beginSyntheticReplaySuppressionLocked()
                        unlockTouchLocked()
                    }
                }
            }
            try {
                delay(OVERLAY_UNLOCK_REPLAY_DELAY_MS)
                val replayResult = HumanTrajectoryLearningSession.recordOverlayGesture(gesture) {
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualTouchRecordLoader) {
                            endSyntheticReplaySuppressionLocked()
                            if (overlayView?.isAttachedToWindow == true &&
                                HumanTrajectoryLearningSession.isActive() &&
                                !HumanTrajectoryLearningSession.isPaused()) {
                                lockTouchLocked()
                            }
                        }
                    }
                }
                executed = replayResult.executed
                recorded = replayResult.recorded
            } finally {
                withContext(Dispatchers.Main) {
                    synchronized(this@ManualTouchRecordLoader) {
                        endSyntheticReplaySuppressionLocked()
                    }
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            OmniLog.w(TAG, "record overlay gesture failed: ${error.message}")
        }

        if (executed && recorded) {
            // Keep recording UI static. Per-gesture indicators/status updates add
            // extra overlay input work and can make the control window ANR.
            if (gesture.actionName == OobActionSchema.TOOL_CLICK) {
                detectAndOfferInput(gesture)
            }
        } else if (executed && !recorded) {
            OmniLog.w(TAG, "manual gesture executed but was not recorded action=${gesture.actionName}")
        }

        sessionStillActive = withContext(Dispatchers.Main) {
            synchronized(this@ManualTouchRecordLoader) {
                val active = HumanTrajectoryLearningSession.isActive() &&
                    !HumanTrajectoryLearningSession.isPaused()
                if (active) {
                    lockTouchLocked()
                }
                active
            }
        }
        if (!sessionStillActive) return false
        return true
    }

    private fun detectAndOfferInput(gesture: ManualOverlayTouchGesture) {
        recordScope.launch {
            HumanTrajectoryLearningSession.detectManualInputTargetAfterClick(
                x = gesture.startX,
                y = gesture.startY,
            )?.let(ManualRecordingControlOverlay::offerInput)
        }
    }

    private fun beginSyntheticReplaySuppressionLocked() {
        syntheticReplayInFlight = true
    }

    private fun endSyntheticReplaySuppressionLocked() {
        syntheticReplayInFlight = false
        syntheticReplaySuppressUntilMs =
            SystemClock.uptimeMillis() + OVERLAY_REPLAY_TOUCH_SUPPRESS_AFTER_MS
    }

    private fun isKeyboardBlackBoxGestureLocked(gesture: ManualOverlayTouchGesture): Boolean {
        val displayHeight = currentDisplaySize().y
        if (displayHeight <= 0) return false
        val keyboardTop = accessibilityImeTopLocked(displayHeight) ?: return false
        if (keyboardTop >= displayHeight) return false
        val gestureY = when (gesture.actionName) {
            OobActionSchema.TOOL_SWIPE -> (gesture.startY + gesture.endY) / 2f
            else -> gesture.startY
        }
        return gestureY >= keyboardTop
    }

    private fun accessibilityImeTopLocked(displayHeight: Int): Int? {
        val context = overlayView?.context ?: UIKit.appContext ?: return null
        val top = AndroidGuiEnvironment(context).inputMethodTop() ?: return null
        return trustedImeTopLocked(top, displayHeight)
    }

    private fun trustedImeTopLocked(top: Int, displayHeight: Int): Int? {
        val minTop = (displayHeight * IME_RELIABLE_TOP_MIN_RATIO).toInt().coerceAtLeast(1)
        val maxTop = (displayHeight * IME_RELIABLE_TOP_MAX_RATIO).toInt()
            .coerceIn(minTop, displayHeight - 1)
        val clamped = top.coerceIn(1, displayHeight - 1)
        return clamped.takeIf { it in minTop..maxTop }
    }

    private fun lockTouchLocked() {
        updateTouchableLocked(touchable = true)
    }

    private fun unlockTouchLocked() {
        updateTouchableLocked(touchable = false)
    }

    private fun updateTouchableLocked(touchable: Boolean) {
        val view = overlayView ?: return
        val manager = windowManager ?: return
        val context = view.context ?: return
        if (!view.isAttachedToWindow) return
        val params = buildParams(
            context = context,
            windowType = overlayParams?.type ?: AndroidGuiOverlayHost.resolve(context).windowType,
            touchable = touchable,
        )
        if (currentTouchable == touchable && currentOverlayHeight == params.height) return
        overlayParams = params
        runCatching { manager.updateViewLayout(view, params) }
            .onSuccess {
                currentTouchable = touchable
                currentOverlayHeight = params.height
                OmniLog.d(
                    TAG,
                    "manual touch overlay updated touchable=$touchable height=${params.height}/$displayHeight"
                )
                if (touchable) {
                    requestControlOverlayTopRefresh()
                }
            }
            .onFailure { OmniLog.w(TAG, "update touchable=$touchable failed: ${it.message}") }
    }

    private fun requestControlOverlayTopRefresh() {
        recordScope.launch(Dispatchers.Main) {
            ManualRecordingControlOverlay.ensureOnTop()
        }
    }

    private fun clampRawPoint(event: MotionEvent): PointF {
        val displaySize = currentDisplaySize()
        val maxX = (displaySize.x - 1).coerceAtLeast(0).toFloat()
        val maxY = (displaySize.y - 1).coerceAtLeast(0).toFloat()
        return PointF(
            event.rawX.coerceIn(0f, maxX),
            event.rawY.coerceIn(0f, maxY)
        )
    }

    private fun currentDisplaySize(): Point {
        val width = displayWidth
        val height = displayHeight
        if (width > 0 && height > 0) return Point(width, height)
        return realDisplaySize(overlayView?.context ?: UIKit.appContext)
    }

    private fun realDisplaySize(context: Context?): Point {
        val safeContext = context ?: UIKit.appContext
        if (safeContext == null) return Point(0, 0)
        return runCatching {
            val manager = safeContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenSize = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(screenSize)
            screenSize
        }.getOrDefault(Point(0, 0))
    }

}
