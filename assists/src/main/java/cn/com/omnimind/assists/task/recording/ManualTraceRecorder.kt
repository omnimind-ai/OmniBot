package cn.com.omnimind.assists.task.recording

import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.assists.ManualOverlayGestureReplayResult
import cn.com.omnimind.assists.ManualOverlayTouchGesture
import cn.com.omnimind.assists.ManualInputTarget
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.actionOf
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class ManualTraceResult(
    val actions: List<ManualRecordedAction>,
    val summary: String,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val actionCount: Int get() = actions.size
}

data class ManualRecordedAction(
    val action: Action,
    val title: String,
    val beforeState: State?,
    val afterState: State?,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val summary: String,
    val eventContext: Map<String, Any?> = emptyMap(),
    val recordingBackend: String = "unknown",
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val evidenceComplete: Boolean = true,
    val evidenceError: String? = null,
    val operationSuccess: Boolean = true,
    val operationError: String? = null,
) {
    val beforePackageName: String? get() = beforeState?.packageName
    val afterPackageName: String? get() = afterState?.packageName
    val beforeXml: String? get() = beforeState?.xml
    val afterXml: String? get() = afterState?.xml
}

internal fun selectManualInputTargetAfterClick(
    clickedTarget: ManualInputTarget?,
): ManualInputTarget? = clickedTarget

internal fun manualInputTextActionArgs(
    text: String,
    inputTarget: ManualInputTarget,
): Map<String, Any?> = linkedMapOf<String, Any?>(
    OobActionSchema.ARG_TARGET_DESCRIPTION to inputTarget.description,
    OobActionSchema.ARG_TEXT to text,
    OobActionSchema.ARG_X to inputTarget.x,
    OobActionSchema.ARG_Y to inputTarget.y,
    OobActionSchema.ARG_NODE_RESOURCE_ID to inputTarget.nodeResourceId,
).filterValues { it != null }

internal fun manualPressKeyActionArgs(
    key: String,
    inputTarget: ManualInputTarget? = null,
): Map<String, Any?> = linkedMapOf<String, Any?>(
    OobActionSchema.ARG_KEY to key,
    OobActionSchema.ARG_TARGET_DESCRIPTION to inputTarget?.description,
    OobActionSchema.ARG_X to inputTarget?.x,
    OobActionSchema.ARG_Y to inputTarget?.y,
    OobActionSchema.ARG_NODE_RESOURCE_ID to inputTarget?.nodeResourceId,
).filterValues { it != null }

internal fun canonicalManualScreenAction(
    tool: String,
    args: Map<String, Any?>,
    displayWidth: Int,
    displayHeight: Int,
): Action {
    require(displayWidth > 0 && displayHeight > 0) { "manual_recording_display_required" }
    val canonicalArgs = ActionCoordinateCodec.toRelative(
        args = args,
        displaySize = ActionCoordinateCodec.DisplaySize(
            displayWidth.toDouble(),
            displayHeight.toDouble(),
        ),
    )
    return actionOf(tool, canonicalArgs)
}

internal fun isManualSystemBackGesture(gesture: ManualOverlayTouchGesture): Boolean {
    if (gesture.actionName != OobActionSchema.TOOL_SWIPE || gesture.displayWidth <= 0) return false
    val edgeWidth = gesture.displayWidth * 0.05f
    return when (gesture.direction) {
        "right" -> gesture.startX <= edgeWidth
        "left" -> gesture.startX >= gesture.displayWidth - edgeWidth
        else -> false
    }
}

