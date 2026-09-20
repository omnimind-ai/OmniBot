package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OmniFlowManagementToolHandler(
    context: Context,
    private val tools: List<cn.com.omnimind.bot.omniflow.RuntimeTool>,
) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override val toolNames: Set<String> = tools.mapTo(linkedSetOf()) { it.name }

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        if (toolName !in toolNames) {
            return ToolExecutionResult.Error(toolName, "Unsupported OmniFlow management tool")
        }
        return try {
            helper.ensureRunActive()
            toolHandle.throwIfStopRequested()
            val normalizedArguments = args.entries.associate { (key, value) ->
                key to jsonElementToManagementValue(value)
            }
            val definition = tools.first { it.name == toolName }
            definition.hostAction?.let {
                return developerOverrideResult(toolName, it, normalizedArguments)
            }
            val modelClient = if (OmniFlowPluginRuntime.isEnabled()) {
                    HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                        .asOmniFlowModelClient(helper.context)
                } else {
                    null
                }
            val payload = OmniFlow.callTool(
                context = helper.context,
                toolCall = OmniFlow.ToolCall(toolName, normalizedArguments),
                modelClient = modelClient,
            ).payload
            val encoded = helper.mapToJsonElement(payload).toString()
            managementToolResult(toolName, payload, encoded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                toolName,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }

    private suspend fun developerOverrideResult(
        toolName: String,
        action: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        val payload = when (action) {
            "source.read" -> {
                val path = arguments["path"]?.toString()?.trim().orEmpty()
                if (path.isEmpty()) {
                    val status = OmniFlowPythonRuntime.developerOverrideStatus(helper.context)
                    mapOf(
                        "success" to true,
                        "override_enabled" to status.enabled,
                        "android_install_directory" to status.androidRoot,
                        "shell_install_directory" to status.shellRoot,
                        "runtime_version" to status.runtimeVersion,
                        "modified_files" to status.modifiedFiles,
                        "editable_glob" to "**/*.py",
                    )
                } else {
                    OmniFlowPythonRuntime.readDeveloperOverride(helper.context, path) +
                        ("success" to true)
                }
            }
            "source.apply" ->
                OmniFlowPythonRuntime.applyDeveloperOverride(
                    helper.context,
                    arguments["path"]?.toString().orEmpty(),
                    arguments["content"]?.toString().orEmpty(),
                )
            "source.clear" -> {
                require(arguments["confirm"] == true) { "confirm_must_be_true" }
                OmniFlowPythonRuntime.clearDeveloperOverride(helper.context)
            }
            "source.reload" ->
                OmniFlowPythonRuntime.reloadDeveloperOverride(helper.context)
            else -> error("unsupported_developer_override_tool:$toolName")
        }
        val encoded = helper.mapToJsonElement(payload).toString()
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = when (action) {
                "source.read" -> "已读取 OmniFlow Python 开发覆盖层"
                "source.apply" -> "Python 修改已校验并热重载"
                "source.clear" -> "已恢复固定版本 OmniFlow runtime"
                else -> "OmniFlow Python worker 已重载"
            },
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
        )
    }

}

private fun jsonElementToManagementValue(
    value: JsonElement,
): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> value.entries.associate { (key, item) ->
        key to jsonElementToManagementValue(item)
    }
    is JsonArray -> value.map(::jsonElementToManagementValue)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.booleanOrNull != null -> value.booleanOrNull
        value.longOrNull != null -> value.longOrNull
        value.doubleOrNull != null -> value.doubleOrNull
        else -> value.content
    }
}

/** Keep failed compiler diagnostics in the same tool item and persisted history. */
internal fun managementToolResult(
    toolName: String,
    payload: Map<String, Any?>,
    encoded: String,
): ToolExecutionResult.ContextResult {
    val success = payload["success"] != false
    val error = payload["error"] as? Map<*, *>
    val summary = if (success) payload["summary"]?.toString() ?: "操作已完成" else
        payload["error_message"]?.toString()
            ?: error?.get("message")?.toString()
            ?: payload["error_code"]?.toString()
            ?: error?.get("code")?.toString()
            ?: "OmniFlow management tool failed"
    return ToolExecutionResult.ContextResult(
        toolName = toolName, summaryText = summary,
        previewJson = encoded, rawResultJson = encoded, success = success,
    )
}
