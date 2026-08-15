package cn.com.omnimind.assists.task.recording

import cn.com.omnimind.androidgui.AndroidGuiActionResult
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.State
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ManualRecordingCommand(
    val action: Action,
    val title: String,
    val summary: String,
    val source: String,
    val startedAtMs: Long,
    val persistOnFailure: Boolean = false,
)

internal data class ManualRecordingObservation(
    val state: State? = null,
    val captureError: String? = null,
)

internal data class ManualRecordingEngineStats(
    val received: Int,
    val committed: Int,
    val failed: Int,
    val pending: Int,
    val pendingSummary: String?,
)

internal data class ManualRecordingOutcome(
    val executed: Boolean,
    val recorded: Boolean,
    val operationResult: AndroidGuiActionResult,
)

internal class ManualRecordingEngine(
    private val journal: ManualRecordingJournal,
    private val observe: suspend (stage: String, command: ManualRecordingCommand) -> ManualRecordingObservation,
    private val execute: suspend (command: ManualRecordingCommand) -> AndroidGuiActionResult,
    private val onActionRecorded: suspend (index: Int, action: ManualRecordedAction) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val performMutex = Mutex()
    private val stateLock = Any()
    private var received = 0
    private var committed = 0
    private var failed = 0
    private var pending = 0
    private var pendingSummary: String? = null

    suspend fun perform(
        command: ManualRecordingCommand,
        onDispatched: suspend (AndroidGuiActionResult) -> Unit = {},
    ): ManualRecordingOutcome = performMutex.withLock {
        val sequence = synchronized(stateLock) {
            received += 1
            pending += 1
            pendingSummary = command.summary
            received
        }
        var recorded = false
        var executed = false
        try {
            val before = safeObserve("${sequence}_before", command)
            val operationResult = try {
                execute(command)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AndroidGuiActionResult(
                    success = false,
                    message = error.message.orEmpty().ifBlank { "${command.action.tool} execution failed" },
                )
            }
            try {
                onDispatched(operationResult)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {}
            executed = operationResult.success
            val after = safeObserve(stage = "${sequence}_after", command = command)
            if (operationResult.success || command.persistOnFailure) {
                val sourceStateRequired = command.action.tool in SOURCE_STATE_REQUIRED_TOOLS
                val evidenceComplete = !sourceStateRequired || !before.state?.xml.isNullOrBlank()
                val action = ManualRecordedAction(
                    action = command.action,
                    title = command.title,
                    beforeState = before.state,
                    afterState = after.state,
                    startedAtMs = command.startedAtMs,
                    finishedAtMs = nowMs(),
                    summary = command.summary,
                    eventContext = linkedMapOf(
                        "schema_version" to "oob.manual_recording.event.v2",
                        "sequence" to sequence,
                        "source" to command.source,
                        "dispatch_status" to if (operationResult.success) "completed" else "failed",
                        "dispatch_error" to operationResult.message.takeUnless {
                            operationResult.success
                        },
                        "evidence_complete" to evidenceComplete,
                        "evidence_error" to before.captureError.takeUnless { evidenceComplete },
                    ).filterValues { it != null } + operationResult.diagnostics,
                    recordingBackend = command.source,
                    displayWidth = after.state?.displayWidth ?: before.state?.displayWidth ?: 0,
                    displayHeight = after.state?.displayHeight ?: before.state?.displayHeight ?: 0,
                    evidenceComplete = evidenceComplete,
                    evidenceError = before.captureError.takeUnless { evidenceComplete },
                    operationSuccess = operationResult.success,
                    operationError = operationResult.message.takeUnless { operationResult.success },
                )
                val index = journal.size()
                onActionRecorded(index, action)
                journal.append(action)
                recorded = true
            }
            ManualRecordingOutcome(
                executed = operationResult.success,
                recorded = recorded,
                operationResult = operationResult,
            )
        } finally {
            synchronized(stateLock) {
                pending = (pending - 1).coerceAtLeast(0)
                if (recorded && executed) committed += 1 else failed += 1
                pendingSummary = null
            }
        }
    }

    suspend fun awaitIdle() {
        performMutex.withLock { Unit }
    }

    fun stats(): ManualRecordingEngineStats = synchronized(stateLock) {
        ManualRecordingEngineStats(
            received = received,
            committed = committed,
            failed = failed,
            pending = pending,
            pendingSummary = pendingSummary,
        )
    }

    private suspend fun safeObserve(
        stage: String,
        command: ManualRecordingCommand,
    ): ManualRecordingObservation {
        return try {
            observe(stage, command).let { observation ->
                if (!observation.state?.xml.isNullOrBlank()) observation
                else observation.copy(captureError = observation.captureError ?: "xml_unavailable")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ManualRecordingObservation(
                captureError = error.message.orEmpty().ifBlank {
                    "${error.javaClass.simpleName}:xml_capture_failed"
                },
            )
        }
    }

    private companion object {
        private val SOURCE_STATE_REQUIRED_TOOLS = setOf(
            "click",
            "long_press",
            "input_text",
            "swipe",
        )
    }
}
