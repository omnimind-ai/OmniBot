package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import cn.com.omnimind.bot.runlog.firstNonBlank
import cn.com.omnimind.bot.runlog.mapArg
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.AndroidAutomationPermissionGate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class VlmToolHandler(context: Context) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override val toolNames: Set<String> = setOf(OmniVlmPlugin.RUN_LOG_TOOL)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        helper.ensureRunActive()
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return ToolExecutionResult.Error(
                OmniVlmPlugin.RUN_LOG_TOOL,
                helper.localized("缺少 goal"),
            )
        val runId = "gui-${UUID.randomUUID()}"
        toolHandle.bindStopAction {
            OmniVlmPlugin.stop(runId)
        }
        helper.reportToolProgress(
            callback = callback,
            toolName = OmniVlmPlugin.RUN_LOG_TOOL,
            progress = "VLM正在配置",
            extras = mapOf(
                "run_id" to runId,
                "stage" to "vlm_runtime_prepare",
            ),
            toolHandle = toolHandle,
        )
        if (!OmniFlowPluginRuntime.isEnabled()) {
            val message = "手机操作模块未启用。请打开插件市场 → OmniFlow → 启用插件（若尚未安装则先安装），确认无障碍服务已开启，并在模型场景中配置 Agent Provider/模型后重试。"
            persistFailure(runId, goal, "omniflow_disabled", message)
            return failedRunResult(
                runId = runId,
                goal = goal,
                doneReason = "omniflow_disabled",
                message = message,
            )
        }
        val permission = AndroidAutomationPermissionGate.check(helper.context)
        if (!permission.granted) {
            persistFailure(runId, goal, "permission_required", "缺少无障碍权限")
            // Keep the typed result all the way through the Agent executor.
            // Calling the callback alone is not enough: the ACP adapter uses
            // PermissionRequired to project a permission_section card instead
            // of letting the model turn this into an assistant-only sentence.
            return helper.permissionRequiredResult(callback, permission.displayNames)
        }
        prepareOfficialModelRoute()?.let { message ->
            persistFailure(runId, goal, "provider_unavailable", message)
            return failedRunResult(
                runId = runId,
                goal = goal,
                doneReason = "provider_unavailable",
                message = message,
            )
        }
        return try {
            val execution = OmniVlmPlugin.execute(
                context = helper.context,
                request = OmniVlmPlugin.Request(
                    goal = goal,
                    runId = runId,
                ),
                modelClient = HttpAgentLlmClient(
                    scope = CoroutineScope(currentCoroutineContext()),
                )
                    .asOmniFlowModelClient(),
                hooks = OmniVlmPlugin.Hooks(
                    beforeOperation = {
                        helper.ensureRunActive()
                        toolHandle.throwIfStopRequested()
                    },
                    stopRequested = toolHandle::isManualStopRequested,
                    onProgress = { progress, extras ->
                        val progressRunId = firstNonBlank(extras["run_id"], runId)
                        helper.reportToolProgress(
                            callback = callback,
                            toolName = OmniVlmPlugin.RUN_LOG_TOOL,
                            progress = progress,
                            extras = extras + ("run_id" to progressRunId),
                            toolHandle = toolHandle,
                        )
                    },
                ),
            )
            val result = execution.payload
            val resultRunId = firstNonBlank(result["run_id"], runId)
            val finalStateId = firstNonBlank(
                mapArg(result["final_state"])["state_id"],
                execution.finalStateId,
            ).takeIf(String::isNotBlank)
            val doneReason = firstNonBlank(result["done_reason"]).ifBlank {
                if (result["success"] == true) "finished" else "error"
            }
            val content = firstNonBlank(result["finished_content"])
            if (doneReason == "waiting_input") {
                val question = content.ifBlank { "请提供继续执行所需的信息。" }
                return ToolExecutionResult.Error(
                    OmniVlmPlugin.RUN_LOG_TOOL,
                    "视觉任务需要额外输入，当前未提供交互通道：$question",
                )
            }
            if (doneReason == "cancelled") {
                val message = firstNonBlank(result["error_message"], result["error_code"])
                    .ifBlank { "视觉任务已停止" }
                val payload = buildVlmTaskContextPayload(
                    requestedRunId = runId,
                    goal = goal,
                    resultRunId = resultRunId,
                    success = false,
                    doneReason = doneReason,
                    content = "",
                    finalStateId = finalStateId,
                    finalState = finalStatePayload(finalStateId),
                    stepCount = runStepCount(resultRunId),
                    extras = mapOf("error" to message),
                )
                val encoded = helper.encodeLocalizedPayload(payload)
                return ToolExecutionResult.Interrupted(
                    toolName = OmniVlmPlugin.RUN_LOG_TOOL,
                    summaryText = helper.localized("视觉任务已停止"),
                    previewJson = encoded,
                    rawResultJson = encoded,
                    interruptedBy = "user",
                    interruptionReason = "vlm_cancelled",
                )
            }
            if (result["success"] != true) {
                val message = firstNonBlank(result["error_message"], result["error_code"])
                    .ifBlank { "gui_task_failed" }
                return failedRunResult(
                    runId = resultRunId,
                    goal = goal,
                    doneReason = doneReason,
                    message = message,
                    finalStateId = finalStateId,
                )
            }
            val completed = content.ifBlank { "视觉任务已完成" }
            val stepCount = runStepCount(resultRunId)
            val payload = buildVlmTaskContextPayload(
                requestedRunId = runId,
                goal = goal,
                resultRunId = resultRunId,
                success = true,
                doneReason = doneReason,
                content = completed,
                finalStateId = finalStateId,
                finalState = finalStatePayload(finalStateId),
                stepCount = stepCount,
                extras = result.filterKeys {
                    it in setOf(
                        "source",
                        "function_id",
                        "recall_hit",
                        "recalled_function_id",
                        "resolved_model",
                        "auto_registered",
                        "registered_function_id",
                        "registration_status",
                        "registration_error",
                    )
                },
            )
            val encoded = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = OmniVlmPlugin.RUN_LOG_TOOL,
                summaryText = helper.localized(completed),
                previewJson = encoded,
                rawResultJson = encoded,
                success = true,
            )
        } catch (error: CancellationException) {
            persistFailure(
                runId = runId,
                goal = goal,
                doneReason = "cancelled",
                message = error.message.orEmpty().ifBlank { "视觉任务已停止" },
            )
            throw error
        } catch (error: Exception) {
            val message = error.message.orEmpty().ifBlank { error.javaClass.simpleName }
            persistFailure(runId, goal, "error", message)
            failedRunResult(
                runId = runId,
                goal = goal,
                doneReason = "error",
                message = message,
            )
        }
    }

    private fun persistFailure(
        runId: String,
        goal: String,
        doneReason: String,
        message: String,
    ) {
        val existing = InternalRunLogStore.getRun(helper.context, runId)
        if (existing == null) {
            InternalRunLogStore.beginRun(
                context = helper.context,
                runId = runId,
                goal = goal,
                source = "vlm",
                toolName = OmniVlmPlugin.RUN_LOG_TOOL,
                operationDescription = goal,
            )
        }
        if (existing?.finishedAtMs == null) {
            InternalRunLogStore.finishRun(
                context = helper.context,
                runId = runId,
                success = false,
                doneReason = doneReason,
                errorMessage = message,
            )
        }
    }

    private fun failedRunResult(
        runId: String,
        goal: String = "",
        doneReason: String,
        message: String,
        finalStateId: String? = null,
    ): ToolExecutionResult.ContextResult {
        val localizedMessage = helper.localized(message)
        val payload = buildVlmTaskContextPayload(
            requestedRunId = runId,
            goal = goal,
            resultRunId = runId,
            success = false,
            doneReason = doneReason,
            content = "",
            finalStateId = finalStateId,
            finalState = finalStatePayload(finalStateId),
            stepCount = runStepCount(runId),
            extras = mapOf("error" to localizedMessage),
        )
        val encoded = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = OmniVlmPlugin.RUN_LOG_TOOL,
            summaryText = localizedMessage,
            previewJson = encoded,
            rawResultJson = encoded,
            success = false,
        )
    }

    private fun runStepCount(runId: String): Int? =
        InternalRunLogStore.getRun(helper.context, runId)?.steps?.size

    private fun finalStatePayload(stateId: String?): Map<String, Any?>? = stateId
        ?.takeIf(String::isNotBlank)
        ?.let { InternalRunLogStore.statePayload(helper.context, it) }
        ?.takeIf(Map<String, Any?>::isNotEmpty)
        ?.let { state ->
            state.filterKeys {
                it == "state_id" || it == "package_name" ||
                    it == "activity_name" || it == "display"
            }
        }

    private suspend fun prepareOfficialModelRoute(): String? {
        if (!SceneOperationConfigStore.getConfig().useOfficialService) return null
        if (OfficialVlmOperationConfigStore.getConfig().isConfigured()) return null

        runCatching { AppUpdateManager.checkNow(helper.context, force = true) }
        if (OfficialVlmOperationConfigStore.getConfig().isConfigured()) return null

        val binding = SceneModelBindingStore.getBinding(SceneOperationConfigStore.SCENE_ID)
        val boundProviderReady = binding
            ?.providerProfileId
            ?.let(ModelProviderConfigStore::getProfile)
            ?.isConfigured() == true
        if (boundProviderReady || ModelProviderConfigStore.getConfig().isConfigured()) return null

        return "小万官方内置模型暂不可用，请稍后重试或在模型场景中选择其他 Provider。"
    }
}

