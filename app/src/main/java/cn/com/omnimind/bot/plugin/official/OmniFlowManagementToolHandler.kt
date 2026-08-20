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
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
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

class OmniFlowManagementToolHandler(context: Context) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override val toolNames: Set<String> = OmniFlowManagementTools.TOOL_NAMES

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
            val normalizedArguments = normalizeOmniFlowManagementArguments(toolName, args)
            if (toolName in DEVELOPER_OVERRIDE_TOOLS) {
                return developerOverrideResult(toolName, normalizedArguments)
            }
            if (
                toolName == OmniFlowManagementTools.SAVE_FUNCTION &&
                normalizedArguments["run_id"]?.toString()?.isNotBlank() == true
            ) {
                val runId = normalizedArguments["run_id"]?.toString().orEmpty().trim()
                val record = InternalRunLogStore.getRun(helper.context, runId)
                if (!isRegisterableRunLog(record)) {
                    return ToolExecutionResult.Error(
                        toolName,
                        "RUN_LOG_NOT_SUCCESSFUL: only a succeeded RunLog can become a Function",
                    )
                }
            }
            val modelClient = if (OmniFlowPluginRuntime.isEnabled()) {
                    HttpAgentLlmClient(CoroutineScope(currentCoroutineContext()))
                        .asOmniFlowModelClient()
                } else {
                    null
                }
            val payload = OmniFlow.callTool(
                context = helper.context,
                toolCall = OmniFlow.ToolCall(toolName, normalizedArguments),
                modelClient = modelClient,
            ).payload
            val encoded = helper.mapToJsonElement(payload).toString()
            if (payload["success"] == false) {
                ToolExecutionResult.Error(
                    toolName,
                    payload["error_message"]?.toString()
                        ?: payload["error_code"]?.toString()
                        ?: "OmniFlow management tool failed",
                )
            } else {
                ToolExecutionResult.ContextResult(
                    toolName = toolName,
                    summaryText = summary(toolName, payload),
                    previewJson = encoded,
                    rawResultJson = encoded,
                    success = true,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                toolName,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }

    private fun summary(toolName: String, payload: Map<String, Any?>): String = when (toolName) {
        OmniFlowManagementTools.LIST_FUNCTIONS ->
            "已读取 ${payload["count"] ?: 0} 个复用指令"
        OmniFlowManagementTools.LIST_RUN_LOGS ->
            "已读取 ${payload["count"] ?: (payload["runs"] as? List<*>)?.size ?: 0} 个 RunLog"
        OmniFlowManagementTools.SAVE_FUNCTION -> "RunLog 已注册为复用指令"
        else -> "OmniFlow 操作已完成"
    }

    private suspend fun developerOverrideResult(
        toolName: String,
        arguments: Map<String, Any?>,
    ): ToolExecutionResult {
        val payload = when (toolName) {
            OmniFlowManagementTools.GET_PYTHON_OVERRIDE -> {
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
                        "editable_glob" to "omniflow/**/*.py",
                    )
                } else {
                    OmniFlowPythonRuntime.readDeveloperOverride(helper.context, path) +
                        ("success" to true)
                }
            }
            OmniFlowManagementTools.APPLY_PYTHON_OVERRIDE ->
                OmniFlowPythonRuntime.applyDeveloperOverride(
                    helper.context,
                    arguments["path"]?.toString().orEmpty(),
                    arguments["content"]?.toString().orEmpty(),
                )
            OmniFlowManagementTools.CLEAR_PYTHON_OVERRIDE -> {
                require(arguments["confirm"] == true) { "confirm_must_be_true" }
                OmniFlowPythonRuntime.clearDeveloperOverride(helper.context)
            }
            OmniFlowManagementTools.RELOAD_PYTHON_OVERRIDE ->
                OmniFlowPythonRuntime.reloadDeveloperOverride(helper.context)
            else -> error("unsupported_developer_override_tool:$toolName")
        }
        val encoded = helper.mapToJsonElement(payload).toString()
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = when (toolName) {
                OmniFlowManagementTools.GET_PYTHON_OVERRIDE -> "已读取 OmniFlow Python 开发覆盖层"
                OmniFlowManagementTools.APPLY_PYTHON_OVERRIDE -> "Python 修改已校验并热重载"
                OmniFlowManagementTools.CLEAR_PYTHON_OVERRIDE -> "已恢复固定版本 OmniFlow runtime"
                else -> "OmniFlow Python worker 已重载"
            },
            previewJson = encoded,
            rawResultJson = encoded,
            success = true,
        )
    }

    companion object {
        private val DEVELOPER_OVERRIDE_TOOLS = setOf(
            OmniFlowManagementTools.GET_PYTHON_OVERRIDE,
            OmniFlowManagementTools.APPLY_PYTHON_OVERRIDE,
            OmniFlowManagementTools.CLEAR_PYTHON_OVERRIDE,
            OmniFlowManagementTools.RELOAD_PYTHON_OVERRIDE,
        )
    }
}

internal fun isRegisterableRunLog(record: CanonicalRunLogRecord?): Boolean =
    record?.success == true && record.status == "succeeded" && record.doneReason != "error"

/**
 * A registered RunLog Function must be visible to the recall router unless the caller
 * explicitly asks for a hidden artifact.
 */
internal fun normalizeOmniFlowManagementArguments(
    toolName: String,
    args: JsonObject,
): Map<String, Any?> = buildMap {
    // Keep the existing helper conversion semantics in the caller for all normal values.
    // This map is intentionally assembled from the JsonObject below so the normalization
    // remains independent of Android Context and is easy to regression-test.
    args.entries.forEach { (key, value) ->
        put(key, jsonElementToManagementValue(value))
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