internal data class ManualTraceSnapshot(
    val isStarted: Boolean,
    val isPaused: Boolean,
    val actionCount: Int,
    val latestActionSummary: String?,
    val pendingActionSummary: String?,
    val accessibilityEventCount: Int,
    val rawTouchEnabled: Boolean,
    val rawTouchAvailable: Boolean,
    val overlayTouchRecordedCount: Int,
    val recordingBackend: String,
    val debugScreenshotsEnabled: Boolean,
    val debugScreenshotStoredCount: Int,
    val debugScreenshotFailedCount: Int,
    val debugScreenshotSkippedCount: Int,
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "is_started" to isStarted,
        "is_paused" to isPaused,
        "action_count" to actionCount,
        "latest_action_summary" to latestActionSummary,
        "pending_action_summary" to pendingActionSummary,
        "accessibility_event_count" to accessibilityEventCount,
        "raw_touch_enabled" to rawTouchEnabled,
        "raw_touch_available" to rawTouchAvailable,
        "overlay_touch_recorded_count" to overlayTouchRecordedCount,
        "recording_backend" to recordingBackend,
        "debug_screenshots_enabled" to debugScreenshotsEnabled,
        "debug_screenshot_stored_count" to debugScreenshotStoredCount,
        "debug_screenshot_failed_count" to debugScreenshotFailedCount,
        "debug_screenshot_skipped_count" to debugScreenshotSkippedCount,
    ).filterValues { it != null }
}

internal object ManualRecordingDiagnostics {
    const val COMPLETE_OVERLAY_TOUCH = "complete_overlay_touch"
    const val COMPLETE_MANUAL_CONTROL = "complete_manual_control"
    const val INCOMPLETE_OVERLAY_TOUCH = "incomplete_overlay_touch"
    const val COMPLETE_RAW_TOUCH = "complete_raw_touch"
    const val MISSING_RAW_TOUCH = "missing_raw_touch"
    const val RAW_TOUCH_INTERRUPTED = "raw_touch_interrupted"

    fun completeness(rawTouchAvailable: Boolean, rawTouchActiveAtStop: Boolean?): String = when {
        rawTouchAvailable && rawTouchActiveAtStop == true -> COMPLETE_RAW_TOUCH
        rawTouchAvailable -> RAW_TOUCH_INTERRUPTED
        else -> MISSING_RAW_TOUCH
    }

    fun guaranteesNoMissingClicks(
        rawTouchAvailable: Boolean,
        rawTouchActiveAtStop: Boolean?,
    ): Boolean = completeness(rawTouchAvailable, rawTouchActiveAtStop) == COMPLETE_RAW_TOUCH

    fun warningMessage(completeness: String): String? = when (completeness) {
        COMPLETE_OVERLAY_TOUCH, COMPLETE_RAW_TOUCH, COMPLETE_MANUAL_CONTROL -> null
        INCOMPLETE_OVERLAY_TOUCH -> "有动作执行失败，本次轨迹未全部提交"
        RAW_TOUCH_INTERRUPTED -> "raw touch 录制中断"
        else -> "raw touch 不可用"
    }

    fun guaranteesNoMissingClicks(diagnostics: Map<String, Any?>): Boolean {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return false
        return manual["guarantees_no_missing_clicks"] == true
    }

    fun warningMessage(diagnostics: Map<String, Any?>): String? {
        val manual = diagnostics["manual_recording"] as? Map<*, *> ?: return null
        return manual["warning_message"]?.toString()?.takeIf { it.isNotBlank() }
    }
}

