package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog

object OmniFlowFunctionRegistration {
    @Suppress("UNUSED_PARAMETER")
    suspend fun saveRunLog(
        context: Context,
        runId: String,
        agentVisible: Boolean = true,
        modelClient: OmniFlowModelClient? = null,
        source: String = "function_registration",
    ): Map<String, Any?> {
        val normalizedRunId = runId.trim()
        require(normalizedRunId.isNotEmpty()) { "run_id_required" }
        val record = requireNotNull(
            InternalRunLogStore.getRun(context.applicationContext, normalizedRunId),
        ) { "run_log_not_found:$normalizedRunId" }
        // Send the canonical RunLog snapshot with the Function draft.  The
        // Python compiler must freeze transfer_states.json from the same
        // evidence that produced this Function; resolving the RunLog again
        // through the Android host introduces a race with RunLog cleanup and
        // was the source of missing source_state failures on replay.
        val sourceRunLog = InternalRunLogStore.timelinePayload(
            context.applicationContext,
            normalizedRunId,
        )
        val payload = OmniFlow.callTool(
            context = context.applicationContext,
            toolCall = OmniFlow.ToolCall(
                name = "save_function",
                arguments = mapOf(
                    "run_id" to normalizedRunId,
                    "run_log" to sourceRunLog,
                ),
            ),
            source = source,
            modelClient = modelClient,
        ).payload
        val status = payload["success"]?.toString() ?: "missing"
        val error = payload["error_message"]?.toString()
            ?: payload["error_code"]?.toString()
            ?: payload["error"]?.toString()
        if (status == "false") {
            OmniLog.w(TAG, "save_function failed run_id=$normalizedRunId error=${error.orEmpty()}")
        } else {
            OmniLog.i(TAG, "save_function completed run_id=$normalizedRunId success=$status")
        }
        return payload
    }

    private const val TAG = "OmniFlowFunctionRegistration"
}
