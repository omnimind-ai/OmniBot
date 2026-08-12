package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.distribution.AppEditionCapabilities
import cn.com.omnimind.bot.util.AssistsUtil
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ContextToolHandler(
    private val helper: SharedHelper
) : ToolHandler {
    override val toolNames: Set<String> = setOf("context_apps_query")

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "context_apps_query" -> executeContextAppsQuery(args, env.runtimeContextRepository, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown context tool")
        }
    }

    private suspend fun executeContextAppsQuery(
        args: JsonObject,
        runtimeContextRepository: AgentRuntimeContextRepository,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "context_apps_query"
        return try {
            if (!AppEditionCapabilities.canQueryInstalledApps) {
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("当前应用版本不提供已安装应用查询。")
                )
            }
            if (!AssistsUtil.Setting.isInstalledAppsPermissionGranted(helper.context)) {
                val missing = listOf("应用列表读取权限")
                return helper.permissionRequiredResult(callback, missing)
            }
            helper.reportToolProgress(callback, toolName, "正在查询已安装应用")
            helper.ensureRunActive()
            val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = helper.parseContextQueryLimit(args["limit"]?.jsonPrimitive?.intOrNull)
            val items = runtimeContextRepository.queryInstalledApps(query = query, limit = limit)
            val payload = linkedMapOf<String, Any?>(
                "query" to query.orEmpty(),
                "limit" to limit,
                "count" to items.size,
                "items" to items.map { item ->
                    mapOf(
                        "appName" to item.appName,
                        "packageName" to item.packageName
                    )
                }
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (items.isEmpty()) {
                    "未找到匹配的已安装应用。"
                } else {
                    "找到 ${items.size} 个已安装应用。"
                }),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ToolExecutionResult.Error(
                toolName,
                helper.localized("查询已安装应用失败")
            )
        }
    }
}
