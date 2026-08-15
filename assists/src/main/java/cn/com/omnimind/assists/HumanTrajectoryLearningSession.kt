package cn.com.omnimind.assists

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.RunLogWriter
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.task.recording.ManualRecordedAction
import cn.com.omnimind.assists.task.recording.ManualTraceRecorder
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

data class HumanTrajectoryLearningResult(
    val success: Boolean,
    val runId: String,
    val name: String,
    val description: String,
    val actionCount: Int,
    val summary: String,
    val errorMessage: String = "",
    val actions: List<ManualRecordedAction> = emptyList(),
    val diagnostics: Map<String, Any?> = emptyMap()
)

data class HumanTrajectoryLearningStatus(
    val active: Boolean,
    val paused: Boolean,
    val runId: String? = null,
    val name: String = "",
    val description: String = "",
    val startedAtMs: Long? = null,
    val actionCount: Int = 0,
    val latestActionSummary: String? = null,
    val pendingActionSummary: String? = null,
    val accessibilityEventCount: Int = 0,
    val rawTouchEnabled: Boolean = false,
    val rawTouchAvailable: Boolean = false,
    val overlayTouchRecordedCount: Int = 0,
    val recordingBackend: String = "overlay_touch",
    val debugScreenshotsEnabled: Boolean = false,
    val debugScreenshotStoredCount: Int = 0,
    val debugScreenshotFailedCount: Int = 0,
    val debugScreenshotSkippedCount: Int = 0
) {
    fun asMap(): Map<String, Any?> = linkedMapOf(
        "active" to active,
        "paused" to paused,
        "recording_active" to active,
        "recording_paused" to paused,
        "run_id" to runId,
        "name" to name,
        "description" to description,
        "started_at_ms" to startedAtMs,
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
        "debug_screenshot_skipped_count" to debugScreenshotSkippedCount
    ).filterValues { it != null }
}

/**
 * Records a full human-operated trajectory and stores it as an Internal RunLog.
 *
 * The app layer owns conversion from this RunLog into a saved Function;
 * this assists-level session only records, completes, and exposes the result so
 * UIKit can finish the session from the floating UI without depending on app.
 */
object HumanTrajectoryLearningSession {
    private const val TAG = "HumanTrajectoryLearningSession"

    private data class ActiveSession(
        val context: Context,
        val runId: String,
        val name: String,
        val description: String,
        val startedAtMs: Long,
        val recorder: ManualTraceRecorder,
        val result: CompletableDeferred<HumanTrajectoryLearningResult>
    )

    private val lock = Any()
    private var activeSession: ActiveSession? = null
    private var activePaused: Boolean = false
    private var completingRunId: String? = null

    fun isActive(): Boolean = synchronized(lock) { activeSession != null }

    fun isPaused(): Boolean = synchronized(lock) { activePaused }

    fun status(): HumanTrajectoryLearningStatus {
        val session = synchronized(lock) { activeSession } ?: return HumanTrajectoryLearningStatus(
            active = false,
            paused = false
        )
        val recorderSnapshot = runCatching { session.recorder.snapshot() }
            .getOrElse { error ->
                OmniLog.w(TAG, "human trajectory status snapshot failed: ${error.message}")
                return HumanTrajectoryLearningStatus(
                    active = true,
                    paused = synchronized(lock) { activePaused },
                    runId = session.runId,
                    name = session.name,
                    description = session.description,
                    startedAtMs = session.startedAtMs
                )
            }
        return HumanTrajectoryLearningStatus(
            active = true,
            paused = synchronized(lock) { activePaused } || recorderSnapshot.isPaused,
            runId = session.runId,
            name = session.name,
            description = session.description,
            startedAtMs = session.startedAtMs,
            actionCount = recorderSnapshot.actionCount,
            latestActionSummary = recorderSnapshot.latestActionSummary,
            pendingActionSummary = recorderSnapshot.pendingActionSummary,
            accessibilityEventCount = recorderSnapshot.accessibilityEventCount,
            rawTouchEnabled = recorderSnapshot.rawTouchEnabled,
            rawTouchAvailable = recorderSnapshot.rawTouchAvailable,
            overlayTouchRecordedCount = recorderSnapshot.overlayTouchRecordedCount,
            recordingBackend = recorderSnapshot.recordingBackend,
            debugScreenshotsEnabled = recorderSnapshot.debugScreenshotsEnabled,
            debugScreenshotStoredCount = recorderSnapshot.debugScreenshotStoredCount,
            debugScreenshotFailedCount = recorderSnapshot.debugScreenshotFailedCount,
            debugScreenshotSkippedCount = recorderSnapshot.debugScreenshotSkippedCount
        )
    }

