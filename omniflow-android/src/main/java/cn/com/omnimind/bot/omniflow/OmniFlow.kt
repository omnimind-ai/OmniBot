package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import cn.com.omnimind.androidgui.AndroidGuiDisplayOffException
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.RunLogWriter
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.bot.omniflow.ui.ExecutionControls
import cn.com.omnimind.bot.omniflow.ui.ExecutionPhase
import cn.com.omnimind.bot.omniflow.ui.ManualCompletionRequested
import cn.com.omnimind.bot.omniflow.ui.initialExecutionPhase
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ExecutionRequest(
    val id: String,
    val goal: String,
    val source: String,
    val runLogToolName: String,
    val toolCall: OmniFlow.ToolCall,
    val title: String = goal,
    val operationDescription: String = goal,
    val startedAtMs: Long = System.currentTimeMillis(),
    val cancelledDoneReason: String = "cancelled",
    val stoppedErrorCode: String = "GUI_TASK_STOPPED",
    val failedErrorCode: String = "GUI_TASK_FAILED",
)

object OmniFlow {
    data class ToolCall(
        val name: String,
        val arguments: Map<String, Any?> = emptyMap(),
    )

    data class Hooks(
        val beforeOperation: suspend () -> Unit = {},
        val stopRequested: () -> Boolean = { false },
        val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
    )

    data class Result(
        val payload: Map<String, Any?>,
        val finalStateId: String?,
    )

    private val executionMutex = Mutex()
    private val controlMutex = Mutex()
    private val executions = ExecutionRegistry()
    private var controlDispatcher: OmniFlowDeviceDispatcher? = null

    fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
    ) {
        OmniFlowPythonRuntime.configure(platform, runtimeProvider)
    }

    fun warmup(context: Context) {
        OmniFlowPythonRuntime.start(context)
    }

    suspend fun prepareAndStart(context: Context): OmniFlowRuntimeManifest =
        OmniFlowPythonRuntime.prepareAndStart(context)

    suspend fun shutdown() {
        executions.stop()
        controlMutex.withLock { controlDispatcher = null }
        OmniFlowPythonRuntime.shutdown()
    }

    suspend fun observe(
        context: Context,
        captureScreenshot: Boolean = false,
        waitToStabilize: Boolean = false,
    ): Map<String, Any?> = control(
        context = context,
        method = "observe",
        payload = mapOf(
            "screenshot" to captureScreenshot,
            "wait_to_stabilize" to waitToStabilize,
        ),
    )

    suspend fun control(
        context: Context,
        method: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = controlMutex.withLock {
        if (method == "reset") {
            controlDispatcher = null
            return@withLock mapOf("reset" to true)
        }
        val dispatcher = controlDispatcher ?: OmniFlowDeviceDispatcher.create(context).also {
            controlDispatcher = it
        }
        dispatcher.callDevice(method = method, payload = payload)
    }

    private suspend fun executeInteractiveTool(
        context: Context,
        request: ExecutionRequest,
        modelClient: OmniFlowModelClient? = null,
        hooks: Hooks = Hooks(),
    ): Result {
        require(request.id.isNotBlank()) { "run_id_required" }
        val appContext = context.applicationContext
        val runFinished = AtomicBoolean(false)
        InternalRunLogStore.beginRun(
            context = appContext,
            runId = request.id,
            goal = request.goal,
            source = request.source,
            toolName = request.runLogToolName,
            operationDescription = request.operationDescription,
            startedAtMs = request.startedAtMs,
        )
        return try {
            executionMutex.withLock {
                coroutineScope {
                    val stopped = AtomicBoolean(false)
                    val completionRequested = AtomicBoolean(false)
                    var executionJob: Job? = null
                    val requestStop = {
                        if (stopped.compareAndSet(false, true)) {
                            executionJob?.cancel(CancellationException("OmniFlow execution stopped"))
                        }
                    }
                    val requestComplete = {
                        if (completionRequested.compareAndSet(false, true)) {
                            executionJob?.cancel(ManualCompletionRequested())
                        }
                    }
                    val executionUi = ExecutionControls.start(
                        context = context,
                        title = request.title,
                        initialPhase = initialExecutionPhase(usesModel = modelClient != null),
                        onStop = requestStop,
                        onComplete = requestComplete,
                    )
                    val registration = executions.begin(
                        runId = request.id,
                        onStop = requestStop,
                    )
                    var result: Map<String, Any?>? = null
                    var cancelled = false
                    try {
                        val beforeOperation: suspend () -> Unit = {
                            executionUi.awaitRunning()
                            ensureRunning(stopped, hooks)
                            hooks.beforeOperation()
                            ensureRunning(stopped, hooks)
                        }
                        val host = OmniFlowDeviceDispatcher(
                            context = context,
                            request = request,
                            runFinished = runFinished,
                            modelClient = modelClient,
                            beforeOperation = beforeOperation,
                            stopRequested = { stopped.get() || hooks.stopRequested() },
                            onPhase = executionUi::updatePhase,
                            beforeAction = executionUi::avoidAction,
                            afterAction = executionUi::restoreDefaultPosition,
                            beforeScreenshot = executionUi::hideForScreenshot,
                            afterScreenshot = executionUi::showAfterScreenshot,
                            onProgress = { progress, extras ->
                                executionUi.update(progress)
                                hooks.onProgress(progress, extras)
                            },
                        )
                        val operation = async(start = CoroutineStart.LAZY) { host.execute() }
                        executionJob = operation
                        when {
                            completionRequested.get() ->
                                operation.cancel(ManualCompletionRequested())
                            stopped.get() -> operation.cancel(
                                CancellationException("OmniFlow execution stopped"),
                            )
                        }
                        try {
                            operation.start()
                            val payload = operation.await()
                            result = payload
                            Result(payload, host.currentStateId)
                        } catch (error: CancellationException) {
                            if (!completionRequested.get()) throw error
                            val payload = host.finishManualCompletion()
                            result = payload
                            Result(payload, host.currentStateId)
                        }
                    } catch (error: CancellationException) {
                        cancelled = true
                        throw error
                    } finally {
                        executions.end(registration)
                        val message = completionMessage(result, cancelled || stopped.get())
                        val visibleMs = completionOverlayVisibleMs(request.source, result)
                        withContext(NonCancellable) {
                            executionUi.finish(message, visibleMs)
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            finishCancelledRun(appContext, request, runFinished, error)
            throw error
        }
    }

    suspend fun callTool(
        context: Context,
        toolCall: ToolCall,
        goal: String = toolCall.name,
        runId: String? = null,
        source: String = "function",
        runLogToolName: String = toolCall.name,
        modelClient: OmniFlowModelClient? = null,
        hooks: Hooks = Hooks(),
    ): Result {
        require(toolCall.name.isNotBlank()) { "tool_call_name_required" }
        if (toolCall.name in NON_INTERACTIVE_TOOL_NAMES) {
            return Result(
                payload = OmniFlowDeviceDispatcher(
                    context = context,
                    modelClient = modelClient,
                ).call(
                    operation = "tools/call",
                    payload = mapOf(
                        "name" to toolCall.name,
                        "arguments" to toolCall.arguments,
                    ),
                ),
                finalStateId = null,
            )
        }
        val startedAtMs = System.currentTimeMillis()
        return executeInteractiveTool(
            context = context,
            request = ExecutionRequest(
                id = runId?.trim().orEmpty().ifBlank { "tool-${UUID.randomUUID()}" },
                goal = goal.ifBlank { toolCall.name },
                source = source,
                runLogToolName = runLogToolName,
                toolCall = toolCall,
                title = goal.ifBlank { toolCall.name },
                operationDescription = "Tool: ${toolCall.name}",
                startedAtMs = startedAtMs,
                cancelledDoneReason = "function_stopped",
                stoppedErrorCode = "FUNCTION_CALL_STOPPED",
                failedErrorCode = "FUNCTION_CALL_FAILED",
            ),
            modelClient = modelClient,
            hooks = hooks,
        )
    }

    internal suspend fun callTool(
        context: Context,
        toolName: String,
        arguments: Map<String, Any?>,
        goal: String,
        runId: String,
        source: String,
        runLogToolName: String,
        modelClient: OmniFlowModelClient,
        hooks: Hooks,
    ): Result = callTool(
        context = context,
        toolCall = ToolCall(toolName, arguments),
        goal = goal,
        runId = runId,
        source = source,
        runLogToolName = runLogToolName,
        modelClient = modelClient,
        hooks = hooks,
    )

    fun stop(runOrTaskId: String? = null): Boolean = executions.stop(runOrTaskId)

    private fun ensureRunning(stopped: AtomicBoolean, hooks: Hooks) {
        if (stopped.get() || hooks.stopRequested()) {
            throw CancellationException("OmniFlow execution stopped")
        }
    }

    private fun completionMessage(result: Map<String, Any?>?, stopped: Boolean): String = when {
        stopped -> "任务已停止"
        result?.get("done_reason") == "waiting_input" -> "任务等待输入"
        result?.get("success") == true -> "任务已完成"
        else -> "任务执行失败"
    }

    private val NON_INTERACTIVE_TOOL_NAMES = setOf(
        "list_functions",
        "get_function",
        "delete_function",
        "clear_functions",
        "list_run_logs",
        "get_run_log",
        "get_run_log_state",
        "save_function",
    )
}

internal class GuiDisplayOffCancellationException : CancellationException(
    "android_gui_display_off",
)

class OmniFlowDeviceDispatcher internal constructor(
    context: Context,
    private val request: ExecutionRequest? = null,
    private val runFinished: AtomicBoolean = AtomicBoolean(false),
    modelClient: OmniFlowModelClient? = null,
    private val beforeOperation: suspend () -> Unit = {},
    private val stopRequested: () -> Boolean = { false },
    private val onPhase: (ExecutionPhase) -> Unit = {},
    private val beforeAction: suspend (Action) -> Unit = {},
    private val afterAction: suspend () -> Unit = {},
    private val beforeScreenshot: suspend () -> Unit = {},
    private val afterScreenshot: suspend () -> Unit = {},
    private val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    companion object {
        fun create(
            context: Context,
            modelClient: OmniFlowModelClient? = null,
        ): OmniFlowDeviceDispatcher = OmniFlowDeviceDispatcher(
            context = context,
            modelClient = modelClient,
        )
    }

    private val appContext = context.applicationContext
    private val environment = AndroidGuiEnvironment(appContext)
    private val writer = request?.let { activeRun ->
        RunLogWriter { record ->
            InternalRunLogStore.upsertRecordedStep(appContext, activeRun.id, record)
        }
    }
    private val modelHost = modelClient?.let { client ->
        OmniFlowModelHost(client) { thinking ->
            onProgress(thinking, progressPayload(mapOf("thinking" to thinking)))
        }
    }
    private val modelMetrics = ModelRunLogMetrics()
    private val hostCall = OmniFlowPythonHostCall(::callDevice)
    private val externalRunWriters = ConcurrentHashMap<String, RunLogWriter>()

    var currentStateId: String? = null
        private set

    private var currentState: State? = null

    private var previousActionTool: String? = null

    suspend fun call(
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = OmniFlowPythonRuntime.call(
        context = appContext,
        operation = operation,
        payload = payload,
        hostCall = hostCall,
    )

    suspend fun execute(): Map<String, Any?> {
        val activeRun = requireNotNull(request) { "run_not_configured" }
        return try {
            beforeOperation()
            check(environment.awaitReady()) { "android_gui_accessibility_not_ready" }
            beforeOperation()
            val payload = mapOf(
                "name" to activeRun.toolCall.name,
                "arguments" to activeRun.toolCall.arguments,
                "_meta" to buildMap<String, Any?> {
                    put("run_id", activeRun.id)
                    put("started_at_ms", activeRun.startedAtMs)
                    put("goal", activeRun.goal)
                    if (modelHost != null) {
                        put("model", OmniVlmPlugin.MODEL_SCENE)
                    }
                },
            )
            val result = call("tools/call", payload)
            require(firstText(result["run_id"]) == activeRun.id) {
                "android_gui_run_id_mismatch"
            }
            finishRun(result)
            applyPostRunActions(result)
        } catch (error: ManualCompletionRequested) {
            throw error
        } catch (error: AndroidGuiDisplayOffException) {
            finishRun(cancelledFailure(activeRun.cancelledDoneReason, error))
            throw GuiDisplayOffCancellationException()
        } catch (error: CancellationException) {
            finishRun(cancelledFailure(activeRun.cancelledDoneReason, error))
            throw error
        } catch (error: Exception) {
            failure(activeRun, error).also(::finishRun)
        }
    }

    fun finishManualCompletion(): Map<String, Any?> {
        val activeRun = requireNotNull(request) { "run_not_configured" }
        return manualCompletionResult(
            runId = activeRun.id,
            startedAtMs = activeRun.startedAtMs,
            source = activeRun.source,
            functionId = activeRun.toolCall.name,
        ).also(::finishRun)
    }

    suspend fun callDevice(
        method: String,
        payload: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        when (method) {
            "observe" -> observe(payload)
            "act" -> act(payload)
            "get_run_log" -> getRunLog(payload)
            "get_state" -> getState(payload)
            "installed_apps" -> installedApps()
            "list_run_logs" -> listRunLogs(payload)
            "begin_run" -> beginExternalRun(payload)
            "record_step" -> recordStep(payload)
            "finish_run" -> finishExternalRun(payload)
            "model_turn" -> modelTurn(payload)
            "complete_json" -> completeJson(payload)
            "schedule_operation" -> schedule(payload)
            "update_run_log_diagnostics" -> updateDiagnostics(payload)
            "request_input" -> error("request_input_must_be_deferred")
            else -> error("unsupported_host_call:$method")
        }

    private suspend fun observe(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        val captureScreenshot = payload["screenshot"] != false
        val waitToStabilize = payload["wait_to_stabilize"] == true
        val suppressOverlay = shouldSuppressOverlayForScreenshot(
            captureScreenshot = captureScreenshot,
            screenshotExcludesOverlays = environment.screenshotExcludesOverlays(),
        )
        if (suppressOverlay) beforeScreenshot()
        return try {
            environment.observeWithDiagnostics(
                captureScreenshot = captureScreenshot,
                waitToStabilize = waitToStabilize,
            ).also {
                currentStateId = it.state.stateId
                currentState = it.state
            }.state.asHostMap(includeImage = captureScreenshot)
        } finally {
            if (suppressOverlay) afterScreenshot()
        }
    }

    private suspend fun act(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        onPhase(ExecutionPhase.AUTOMATIC)
        val action = Action.fromMap(mapValue(payload["action"]))
        val suppliedState = mapValue(payload["state"])
        val sourceState = if (suppliedState.isNotEmpty()) {
            State.fromMap(suppliedState)
        } else {
            currentState ?: error("host_action_state_required")
        }
        require(sourceState.stateId == currentStateId) { "host_action_state_stale" }
        if (blocksPaymentConfirmation(sourceState, action)) {
            return mapOf(
                "success" to false,
                "error" to "payment_confirmation_blocked",
                "extra" to mapOf("message" to "payment_confirmation_blocked"),
            )
        }
        beforeAction(action)
        return try {
            val metadata = mapValue(payload["metadata"])
            onProgress(
                firstText(metadata["summary"], action.tool).ifBlank { "GUI action" },
                progressPayload(metadata + mapOf("action" to action.asMap())),
            )
            if (
                shouldSkipFunctionReplayImeBack(
                    source = request?.source,
                    previousTool = previousActionTool,
                    action = action,
                    inputMethodTop = environment.inputMethodTop(),
                )
            ) {
                previousActionTool = action.tool
                return mapOf(
                    "success" to true,
                    "extra" to mapOf(
                        "message" to "press_key_back_noop_ime_absent",
                        "diagnostics" to mapOf("ime_dismiss" to "already_hidden"),
                    ),
                )
            }
            val result = environment.act(
                action = action,
                awaitStabilization = payload["await_stabilization"] != false,
            )
            if (result.success) previousActionTool = action.tool
            linkedMapOf<String, Any?>(
                "success" to result.success,
                "error" to result.message.takeUnless { result.success },
                "extra" to linkedMapOf(
                    "message" to result.message,
                    "diagnostics" to result.diagnostics,
                ),
            ).filterValues { it != null }
        } finally {
            afterAction()
        }
    }

    private fun getRunLog(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedRunId = firstText(payload["run_id"])
        require(requestedRunId.isNotEmpty()) { "run_id_required" }
        return InternalRunLogStore.timelinePayload(appContext, requestedRunId)
    }

    private fun getState(payload: Map<String, Any?>): Map<String, Any?> {
        val stateId = firstText(payload["state_id"])
        require(stateId.isNotEmpty()) { "state_id_required" }
        val state = InternalRunLogStore.statePayload(appContext, stateId)
            .also { require(it.isNotEmpty()) { "state_not_found:$stateId" } }
        return State.fromMap(state).asHostMap(includeImage = true)
    }

    private suspend fun installedApps(): Map<String, Any?> =
        mapOf("apps" to environment.installedApplications())

    private fun listRunLogs(payload: Map<String, Any?>): Map<String, Any?> =
        InternalRunLogStore.listRuns(
            context = appContext,
            limit = intValue(payload["limit"], defaultValue = 50).coerceIn(1, 200),
            offset = intValue(payload["offset"], defaultValue = 0).coerceAtLeast(0),
            source = firstText(payload["source"]),
            status = firstText(payload["status"]),
            model = firstText(payload["model"]),
            query = firstText(payload["query"]),
        )

    private suspend fun recordStep(payload: Map<String, Any?>): Map<String, Any?> {
        val fact = mapValue(payload["fact"])
        requireStateExists(firstText(fact["before_state_id"]))
        requireStateExists(firstText(fact["after_state_id"]))
        val externalRunId = firstText(payload["run_id"])
        val activeWriter = writer ?: externalRunWriters[externalRunId]
        val record = requireNotNull(activeWriter) { "record_step_run_not_configured" }.write(fact)
        return mapOf("step" to record.step)
    }

    private fun beginExternalRun(payload: Map<String, Any?>): Map<String, Any?> {
        val runId = firstText(payload["run_id"])
        require(runId.isNotEmpty()) { "run_id_required" }
        InternalRunLogStore.beginRun(
            context = appContext,
            runId = runId,
            goal = firstText(payload["goal"]),
            source = firstText(payload["source"]).ifBlank { "mcp" },
            toolName = firstText(payload["tool_name"]),
            operationDescription = firstText(payload["description"], payload["goal"]),
            startedAtMs = (payload["started_at_ms"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
        )
        externalRunWriters[runId] = RunLogWriter { record ->
            InternalRunLogStore.upsertRecordedStep(appContext, runId, record)
        }
        return mapOf("started" to true, "run_id" to runId)
    }

    private fun finishExternalRun(payload: Map<String, Any?>): Map<String, Any?> {
        val runId = firstText(payload["run_id"])
        require(runId.isNotEmpty()) { "run_id_required" }
        externalRunWriters.remove(runId)
        InternalRunLogStore.finishRun(
            context = appContext,
            runId = runId,
            success = payload["success"] == true,
            doneReason = firstText(payload["done_reason"]).ifBlank {
                if (payload["success"] == true) "finished" else "error"
            },
            errorMessage = firstText(payload["error_message"]).takeIf(String::isNotEmpty),
            finishedAtMs = (payload["finished_at_ms"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
            finalStateId = firstText(payload["final_state_id"]).takeIf(String::isNotEmpty),
        )
        return mapOf("finished" to true, "run_id" to runId)
    }

    private suspend fun modelTurn(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        onPhase(ExecutionPhase.REASONING)
        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            requireNotNull(modelHost) { "model_turn_not_available" }.modelTurn(payload).also {
                modelMetrics.recordSuccess(
                    result = it,
                    durationMs = SystemClock.elapsedRealtime() - startedAtMs,
                )
            }
        } catch (error: Throwable) {
            modelMetrics.recordFailure(SystemClock.elapsedRealtime() - startedAtMs)
            throw error
        }
    }

    private suspend fun completeJson(payload: Map<String, Any?>): Map<String, Any?> {
        beforeOperation()
        return modelHost?.completeJson(
            payload = payload,
            modelOverride = OmniVlmPlugin.MODEL_SCENE,
        ) ?: OmniFlowModelHost.completeJson(payload)
    }

    private fun schedule(payload: Map<String, Any?>): Map<String, Any?> =
        OmniFlowPythonRuntime.schedule(
            context = appContext,
            operation = firstText(payload["operation"]),
            payload = mapValue(payload["payload"]),
            hostCall = hostCall,
        )

    private fun updateDiagnostics(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedRunId = firstText(payload["run_id"])
        require(requestedRunId.isNotEmpty()) { "run_id_required" }
        val diagnostics = mapValue(payload["diagnostics"])
        require(diagnostics.isNotEmpty()) { "run_log_diagnostics_required" }
        InternalRunLogStore.updateDiagnostics(appContext, requestedRunId, diagnostics)
        return mapOf("updated" to true)
    }

    private fun requireStateExists(stateId: String) {
        require(stateId.isNotEmpty()) { "state_id_required" }
        require(InternalRunLogStore.statePayload(appContext, stateId).isNotEmpty()) {
            "run_log_state_not_persisted:$stateId"
        }
    }

    private fun progressPayload(value: Map<String, Any?>): Map<String, Any?> =
        request?.let { value + ("run_id" to it.id) } ?: value

    private fun finishRun(result: Map<String, Any?>) {
        val activeRun = requireNotNull(request)
        if (!runFinished.compareAndSet(false, true)) return
        val success = result["success"] == true
        val resultFinalStateId = firstText(mapValue(result["final_state"])["state_id"])
        val diagnostics = buildMap<String, Any?> {
            plannerRunLogDiagnostics(result)?.let(::putAll)
            putAll(modelMetrics.diagnostics())
        }
        diagnostics.takeIf(Map<String, Any?>::isNotEmpty)?.let {
            InternalRunLogStore.updateDiagnostics(
                context = appContext,
                runId = activeRun.id,
                diagnostics = it,
            )
        }
        InternalRunLogStore.finishRun(
            context = appContext,
            runId = activeRun.id,
            success = success,
            doneReason = firstText(result["done_reason"]).ifBlank {
                if (success) "finished" else "error"
            },
            errorMessage = firstText(result["error_message"], result["error_code"])
                .takeIf(String::isNotEmpty),
            finishedAtMs = (result["finished_at_ms"] as? Number)?.toLong()
                ?: System.currentTimeMillis(),
            finalStateId = resultFinalStateId.ifEmpty { currentStateId },
        )
    }

    private suspend fun applyPostRunActions(
        result: Map<String, Any?>,
    ): Map<String, Any?> {
        val actions = (result["post_run_actions"] as? List<*>).orEmpty()
            .mapNotNull { it as? Map<*, *> }
        if (actions.isEmpty()) return result
        var merged = result - "post_run_actions"
        actions.forEach { rawAction ->
            val action = rawAction.entries.associate { (key, value) -> key.toString() to value }
            val name = firstText(action["name"])
            if (name != "save_function") return@forEach
            val arguments = mapValue(action["arguments"])
            val registration = runCatching {
                call(
                    "tools/call",
                    mapOf(
                        "name" to name,
                        "arguments" to arguments,
                    ),
                )
            }.fold(
                onSuccess = { conversion ->
                    linkedMapOf<String, Any?>(
                        "auto_registered" to (
                            conversion["success"] == true &&
                                conversion["registered"] == true
                            ),
                        "registered_function_id" to conversion["function_id"],
                        "registration_status" to conversion["status"],
                        "registration_error" to firstText(
                            conversion["error_message"],
                            conversion["error_code"],
                            conversion["error"],
                        ).takeIf(String::isNotEmpty),
                    ).filterValues { it != null }
                },
                onFailure = { error ->
                    mapOf(
                        "auto_registered" to false,
                        "registration_error" to error.message.orEmpty().ifBlank {
                            error.javaClass.simpleName
                        },
                    )
                },
            )
            merged += registration
        }
        return merged
    }

    private fun failure(activeRun: ExecutionRequest, error: Exception): Map<String, Any?> {
        val stopped = stopRequested()
        val finishedAtMs = System.currentTimeMillis()
        return linkedMapOf<String, Any?>(
            "success" to false,
            "status" to "failed",
            "run_id" to activeRun.id,
            "function_id" to activeRun.toolCall.name.takeIf(String::isNotEmpty),
            "source" to activeRun.source,
            "started_at_ms" to activeRun.startedAtMs,
            "finished_at_ms" to finishedAtMs,
            "duration_ms" to (finishedAtMs - activeRun.startedAtMs).coerceAtLeast(0L),
            "error_code" to if (stopped) activeRun.stoppedErrorCode else activeRun.failedErrorCode,
            "error_message" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            "done_reason" to if (stopped) activeRun.cancelledDoneReason else "error",
        ).filterValues { it != null }
    }

    private fun cancelledFailure(doneReason: String, error: Exception): Map<String, Any?> =
        mapOf(
            "success" to false,
            "done_reason" to doneReason,
            "error_message" to error.message.orEmpty().ifBlank { error.javaClass.simpleName },
        )
}

private fun finishCancelledRun(
    context: Context,
    request: ExecutionRequest,
    runFinished: AtomicBoolean,
    error: CancellationException,
) {
    if (!runFinished.compareAndSet(false, true)) return
    InternalRunLogStore.finishRun(
        context = context,
        runId = request.id,
        success = false,
        doneReason = request.cancelledDoneReason,
        errorMessage = error.message.orEmpty().ifBlank { "OmniFlow execution cancelled" },
    )
}

internal fun manualCompletionResult(
    runId: String,
    startedAtMs: Long,
    source: String,
    functionId: String,
    finishedAtMs: Long = System.currentTimeMillis(),
): Map<String, Any?> = mapOf(
    "success" to true,
    "status" to "succeeded",
    "run_id" to runId,
    "function_id" to functionId,
    "source" to source,
    "started_at_ms" to startedAtMs,
    "finished_at_ms" to finishedAtMs,
    "duration_ms" to (finishedAtMs - startedAtMs).coerceAtLeast(0L),
    "done_reason" to "manual_finished",
)

internal fun State.asHostMap(includeImage: Boolean): Map<String, Any?> {
    if (!includeImage) return asMap()
    val screenshot = screenshotPath
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?: return asMap()
    val visualRgb = screenshot.visualRgbPayload()
    return asMap() + buildMap {
        put("image_base64", Base64.getEncoder().encodeToString(screenshot.readBytes()))
        visualRgb?.let { put("extra", mapOf("visual_rgb" to it)) }
    }
}

private fun File.visualRgbPayload(maxEdge: Int = 384): Map<String, Any?>? =
    runCatching {
        val decoded = BitmapFactory.decodeFile(absolutePath) ?: return null
        val scale = minOf(1.0, maxEdge.toDouble() / maxOf(decoded.width, decoded.height))
        val width = maxOf(1, (decoded.width * scale).toInt())
        val height = maxOf(1, (decoded.height * scale).toInt())
        val bitmap = if (width == decoded.width && height == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
        }
        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            encodeVisualRgb(width, height, pixels)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

internal fun encodeVisualRgb(
    width: Int,
    height: Int,
    argbPixels: IntArray,
): Map<String, Any?> {
    require(width > 0 && height > 0 && argbPixels.size == width * height) {
        "visual_rgb_dimensions_invalid"
    }
    val rgb = ByteArray(argbPixels.size * 3)
    argbPixels.forEachIndexed { index, color ->
        val offset = index * 3
        rgb[offset] = (color shr 16).toByte()
        rgb[offset + 1] = (color shr 8).toByte()
        rgb[offset + 2] = color.toByte()
    }
    val compressed = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(rgb) }
        output.toByteArray()
    }
    return linkedMapOf(
        "width" to width,
        "height" to height,
        "compression" to "zlib",
        "data_base64" to Base64.getEncoder().encodeToString(compressed),
    )
}

internal fun shouldSkipFunctionReplayImeBack(
    source: String?,
    previousTool: String?,
    action: Action,
    inputMethodTop: Int?,
): Boolean =
    source == "function" &&
        previousTool == OobActionSchema.TOOL_INPUT_TEXT &&
        action.tool == OobActionSchema.TOOL_PRESS_KEY &&
        action.args[OobActionSchema.ARG_KEY]?.toString()?.trim()?.lowercase() == "back" &&
        inputMethodTop == null

internal fun completionOverlayVisibleMs(
    source: String,
    result: Map<String, Any?>?,
): Long = when {
    source.equals("vlm", ignoreCase = true) -> 0L
    result?.get("success") == false -> 2_500L
    else -> 900L
}

internal fun shouldSuppressOverlayForScreenshot(
    captureScreenshot: Boolean,
    screenshotExcludesOverlays: Boolean,
): Boolean = captureScreenshot && !screenshotExcludesOverlays

internal class ModelRunLogMetrics {
    private data class Call(
        val durationMs: Long,
        val success: Boolean,
        val requestedModel: String,
        val resolvedModel: String,
        val usage: Map<String, Any?>,
    )

    private val calls = mutableListOf<Call>()

    fun recordSuccess(result: Map<String, Any?>, durationMs: Long) {
        calls += Call(
            durationMs = durationMs.coerceAtLeast(0L),
            success = true,
            requestedModel = firstText(result["requested_model"]),
            resolvedModel = firstText(result["resolved_model"]),
            usage = mapValue(result["usage"]),
        )
    }

    fun recordFailure(durationMs: Long) {
        calls += Call(
            durationMs = durationMs.coerceAtLeast(0L),
            success = false,
            requestedModel = "",
            resolvedModel = "",
            usage = emptyMap(),
        )
    }

    fun diagnostics(): Map<String, Any?> {
        if (calls.isEmpty()) return emptyMap()
        val resolvedModels = calls.map(Call::resolvedModel).filter(String::isNotEmpty).distinct()
        val requestedModels = calls.map(Call::requestedModel).filter(String::isNotEmpty).distinct()
        val tokenUsage = linkedMapOf<String, Any?>(
            "call_count" to calls.size,
            "successful_call_count" to calls.count(Call::success),
            "failed_call_count" to calls.count { !it.success },
        )
        sumUsage("prompt_tokens")?.let { tokenUsage["prompt_tokens"] = it }
        sumUsage("completion_tokens")?.let { tokenUsage["completion_tokens"] = it }
        sumUsage("total_tokens")?.let { tokenUsage["total_tokens"] = it }
        if (resolvedModels.isNotEmpty()) {
            tokenUsage["resolved_models"] = resolvedModels
            if (resolvedModels.size == 1) tokenUsage["resolved_model"] = resolvedModels.single()
        }
        if (requestedModels.size == 1) tokenUsage["model"] = requestedModels.single()
        return linkedMapOf(
            "model_duration_ms" to calls.sumOf(Call::durationMs),
            "token_usage" to tokenUsage,
            "token_usage_by_call" to calls.mapIndexed { index, call ->
                linkedMapOf<String, Any?>(
                    "call_index" to index,
                    "duration_ms" to call.durationMs,
                    "success" to call.success,
                    "requested_model" to call.requestedModel.takeIf(String::isNotEmpty),
                    "resolved_model" to call.resolvedModel.takeIf(String::isNotEmpty),
                    "token_usage" to call.usage.takeIf(Map<String, Any?>::isNotEmpty),
                ).filterValues { it != null }
            },
            "resolved_models" to resolvedModels.takeIf(List<String>::isNotEmpty),
        ).filterValues { it != null }
    }

    private fun sumUsage(key: String): Long? {
        val values = calls.mapNotNull { call ->
            when (val value = call.usage[key]) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        }
        return values.takeIf(List<Long>::isNotEmpty)?.sum()
    }
}

internal fun plannerRunLogDiagnostics(
    result: Map<String, Any?>,
): Map<String, Any?>? = buildMap<String, Any?> {
    mapValue(result["planner_diagnostics"])
        .takeIf(Map<String, Any?>::isNotEmpty)
        ?.let { put("planner", it) }
    firstText(result["function_id"])
        .takeIf(String::isNotEmpty)
        ?.let { put("function_id", it) }
}.takeIf(Map<String, Any?>::isNotEmpty)