class ManualTraceRecorder(
    context: Context,
    private val sessionLabel: String,
    private val enableRawTouch: Boolean = false,
    private val enableDebugScreenshots: Boolean = false,
    private val onActionRecorded: (suspend (index: Int, action: ManualRecordedAction) -> Unit)? = null,
) {
    private val stateLock = Any()
    private val journal = ManualRecordingJournal()
    private val environment = AndroidGuiEnvironment(context)
    private val engine = ManualRecordingEngine(
        journal = journal,
        observe = { stage, command -> captureObservation(stage, command) },
        execute = { command -> environment.act(command.action, awaitStabilization = false) },
        onActionRecorded = { index, action -> onActionRecorded?.invoke(index, action) },
    )

    @Volatile private var isStarted = false
    @Volatile private var isPaused = false

    fun start(): Boolean {
        if (isStarted) return true
        if (!environment.isReady()) {
            OmniLog.w(TAG, "manual recorder unavailable: accessibility service is not ready")
            return false
        }
        synchronized(stateLock) {
            isStarted = true
            isPaused = false
        }
        OmniLog.i(
            TAG,
            "manual recorder started session=$sessionLabel source=explicit_actions raw_requested=$enableRawTouch",
        )
        return true
    }

    fun pause(): Boolean = synchronized(stateLock) {
        if (!isStarted) return false
        isPaused = true
        true
    }

    fun resume(): Boolean = synchronized(stateLock) {
        if (!isStarted) return false
        isPaused = false
        true
    }

    fun stop(): ManualTraceResult {
        synchronized(stateLock) {
            isStarted = false
            isPaused = false
        }
        runBlocking { engine.awaitIdle() }
        val actions = journal.snapshot()
        return ManualTraceResult(
            actions = actions,
            summary = journal.summary(MAX_SUMMARY_ACTIONS),
            diagnostics = buildDiagnostics(actions),
        )
    }

    internal fun snapshot(): ManualTraceSnapshot {
        val engineStats = engine.stats()
        val actions = journal.snapshot()
        return ManualTraceSnapshot(
            isStarted = isStarted,
            isPaused = isPaused,
            actionCount = actions.size,
            latestActionSummary = engineStats.pendingSummary ?: actions.lastOrNull()?.summary,
            pendingActionSummary = engineStats.pendingSummary,
            accessibilityEventCount = 0,
            rawTouchEnabled = enableRawTouch,
            rawTouchAvailable = false,
            overlayTouchRecordedCount = actions.count {
                it.recordingBackend == OVERLAY_TOUCH_SOURCE
            },
            recordingBackend = recordingBackend(actions),
            debugScreenshotsEnabled = enableDebugScreenshots,
            debugScreenshotStoredCount = actions.sumOf { action ->
                listOf(action.beforeState, action.afterState).count { !it?.screenshotPath.isNullOrBlank() }
            },
            debugScreenshotFailedCount = 0,
            debugScreenshotSkippedCount = if (enableDebugScreenshots) 0 else actions.size * 2,
        )
    }

    suspend fun recordManualInputText(
        text: String,
        inputTarget: ManualInputTarget? = null,
    ): Boolean {
        if (text.isEmpty() || !isRecording()) {
            return false
        }
        val target = inputTarget ?: awaitInputTarget()
        if (target == null || target.password) return false
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_INPUT_TEXT,
                args = manualInputTextActionArgs(text, target),
                title = "输入文本",
                summary = "输入文本：${text.take(MAX_TEXT_SUMMARY_CHARS)}",
                source = MANUAL_CONTROL_SOURCE,
                screenCoordinates = true,
            ).copy(persistOnFailure = true)
        ).executed
    }

    suspend fun recordManualPressKey(
        key: String,
        inputTarget: ManualInputTarget? = null,
    ): Boolean {
        val canonicalKey = key.trim().lowercase().takeIf { it in SUPPORTED_KEYS } ?: return false
        if (!isRecording()) return false
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_PRESS_KEY,
                args = manualPressKeyActionArgs(canonicalKey, inputTarget),
                title = "按键 $canonicalKey",
                summary = "按键：$canonicalKey",
                source = MANUAL_CONTROL_SOURCE,
                screenCoordinates = inputTarget != null,
            )
        ).recorded
    }

    suspend fun recordManualWait(durationMs: Long): Boolean {
        if (durationMs !in 1L..MAX_CANONICAL_WAIT_MS || !isRecording()) return false
        return engine.perform(
            command(
                tool = OobActionSchema.TOOL_WAIT,
                args = mapOf(OobActionSchema.ARG_DURATION_MS to durationMs),
                title = "等待 ${formatDuration(durationMs)}",
                summary = "等待 ${formatDuration(durationMs)}",
                source = MANUAL_CONTROL_SOURCE,
            )
        ).recorded
    }

    suspend fun recordOverlayGesture(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit = {},
    ): ManualOverlayGestureReplayResult {
        if (!isRecording() || gesture.actionName !in SUPPORTED_GESTURES) {
            return ManualOverlayGestureReplayResult(executed = false, recorded = false)
        }
        val command = gesture.toRecordingCommand()
        val outcome = engine.perform(command) { dispatchResult ->
            onGestureDispatched(
                dispatchResult.success && gesture.actionName == OobActionSchema.TOOL_CLICK,
            )
        }
        return ManualOverlayGestureReplayResult(
            executed = outcome.executed,
            recorded = outcome.recorded,
            mayOpenIme = outcome.executed && gesture.actionName == OobActionSchema.TOOL_CLICK,
            inputTarget = null,
        )
    }

    suspend fun detectInputTargetAfterClick(
        x: Float,
        y: Float,
    ): ManualInputTarget? = withTimeoutOrNull(INPUT_TARGET_TIMEOUT_MS) {
        var target: ManualInputTarget? = null
        while (target == null && isRecording()) {
            target = selectManualInputTargetAfterClick(
                environment.inputTarget(x, y)?.toManualInputTarget(),
            )
            if (target == null) delay(INPUT_TARGET_POLL_MS)
        }
        target
    }

    private suspend fun awaitInputTarget(): ManualInputTarget? = withTimeoutOrNull(INPUT_TARGET_TIMEOUT_MS) {
        var target: ManualInputTarget? = null
        while (target == null) {
            target = environment.inputTarget()?.toManualInputTarget()
            if (target == null) {
                delay(INPUT_TARGET_POLL_MS)
            }
        }
        target
    }

    private fun ManualOverlayTouchGesture.toRecordingCommand(): ManualRecordingCommand {
        if (isManualSystemBackGesture(this)) {
            return command(
                tool = OobActionSchema.TOOL_PRESS_KEY,
                args = manualPressKeyActionArgs("back"),
                title = "返回",
                summary = "侧滑返回",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }
        return when (actionName) {
            OobActionSchema.TOOL_CLICK -> command(
                tool = actionName,
                args = mapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕坐标",
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                ),
                title = "点击 (${startX.toInt()}, ${startY.toInt()})",
                summary = "点击屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

            OobActionSchema.TOOL_LONG_PRESS -> command(
                tool = actionName,
                args = mapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕坐标",
                    OobActionSchema.ARG_X to startX,
                    OobActionSchema.ARG_Y to startY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                ),
                title = "长按 (${startX.toInt()}, ${startY.toInt()})",
                summary = "长按屏幕 (${startX.toInt()}, ${startY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

            else -> command(
                tool = OobActionSchema.TOOL_SWIPE,
                args = linkedMapOf(
                    OobActionSchema.ARG_TARGET_DESCRIPTION to "屏幕区域",
                    OobActionSchema.ARG_X1 to startX,
                    OobActionSchema.ARG_Y1 to startY,
                    OobActionSchema.ARG_X2 to endX,
                    OobActionSchema.ARG_Y2 to endY,
                    OobActionSchema.ARG_DURATION_MS to durationMs.coerceAtLeast(1L),
                    OobActionSchema.ARG_DIRECTION to direction.orEmpty().ifBlank {
                        if (kotlin.math.abs(endX - startX) >= kotlin.math.abs(endY - startY)) {
                            if (endX >= startX) "right" else "left"
                        } else {
                            if (endY >= startY) "down" else "up"
                        }
                    },
                ),
                title = "${directionLabel(direction)}滑动",
                summary = "从 (${startX.toInt()}, ${startY.toInt()}) 滑动到 " +
                    "(${endX.toInt()}, ${endY.toInt()})",
                source = OVERLAY_TOUCH_SOURCE,
                startedAtMs = startedAtMs,
                screenCoordinates = true,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }
    }

    private fun command(
        tool: String,
        args: Map<String, Any?>,
        title: String,
        summary: String,
        source: String,
        startedAtMs: Long = System.currentTimeMillis(),
        screenCoordinates: Boolean = false,
        displayWidth: Int = environment.displaySize().first,
        displayHeight: Int = environment.displaySize().second,
    ): ManualRecordingCommand = ManualRecordingCommand(
        action = if (screenCoordinates) {
            val display = environment.displaySize()
            val width = displayWidth.takeIf { it > 0 } ?: display.first
            val height = displayHeight.takeIf { it > 0 } ?: display.second
            canonicalManualScreenAction(
                tool = tool,
                args = args,
                displayWidth = width,
                displayHeight = height,
            )
        } else {
            actionOf(tool, args)
        },
        title = title,
        summary = summary,
        source = source,
        startedAtMs = startedAtMs,
    )

    private suspend fun captureObservation(
        stage: String,
        command: ManualRecordingCommand,
    ): ManualRecordingObservation {
        return runCatching {
            ManualRecordingObservation(
                state = environment.observe(captureScreenshot = enableDebugScreenshots),
            )
        }.getOrElse { error ->
            OmniLog.w(TAG, "manual observation failed stage=$stage tool=${command.action.tool}: ${error.message}")
            ManualRecordingObservation(
                captureError = error.message.orEmpty().ifBlank { "state_capture_failed" },
            )
        }
    }

    private fun isRecording(): Boolean = isStarted && !isPaused

    private fun buildDiagnostics(actions: List<ManualRecordedAction>): Map<String, Any?> {
        val stats = engine.stats()
        val evidenceFailureCount = actions.count { !it.evidenceComplete }
        val complete = stats.pending == 0 &&
            stats.failed == 0 &&
            stats.received == stats.committed &&
            evidenceFailureCount == 0
        val backend = recordingBackend(actions)
        val completeness = when {
            !complete -> ManualRecordingDiagnostics.INCOMPLETE_OVERLAY_TOUCH
            backend == MANUAL_CONTROL_SOURCE -> ManualRecordingDiagnostics.COMPLETE_MANUAL_CONTROL
            else -> ManualRecordingDiagnostics.COMPLETE_OVERLAY_TOUCH
        }
        return linkedMapOf(
            "manual_recording" to linkedMapOf(
                "schema_version" to "oob.manual_recording.diagnostics.v2",
                "action_model" to "explicit_canonical_actions",
                "action_source" to backend,
                "recording_backend_counts" to actions.map {
                    it.recordingBackend
                }.groupingBy { it }.eachCount(),
                "completeness" to completeness,
                "guarantees_no_missing_clicks" to complete,
                "guarantee_scope" to "accepted_actions_while_process_alive",
                "process_crash_safe" to (onActionRecorded != null),
                "received_action_count" to stats.received,
                "committed_action_count" to stats.committed,
                "failed_action_count" to stats.failed,
                "pending_action_count" to stats.pending,
                "incomplete_state_count" to evidenceFailureCount,
                "a11_replay_actions_enabled" to false,
                "a11_role" to "observation_only",
                "raw_touch_enabled" to false,
                "raw_touch_requested" to enableRawTouch,
                "raw_touch_available" to false,
                "warning_message" to ManualRecordingDiagnostics.warningMessage(completeness),
            ).filterValues { it != null },
        )
    }

    private fun cn.com.omnimind.androidgui.AndroidGuiInputTarget.toManualInputTarget(): ManualInputTarget =
        ManualInputTarget(
            description = description,
            x = x,
            y = y,
            nodeResourceId = nodeResourceId.takeIf(String::isNotBlank),
            password = password,
        )

    private fun recordingBackend(actions: List<ManualRecordedAction>): String {
        val sources = actions.map(ManualRecordedAction::recordingBackend).toSet()
        return when {
            sources.isEmpty() -> "explicit_actions"
            sources.size == 1 -> sources.first()
            else -> "mixed_explicit_actions"
        }
    }

    private fun directionLabel(direction: String?): String = when (direction) {
        "up" -> "向上"
        "down" -> "向下"
        "left" -> "向左"
        "right" -> "向右"
        else -> ""
    }

    private fun formatDuration(durationMs: Long): String = if (durationMs % 1_000L == 0L) {
        "${durationMs / 1_000L} 秒"
    } else {
        "${durationMs} 毫秒"
    }

    private companion object {
        const val INPUT_TARGET_TIMEOUT_MS = 3_500L
        const val INPUT_TARGET_POLL_MS = 100L
        private const val TAG = "ManualTraceRecorder"
        private const val OVERLAY_TOUCH_SOURCE = "overlay_touch"
        private const val MANUAL_CONTROL_SOURCE = "manual_control"
        private const val TAP_ANCHOR_TTL_MS = 30_000L
        private const val MAX_TEXT_SUMMARY_CHARS = 80
        private const val MAX_SUMMARY_ACTIONS = 8
        private const val MAX_CANONICAL_WAIT_MS = 60_000L
        private val SUPPORTED_KEYS = setOf("back", "home", "enter")
        private val SUPPORTED_GESTURES = setOf(
            OobActionSchema.TOOL_CLICK,
            OobActionSchema.TOOL_LONG_PRESS,
            OobActionSchema.TOOL_SWIPE,
        )
    }
}