    /**
     * Returns the active manual recording RunLog id for app-layer evidence writes.
     *
     * The assists module does not own app-only assets such as UDEG nodes; callers
     * use this id only to attach non-replay evidence to the current manual run.
     */
    fun activeRunId(): String? = synchronized(lock) { activeSession?.runId }

    fun start(
        context: Context,
        name: String,
        description: String,
        enableRawTouch: Boolean = false,
        enableDebugScreenshots: Boolean = false
    ): CompletableDeferred<HumanTrajectoryLearningResult> {
        val normalizedName = name.trim().ifEmpty { "人工学习轨迹" }
        val normalizedDescription = description.trim().ifEmpty { normalizedName }
        val appContext = context.applicationContext ?: context
        val runId = "human_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val startedAtMs = System.currentTimeMillis()
        val deferred = CompletableDeferred<HumanTrajectoryLearningResult>()
        val runLogWriter = RunLogWriter { record ->
            InternalRunLogStore.upsertRecordedStep(appContext, runId, record)
        }
        val recorder = ManualTraceRecorder(
            context = appContext,
            sessionLabel = "human_trajectory:$runId",
            enableRawTouch = enableRawTouch,
            enableDebugScreenshots = enableDebugScreenshots,
            onActionRecorded = { _, action ->
                persistAction(
                    context = appContext,
                    runId = runId,
                    writer = runLogWriter,
                    action = action,
                )
            },
        )
        synchronized(lock) {
            if (completingRunId != null) {
                deferred.completeExceptionally(
                    IllegalStateException("上一条人工轨迹仍在保存，请稍后再试"),
                )
                return deferred
            }
            // Auto-cancel any stale session whose coroutine was cancelled without
            // calling completeActive() / cancelActive() (e.g. app crash, mainJob cancel).
            val stale = activeSession
            if (stale != null) {
                OmniLog.w(TAG, "auto-cancelling stale session ${stale.runId} before starting new one")
                activeSession = null
                activePaused = false
                runCatching { stale.recorder.stop() }
                runCatching {
                    stale.result.complete(
                        HumanTrajectoryLearningResult(
                            success = false,
                            runId = stale.runId,
                            name = stale.name,
                            description = stale.description,
                            actionCount = 0,
                            summary = "",
                            errorMessage = "会话被新录制强制取消",
                        )
                    )
                }
            }
            activePaused = false
            OmniLog.i(TAG, "start beginRun: $runId")
            InternalRunLogStore.beginRun(
                context = appContext,
                runId = runId,
                goal = normalizedDescription,
                source = "human_trajectory",
                toolName = "human_trajectory",
                operationDescription = normalizedName
            )
            OmniLog.i(TAG, "start beginRun done: $runId")
            OmniLog.i(TAG, "start recorder.start: $runId")
            if (!recorder.start()) {
                InternalRunLogStore.finishRun(
                    context = appContext,
                    runId = runId,
                    success = false,
                    doneReason = "recorder_unavailable",
                    errorMessage = "无障碍服务未就绪，无法学习轨迹"
                )
                deferred.completeExceptionally(
                    IllegalStateException("无障碍服务未就绪，无法学习轨迹")
                )
                return deferred
            }
            OmniLog.i(TAG, "start recorder.start done: $runId")
            activeSession = ActiveSession(
                context = appContext,
                runId = runId,
                name = normalizedName,
                description = normalizedDescription,
                startedAtMs = startedAtMs,
                recorder = recorder,
                result = deferred
            )
        }
        OmniLog.d(TAG, "human trajectory learning started: $runId")
        return deferred
    }

