package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentToolVisibilitySelector
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Progressive tool discovery entrypoint. The model sees this small schema in
 * the first round and receives concrete tool schemas only after searching.
 */
class ToolSearchHandler(
    private val catalog: cn.com.omnimind.bot.agent.AgentToolCatalog,
    private val helper: SharedHelper,
) : ToolHandler {
    companion object {
        const val NAME = AgentToolVisibilitySelector.TOOL_SEARCH_NAME
    }

    override val toolNames: Set<String> = setOf(NAME)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) {
            return ToolExecutionResult.Error(NAME, helper.localized("缺少工具搜索目标"))
        }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(1, 20)
        val matches = catalog.searchTools(query, limit)
        val payload = mapOf(
            "query" to query,
            "count" to matches.size,
            "tools" to matches.map { entry ->
                mapOf(
                    "name" to entry.name,
                    "displayName" to entry.displayName,
                    "description" to entry.description,
                    "toolType" to entry.toolType,
                    "serverName" to entry.serverName,
                )
            },
            "nextStep" to if (matches.isEmpty()) {
                "No matching tool was found. Search again with a broader goal."
            } else {
                "The matching full schemas are injected into the next model request. Call a returned capability only after its schema appears there."
            },
            "schemaInjection" to if (matches.isEmpty()) "none" else "next_model_request",
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = NAME,
            summaryText = helper.localized(
                if (matches.isEmpty()) "未找到匹配的工具。"
                else "已找到 ${matches.size} 个能力，完整 schema 将注入下一轮。"
            ),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true,
        )
    }
}
