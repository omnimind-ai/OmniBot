package cn.com.omnimind.baselib.runlog

import android.content.Context
import android.os.Trace
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private const val CANONICAL_RUN_LOG_SCHEMA_VERSION = "omniflow.canonical_run_log.v1"

data class CanonicalRunLogRecord(
    @SerializedName("schema_version")
    val schemaVersion: String = CANONICAL_RUN_LOG_SCHEMA_VERSION,
    @SerializedName("run_id")
    val runId: String = "",
    val goal: String = "",
    val status: String = "running",
    val success: Boolean = false,
    val error: String? = null,
    @SerializedName("started_at_ms")
    val startedAtMs: Long = System.currentTimeMillis(),
    @SerializedName("finished_at_ms")
    val finishedAtMs: Long? = null,
    val steps: List<Map<String, Any?>> = emptyList(),
    @SerializedName("final_state_id")
    val finalStateId: String? = null,
    val diagnostics: Map<String, Any?> = emptyMap(),
) {
    val source: String get() = diagnostics["source"]?.toString()?.trim().orEmpty()
    val toolName: String get() = diagnostics["tool_name"]?.toString()?.trim().orEmpty()
    val operationDescription: String
        get() = diagnostics["description"]?.toString()?.trim().orEmpty()
    val doneReason: String get() = diagnostics["done_reason"]?.toString()?.trim().orEmpty()
    val errorMessage: String get() = error.orEmpty()
    val eventSeq: Long get() = when (val value = diagnostics["event_seq"]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }
}

data class InternalRunLogFinishEvent(
    val runId: String,
    val goal: String,
    val source: String,
    val toolName: String,
    val operationDescription: String,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val success: Boolean,
    val doneReason: String,
    val errorMessage: String,
    val stepCount: Int
)

object InternalRunLogStore {
    private const val TAG = "InternalRunLogStore"
    private const val PROVIDER = "internal_oob"
    private const val STORAGE_DIR_NAME = "run_logs"
    private const val MAX_RUN_COUNT = 200
    private const val MAX_STATE_COUNT = 2_000
    private const val RUN_LOG_EVENT_SCHEMA_VERSION = "oob.run_log_event.v1"

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()
    private val compactGson = GsonBuilder()
        .disableHtmlEscaping()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val lastEventSeqByRun = mutableMapOf<String, Long>()
    private val reportedRunFileFailures = mutableSetOf<String>()
    @Volatile
    private var finishListener: ((InternalRunLogFinishEvent) -> Unit)? = null

    fun setFinishListener(listener: ((InternalRunLogFinishEvent) -> Unit)?) {
        finishListener = listener
    }

    fun canonicalStep(step: Map<String, Any?>): Map<String, Any?> {
        val value = sanitizeMap(step)
        val required = setOf(
            "step_index",
            "before_state_id",
            "action",
            "result",
            "after_state_id",
        )
        require(value.keys.containsAll(required)) { "run_log_step_required_fields_missing" }
        require(value.keys.all { it in required || it == "metadata" }) {
            "run_log_step_fields_invalid"
        }
        val stepIndex = value["step_index"] as? Number
        require(
            stepIndex != null &&
                stepIndex.toDouble().isFinite() &&
                stepIndex.toDouble() == stepIndex.toLong().toDouble() &&
                stepIndex.toLong() >= 0L
        ) {
            "run_log_step_index_invalid"
        }
        require(textValue(value["before_state_id"]).isNotEmpty()) {
            "run_log_before_state_id_required"
        }
        require(textValue(value["after_state_id"]).isNotEmpty()) {
            "run_log_after_state_id_required"
        }
        val action = stringMap(value["action"])
        require(action.keys == setOf("tool", "args")) { "canonical_action_fields_invalid" }
        val tool = textValue(action["tool"])
        require(tool.isNotEmpty()) { "canonical_action_tool_required" }
        require(OobActionSchema.canonicalToolName(tool) == tool) { "canonical_action_tool_invalid" }
        require(action["args"] is Map<*, *>) { "canonical_action_args_invalid" }
        val result = stringMap(value["result"])
        require(result.keys.all { it == "success" || it == "error" }) {
            "run_log_result_fields_invalid"
        }
        require(result["success"] is Boolean) {
            "run_log_result_success_required"
        }
        require(result["error"] == null || result["error"] is String) {
            "run_log_result_error_invalid"
        }
        require(value["metadata"] == null || value["metadata"] is Map<*, *>) {
            "run_log_step_metadata_invalid"
        }
        return value
    }

    @Synchronized
    fun replaceRun(
        context: Context,
        runId: String,
        goal: String,
        source: String,
        operationDescription: String,
        steps: List<Map<String, Any?>>,
        success: Boolean,
        doneReason: String,
        finalStateId: String? = null,
    ) {
        val normalizedRunId = runId.trim()
        require(normalizedRunId.isNotEmpty()) { "run_id_required" }
        val file = runFile(context, normalizedRunId)
        runEventsFile(file).delete()
        file.delete()
        lastEventSeqByRun.remove(normalizedRunId)
        beginRun(
            context = context,
            runId = normalizedRunId,
            goal = goal,
            source = source,
            operationDescription = operationDescription,
        )
        appendSteps(context, normalizedRunId, steps)
        finishRun(
            context = context,
            runId = normalizedRunId,
            success = success,
            doneReason = doneReason,
            finalStateId = finalStateId,
        )
    }

