package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.ToolConcurrency
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `memory_load(slug)` — fetches the full body of a long-term memory entry
 * by its slug from a prior `memory_search` result.
 *
 * Same-turn dedup: if the slug was already loaded this turn we return a
 * short "alreadyInContext" hint instead of the body again — saves tokens
 * and signals to the LLM that the content is already visible.
 */
class MemoryLoadToolHandler(
    private val helper: SharedHelper
) : ToolHandler, ToolHandlerConcurrencyHint {

    override val toolNames: Set<String> = setOf("memory_load")

    override fun concurrencyFor(toolName: String, args: JsonObject): ToolConcurrency? {
        return ToolConcurrency.PARALLEL_SAFE
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            val slug = args["slug"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(slug.isNotEmpty()) { "slug 不能为空" }
            val ltmIndex = env.longTermMemoryIndex
                ?: return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("当前会话未启用长期记忆索引")
                )

            val tracker = env.turnMemoryLoadTracker
            if (tracker != null && tracker.isLoaded(slug)) {
                return alreadyLoadedResult(toolName, slug)
            }

            val entry = ltmIndex.get(slug)
                ?: return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("未找到 slug=$slug 对应的长期记忆条目")
                )

            if (tracker != null && !tracker.markLoadedIfAbsent(slug)) {
                return alreadyLoadedResult(toolName, slug)
            }

            val payload = linkedMapOf<String, Any?>(
                "slug" to entry.slug,
                "title" to entry.title,
                "body" to entry.body,
                "tokens" to entry.tokens,
                "updatedAt" to entry.updatedAt
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已加载长期记忆条目：${entry.title.take(40)}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                toolName,
                helper.localized(e.message ?: "memory_load failed")
            )
        }
    }

    private fun alreadyLoadedResult(
        toolName: String,
        slug: String
    ): ToolExecutionResult.ContextResult {
        val payload = mapOf(
            "slug" to slug,
            "alreadyInContext" to true,
            "summary" to "该长期记忆已在本轮上下文中。"
        )
        val payloadJson = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = helper.localized("该长期记忆已加载，跳过重复读取。"),
            previewJson = payloadJson,
            rawResultJson = payloadJson,
            success = true
        )
    }
}
