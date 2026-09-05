package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.SubagentDispatcher
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `subagent_dispatch` — spawns one or more independent subagents and waits
 * for all of them to finish, returning aggregated results to the parent.
 *
 * Backwards-compatible input shapes:
 *   tasks: ["do thing A", "do thing B"]              ← old string array
 *   tasks: [{profileId: "explorer", instruction: "..."}, ...]  ← new object form
 *   tasks: [{instruction: "..."}, "plain string"]    ← mixed
 *
 * Optional top-level `defaultProfileId` (default "general") applies to any
 * task that doesn't specify its own profileId.
 */
class SubagentToolHandler(
    private val helper: SharedHelper,
    private val dispatcher: SubagentDispatcher
) : ToolHandler {
    override val toolNames: Set<String> = setOf("subagent_dispatch")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "subagent_dispatch"
        return try {
            val defaultProfileId = args["defaultProfileId"]
                ?.jsonPrimitive?.contentOrNull?.trim()
                ?.ifEmpty { null }
                ?: "general"
            val rawTasks = args["tasks"] as? JsonArray ?: JsonArray(emptyList())
            val specs = rawTasks.mapNotNull { element ->
                parseTaskSpec(element, defaultProfileId)
            }
            require(specs.isNotEmpty()) { "tasks 不能为空" }
            val concurrency = resolveSubagentConcurrency(
                requested = args["concurrency"]?.jsonPrimitive?.intOrNull,
                taskCount = specs.size,
            )
            val mergeInstruction = args["mergeInstruction"]?.jsonPrimitive?.contentOrNull?.trim()

            val results = dispatcher.dispatch(
                parentEnv = env,
                tasks = specs,
                concurrency = concurrency,
                progressReporter = { event ->
                    helper.reportToolProgress(
                        callback = callback,
                        toolName = toolName,
                        progress = event.summary,
                        extras = mapOf(
                            "subagentStatusText" to event.summary,
                            "subagentEvents" to listOf(event.toPayload())
                        ),
                        toolHandle = toolHandle
                    )
                }
            )

            val payload = linkedMapOf<String, Any?>(
                "count" to results.size,
                "concurrency" to concurrency,
                "mergeInstruction" to mergeInstruction,
                "results" to results.map { it.toPayload() }
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            val failed = results.count { it.status != "completed" }
            val summary = if (failed > 0) {
                "已完成 ${results.size - failed}/${results.size} 个 subagent 子任务（$failed 失败）。"
            } else {
                "已完成 ${results.size} 个 subagent 子任务。"
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(summary),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = failed == 0
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                toolName,
                helper.localized(e.message ?: "subagent dispatch failed")
            )
        }
    }

    private fun parseTaskSpec(
        element: kotlinx.serialization.json.JsonElement,
        defaultProfileId: String
    ): SubagentDispatcher.SubagentTaskSpec? {
        return when (element) {
            is JsonPrimitive -> {
                val text = element.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) {
                    null
                } else {
                    SubagentDispatcher.SubagentTaskSpec(
                        profileId = defaultProfileId,
                        instruction = text
                    )
                }
            }
            is JsonObject -> {
                val instruction = element["instruction"]?.jsonPrimitive?.contentOrNull?.trim()
                    .orEmpty()
                if (instruction.isEmpty()) {
                    // Tolerate alternative key "task" used by some legacy callers
                    val fallback = element["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (fallback.isEmpty()) return null
                    return SubagentDispatcher.SubagentTaskSpec(
                        profileId = (element["profileId"]?.jsonPrimitive?.contentOrNull
                            ?.trim()?.ifEmpty { null }) ?: defaultProfileId,
                        instruction = fallback
                    )
                }
                SubagentDispatcher.SubagentTaskSpec(
                    profileId = (element["profileId"]?.jsonPrimitive?.contentOrNull
                        ?.trim()?.ifEmpty { null }) ?: defaultProfileId,
                    instruction = instruction
                )
            }
            else -> null
        }
    }

    private fun SubagentDispatcher.SubagentRunResult.toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "taskIndex" to taskIndex,
            "subagentId" to subagentId,
            "profileId" to profileId,
            "status" to status,
            "result" to finalContent,
            "toolCalls" to toolCallSummaries,
            "error" to errorMessage
        )
    }
}

internal fun resolveSubagentConcurrency(requested: Int?, taskCount: Int): Int {
    require(taskCount > 0) { "tasks 不能为空" }
    if (requested != null) {
        require(requested > 0) { "concurrency 必须大于 0" }
        return requested
    }
    return taskCount
}