/** Stable context envelope returned to the outer Agent after a VLM task. */
internal fun buildVlmTaskContextPayload(
    requestedRunId: String,
    goal: String,
    resultRunId: String,
    success: Boolean,
    doneReason: String,
    content: String,
    finalStateId: String?,
    finalState: Map<String, Any?>? = null,
    stepCount: Int? = null,
    extras: Map<String, Any?> = emptyMap(),
): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    val autoRegistered = extras["auto_registered"] == true
    put("context_type", "vlm_task_result")
    put("requested_run_id", requestedRunId)
    put("run_id", resultRunId)
    put("run_log_id", resultRunId)
    put("goal", goal)
    put("success", success)
    put("status", if (success) "succeeded" else "failed")
    put("done_reason", doneReason)
    put("content", content)
    put("finished_content", content)
    put("final_state_id", finalStateId)
    put("final_state", finalState)
    put("step_count", stepCount)
    put("action_count", stepCount)
    if (success) {
        put("registration_available", true)
        put(
            "registration_hint",
            if (autoRegistered) {
                "本次任务已自动注册为复用指令，可在复用指令中直接执行。"
            } else {
                "本次成功操作已保存为 RunLog，可注册为复用指令以便下次快速执行。"
            },
        )
    }
    put(
        "next_agent_instruction",
        if (success) {
            if (autoRegistered) {
                "Treat this VLM task result as the current execution context. Tell the " +
                    "user the task completed and was registered as a reusable Function. " +
                    "Use registered_function_id for reuse and final_state for follow-up."
            } else {
                "Treat this VLM task result as the current execution context. Tell the " +
                    "user the successful run was saved as a RunLog and can be registered " +
                    "as a reusable Function. Do not claim registration already succeeded. " +
                    "Use run_log_id for registration and final_state for follow-up."
            }
        } else {
            "Treat this as the current VLM task failure context. Preserve goal and " +
                "run_log_id when explaining the failure or deciding whether to retry."
        },
    )
    putAll(extras.filterValues { it != null })
}.filterValues { it != null }
