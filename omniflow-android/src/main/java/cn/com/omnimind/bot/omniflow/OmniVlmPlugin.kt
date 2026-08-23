package cn.com.omnimind.bot.omniflow

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class OmniVlmPlugin internal constructor(
    private val backend: OmniVlmBackend,
) {
    data class Request(
        val goal: String,
        val runId: String = "gui-${UUID.randomUUID()}",
        val stepSkillGuidance: String = "",
        val deferUserInput: Boolean = true,
        val maxSteps: Int = DEFAULT_MAX_STEPS,
    )

    data class Hooks(
        val beforeOperation: suspend () -> Unit = {},
        val stopRequested: () -> Boolean = { false },
        val onProgress: suspend (String, Map<String, Any?>) -> Unit = { _, _ -> },
        val afterExecution: suspend () -> Unit = {},
    )

    data class Result(
        val payload: Map<String, Any?>,
        val finalStateId: String?,
    )

    suspend fun execute(
        context: Context,
        request: Request,
        modelClient: OmniFlowModelClient,
        hooks: Hooks = Hooks(),
    ): Result {
        val goal = request.goal.trim()
        require(goal.isNotEmpty()) { "omni_vlm_goal_required" }
        val runId = request.runId.trim()
        require(runId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return try {
            backend.execute(
                context = context,
                request = request.copy(goal = goal, runId = runId),
                modelClient = modelClient,
                hooks = hooks,
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { hooks.afterExecution() }
            }
        }
    }

    fun stop(runId: String): Boolean {
        val normalizedRunId = runId.trim()
        require(normalizedRunId.isNotEmpty()) { "omni_vlm_run_id_required" }
        return backend.stop(normalizedRunId)
    }

    companion object {
        const val MODEL_SCENE = "scene.vlm.operation.primary"
        const val RUN_GUI_TOOL = "run_gui"
        const val RUN_LOG_TOOL = "vlm_task"
        const val DEFAULT_MAX_STEPS = 20
        private val shared = OmniVlmPlugin(DefaultOmniVlmBackend)

        suspend fun execute(
            context: Context,
            request: Request,
            modelClient: OmniFlowModelClient,
            hooks: Hooks = Hooks(),
        ): Result = shared.execute(context, request, modelClient, hooks)

        fun stop(runId: String): Boolean = shared.stop(runId)
    }
}

internal interface OmniVlmBackend {
    suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result

    fun stop(runId: String): Boolean
}

private object DefaultOmniVlmBackend : OmniVlmBackend {
    override suspend fun execute(
        context: Context,
        request: OmniVlmPlugin.Request,
        modelClient: OmniFlowModelClient,
        hooks: OmniVlmPlugin.Hooks,
    ): OmniVlmPlugin.Result {
        check(OmniFlowPluginRuntime.isEnabled()) { "omniflow_plugin_not_enabled" }
        val execution = OmniFlow.callTool(
            context = context,
            toolName = OmniVlmPlugin.RUN_GUI_TOOL,
            arguments = request.runGuiArguments(),
            goal = request.goal,
            runId = request.runId,
            source = "vlm",
            runLogToolName = OmniVlmPlugin.RUN_LOG_TOOL,
            modelClient = modelClient,
            hooks = OmniFlow.Hooks(
                beforeOperation = hooks.beforeOperation,
                stopRequested = hooks.stopRequested,
                onProgress = hooks.onProgress,
            ),
        )
        return OmniVlmPlugin.Result(
            payload = safePaymentResult(execution.payload),
            finalStateId = execution.finalStateId,
        )
    }

    override fun stop(runId: String): Boolean = OmniFlow.stop(runId)
}

private fun safePaymentResult(payload: Map<String, Any?>): Map<String, Any?> {
    val failure = listOf(
        payload["error_message"],
        payload["error_code"],
        payload["done_reason"],
    ).joinToString(" ") { it?.toString().orEmpty() }
    if (
        payload["payment_confirmation_blocked"] != true &&
        !failure.contains("payment_confirmation_blocked", ignoreCase = true)
    ) {
        return payload
    }
    return payload + mapOf(
        "success" to true,
        "status" to "succeeded",
        "done_reason" to "pending_unpaid_order",
        "payment_confirmation_blocked" to true,
        "error_code" to null,
        "error_message" to null,
    )
}

internal fun OmniVlmPlugin.Request.runGuiArguments(): Map<String, Any?> = buildMap {
    put("goal", goal)
    put("model", OmniVlmPlugin.MODEL_SCENE)
    stepSkillGuidance.trim().takeIf(String::isNotEmpty)?.let {
        put("step_skill_guidance", it)
    }
    put("defer_user_input", deferUserInput)
    put("max_steps", maxSteps)
}