    /**
     * Suspends action capture for the active manual session.
     *
     * @return True when an active session remains available in paused state.
     */
    fun pauseActive(): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        val paused = session.recorder.pause()
        if (paused) {
            synchronized(lock) { activePaused = true }
        }
        return paused
    }

    /**
     * Resumes capture after refreshing the active recorder's page baseline.
     *
     * @return True when an active session remains available for recording.
     */
    fun resumeActive(): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        val resumed = session.recorder.resume()
        if (resumed) {
            synchronized(lock) { activePaused = false }
        }
        return resumed
    }

    suspend fun recordOverlayGesture(
        gesture: ManualOverlayTouchGesture,
        onGestureDispatched: suspend (mayOpenIme: Boolean) -> Unit = {}
    ): ManualOverlayGestureReplayResult {
        val session = synchronized(lock) { activeSession }
            ?: return ManualOverlayGestureReplayResult(executed = false)
        if (synchronized(lock) { activePaused }) {
            return ManualOverlayGestureReplayResult(executed = false)
        }
        return runCatching { session.recorder.recordOverlayGesture(gesture, onGestureDispatched) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual overlay gesture failed: ${error.message}")
                ManualOverlayGestureReplayResult(executed = false)
            }
    }

    suspend fun detectManualInputTargetAfterClick(
        x: Float,
        y: Float,
    ): ManualInputTarget? {
        val session = synchronized(lock) { activeSession } ?: return null
        if (synchronized(lock) { activePaused }) return null
        return runCatching { session.recorder.detectInputTargetAfterClick(x, y) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual input target detection failed: ${error.message}")
                null
            }
    }

    suspend fun recordManualInputText(
        text: String,
        inputTarget: ManualInputTarget? = null,
    ): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        if (synchronized(lock) { activePaused }) return false
        return runCatching { session.recorder.recordManualInputText(text, inputTarget) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual input_text record failed: ${error.message}")
                false
            }
    }

    suspend fun recordManualPressKey(
        key: String,
        inputTarget: ManualInputTarget? = null,
    ): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        if (synchronized(lock) { activePaused }) return false
        return runCatching { session.recorder.recordManualPressKey(key, inputTarget) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual press_key record failed: ${error.message}")
                false
            }
    }

    suspend fun recordManualWait(durationMs: Long): Boolean {
        val session = synchronized(lock) { activeSession } ?: return false
        if (synchronized(lock) { activePaused }) return false
        return runCatching { session.recorder.recordManualWait(durationMs) }
            .getOrElse { error ->
                OmniLog.w(TAG, "manual wait record failed: ${error.message}")
                false
            }
    }

    suspend fun completeActive(expectedRunId: String): Boolean {
        val completeStartedAtMs = System.currentTimeMillis()
        val session = synchronized(lock) {
            activeSession
                ?.takeIf { it.runId == expectedRunId }
                ?.also {
                    activePaused = true
                    completingRunId = it.runId
                }
        } ?: run {
            OmniLog.w(
                TAG,
                "complete ignored for stale session expected=$expectedRunId active=${activeRunId().orEmpty()}"
            )
            return false
        }
        var stopMs = 0L
        val trace = runCatching {
            val startedAtMs = System.currentTimeMillis()
            session.recorder.stop().also {
                stopMs = System.currentTimeMillis() - startedAtMs
            }
        }
            .getOrElse { error ->
                InternalRunLogStore.finishRun(
                    context = session.context,
                    runId = session.runId,
                    success = false,
                    doneReason = "recording_failed",
                    errorMessage = error.message.orEmpty()
                )
                session.result.complete(
                    HumanTrajectoryLearningResult(
                        success = false,
                        runId = session.runId,
                        name = session.name,
                        description = session.description,
                        actionCount = 0,
                        summary = "",
                        errorMessage = error.message.orEmpty(),
                        actions = emptyList()
                    )
                )
                clearCompletedSession(session)
                OmniLog.w(TAG, "human trajectory learning failed: ${error.message}")
                return true
            }
        var diagnosticsMs = 0L
        var finishRunMs = 0L
        val persisted = runCatching {
            val persistedCount = InternalRunLogStore.getRun(session.context, session.runId)
                ?.steps
                ?.size
                ?: 0
            require(persistedCount == trace.actionCount) {
                "manual_recording_step_count_mismatch:${trace.actionCount}:$persistedCount"
            }
            if (trace.diagnostics.isNotEmpty()) {
                val diagnosticsStartedAtMs = System.currentTimeMillis()
                InternalRunLogStore.updateDiagnostics(
                    context = session.context,
                    runId = session.runId,
                    diagnostics = trace.diagnostics
                )
                diagnosticsMs = System.currentTimeMillis() - diagnosticsStartedAtMs
            }
            persistedCount
        }
        val hasActions = trace.actions.isNotEmpty()
        val actionsExecuted = manualOperationFailuresResolved(trace.actions)
        val success = hasActions && actionsExecuted && persisted.isSuccess
        val doneReason = when {
            persisted.isFailure -> "runlog_persist_failed"
            !actionsExecuted -> "action_execution_failed"
            success -> "user_completed"
            else -> "empty_recording"
        }
        val errorMessage = when {
            persisted.isFailure -> "RunLog 保存失败：${persisted.exceptionOrNull()?.message.orEmpty()}"
            !actionsExecuted -> "部分手动操作执行失败，RunLog 已保留失败动作和原因"
            !hasActions -> "未记录到可复用的人类操作"
            else -> null
        }
        val finishStartedAtMs = System.currentTimeMillis()
        val finalStateId = InternalRunLogStore.getRun(session.context, session.runId)
            ?.steps
            ?.lastOrNull()
            ?.get("after_state_id")
            ?.toString()
        runCatching {
            InternalRunLogStore.finishRun(
                context = session.context,
                runId = session.runId,
                success = success,
                doneReason = doneReason,
                errorMessage = errorMessage,
                finalStateId = finalStateId,
            )
        }.onFailure { error ->
            OmniLog.w(TAG, "finish human trajectory run failed: ${session.runId}, ${error.message}")
        }
        finishRunMs = System.currentTimeMillis() - finishStartedAtMs
        val persistenceTiming = linkedMapOf<String, Any?>(
            "schema_version" to "oob.manual_recording.persist_timing.v1",
            "stop_ms" to stopMs,
            "diagnostics_ms" to diagnosticsMs,
            "finish_run_ms" to finishRunMs,
            "total_ms" to (System.currentTimeMillis() - completeStartedAtMs).coerceAtLeast(0L)
        )
        val diagnostics = if (persisted.isFailure) {
            trace.diagnostics + linkedMapOf(
                "runlog_persist_error" to persisted.exceptionOrNull()?.message.orEmpty(),
                "runlog_persist_error_type" to persisted.exceptionOrNull()?.javaClass?.name.orEmpty(),
                "runlog_persistence_timing" to persistenceTiming
            )
        } else {
            trace.diagnostics + linkedMapOf(
                "runlog_persistence_timing" to persistenceTiming
            )
        }
        runCatching {
            InternalRunLogStore.updateDiagnostics(
                context = session.context,
                runId = session.runId,
                diagnostics = diagnostics
            )
        }.onFailure { error ->
            OmniLog.w(TAG, "update final human trajectory diagnostics failed: ${session.runId}, ${error.message}")
        }
        session.result.complete(
            HumanTrajectoryLearningResult(
                success = success,
                runId = session.runId,
                name = session.name,
                description = session.description,
                actionCount = trace.actionCount,
                summary = trace.summary,
                errorMessage = errorMessage.orEmpty(),
                actions = trace.actions,
                diagnostics = diagnostics
            )
        )
        clearCompletedSession(session)
        if (success) {
            OmniLog.d(
                TAG,
                "human trajectory learning completed: ${session.runId} actions=${trace.actionCount} " +
                    "steps=${persisted.getOrNull()} stop_ms=$stopMs diagnostics_ms=$diagnosticsMs " +
                    "finish_run_ms=$finishRunMs " +
                    "total_ms=${System.currentTimeMillis() - completeStartedAtMs}"
            )
        } else {
            OmniLog.w(
                TAG,
                "human trajectory learning completed with failure: ${session.runId} actions=${trace.actionCount} " +
                    "reason=$doneReason error=${errorMessage.orEmpty()} stop_ms=$stopMs " +
                    "diagnostics_ms=$diagnosticsMs finish_run_ms=$finishRunMs " +
                    "total_ms=${System.currentTimeMillis() - completeStartedAtMs}"
            )
        }
        return true
    }

    fun cancelActive(
        expectedRunId: String,
        message: String = "人工轨迹学习已取消"
    ): Boolean {
        val session = synchronized(lock) {
            if (completingRunId == expectedRunId) return@synchronized null
            activeSession
                ?.takeIf { it.runId == expectedRunId }
                ?.also {
                    activePaused = false
                    activeSession = null
                }
        } ?: run {
            OmniLog.w(
                TAG,
                "cancel ignored for stale session expected=$expectedRunId active=${activeRunId().orEmpty()}"
            )
            return false
        }
        runCatching { session.recorder.stop() }
        InternalRunLogStore.finishRun(
            context = session.context,
            runId = session.runId,
            success = false,
            doneReason = "cancelled",
            errorMessage = message
        )
        session.result.complete(
            HumanTrajectoryLearningResult(
                success = false,
                runId = session.runId,
                name = session.name,
                description = session.description,
                actionCount = 0,
                summary = "",
                errorMessage = message,
                actions = emptyList()
            )
        )
        return true
    }

    private fun clearCompletedSession(session: ActiveSession) {
        synchronized(lock) {
            if (activeSession === session) {
                activeSession = null
                activePaused = false
                completingRunId = null
            }
        }
    }

    internal fun buildRunLogFact(
        runId: String,
        index: Int,
        action: ManualRecordedAction,
    ): Map<String, Any?> = manualRunLogFact(
        stepId = "$runId-human-$index",
        action = action,
        source = "human_trajectory",
    )

    private suspend fun persistAction(
        context: Context,
        runId: String,
        writer: RunLogWriter,
        action: ManualRecordedAction,
    ) {
        val index = writer.stepCount
        runCatching {
            writer.write(
                fact = buildRunLogFact(runId, index, action),
                states = manualRunLogStates(action),
            )
        }
            .onFailure { error ->
                InternalRunLogStore.updateDiagnostics(
                    context = context,
                    runId = runId,
                    diagnostics = mapOf(
                        "record_step_error" to error.message.orEmpty(),
                        "record_step_error_index" to index,
                    ),
                )
            }
    }

}

