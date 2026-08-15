package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompiler

object OmniFlowFunctionRegistration {
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
        val function = RunLogReusableFunctionCompiler.compile(record, agentVisible)
        return OmniFlow.callTool(
            context = context.applicationContext,
            toolCall = OmniFlow.ToolCall(
                name = "save_function",
                arguments = mapOf("function" to function),
            ),
            source = source,
            modelClient = modelClient,
        ).payload
    }
}
