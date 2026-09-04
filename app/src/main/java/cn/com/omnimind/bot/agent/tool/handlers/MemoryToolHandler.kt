package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

class MemoryToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "memory_search", "memory_write_daily", "memory_upsert_longterm", "memory_rollup_day"
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return executeMemoryTool(toolCall.function.name, args, env, callback)
    }

    private suspend fun executeMemoryTool(
        toolName: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "memory_search" -> {
                    helper.reportToolProgress(callback, toolName, "正在检索 workspace 记忆")
                    val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(query.isNotEmpty()) { "query 不能为空" }
                    val limit = args["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 20) ?: 8
                    val result = env.workspaceMemoryService.searchMemory(query, limit)
                    val tracker = env.turnMemoryLoadTracker
                    val payload = linkedMapOf<String, Any?>(
                        "query" to result.query,
                        "usedEmbedding" to result.usedEmbedding,
                        "fallbackLexical" to result.fallbackLexical,
                        "count" to result.hits.size,
                        "hits" to result.hits.map { hit ->
                            mapOf(
                                "id" to hit.id,
                                "slug" to hit.slug,
                                "text" to hit.text,
                                "source" to hit.source,
                                "date" to hit.date,
                                "score" to hit.score,
                                "alreadyInContext" to (
                                    tracker?.isLoaded(hit.slug ?: hit.id) == true
                                )
                            )
                        }
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(when {
                            result.hits.isEmpty() -> "未命中相关记忆。"
                            result.fallbackLexical -> "命中 ${result.hits.size} 条记忆（词法检索）。"
                            else -> "命中 ${result.hits.size} 条记忆。"
                        }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                "memory_write_daily" -> {
                    helper.reportToolProgress(callback, toolName, "正在写入当日记忆")
                    val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(text.isNotEmpty()) { "text 不能为空" }
                    val inserted = env.workspaceMemoryService.appendDailyMemoryIfNovel(text)
                    val payload = mapOf(
                        "inserted" to inserted,
                        "summary" to if (inserted) "已写入当日记忆" else "检测到重复，已跳过"
                    )
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(
                            if (inserted) "已写入当日短期记忆。" else "当日短期记忆已存在同类条目，跳过写入。"
                        ),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                "memory_upsert_longterm" -> {
                    helper.reportToolProgress(callback, toolName, "正在沉淀长期记忆")
                    val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    require(text.isNotEmpty()) { "text 不能为空" }
                    val inserted = env.workspaceMemoryService.upsertLongTermMemory(text)
                    val payload = mapOf("inserted" to inserted, "summary" to if (inserted) "已写入长期记忆" else "检测到重复，已跳过")
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(if (inserted) "已沉淀一条长期记忆。" else "长期记忆已存在同类条目，跳过写入。"),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = true
                    )
                }
                "memory_rollup_day" -> {
                    helper.reportToolProgress(callback, toolName, "正在整理当日记忆")
                    val dateRaw = args["date"]?.jsonPrimitive?.contentOrNull?.trim()
                    val date = dateRaw?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } ?: LocalDate.now()
                    val payload = env.workspaceMemoryService.rollupDay(date)
                    val payloadJson = helper.encodeLocalizedPayload(payload)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = helper.localized(payload["summary"]?.toString().orEmpty().ifBlank { "记忆整理完成" }),
                        previewJson = payloadJson, rawResultJson = payloadJson, success = payload["success"] != false
                    )
                }
                else -> ToolExecutionResult.Error(toolName, "Unknown memory tool")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "memory tool failed")) }
    }
}