internal fun manualOperationFailuresResolved(actions: List<ManualRecordedAction>): Boolean =
    actions.indices.all { index ->
        val failed = actions[index]
        failed.operationSuccess || actions.drop(index + 1).any { retry ->
            retry.operationSuccess && retry.action == failed.action
        }
    }

private fun manualRunLogFact(
    stepId: String,
    action: ManualRecordedAction,
    source: String,
): Map<String, Any?> {
    val beforeState = action.beforeState ?: manualPlaceholderState(action, "before")
    val afterState = action.afterState ?: manualPlaceholderState(action, "after")
    return linkedMapOf(
        "before_state_id" to beforeState.stateId,
        "action" to action.action.asMap(),
        "result" to linkedMapOf<String, Any?>(
            "success" to action.operationSuccess,
            "error" to action.operationError.takeUnless { action.operationSuccess },
        ).filterValues { it != null },
        "after_state_id" to afterState.stateId,
        "metadata" to linkedMapOf(
            "step_id" to stepId,
            "status" to if (action.operationSuccess) "succeeded" else "failed",
            "summary" to action.title,
            "duration_ms" to (action.finishedAtMs - action.startedAtMs).coerceAtLeast(0L),
            "started_at_ms" to action.startedAtMs,
            "finished_at_ms" to action.finishedAtMs,
            "source" to source,
            "recording_backend" to action.recordingBackend,
            "event_context" to action.eventContext.takeIf { it.isNotEmpty() },
            "evidence_complete" to action.evidenceComplete,
            "evidence_error" to action.evidenceError,
        ).filterValues { it != null },
    )
}

internal fun manualRunLogStates(action: ManualRecordedAction): List<Map<String, Any?>> =
    listOf(
        (action.beforeState ?: manualPlaceholderState(action, "before")).asMap(),
        (action.afterState ?: manualPlaceholderState(action, "after")).asMap(),
    )

private fun manualPlaceholderState(action: ManualRecordedAction, stage: String): State = State.create(
    packageName = action.beforePackageName ?: action.afterPackageName.orEmpty(),
    activityName = "manual_recording_$stage",
    displayWidth = action.displayWidth.coerceAtLeast(1),
    displayHeight = action.displayHeight.coerceAtLeast(1),
    xml = "<hierarchy capture=\"unavailable\" stage=\"$stage\" />",
)
