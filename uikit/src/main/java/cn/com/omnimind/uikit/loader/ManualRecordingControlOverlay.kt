package cn.com.omnimind.uikit.loader

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.AndroidGuiOverlayHost
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.dpToPx
import cn.com.omnimind.uikit.UIKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ManualRecordingControlOverlay {
    private const val TAG = "ManualRecordingControlOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var state: State = State.READY
    private val recordingControlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastOverlayX: Int? = null
    private var lastOverlayY: Int? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragging = false
    private var transientStatusToken = 0
    private var manualActionDialogShowing = false
    private var captureStateCallback: (suspend () -> Map<String, Any?>)? = null
    private var sessionRunId: String? = null
    private var suppressUnexpectedDetach = false

    enum class State {
        PREPARING,
        READY,
        RECORDING,
        PAUSED
    }

    fun show(
        context: Context? = UIKit.appContext,
        runId: String,
        state: State = State.READY,
        onCaptureState: (suspend () -> Map<String, Any?>)? = null
    ): Boolean {
        require(runId.isNotBlank()) { "manual_recording_run_id_required" }
        this.state = state
        val fallbackContext = context ?: UIKit.appContext ?: return false
        val overlayHandle = AndroidGuiOverlayHost.resolve(fallbackContext)
        return synchronized(this) {
            if (overlayView?.isAttachedToWindow == true) {
                sessionRunId = runId
                captureStateCallback = onCaptureState
                bindState(overlayView, state)
                return@synchronized true
            }
            dismissLocked()
            sessionRunId = runId
            captureStateCallback = onCaptureState
            var shown = tryShow(
                context = overlayHandle.context,
                windowType = overlayHandle.windowType,
                trusted = overlayHandle.trusted,
                state = state,
            )
            if (!shown && overlayHandle.trusted && Settings.canDrawOverlays(fallbackContext)) {
                shown = tryShow(
                    context = fallbackContext.applicationContext ?: fallbackContext,
                    windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    trusted = false,
                    state = state,
                )
            }
            if (!shown) {
                sessionRunId = null
                captureStateCallback = null
            }
            shown
        }
    }

    /**
     * Shows the overlay in active recording state.
     */
    fun markRecording() {
        val context = synchronized(this) {
            state = State.RECORDING
            bindState(overlayView, State.RECORDING)
            overlayView?.context
        }
        val shown = ManualTouchRecordLoader.show(context ?: UIKit.appContext)
        if (shown) {
            keepControlsAboveTouchRecorder()
        } else {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.pauseActive()
                withContext(Dispatchers.Main) {
                    markPaused()
                    showTransientStatus(
                        localizedText("开启悬浮窗权限", "Allow display over other apps"),
                        1400L,
                    )
                }
            }
        }
    }

    /**
     * Shows the overlay in ready state before event capture starts.
     */
    fun markReady() {
        synchronized(this) {
            state = State.READY
            bindState(overlayView, State.READY)
        }
        ManualTouchRecordLoader.hide()
    }

    /**
     * Shows the overlay in paused state.
     */
    fun markPaused() {
        synchronized(this) {
            state = State.PAUSED
            bindState(overlayView, State.PAUSED)
        }
        ManualTouchRecordLoader.hide()
    }

    fun dismiss() {
        val runId = synchronized(this) { sessionRunId }
        val cancelledMessage = localizedText(
            "录制窗口关闭，轨迹学习已取消",
            "Recording window closed. Manual recording cancelled.",
        )
        synchronized(this) {
            dismissLocked()
        }
        // Cancel any active session that was never explicitly completed.
        // This covers force-dismissal paths (back press, system overlay kill, etc.)
        // where the Finish button was never tapped.
        if (runId != null) {
            recordingControlScope.launch {
                HumanTrajectoryLearningSession.cancelActive(
                    expectedRunId = runId,
                    message = cancelledMessage,
                )
            }
        }
    }

    fun cancelRecording(message: String? = null) {
        val runId = synchronized(this) { sessionRunId }
        val cancelledMessage = message ?: localizedText(
            "人工轨迹学习已取消",
            "Manual recording cancelled.",
        )
        synchronized(this) {
            dismissLocked()
        }
        recordingControlScope.launch {
            val updated = runCatching {
                runId != null && HumanTrajectoryLearningSession.cancelActive(
                    expectedRunId = runId,
                    message = cancelledMessage,
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "cancel manual recording failed: ${error.message}", error)
                false
            }
            if (!updated) {
                OmniLog.w(TAG, "cancel requested without active manual recording session")
            }
        }
    }

    private fun dismissLocked() {
        ManualTouchRecordLoader.hide()
        val view = overlayView
        val manager = windowManager
        val params = overlayParams
        if (params != null) {
            lastOverlayX = params.x
            lastOverlayY = params.y
        }
        overlayView = null
        windowManager = null
        overlayParams = null
        captureStateCallback = null
        sessionRunId = null
        if (view != null && manager != null && view.isAttachedToWindow) {
            suppressUnexpectedDetach = true
            try {
                runCatching { manager.removeView(view) }
                    .onFailure { OmniLog.w(TAG, "dismiss failed: ${it.message}") }
            } finally {
                suppressUnexpectedDetach = false
            }
        }
    }

    fun ensureOnTop() {
        synchronized(this) {
            keepControlsAboveTouchRecorderLocked()
        }
    }

    fun showTransientStatus(message: String, durationMs: Long = 800L) {
        val token = synchronized(this) {
            transientStatusToken += 1
            transientStatusToken
        }
        recordingControlScope.launch {
            withContext(Dispatchers.Main) {
                setTitleText(message)
            }
            delay(durationMs)
            withContext(Dispatchers.Main) {
                synchronized(this@ManualRecordingControlOverlay) {
                    if (transientStatusToken == token) {
                        bindState(overlayView, state)
                    }
                }
            }
        }
    }

    fun offerInput(target: ManualInputTarget) {
        if (target.password) {
            showTransientStatus(
                localizedText("密码输入不录制", "Password input is not recorded"),
                1_400L,
            )
            return
        }
        recordingControlScope.launch {
            if (!ManualTouchRecordLoader.awaitIdle()) return@launch
            withContext(Dispatchers.Main) {
                val context = synchronized(this@ManualRecordingControlOverlay) {
                    if (state != State.RECORDING || manualActionDialogShowing) return@withContext
                    overlayView?.context ?: UIKit.appContext
                } ?: return@withContext
                if (!ManualTouchRecordLoader.prepareForManualAction()) return@withContext
                val inputAvailable = synchronized(this@ManualRecordingControlOverlay) {
                    if (state != State.RECORDING || manualActionDialogShowing) {
                        false
                    } else {
                        manualActionDialogShowing = true
                        true
                    }
                }
                if (!inputAvailable) {
                    return@withContext
                }
                showManualInputTextDialog(context, target)
            }
        }
    }

    private fun tryShow(
        context: Context,
        windowType: Int,
        trusted: Boolean,
        state: State,
    ): Boolean {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = buildView(context)
        bindState(view, state)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams().apply {
            type = windowType
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            format = android.graphics.PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or Gravity.START
            x = lastOverlayX ?: ((screenWidth - 140.dpToPx()) / 2).coerceAtLeast(8.dpToPx())
            y = lastOverlayY ?: 56.dpToPx()
        }
        attachDragHandler(view, manager, params)
        return runCatching {
            manager.addView(view, params)
            windowManager = manager
            overlayView = view
            overlayParams = params
            OmniLog.d(
                TAG,
                "manual recording control overlay shown trusted=$trusted state=$state"
            )
            true
        }.getOrElse { error ->
            OmniLog.e(
                TAG,
                "show failed trusted=$trusted: ${error.message}",
                error
            )
            false
        }
    }

    private fun keepControlsAboveTouchRecorder() {
        synchronized(this) {
            keepControlsAboveTouchRecorderLocked()
        }
    }

    private fun keepControlsAboveTouchRecorderLocked() {
        val view = overlayView ?: return
        val manager = windowManager ?: return
        val params = overlayParams ?: return
        if (!view.isAttachedToWindow) return
        suppressUnexpectedDetach = true
        try {
            runCatching {
                manager.removeViewImmediate(view)
                manager.addView(view, params)
            }.recoverCatching {
                if (view.isAttachedToWindow) {
                    manager.updateViewLayout(view, params)
                } else {
                    manager.addView(view, params)
                }
            }.onFailure { error ->
                OmniLog.w(TAG, "keep controls above touch recorder failed: ${error.message}")
            }
        } finally {
            suppressUnexpectedDetach = false
        }
    }

    private fun buildView(context: Context): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12.dpToPx().toFloat()
                setColor(Color.rgb(28, 30, 36))
                setStroke(1.dpToPx(), Color.argb(60, 255, 255, 255))
            }
            elevation = 6.dpToPx().toFloat()
        }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                val unexpected = synchronized(this@ManualRecordingControlOverlay) {
                    overlayView === view && !suppressUnexpectedDetach
                }
                if (unexpected) {
                    cancelRecording(
                        localizedText(
                            "录制窗口被系统关闭，轨迹已取消",
                            "Recording window was closed by the system.",
                        ),
                    )
                }
            }
        })
        val title = TextView(context).apply {
            tag = "manual_recording_title"
            text = localizedText(context, "录制", "Record")
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 2.dpToPx(), 0)
        }
        val pauseButton = TextView(context).apply {
            tag = "manual_recording_pause_action"
            text = localizedText(context, "暂停", "Pause")
            contentDescription = localizedText(
                context,
                "暂停手动录制",
                "Pause manual recording",
            )
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(58, 64, 78))
            }
            setOnClickListener {
                if (synchronized(this@ManualRecordingControlOverlay) { manualActionDialogShowing }) {
                    showTransientStatus(
                        localizedText("动作处理中", "Processing action"),
                        900L,
                    )
                    return@setOnClickListener
                }
                val shouldResume = when (ManualRecordingControlOverlay.state) {
                    State.PREPARING -> return@setOnClickListener
                    State.READY -> true
                    State.RECORDING -> false
                    State.PAUSED -> true
                }
                isEnabled = false
                recordingControlScope.launch {
                    val updated = if (shouldResume) {
                        HumanTrajectoryLearningSession.resumeActive()
                    } else {
                        ManualTouchRecordLoader.beginFinishing()
                        if (ManualTouchRecordLoader.awaitIdle()) {
                            HumanTrajectoryLearningSession.pauseActive()
                        } else {
                            false
                        }
                    }
                    withContext(Dispatchers.Main) {
                        isEnabled = true
                        if (!updated) {
                            if (!shouldResume) {
                                markRecording()
                                showTransientStatus(
                                    localizedText(
                                        "动作尚未保存，暂停失败",
                                        "Action not saved. Could not pause.",
                                    ),
                                    1200L,
                                )
                            }
                            return@withContext
                        }
                        if (shouldResume) {
                            markRecording()
                        } else {
                            markPaused()
                        }
                    }
                }
            }
        }
        val manualActionButton = TextView(context).apply {
            tag = "manual_recording_manual_action"
            text = localizedText(context, "动作", "Action")
            contentDescription = localizedText(
                context,
                "手动补录 input_text、press_key 或 wait",
                "Manually add input_text, press_key, or wait",
            )
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(74, 66, 122))
            }
            setOnClickListener {
                showManualActionDialog(context)
            }
        }
        val cancelButton = TextView(context).apply {
            tag = "manual_recording_cancel_action"
            text = localizedText(context, "取消", "Cancel")
            contentDescription = localizedText(
                context,
                "取消手动录制",
                "Cancel manual recording",
            )
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(92, 56, 56))
            }
            setOnClickListener {
                isEnabled = false
                text = localizedText(context, "取消中", "Cancelling")
                this@ManualRecordingControlOverlay.cancelRecording()
            }
        }
        val finishButton = TextView(context).apply {
            tag = "manual_recording_finish_action"
            text = localizedText(context, "完成", "Finish")
            contentDescription = localizedText(
                context,
                "结束手动录制",
                "Finish manual recording",
            )
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(7.dpToPx(), 4.dpToPx(), 7.dpToPx(), 4.dpToPx())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9.dpToPx().toFloat()
                setColor(Color.rgb(31, 111, 235))
            }
            setOnClickListener {
                if (synchronized(this@ManualRecordingControlOverlay) { manualActionDialogShowing }) {
                    showTransientStatus(
                        localizedText("动作处理中", "Processing action"),
                        900L,
                    )
                    return@setOnClickListener
                }
                isEnabled = false
                text = localizedText(context, "保存中", "Saving")
                val runId = synchronized(this@ManualRecordingControlOverlay) { sessionRunId }
                ManualTouchRecordLoader.beginFinishing()
                recordingControlScope.launch {
                    val drained = ManualTouchRecordLoader.awaitIdle()
                    if (!drained) {
                        OmniLog.w(TAG, "finishing manual recording with undrained touch work")
                    }
                    val updated = runCatching {
                        runId != null && HumanTrajectoryLearningSession.completeActive(runId)
                    }.getOrElse { error ->
                        OmniLog.e(TAG, "finish manual recording failed: ${error.message}", error)
                        false
                    }
                    if (!updated) {
                        OmniLog.w(TAG, "finish clicked without active manual recording session")
                    }
                    withContext(Dispatchers.Main) {
                        synchronized(this@ManualRecordingControlOverlay) {
                            dismissLocked()
                        }
                    }
                }
            }
        }
        container.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            pauseButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            manualActionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        container.addView(
            finishButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 5.dpToPx()
            }
        )
        return container
    }

    private fun showManualActionDialog(context: Context) {
        recordingControlScope.launch {
            val canShow = withContext(Dispatchers.Main.immediate) {
                synchronized(this@ManualRecordingControlOverlay) {
                    if (manualActionDialogShowing || state != State.RECORDING) {
                        false
                    } else {
                        manualActionDialogShowing = true
                        true
                    }
                }
            }
            if (!canShow) {
                withContext(Dispatchers.Main.immediate) {
                    showTransientStatus(
                        localizedText("先开始录制", "Start recording first"),
                        900L,
                    )
                }
                return@launch
            }

            val inputTarget = runCatching {
                AndroidGuiEnvironment(context).inputTarget()?.let {
                    ManualInputTarget(
                        description = it.description,
                        x = it.x,
                        y = it.y,
                        nodeResourceId = it.nodeResourceId.takeIf(String::isNotBlank),
                        password = it.password,
                    )
                }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (!ManualTouchRecordLoader.prepareForManualAction()) {
                    finishManualActionDialog(
                        localizedText("稍后再试", "Try again shortly"),
                    )
                    return@withContext
                }
                val labels = arrayOf(
                    localizedText(context, "输入文字", "Enter text"),
                    localizedText(context, "按回车", "Press Enter"),
                    localizedText(context, "按返回", "Press Back"),
                    localizedText(context, "回到桌面", "Go Home"),
                    localizedText(context, "等待", "Wait"),
                )
                val dialog = AlertDialog.Builder(context)
                    .setTitle(localizedText(context, "补录动作", "Add action"))
                    .setItems(labels) { _, which ->
                        when (which) {
                            0 -> when {
                                inputTarget?.password == true -> finishManualActionDialog(
                                    localizedText(
                                        context,
                                        "密码输入不录制",
                                        "Password input is not recorded",
                                    ),
                                )
                                else -> showManualInputTextDialog(context, inputTarget)
                            }
                            1 -> executeManualPressKey("enter", inputTarget)
                            2 -> executeManualPressKey("back", inputTarget)
                            3 -> executeManualPressKey("home", inputTarget)
                            4 -> showManualWaitDialog(context)
                            else -> finishManualActionDialog()
                        }
                    }
                    .setNegativeButton(localizedText(context, "取消", "Cancel")) { _, _ ->
                        finishManualActionDialog()
                    }
                    .create()
                dialog.setOnCancelListener {
                    finishManualActionDialog()
                }
                if (!showOverlayDialog(dialog)) {
                    finishManualActionDialog(
                        localizedText(context, "补录窗口失败", "Could not open action dialog"),
                    )
                }
            }
        }
    }

    private fun showManualInputTextDialog(
        context: Context,
        inputTarget: ManualInputTarget?,
        draft: String = "",
        errorMessage: String? = null,
    ) {
        val input = EditText(context).apply {
            hint = errorMessage ?: localizedText(
                context,
                "输入要写入目标输入框的文本",
                "Enter text for the selected input field",
            )
            minLines = 1
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            setText(draft)
            setSelection(text?.length ?: 0)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(
                inputTarget?.description?.let {
                    localizedText(context, "输入：$it", "Input: $it")
                } ?: localizedText(context, "输入文字", "Enter text"),
            )
            .setView(input)
            .setPositiveButton(localizedText(context, "输入", "Enter"), null)
            .setNeutralButton(localizedText(context, "输入并回车", "Enter and submit"), null)
            .setNegativeButton(
                if (inputTarget == null) {
                    localizedText(context, "取消", "Cancel")
                } else {
                    localizedText(context, "仅保留点击", "Keep tap only")
                },
            ) { _, _ ->
                finishManualActionDialog()
            }
            .create()
        dialog.setOnShowListener {
            val submit: (Boolean) -> Unit = submit@{ pressEnter ->
                val text = input.text?.toString().orEmpty()
                if (text.isEmpty()) {
                    input.error = localizedText(context, "请输入文本", "Enter text")
                    return@submit
                }
                dialog.dismiss()
                executeManualInputText(context, text, inputTarget, pressEnter)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { submit(false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { submit(true) }
            input.requestFocus()
            dialog.window?.apply {
                setGravity(Gravity.BOTTOM)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                )
            }
            input.post {
                val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                inputMethodManager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.setOnCancelListener {
            finishManualActionDialog()
        }
        if (!showOverlayDialog(dialog)) {
            finishManualActionDialog(
                localizedText(context, "补录窗口失败", "Could not open action dialog"),
            )
        }
    }

    private fun showManualWaitDialog(context: Context) {
        val input = EditText(context).apply {
            hint = localizedText(context, "等待秒数（1-60）", "Seconds to wait (1-60)")
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("wait")
            .setView(input)
            .setPositiveButton(localizedText(context, "执行并记录", "Run and record"), null)
            .setNegativeButton(localizedText(context, "取消", "Cancel")) { _, _ ->
                finishManualActionDialog()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val seconds = input.text?.toString()?.trim()?.toLongOrNull()
                if (seconds == null || seconds !in 1L..60L) {
                    input.error = localizedText(context, "请输入 1-60 秒", "Enter 1-60 seconds")
                    return@setOnClickListener
                }
                dialog.dismiss()
                executeManualWait(seconds * 1_000L)
            }
        }
        dialog.setOnCancelListener {
            finishManualActionDialog()
        }
        if (!showOverlayDialog(dialog)) {
            finishManualActionDialog(
                localizedText(context, "补录窗口失败", "Could not open action dialog"),
            )
        }
    }

    private fun executeManualInputText(
        context: Context,
        text: String,
        inputTarget: ManualInputTarget?,
        pressEnter: Boolean,
    ) {
        showTransientStatus(localizedText(context, "输入中", "Entering text"), 600L)
        recordingControlScope.launch {
            if (!ensureTouchRecordingBlocked(context)) {
                withContext(Dispatchers.Main) {
                    finishManualActionDialog(
                        localizedText(
                            context,
                            "触控录制层未就绪",
                            "Touch recording layer is not ready",
                        ),
                    )
                }
                return@launch
            }
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualInputText(text, inputTarget)
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual input_text action failed: ${error.message}", error)
                false
            }
            val enterRecorded = if (recorded && pressEnter) {
                runCatching {
                    HumanTrajectoryLearningSession.recordManualPressKey(
                        key = "enter",
                        inputTarget = inputTarget,
                    )
                }
                    .getOrElse { error ->
                        OmniLog.e(TAG, "manual enter action failed: ${error.message}", error)
                        false
                    }
            } else {
                !pressEnter
            }
            withContext(Dispatchers.Main) {
                when {
                    !recorded && ManualTouchRecordLoader.prepareForManualAction() -> {
                        showManualInputTextDialog(
                            context = context,
                            inputTarget = inputTarget,
                            draft = text,
                            errorMessage = localizedText(
                                context,
                                "输入失败，内容已保留，请重试",
                                "Input failed. Text was kept; try again.",
                            ),
                        )
                    }
                    !recorded -> finishManualActionDialog(
                        localizedText(
                            context,
                            "输入失败：未找到可用输入框",
                            "Input failed: no usable input field",
                        ),
                    )
                    !enterRecorded -> finishManualActionDialog(
                        localizedText(context, "文字已输入，回车失败", "Text entered; submit failed"),
                    )
                    pressEnter -> finishManualActionDialog(
                        localizedText(context, "已输入并回车", "Text entered and submitted"),
                    )
                    else -> finishManualActionDialog(
                        localizedText(context, "已输入", "Text entered"),
                    )
                }
            }
        }
    }

    private fun executeManualPressKey(
        key: String,
        inputTarget: ManualInputTarget? = null,
    ) {
        showTransientStatus(localizedText("补录中", "Adding action"), 600L)
        recordingControlScope.launch {
            if (!ensureTouchRecordingBlocked()) {
                withContext(Dispatchers.Main) {
                    finishManualActionDialog(
                        localizedText(
                            "触控录制层未就绪",
                            "Touch recording layer is not ready",
                        ),
                    )
                }
                return@launch
            }
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualPressKey(
                    key = key,
                    inputTarget = inputTarget,
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual press_key action failed: ${error.message}", error)
                false
            }
            withContext(Dispatchers.Main) {
                finishManualActionDialog(
                    if (recorded) {
                        localizedText("已补录 press_key", "press_key added")
                    } else {
                        localizedText(
                            "按键执行失败：目标控件未响应",
                            "Key action failed: target did not respond",
                        )
                    },
                )
            }
        }
    }

    private fun executeManualWait(durationMs: Long) {
        val seconds = durationMs / 1_000L
        showTransientStatus(
            localizedText("等待 $seconds 秒", "Waiting ${seconds}s"),
            durationMs + 400L,
        )
        recordingControlScope.launch {
            if (!ensureTouchRecordingBlocked()) {
                withContext(Dispatchers.Main) {
                    finishManualActionDialog(localizedText("等待失败", "Wait failed"))
                }
                return@launch
            }
            val recorded = runCatching {
                HumanTrajectoryLearningSession.recordManualWait(durationMs)
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual wait action failed: ${error.message}", error)
                false
            }
            withContext(Dispatchers.Main) {
                finishManualActionDialog(
                    if (recorded) {
                        localizedText("已记录 wait ${seconds}s", "Recorded wait ${seconds}s")
                    } else {
                        localizedText("等待失败", "Wait failed")
                    },
                )
            }
        }
    }

    private fun finishManualActionDialog(message: String? = null) {
        synchronized(this) {
            manualActionDialogShowing = false
        }
        if (HumanTrajectoryLearningSession.isActive() && !HumanTrajectoryLearningSession.isPaused()) {
            markRecording()
        } else {
            markPaused()
        }
        if (!message.isNullOrBlank()) {
            showTransientStatus(message, 1_000L)
        }
    }

    private suspend fun ensureTouchRecordingBlocked(context: Context? = UIKit.appContext): Boolean {
        repeat(6) { attempt ->
            val blocked = withContext(Dispatchers.Main.immediate) {
                ManualTouchRecordLoader.blockTouches(context)
            }
            if (blocked) return true
            if (attempt < 5) delay(100L)
        }
        return false
    }

    private fun showOverlayDialog(dialog: AlertDialog): Boolean {
        return runCatching {
            applyOverlayWindowType(dialog)
            dialog.show()
            applyOverlayWindowType(dialog)
            true
        }.getOrElse { error ->
            OmniLog.e(TAG, "show manual action dialog failed: ${error.message}", error)
            false
        }
    }

    private fun applyOverlayWindowType(dialog: AlertDialog) {
        dialog.window?.setType(AndroidGuiOverlayHost.resolve(dialog.context).windowType)
    }

    private fun attachDragHandler(
        view: View,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ) {
        val touchListener = View.OnTouchListener { target, event ->
            handleDragTouch(target, event, manager, params)
        }
        view.setOnTouchListener(touchListener)
        (view as? LinearLayout)?.let { container ->
            (0 until container.childCount)
                .map { container.getChildAt(it) }
                .firstOrNull { it.tag == "manual_recording_title" }
                ?.setOnTouchListener(touchListener)
        }
    }

    private fun handleDragTouch(
        target: View,
        event: MotionEvent,
        manager: WindowManager,
        params: WindowManager.LayoutParams
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragging && (abs(dx) > 6.dpToPx() || abs(dy) > 6.dpToPx())) {
                    dragging = true
                    beginDragRecordingSuppression()
                }
                if (dragging) {
                    val layoutView = overlayView ?: target
                    val display = target.context.resources.displayMetrics
                    val maxX = max(0, display.widthPixels - layoutView.width)
                    val maxY = max(0, display.heightPixels - layoutView.height)
                    params.x = min(max(0, dragStartX + dx.toInt()), maxX)
                    params.y = min(max(8.dpToPx(), dragStartY + dy.toInt()), maxY)
                    lastOverlayX = params.x
                    lastOverlayY = params.y
                    runCatching {
                        if (layoutView.isAttachedToWindow) {
                            manager.updateViewLayout(layoutView, params)
                        }
                    }.onFailure { OmniLog.w(TAG, "drag update failed: ${it.message}") }
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    endDragRecordingSuppression()
                }
                return true
            }
        }
        return true
    }

    private fun beginDragRecordingSuppression() {
        // Keep drag handling UI-only. Session state changes can wait for replay/XML
        // work, and doing that inside ACTION_MOVE can trigger an overlay Input ANR.
    }

    private fun endDragRecordingSuppression() {
    }

    private fun bindState(view: View?, state: State) {
        val container = view as? LinearLayout ?: return
        val context = container.context
        val title = findChildByTag(container, "manual_recording_title") as? TextView
        val button = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_finish_action" } as? TextView
        val pauseButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_pause_action" } as? TextView
        val manualActionButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_manual_action" } as? TextView
        val cancelButton = (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == "manual_recording_cancel_action" } as? TextView
        title?.text = when (state) {
            State.PREPARING -> localizedText(context, "准备", "Preparing")
            State.READY -> localizedText(context, "待机", "Ready")
            State.RECORDING -> localizedText(context, "录制", "Recording")
            State.PAUSED -> localizedText(context, "暂停", "Paused")
        }
        pauseButton?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = when (state) {
                State.PREPARING -> localizedText(context, "暂停", "Pause")
                State.READY -> localizedText(context, "开始", "Start")
                State.RECORDING -> localizedText(context, "暂停", "Pause")
                State.PAUSED -> localizedText(context, "继续", "Resume")
            }
            contentDescription = when (state) {
                State.PREPARING -> localizedText(context, "暂停手动录制", "Pause manual recording")
                State.READY -> localizedText(context, "开始手动录制", "Start manual recording")
                State.RECORDING -> localizedText(context, "暂停手动录制", "Pause manual recording")
                State.PAUSED -> localizedText(context, "继续手动录制", "Resume manual recording")
            }
        }
        manualActionButton?.apply {
            visibility = if (state == State.RECORDING) View.VISIBLE else View.GONE
            isEnabled = state == State.RECORDING
            text = localizedText(context, "动作", "Action")
            contentDescription = localizedText(
                context,
                "手动补录 input_text 或 press_key",
                "Manually add input_text or press_key",
            )
        }
        cancelButton?.apply {
            visibility = View.VISIBLE
            isEnabled = true
            text = localizedText(context, "取消", "Cancel")
            contentDescription = localizedText(context, "取消手动录制", "Cancel manual recording")
        }
        button?.apply {
            visibility = if (state == State.PREPARING) View.GONE else View.VISIBLE
            isEnabled = state != State.PREPARING
            text = localizedText(context, "完成", "Finish")
            contentDescription = localizedText(
                context,
                "完成并保存手动录制",
                "Finish and save manual recording",
            )
        }
    }

    internal fun localizedText(zh: String, en: String): String =
        localizedText(overlayView?.context ?: UIKit.appContext, zh, en)

    private fun localizedText(context: Context?, zh: String, en: String): String {
        val locale = if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context?.resources?.configuration?.locale ?: Locale.getDefault()
        }
        return if (locale.language.equals("zh", ignoreCase = true)) zh else en
    }

    private fun setTitleText(message: String) {
        val container = overlayView as? LinearLayout ?: return
        val title = findChildByTag(container, "manual_recording_title") as? TextView ?: return
        title.text = message
    }

    private fun findChildByTag(container: LinearLayout, tag: String): View? {
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .firstOrNull { it.tag == tag }
    }
}