    @Synchronized
    fun beginRun(
        context: Context,
        runId: String,
        goal: String,
        source: String,
        toolName: String = "",
        operationDescription: String = goal,
        startedAtMs: Long = System.currentTimeMillis()
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val now = startedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val existing = readRunLocked(context, normalizedRunId)
        val base = existing ?: CanonicalRunLogRecord(
            runId = normalizedRunId,
            startedAtMs = now,
        )
        val runDiagnostics = sanitizeMap(
            base.diagnostics + linkedMapOf(
                "source" to source.ifBlank { existing?.source ?: PROVIDER },
                "tool_name" to toolName.ifBlank { existing?.toolName.orEmpty() },
                "description" to operationDescription.ifBlank {
                    existing?.operationDescription.orEmpty()
                },
            ),
        )
        val record = base.copy(
            goal = goal.ifBlank { existing?.goal.orEmpty() },
            status = "running",
            success = false,
            error = null,
            startedAtMs = now,
            finishedAtMs = null,
            finalStateId = null,
            diagnostics = runDiagnostics - "done_reason" - "event_seq",
        )
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "run_started",
            payload = linkedMapOf(
                "goal" to record.goal,
                "source" to record.source,
                "tool_name" to record.toolName,
                "operation_description" to record.operationDescription,
                "started_at_ms" to record.startedAtMs
            )
        )
        saveRunLocked(context, record.withEventSeq(eventSeq))
        pruneLocked(context, preserveRunId = normalizedRunId)
    }

    @Synchronized
    private fun appendSteps(
        context: Context,
        runId: String,
        steps: List<Map<String, Any?>>,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty() || steps.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        val sanitizedSteps = steps.map(::canonicalStep)
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "steps_appended",
            payload = linkedMapOf("steps" to sanitizedSteps)
        )
        saveRunLocked(
            context,
            record.copy(
                steps = record.steps + sanitizedSteps,
            ).withEventSeq(eventSeq),
        )
        pruneLocked(context, preserveRunId = normalizedRunId)
    }

    @Synchronized
    fun updateDiagnostics(
        context: Context,
        runId: String,
        diagnostics: Map<String, Any?>,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty() || diagnostics.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        val sanitizedDiagnostics = sanitizeMap(diagnostics)
        val mergedDiagnostics = sanitizeMap(record.diagnostics + sanitizedDiagnostics)
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "diagnostics_updated",
            payload = linkedMapOf("diagnostics" to sanitizedDiagnostics)
        )
        saveRunLocked(
            context,
            record.copy(
                diagnostics = mergedDiagnostics,
            ).withEventSeq(eventSeq),
        )
        pruneLocked(context, preserveRunId = normalizedRunId)
    }

    @Synchronized
    private fun upsertStep(
        context: Context,
        runId: String,
        step: Map<String, Any?>
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        val sanitizedStep = canonicalStep(step)
        val updatedSteps = record.steps.toMutableList()
        val stepIndex = numberToLong(sanitizedStep["step_index"])
        val replaceIndex = updatedSteps.indexOfFirst { existing ->
            numberToLong(existing["step_index"]) == stepIndex
        }
        if (replaceIndex >= 0) {
            updatedSteps[replaceIndex] = sanitizedStep
        } else {
            updatedSteps += sanitizedStep
        }
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "step_upserted",
            payload = linkedMapOf(
                "step" to sanitizedStep
            )
        )
        val updatedRecord = record.copy(
            steps = updatedSteps,
        ).withEventSeq(eventSeq)
        saveRunLocked(context, updatedRecord)
        pruneLocked(context, preserveRunId = normalizedRunId)
    }

    @Synchronized
    fun upsertRecordedStep(
        context: Context,
        runId: String,
        record: RunLogStepRecord,
    ) {
        record.states.forEach { persistStateLocked(context, stateId(it), sanitizeMap(it)) }
        upsertStep(context, runId, record.step)
    }

    @Synchronized
    fun finishRun(
        context: Context,
        runId: String,
        success: Boolean,
        doneReason: String,
        errorMessage: String? = null,
        finishedAtMs: Long = System.currentTimeMillis(),
        finalStateId: String? = null,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val record = readRunLocked(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        val normalizedFinishedAtMs = finishedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val normalizedFinalStateId = finalStateId?.trim().orEmpty()
        val eventSeq = appendRunEventLocked(
            context = context,
            runId = normalizedRunId,
            eventType = "run_finished",
            payload = linkedMapOf<String, Any?>(
                "finished_at_ms" to normalizedFinishedAtMs,
                "success" to success,
                "done_reason" to doneReason,
                "error_message" to errorMessage.orEmpty(),
                "final_state_id" to normalizedFinalStateId.takeIf { it.isNotEmpty() },
            ).filterValues { it != null }
        )
        val finishedRecord = record.copy(
            status = when {
                doneReason == "cancelled" -> "cancelled"
                success -> "succeeded"
                else -> "failed"
            },
            success = success,
            error = errorMessage?.takeIf(String::isNotBlank),
            finishedAtMs = normalizedFinishedAtMs,
            finalStateId = normalizedFinalStateId.takeIf(String::isNotEmpty),
            diagnostics = sanitizeMap(
                record.diagnostics + mapOf("done_reason" to doneReason),
            ),
        ).withEventSeq(eventSeq)
        saveRunLocked(context, finishedRecord)
        pruneLocked(context, preserveRunId = normalizedRunId)
        notifyFinishListener(
            InternalRunLogFinishEvent(
                runId = normalizedRunId,
                goal = record.goal,
                source = record.source,
                toolName = record.toolName,
                operationDescription = record.operationDescription,
                startedAtMs = record.startedAtMs,
                finishedAtMs = normalizedFinishedAtMs,
                success = success,
                doneReason = doneReason,
                errorMessage = errorMessage.orEmpty(),
                stepCount = record.steps.size
            )
        )
    }

    private fun notifyFinishListener(event: InternalRunLogFinishEvent) {
        val listener = finishListener ?: return
        runCatching { listener(event) }
            .onFailure { OmniLog.w(TAG, "run finish listener failed: ${it.message}") }
    }

    @Synchronized
    fun listRuns(
        context: Context,
        limit: Int = 50,
        offset: Int = 0,
        source: String = "",
        status: String = "",
        model: String = "",
        query: String = "",
    ): Map<String, Any?> {
        val safeLimit = limit.coerceIn(1, MAX_RUN_COUNT)
        val safeOffset = offset.coerceAtLeast(0)
        val allRuns = readAllRunsLocked(context)
            .sortedByDescending { it.startedAtMs }
        val availableModels = allRuns
            .flatMap(::modelNames)
            .distinctBy { it.lowercase(Locale.US) }
            .sortedBy { it.lowercase(Locale.US) }
        val filteredRuns = allRuns.filter { record ->
            matchesSourceFilter(record, source) &&
                matchesStatusFilter(record, status) &&
                matchesModelFilter(record, model) &&
                matchesQuery(record, query)
        }
        val runs = filteredRuns
            .drop(safeOffset)
            .take(safeLimit)
        return linkedMapOf<String, Any?>(
            "success" to true,
            "count" to runs.size,
            "total_count" to filteredRuns.size,
            "limit" to safeLimit,
            "offset" to safeOffset,
            "next_offset" to (safeOffset + runs.size),
            "has_more" to (safeOffset + runs.size < filteredRuns.size),
            "available_models" to availableModels,
            "runs" to runs.map { record ->
                summaryMap(record)
            }
        ).filterValues { it != null }
    }

    @Synchronized
    fun listRunRecords(
        context: Context,
        limit: Int = 50,
        offset: Int = 0
    ): List<CanonicalRunLogRecord> {
        val safeLimit = limit.coerceIn(1, MAX_RUN_COUNT)
        val safeOffset = offset.coerceAtLeast(0)
        return readAllRunsLocked(context)
            .sortedByDescending { it.startedAtMs }
            .drop(safeOffset)
            .take(safeLimit)
    }

    @Synchronized
    fun getRun(context: Context, runId: String): CanonicalRunLogRecord? {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return null
        return readRunLocked(context, normalizedRunId)
    }

    @Synchronized
    fun timelinePayload(context: Context, runId: String): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) {
            return notFoundPayload(normalizedRunId)
        }
        val record = readRunLocked(context, normalizedRunId)
            ?: return notFoundPayload(normalizedRunId)
        val steps = record.steps.map { step -> externalStepPayload(context, step) }
        val recordedTokenUsage = stringMap(record.diagnostics["token_usage"])
        val tokenUsage = recordedTokenUsage.ifEmpty { tokenUsageSummary(steps) }
        val recordedTokenUsageByCall = listOfMaps(record.diagnostics["token_usage_by_call"])
        val tokenUsageByCall = recordedTokenUsageByCall.ifEmpty { tokenUsageByCall(steps) }
        val diagnostics = linkedMapOf<String, Any?>().apply {
            putAll(record.diagnostics - "event_seq")
            putAll(linkedMapOf(
            "source" to record.source,
            "tool_name" to record.toolName,
            "description" to record.operationDescription,
            "duration_ms" to durationMs(record),
            "done_reason" to record.doneReason,
            "token_usage" to tokenUsage.takeIf { it.isNotEmpty() },
            "token_usage_by_step" to tokenUsageByStep(steps).takeIf { it.isNotEmpty() },
            "token_usage_by_call" to tokenUsageByCall.takeIf { it.isNotEmpty() },
            ).filterValues { it != null })
        }
        val success = record.success == true
        val status = if (success) "succeeded" else "failed"
        val payload = linkedMapOf<String, Any?>(
            // The on-device store uses an internal schema, while the Python
            // management tools consume the public OmniFlow RunLog contract.
            "schema_version" to "omniflow.run_log.v1",
            "run_id" to record.runId,
            "task_name" to record.toolName.ifBlank { "vlm_task" },
            "goal" to record.goal,
            "task_parameters" to linkedMapOf<String, Any?>("goal" to record.goal),
            "seed" to null,
            "status" to status,
            "success" to success,
            "validator" to linkedMapOf(
                "official" to true,
                "success" to success,
                "reward" to if (success) 1.0 else 0.0,
            ),
            "provenance" to linkedMapOf<String, Any?>(
                "kind" to "runtime",
                "source_schema_version" to CANONICAL_RUN_LOG_SCHEMA_VERSION,
            ),
            "started_at_ms" to record.startedAtMs,
            "steps" to steps,
        )
        record.finishedAtMs?.let { payload["finished_at_ms"] = it }
        record.finalStateId
            ?.takeIf(String::isNotBlank)
            ?.let { payload["final_observation"] = externalStatePayload(context, it) }
        diagnostics.takeIf { it.isNotEmpty() }?.let { payload["diagnostics"] = it }
        return payload
    }

    private fun externalStepPayload(
        context: Context,
        step: Map<String, Any?>,
    ): Map<String, Any?> {
        val beforeStateId = textValue(step["before_state_id"])
        val afterStateId = textValue(step["after_state_id"])
        val beforeState = statePayload(context, beforeStateId)
        val payload = linkedMapOf<String, Any?>(
            "step_index" to (numberToLong(step["step_index"]) ?: 0L).toInt(),
            "before_state_id" to beforeStateId,
            "observation" to externalStatePayload(context, beforeStateId),
            "action" to externalActionPayload(
                action = stringMap(step["action"]),
                display = stringMap(beforeState["display"]),
            ),
            "result" to externalResultPayload(stringMap(step["result"])),
            "after_state_id" to afterStateId,
            "next_observation" to externalStatePayload(context, afterStateId),
        )
        stringMap(step["metadata"]).takeIf { it.isNotEmpty() }?.let {
            payload["metadata"] = it
        }
        return payload
    }

    private fun externalStatePayload(
        context: Context,
        stateId: String,
    ): Map<String, Any?> {
        val state = statePayload(context, stateId)
        return linkedMapOf(
            // State screenshots and XML remain available through get_run_log_state.
            // The public RunLog schema permits a null pixel reference when the
            // recording only persisted the state id.
            "pixels" to null,
            "forest" to null,
            "ui_elements" to emptyList<Any?>(),
            "auxiliaries" to linkedMapOf<String, Any?>(
                "state_id" to stateId,
                "package_name" to state["package_name"],
                "activity_name" to state["activity_name"],
                "display" to state["display"],
            ).filterValues { it != null },
        )
    }

    private fun externalActionPayload(
        action: Map<String, Any?>,
        display: Map<String, Any?>,
    ): Map<String, Any?> {
        val tool = textValue(action["tool"])
        val args = stringMap(action["args"])
        val actionType = when (tool) {
            OobActionSchema.TOOL_CLICK -> "click"
            OobActionSchema.TOOL_LONG_PRESS -> "long_press"
            OobActionSchema.TOOL_INPUT_TEXT -> "input_text"
            OobActionSchema.TOOL_SWIPE -> "swipe"
            OobActionSchema.TOOL_OPEN_APP -> "open_app"
            OobActionSchema.TOOL_PRESS_KEY -> "keyboard_enter"
            OobActionSchema.TOOL_WAIT -> "wait"
            OobActionSchema.TOOL_FINISHED -> "status"
            else -> "unknown"
        }
        val payload = linkedMapOf<String, Any?>("action_type" to actionType)
        fun copy(vararg keys: String) {
            keys.forEach { key -> args[key]?.let { payload[key] = it } }
        }
        fun copyPixelCoordinate(key: String, displayKey: String) {
            val value = args[key] ?: return
            val number = (value as? Number)?.toDouble() ?: return
            val dimension = numberToLong(display[displayKey])?.toDouble()
            payload[key] = if (dimension != null && dimension > 0.0) {
                number / 1000.0 * dimension
            } else {
                number
            }
        }
        fun copyPoint() {
            copyPixelCoordinate("x", "width")
            copyPixelCoordinate("y", "height")
        }
        when (actionType) {
            "click", "long_press" -> copyPoint()
            "input_text" -> {
                copy("text")
                copyPoint()
            }
            // The public RunLog schema intentionally stores swipe direction;
            // replay derives canonical coordinates from the observation. The
            // internal OOB x1/y1/x2/y2 fields are not valid public properties.
            "swipe" -> copy("direction")
            "open_app" -> {
                args[OobActionSchema.ARG_PACKAGE_NAME]?.let { payload["app_name"] = it }
            }
            "keyboard_enter" -> {
                val key = textValue(args[OobActionSchema.ARG_KEY])
                if (key.isNotBlank()) {
                    payload["keycode"] = if (key.startsWith("KEYCODE_")) key else "KEYCODE_$key"
                }
            }
            "status" -> args[OobActionSchema.ARG_CONTENT]?.let { payload["goal_status"] = it }
            "wait" -> copy("duration_ms")
        }
        return payload
    }

    private fun externalResultPayload(result: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>(
            "success" to (result["success"] == true),
            "error" to textValue(result["error"]).takeIf(String::isNotBlank),
        ).filterValues { it != null }
    }

    @Synchronized
    fun statePayload(context: Context, stateId: String): Map<String, Any?> {
        val normalizedStateId = stateId.trim()
        if (normalizedStateId.isEmpty()) return emptyMap()
        val file = stateFile(context, normalizedStateId)
        if (!file.isFile) return emptyMap()
        val state = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
        if (textValue(state["state_id"]) != normalizedStateId) return emptyMap()
        val xml = stateXmlFile(context, normalizedStateId)
            .takeIf(File::isFile)
            ?.runCatching(File::readText)
            ?.getOrNull()
        return linkedMapOf<String, Any?>().apply {
            put("state_id", normalizedStateId)
            listOf("package_name", "activity_name", "display", "screenshot_path").forEach { key ->
                state[key]?.let { put(key, it) }
            }
            xml?.takeIf(String::isNotBlank)?.let { put("xml", it) }
        }
    }

    @Synchronized
    fun persistState(
        context: Context,
        state: State,
        screenshotJpeg: ByteArray? = null,
    ): State {
        val payload = state.asMap().toMutableMap().apply {
            screenshotJpeg?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        val stored = persistStateLocked(context, state.stateId, sanitizeMap(payload))
        return state.copy(
            screenshotPath = textValue(stored["screenshot_path"]).takeIf(String::isNotEmpty),
        )
    }

    private fun persistStateLocked(
        context: Context,
        stateId: String,
        state: Map<String, Any?>,
    ): Map<String, Any?> {
        val allowed = setOf(
            "state_id",
            "package_name",
            "activity_name",
            "display",
            "xml",
            "screenshot_path",
            "screenshot_base64",
        )
        require(state.keys.all(allowed::contains)) { "state_contract_fields_invalid" }
        require(textValue(state["state_id"]) == stateId) { "state_id_mismatch" }
        val stateJsonFile = stateFile(context, stateId)
        val existing = stateJsonFile.takeIf(File::isFile)?.runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(readText(), Map::class.java) as? Map<String, Any?>
        }?.getOrNull().orEmpty().takeIf {
            textValue(it["state_id"]) == stateId
        }.orEmpty()
        val incomingXml = textValue(state["xml"])
        val xmlFile = stateXmlFile(context, stateId)
        val sourceXml = incomingXml.ifBlank {
            xmlFile.takeIf(File::isFile)?.runCatching(File::readText)?.getOrNull().orEmpty()
        }
        val stored = linkedMapOf<String, Any?>("state_id" to stateId).apply {
            listOf("screenshot_path", "package_name", "activity_name", "display").forEach { key ->
                existing[key]?.let { put(key, it) }
            }
        }
        listOf(
            "screenshot_path",
            "package_name",
            "activity_name",
        ).forEach { key -> state[key]?.let { stored[key] = it } }
        stringMap(state["display"])
            .takeIf { display ->
                numberToLong(display["width"])?.let { it > 0L } == true &&
                    numberToLong(display["height"])?.let { it > 0L } == true
            }
            ?.let { display ->
                stored["display"] = linkedMapOf(
                    "width" to numberToLong(display["width"]),
                    "height" to numberToLong(display["height"]),
                )
            }
        if (sourceXml.isNotBlank()) {
            if (incomingXml.isNotBlank() || !xmlFile.isFile) {
                val temporary = File(xmlFile.parentFile, "${xmlFile.name}.tmp")
                temporary.writeText(sourceXml)
                if (!temporary.renameTo(xmlFile)) {
                    xmlFile.writeText(sourceXml)
                    temporary.delete()
                }
            }
        }
        val screenshotBase64 = textValue(state["screenshot_base64"])
        if (screenshotBase64.isNotBlank()) {
            runCatching {
                val encoded = screenshotBase64.substringAfter(",", screenshotBase64)
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                val screenshotFile = File(
                    statesDir(context),
                    "${sha256(stateId).take(16)}_${safeFilePart(stateId)}.jpg",
                )
                screenshotFile.writeBytes(bytes)
                stored["screenshot_path"] = screenshotFile.absolutePath
            }.onFailure {
                OmniLog.w(TAG, "persist screenshot failed for $stateId: ${it.message}")
            }
        }
        val temporary = File(stateJsonFile.parentFile, "${stateJsonFile.name}.tmp")
        temporary.writeText(gson.toJson(stored))
        if (!temporary.renameTo(stateJsonFile)) {
            stateJsonFile.writeText(gson.toJson(stored))
            temporary.delete()
        }
        pruneStatesLocked(context, preserveStateId = stateId)
        return stored
    }

    private fun stateId(state: Map<String, Any?>): String =
        textValue(state["state_id"]).also { require(it.isNotEmpty()) { "state_id_required" } }

    private fun summaryMap(record: CanonicalRunLogRecord): Map<String, Any?> {
        val tokenUsage = tokenUsageSummary(record.steps)
        val diagnostics = linkedMapOf<String, Any?>().apply {
            putAll(record.diagnostics - "event_seq")
            if (tokenUsage.isNotEmpty()) put("token_usage", tokenUsage)
        }
        return linkedMapOf(
            "schema_version" to CANONICAL_RUN_LOG_SCHEMA_VERSION,
            "run_id" to record.runId,
            "goal" to record.goal,
            "status" to record.status,
            "success" to record.success,
            "error" to record.error,
            "started_at_ms" to record.startedAtMs,
            "finished_at_ms" to record.finishedAtMs,
            "step_count" to record.steps.size,
            "diagnostics" to diagnostics.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun notFoundPayload(runId: String): Map<String, Any?> {
        return linkedMapOf(
            "success" to false,
            "provider" to PROVIDER,
            "run_id" to runId,
            "error_code" to "NOT_FOUND",
            "error_message" to "Internal run log not found"
        )
    }

    private fun durationMs(record: CanonicalRunLogRecord): Long? {
        val finishedAt = record.finishedAtMs ?: return null
        return (finishedAt - record.startedAtMs).coerceAtLeast(0L)
    }

    private fun tokenUsageSummary(steps: List<Map<String, Any?>>): Map<String, Any?> {
        val usages = steps.mapNotNull(::extractTokenUsage)
        if (usages.isEmpty()) return emptyMap()
        val summary = linkedMapOf<String, Any?>()
        putSum(summary, "prompt_tokens", usages)
        putSum(summary, "completion_tokens", usages)
        putSum(summary, "total_tokens", usages)
        if (!summary.containsKey("total_tokens")) {
            val prompt = numberToLong(summary["prompt_tokens"])
            val completion = numberToLong(summary["completion_tokens"])
            if (prompt != null || completion != null) {
                summary["total_tokens"] = (prompt ?: 0L) + (completion ?: 0L)
            }
        }
        putSum(summary, "reasoning_tokens", usages)
        putSum(summary, "text_tokens", usages)
        putSum(summary, "image_tokens", usages)
        putSum(summary, "cached_tokens", usages)
        putSum(summary, "attempt_count", usages)
        summary["step_count"] = usages.size
        val callCount = tokenUsageCallCount(steps)
        if (callCount > 0) {
            summary["call_count"] = callCount
        }
        val resolvedModels = usageValues(steps, "resolved_model")
        val routes = usageValues(steps, "route")
        if (resolvedModels.isNotEmpty()) {
            summary["resolved_models"] = resolvedModels
            if (resolvedModels.size == 1) summary["resolved_model"] = resolvedModels.first()
        }
        if (routes.isNotEmpty()) {
            summary["routes"] = routes
            if (routes.size == 1) summary["route"] = routes.first()
        }
        return summary
    }

    private fun tokenUsageByStep(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        return steps.mapIndexedNotNull { index, step ->
            val usage = extractTokenUsage(step) ?: return@mapIndexedNotNull null
            val action = stringMap(step["action"])
            linkedMapOf<String, Any?>(
                "step_index" to (numberToLong(step["step_index"]) ?: index.toLong()).toInt(),
                "tool" to textValue(action["tool"]),
                "token_usage" to usage
            )
        }
    }

    private fun tokenUsageByCall(steps: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val calls = mutableListOf<Map<String, Any?>>()
        steps.forEachIndexed { index, step ->
            val attempts = extractTokenUsageAttempts(step)
            val action = stringMap(step["action"])
            val usages = attempts.ifEmpty {
                extractTokenUsage(step)?.let { listOf(it) } ?: emptyList()
            }
            usages.forEach { usage ->
                calls += linkedMapOf(
                    "call_index" to calls.size,
                    "step_index" to (numberToLong(step["step_index"]) ?: index.toLong()).toInt(),
                    "tool" to textValue(action["tool"]),
                    "attempt_index" to numberToLong(usage["attempt_index"])?.toInt(),
                    "stability_attempt" to numberToLong(usage["stability_attempt"])?.toInt(),
                    "tool_retry_index" to numberToLong(usage["tool_retry_index"])?.toInt(),
                    "token_usage" to usage
                ).filterValues { it != null }
            }
        }
        return calls
    }

    private fun tokenUsageCallCount(steps: List<Map<String, Any?>>): Int {
        return steps.sumOf { step ->
            val attempts = extractTokenUsageAttempts(step)
            val usage = extractTokenUsage(step)
            when {
                attempts.isNotEmpty() -> attempts.size
                usage != null -> (numberToLong(usage["attempt_count"]) ?: 1L).coerceAtLeast(1L).toInt()
                else -> 0
            }
        }
    }

    private fun extractTokenUsage(step: Map<String, Any?>): Map<String, Any?>? {
        val metadata = stringMap(step["metadata"])
        return stringMap(metadata["token_usage"]).takeIf { it.isNotEmpty() }
    }

    private fun extractTokenUsageAttempts(step: Map<String, Any?>): List<Map<String, Any?>> {
        val metadata = stringMap(step["metadata"])
        return listOfMaps(metadata["token_usage_attempts"])
    }

    private fun modelNames(record: CanonicalRunLogRecord): List<String> {
        return usageValues(record.steps, "resolved_model")
    }

    private fun usageValues(steps: List<Map<String, Any?>>, key: String): List<String> {
        return steps.flatMap { step ->
            val usages = extractTokenUsageAttempts(step).ifEmpty {
                extractTokenUsage(step)?.let(::listOf).orEmpty()
            }
            usages.mapNotNull { usage ->
                textValue(usage[key])
                    .takeIf { it.isNotBlank() && it != "multiple" }
            }
        }.distinctBy { it.lowercase(Locale.US) }
    }

    private fun matchesSourceFilter(record: CanonicalRunLogRecord, filter: String): Boolean {
        return when (filter.trim().lowercase(Locale.US)) {
            "", "all" -> true
            "vlm" -> record.source.lowercase(Locale.US) == "vlm"
            "manual" -> record.source.lowercase(Locale.US) in setOf(
                "manual_recording",
                "human_trajectory",
                "human_takeover",
            )
            else -> record.source.equals(filter.trim(), ignoreCase = true)
        }
    }

    private fun matchesStatusFilter(record: CanonicalRunLogRecord, filter: String): Boolean {
        return when (filter.trim().lowercase(Locale.US)) {
            "", "all" -> true
            "success", "succeeded" -> record.status == "succeeded"
            "failure", "failed" -> record.status in setOf("failed", "cancelled")
            else -> record.status.equals(filter.trim(), ignoreCase = true)
        }
    }

    private fun matchesModelFilter(record: CanonicalRunLogRecord, filter: String): Boolean {
        val normalized = filter.trim()
        return normalized.isEmpty() || normalized == "all" ||
            modelNames(record).any { it.equals(normalized, ignoreCase = true) }
    }

    private fun matchesQuery(record: CanonicalRunLogRecord, query: String): Boolean {
        val normalized = query.trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return true
        return listOf(
            record.runId,
            record.goal,
            record.operationDescription,
            record.toolName,
            record.source,
            record.status,
        ).plus(modelNames(record)).any { value ->
            value.lowercase(Locale.US).contains(normalized)
        }
    }

    private fun putSum(
        target: MutableMap<String, Any?>,
        key: String,
        usages: List<Map<String, Any?>>
    ) {
        var hasValue = false
        var total = 0L
        usages.forEach { usage ->
            val value = numberToLong(usage[key])
            if (value != null) {
                hasValue = true
                total += value
            }
        }
        if (hasValue) {
            target[key] = total
        }
    }

    private fun readAllRunsLocked(context: Context): List<CanonicalRunLogRecord> {
        val dir = storageDir(context)
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> readRunFileLocked(context, file) }
            .orEmpty()
    }

    private fun readRunLocked(context: Context, runId: String): CanonicalRunLogRecord? {
        return readRunFileLocked(context, runFile(context, runId))
    }

    private fun readRunFileLocked(context: Context, file: File): CanonicalRunLogRecord? {
        val snapshot = if (file.exists()) {
            runCatching {
                parseRunSnapshot(file.readText())
            }.getOrElse {
                reportRunFileFailureOnce("read", file, it)
                null
            }
        } else {
            null
        }
        val runId = snapshot?.runId ?: runIdFromRunFile(file)
        val events = readRunEventsLocked(
            file = runEventsFile(file),
            afterSeq = snapshot?.eventSeq ?: 0L
        )
        if (snapshot == null && events.isEmpty()) return null
        return runCatching {
            applyRunEventsLocked(
                base = snapshot ?: CanonicalRunLogRecord(runId = runId),
                events = events
            )
        }.getOrElse {
            reportRunFileFailureOnce("apply events", file, it)
            snapshot
        }
    }

    private fun reportRunFileFailureOnce(phase: String, file: File, error: Throwable) {
        val reason = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
        val fingerprint = "$phase:${error.javaClass.name}:$reason"
        val shouldReport = synchronized(reportedRunFileFailures) {
            reportedRunFileFailures.add(fingerprint)
        }
        if (shouldReport) {
            OmniLog.w(
                TAG,
                "$phase run log failed: ${file.absolutePath}, $reason; identical failures suppressed",
            )
        }
    }

    private fun parseRunSnapshot(json: String): CanonicalRunLogRecord {
        @Suppress("UNCHECKED_CAST")
        val value = gson.fromJson(json, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        require(textValue(value["schema_version"]) == CANONICAL_RUN_LOG_SCHEMA_VERSION) {
            "run_log_schema_version_invalid"
        }
        val runId = textValue(value["run_id"])
        require(runId.isNotEmpty()) { "run_id_required" }
        val success = booleanValue(value["success"])
            ?: error("run_log_success_required")
        val status = textValue(value["status"])
        val diagnostics = sanitizeMap(stringMap(value["diagnostics"]))
        val steps = listOfMaps(value["steps"]).map(::canonicalStep)
        return CanonicalRunLogRecord(
            runId = runId,
            goal = textValue(value["goal"]),
            status = status,
            success = success,
            error = textValue(value["error"]).takeIf(String::isNotEmpty),
            startedAtMs = numberToLong(value["started_at_ms"]) ?: System.currentTimeMillis(),
            finishedAtMs = numberToLong(value["finished_at_ms"]),
            steps = steps,
            finalStateId = textValue(value["final_state_id"]).takeIf(String::isNotEmpty),
            diagnostics = diagnostics,
        )
    }

    private fun saveRunLocked(context: Context, record: CanonicalRunLogRecord) {
        val file = runFile(context, record.runId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            Trace.beginSection("InternalRunLogStore.saveSnapshot")
            try {
                tmp.writeText(gson.toJson(record))
                if (!tmp.renameTo(file)) {
                    file.writeText(gson.toJson(record))
                    tmp.delete()
                }
            } finally {
                Trace.endSection()
            }
        }.onFailure {
            OmniLog.w(TAG, "save run log failed: ${file.absolutePath}, ${it.message}")
            tmp.delete()
        }
    }

    private fun pruneLocked(context: Context, preserveRunId: String = "") {
        val dir = storageDir(context)
        val preserveFileName = preserveRunId.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { runFile(context, it).name }
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val preservedFile = preserveFileName?.let { name ->
            files.firstOrNull { file -> file.name == name }
        }
        val keepNonPreservedCount = if (preservedFile != null) {
            (MAX_RUN_COUNT - 1).coerceAtLeast(0)
        } else {
            MAX_RUN_COUNT
        }
        files
            .filter { file -> file.name != preserveFileName }
            .drop(keepNonPreservedCount)
            .forEach { file ->
                runCatching {
                    runEventsFile(file).delete()
                    file.delete()
                }
            }
    }

    private fun storageDir(context: Context): File {
        return File(context.applicationContext.filesDir, STORAGE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun statesDir(context: Context): File =
        File(storageDir(context), "states").apply { mkdirs() }

    private fun stateFile(context: Context, stateId: String): File =
        File(statesDir(context), "${sha256(stateId).take(16)}_${safeFilePart(stateId)}.json")

    private fun stateXmlFile(context: Context, stateId: String): File =
        File(statesDir(context), "${sha256(stateId).take(16)}_${safeFilePart(stateId)}.xml")

    private fun pruneStatesLocked(context: Context, preserveStateId: String) {
        val preserved = stateFile(context, preserveStateId)
        statesDir(context)
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .filter { it != preserved }
            .drop((MAX_STATE_COUNT - 1).coerceAtLeast(0))
            .forEach { file ->
                File(file.parentFile, "${file.nameWithoutExtension}.xml").delete()
                file.delete()
            }
    }

    private fun runFile(context: Context, runId: String): File {
        return File(storageDir(context), "${sha256(runId).take(16)}_${safeFilePart(runId)}.json")
    }

    private fun runEventsFile(runFile: File): File {
        return File(runFile.parentFile, "${runFile.nameWithoutExtension}.events.ndjson")
    }

    private fun runIdFromRunFile(file: File): String {
        return file.nameWithoutExtension.substringAfter('_', file.nameWithoutExtension)
    }

    private fun appendRunEventLocked(
        context: Context,
        runId: String,
        eventType: String,
        payload: Map<String, Any?>
    ): Long {
        val file = runEventsFile(runFile(context, runId))
        val eventSeq = nextRunEventSeqLocked(file, runId)
        val event = linkedMapOf<String, Any?>(
            "schema_version" to RUN_LOG_EVENT_SCHEMA_VERSION,
            "provider" to PROVIDER,
            "run_id" to runId,
            "event_seq" to eventSeq,
            "event_type" to eventType,
            "created_at_ms" to System.currentTimeMillis(),
            "payload" to sanitizeMap(payload)
        )
        runCatching {
            Trace.beginSection("InternalRunLogStore.appendEvent")
            try {
                file.parentFile?.mkdirs()
                file.appendText(compactGson.toJson(event) + "\n")
            } finally {
                Trace.endSection()
            }
        }.onFailure {
            OmniLog.w(TAG, "append run log event failed: ${file.absolutePath}, ${it.message}")
        }
        return eventSeq
    }

    private fun nextRunEventSeqLocked(file: File, runId: String): Long {
        val cached = lastEventSeqByRun[runId]
        if (cached != null) {
            val next = cached + 1L
            lastEventSeqByRun[runId] = next
            return next
        }
        val current = readLastRunEventSeqLocked(file)
        val next = current + 1L
        lastEventSeqByRun[runId] = next
        return next
    }

    private fun readLastRunEventSeqLocked(file: File): Long {
        if (!file.exists()) return 0L
        return runCatching {
            var last = 0L
            file.forEachLine { line ->
                val event = parseEventLine(line) ?: return@forEachLine
                val seq = numberToLong(event["event_seq"]) ?: return@forEachLine
                if (seq > last) last = seq
            }
            last
        }.getOrElse {
            OmniLog.w(TAG, "read run log event seq failed: ${file.absolutePath}, ${it.message}")
            0L
        }
    }

    private fun readRunEventsLocked(
        file: File,
        afterSeq: Long
    ): List<Map<String, Any?>> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val events = mutableListOf<Map<String, Any?>>()
            file.forEachLine { line ->
                val event = parseEventLine(line) ?: return@forEachLine
                val seq = numberToLong(event["event_seq"]) ?: return@forEachLine
                if (seq > afterSeq) {
                    events += event
                }
            }
            events.sortedBy { numberToLong(it["event_seq"]) ?: 0L }
        }.getOrElse {
            OmniLog.w(TAG, "read run log events failed: ${file.absolutePath}, ${it.message}")
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseEventLine(line: String): Map<String, Any?>? {
        val normalized = line.trim()
        if (normalized.isEmpty()) return null
        return runCatching {
            compactGson.fromJson<Map<String, Any?>>(normalized, mapType)
        }.getOrNull()
    }

    private fun applyRunEventsLocked(
        base: CanonicalRunLogRecord,
        events: List<Map<String, Any?>>
    ): CanonicalRunLogRecord {
        var record = base
        var maxEventSeq = base.eventSeq
        for (event in events) {
            val eventSeq = numberToLong(event["event_seq"]) ?: continue
            if (eventSeq <= maxEventSeq) continue
            val payload = stringMap(event["payload"])
            record = when (event["event_type"]?.toString()) {
                "run_started" -> record.copy(
                    goal = textValue(payload["goal"]).ifBlank { record.goal },
                    status = "running",
                    success = false,
                    error = null,
                    startedAtMs = numberToLong(payload["started_at_ms"]) ?: record.startedAtMs,
                    finishedAtMs = null,
                    finalStateId = null,
                    diagnostics = sanitizeMap(
                        (record.diagnostics - "done_reason") + linkedMapOf(
                            "source" to textValue(payload["source"]).ifBlank { record.source },
                            "tool_name" to textValue(payload["tool_name"])
                                .ifBlank { record.toolName },
                            "description" to textValue(payload["operation_description"])
                                .ifBlank { record.operationDescription },
                        ),
                    ),
                )
                "step_appended" -> record.copy(
                    steps = record.steps + canonicalStep(stringMap(payload["step"]))
                )
                "steps_appended" -> record.copy(
                    steps = record.steps + listOfMaps(payload["steps"]).map(::canonicalStep)
                )
                "step_upserted" -> record.copy(
                    steps = upsertStepList(
                        steps = record.steps,
                        step = canonicalStep(stringMap(payload["step"]))
                    )
                )
                "run_finished" -> record.copy(
                    status = when {
                        textValue(payload["done_reason"]) == "cancelled" -> "cancelled"
                        booleanValue(payload["success"]) == true -> "succeeded"
                        else -> "failed"
                    },
                    success = booleanValue(payload["success"]) ?: false,
                    error = textValue(payload["error_message"]).takeIf(String::isNotEmpty),
                    finishedAtMs = numberToLong(payload["finished_at_ms"]),
                    finalStateId = textValue(payload["final_state_id"]).takeIf(String::isNotEmpty),
                    diagnostics = sanitizeMap(
                        record.diagnostics + mapOf(
                            "done_reason" to textValue(payload["done_reason"]),
                        ),
                    ),
                )
                "diagnostics_updated" -> record.copy(
                    diagnostics = sanitizeMap(
                        record.diagnostics + stringMap(payload["diagnostics"]),
                    ),
                )
                else -> record
            }.let { updated ->
                updated.withEventSeq(eventSeq)
            }
            maxEventSeq = eventSeq
        }
        return record
    }

    private fun upsertStepList(
        steps: List<Map<String, Any?>>,
        step: Map<String, Any?>
    ): List<Map<String, Any?>> {
        val stepIndex = numberToLong(step["step_index"])
            ?: error("run_log_step_index_invalid")
        val updatedSteps = steps.toMutableList()
        val replaceIndex = updatedSteps.indexOfFirst { existing ->
            numberToLong(existing["step_index"]) == stepIndex
        }
        if (replaceIndex >= 0) {
            updatedSteps[replaceIndex] = step
        } else {
            updatedSteps += step
        }
        return updatedSteps
    }

    private fun CanonicalRunLogRecord.withEventSeq(eventSeq: Long): CanonicalRunLogRecord =
        copy(diagnostics = sanitizeMap(diagnostics + mapOf("event_seq" to eventSeq)))

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "run" }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun textValue(value: Any?): String {
        return value?.toString()?.trim().orEmpty()
    }

    private fun numberToLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun booleanValue(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase(Locale.US)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> null
        }
    }

    private fun stringMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) return emptyMap()
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                if (key != null) {
                    put(key.toString(), item)
                }
            }
        }
    }

    private fun listOfMaps(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { item ->
            stringMap(item).takeIf { it.isNotEmpty() }
        }
    }

    internal fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> {
        return linkedMapOf<String, Any?>().apply {
            value.forEach { (key, item) ->
                put(key, sanitizeValue(item))
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else value
            is Float -> if (value.isFinite() && value % 1f == 0f) value.toLong() else value
            is String, is Number, is Boolean -> value
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) {
                        put(key.toString(), sanitizeValue(item))
                    }
                }
            }
            is List<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }
    }

}
