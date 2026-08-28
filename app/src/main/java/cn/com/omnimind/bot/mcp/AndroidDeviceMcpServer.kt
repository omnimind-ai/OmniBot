package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentAlarmCreateRequest
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRegistration
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.official.OmniVlmLiteProvider
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal object AndroidDeviceMcpServer {
    internal data class DeviceTool(
        val name: String,
        val operation: String,
        val description: String,
        val properties: Map<String, JsonObject> = emptyMap(),
        val required: List<String> = emptyList(),
        /** Restore the optional OmniFlow provider only for explicit OmniFlow calls. */
        val requiresOmniFlowPlugin: Boolean = false,
    )

    // Keep the ACP-facing MCP surface limited to official device capabilities.
    // The app's private plugin-project protocol is intentionally not advertised
    // here: DSH and other ACP Agents must use their own native plugin schema.
    private val omniFlowTools = listOf(
        DeviceTool(
            name = "run_gui",
            operation = "run_gui",
            description = "Execute a new Android GUI task with the installed OmniFlow runtime.",
            properties = mapOf(
                "goal" to schema("string", "GUI task to complete."),
                "max_steps" to schema("integer", "Maximum execution steps."),
                "defer_user_input" to schema("boolean", "Return when user input is required."),
                "step_skill_guidance" to schema("string", "Optional step guidance."),
            ),
            required = listOf("goal"),
            requiresOmniFlowPlugin = true,
        ),
        DeviceTool(
            name = "run_function",
            operation = "run_function",
            description = "Replay one registered OmniFlow Function.",
            properties = mapOf(
                "function_id" to schema("string", "Registered Function id."),
                "arguments" to schema("object", "Semantic Function arguments."),
                "goal" to schema("string", "Optional display goal."),
            ),
            required = listOf("function_id"),
            requiresOmniFlowPlugin = true,
        ),
        DeviceTool(
            name = "list_functions",
            operation = "list_functions",
            description = "List registered OmniFlow Functions.",
            properties = mapOf(
                "limit" to schema("integer", "Maximum results."),
                "offset" to schema("integer", "Pagination offset."),
                "include_hidden" to schema("boolean", "Include hidden Functions."),
            ),
            requiresOmniFlowPlugin = true,
        ),
        DeviceTool(
            name = "register_function",
            operation = "save_function",
            description = "Register one successful OmniFlow RunLog as a reusable Function.",
            properties = mapOf(
                "run_id" to schema("string", "Successful RunLog id returned by run_gui."),
            ),
            required = listOf("run_id"),
            requiresOmniFlowPlugin = true,
        ),
        DeviceTool(
            name = "context_apps_query",
            operation = "context_apps_query",
            description = "Query launchable apps installed on the Android device.",
            properties = mapOf(
                "query" to schema("string", "App name or package substring."),
                "limit" to schema("integer", "Maximum number of results."),
            ),
        ),
        DeviceTool(
            name = "file_transfer",
            operation = "file_transfer",
            description = "List or retrieve files shared to the OpenOmniBot device inbox.",
            properties = mapOf(
                "action" to schema("string", "latest | wait | list | get | clear."),
                "fileId" to schema("string", "File id for get or clear."),
                "afterFileId" to schema("string", "For wait, return a newer file."),
                "timeoutMs" to schema("integer", "Wait timeout in milliseconds."),
                "limit" to schema("integer", "Maximum number of files."),
            ),
        ),
        DeviceTool(
            name = "schedule_task_create",
            operation = "schedule_task_create",
            description = "Create a persistent scheduled Agent task on this device.",
            properties = mapOf(
                "taskId" to schema("string", "Stable task id."),
                "title" to schema("string", "Task title."),
                "scheduleType" to schema("string", "fixed_time or countdown."),
                "fixedTime" to schema("string", "ISO local date-time for fixed_time."),
                "countdownMinutes" to schema("integer", "Delay in minutes for countdown."),
                "repeatDaily" to schema("boolean", "Repeat every day."),
                "enabled" to schema("boolean", "Whether the task is enabled."),
                "subagentPrompt" to schema("string", "Prompt executed when triggered."),
                "notificationEnabled" to schema("boolean", "Show completion notification."),
            ),
            required = listOf("taskId", "title", "subagentPrompt"),
        ),
        DeviceTool(
            name = "schedule_task_list",
            operation = "schedule_task_list",
            description = "List persistent scheduled Agent tasks on this device.",
            properties = mapOf("limit" to schema("integer", "Maximum number of tasks.")),
        ),
        DeviceTool(
            name = "schedule_task_update",
            operation = "schedule_task_update",
            description = "Update a persistent scheduled Agent task.",
            properties = mapOf(
                "taskId" to schema("string", "Stable task id."),
                "title" to schema("string", "Task title."),
                "scheduleType" to schema("string", "fixed_time or countdown."),
                "fixedTime" to schema("string", "ISO local date-time for fixed_time."),
                "countdownMinutes" to schema("integer", "Delay in minutes for countdown."),
                "repeatDaily" to schema("boolean", "Repeat every day."),
                "enabled" to schema("boolean", "Whether the task is enabled."),
                "subagentPrompt" to schema("string", "Prompt executed when triggered."),
                "notificationEnabled" to schema("boolean", "Show completion notification."),
            ),
            required = listOf("taskId"),
        ),
        DeviceTool(
            name = "schedule_task_delete",
            operation = "schedule_task_delete",
            description = "Delete a persistent scheduled Agent task.",
            properties = mapOf("taskId" to schema("string", "Stable task id.")),
            required = listOf("taskId"),
        ),
        DeviceTool(
            name = "alarm_reminder_create",
            operation = "alarm_reminder_create",
            description = "Create a persistent reminder alarm on this device.",
            properties = mapOf(
                "mode" to schema("string", "exact_alarm or clock_app."),
                "title" to schema("string", "Reminder title."),
                "triggerAt" to schema("string", "ISO timestamp or local date-time."),
                "message" to schema("string", "Optional reminder message."),
                "timezone" to schema("string", "Optional IANA timezone."),
                "allowWhileIdle" to schema("boolean", "Allow delivery while idle."),
                "skipUi" to schema("boolean", "Do not open the system clock UI."),
            ),
            required = listOf("mode", "title", "triggerAt"),
        ),
        DeviceTool(
            name = "alarm_reminder_list",
            operation = "alarm_reminder_list",
            description = "List persistent reminder alarms on this device.",
        ),
        DeviceTool(
            name = "alarm_reminder_delete",
            operation = "alarm_reminder_delete",
            description = "Delete a reminder alarm on this device.",
            properties = mapOf("alarmId" to schema("string", "Reminder alarm id.")),
            required = listOf("alarmId"),
        ),
    )

    /**
     * The ACP/MCP declaration is intentionally a device-capability boundary.
     * Harnesses already provide their own general-purpose tools (read/write,
     * shell, plan, subagents, etc.); advertising OmniBot's native Agent
     * catalog here makes the model choose the wrong implementation and can
     * result in duplicate or recursive tool calls.  Keep those capabilities
     * available to the in-app Agent runtime, but do not inject them into a
     * Harness session.
     */
    internal val publicToolNames: Set<String> = omniFlowTools.map { it.name }.toSet()

    internal const val MCP_INSTRUCTIONS: String =
        "Use the OmniBot Android device MCP tools for GUI automation, installed apps, " +
            "device file transfer, scheduled tasks, reminders, and OmniFlow Functions."

    internal fun modernToolDescriptors(
        context: Context,
        scope: CoroutineScope,
    ): List<DeviceTool> {
        // Only Android/device tools are part of the public ACP surface.  The
        // Harness owns its built-in/general tools and must discover those from
        // its own protocol instead of receiving OmniBot's native catalog.
        return omniFlowTools
    }

    internal suspend fun modernCallTool(
        context: Context,
        scope: CoroutineScope,
        name: String,
        arguments: Map<String, JsonElement>,
    ): CallToolResult {
        val modelClient = HttpAgentLlmClient(scope).asOmniFlowModelClient()
        val tool = omniFlowTools.firstOrNull { it.name == name }
            ?: return errorResult(IllegalArgumentException("Unknown MCP tool: $name"))
        return runCatching {
            if (tool.requiresOmniFlowPlugin) {
                ensureOmniFlowReady(context)
            }
            callOmniFlowTool(
                context = context,
                tool = tool,
                arguments = arguments.toKotlinMap(),
                modelClient = modelClient,
            )
        }.fold(
            onSuccess = ::successResult,
            onFailure = ::errorResult,
        )
    }

    fun create(
        context: Context,
        scope: CoroutineScope,
    ): Server {
        val modelClient = HttpAgentLlmClient(scope).asOmniFlowModelClient()
        return Server(
            serverInfo = Implementation(
                // Keep the MCP server identity identical across ACP adapters,
                // the phone endpoint, and the official DSH MCP client.
                name = "omnibot",
                version = "1.0.0",
                title = "OmniBot",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
            instructions = MCP_INSTRUCTIONS,
        ).apply {
            omniFlowTools.forEach { tool ->
                addTool(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = ToolSchema(
                        properties = JsonObject(tool.properties),
                        required = tool.required.takeIf(List<String>::isNotEmpty),
                    ),
                ) { request ->
                    runCatching {
                        if (tool.requiresOmniFlowPlugin) {
                            ensureOmniFlowReady(context)
                        }
                        callOmniFlowTool(
                            context = context,
                            tool = tool,
                            arguments = request.params.arguments.orEmpty().toKotlinMap(),
                            modelClient = modelClient,
                        )
                    }.fold(
                        onSuccess = ::successResult,
                        onFailure = ::errorResult,
                    )
                }
            }
        }
    }

    private suspend fun ensureOmniFlowReady(context: Context) {
        val host = OmniPluginHost.get(context)
        requireDefaultPluginEnabled(
            isEnabled = OmniFlowPluginRuntime::isEnabled,
            inspect = {
                host.list()
                    .firstOrNull { it.descriptor.id == OmniVlmLiteProvider.ID }
                    ?.let { DefaultPluginStatus(installed = it.installed, enabled = it.enabled) }
            },
        )
    }

    internal data class DefaultPluginStatus(
        val installed: Boolean,
        val enabled: Boolean,
    )

    internal suspend fun requireDefaultPluginEnabled(
        isEnabled: () -> Boolean,
        inspect: suspend () -> DefaultPluginStatus?,
    ) {
        if (isEnabled()) return
        val status = inspect()
        val guidance = if (status?.installed == true) {
            "手机操作未启用。请打开插件市场 → OmniFlow → 启用插件，确认无障碍服务已开启，并在模型场景中配置 Agent Provider/模型后重试。"
        } else {
            "手机操作未启用。请打开插件市场 → OmniFlow → 安装并启用插件，确认无障碍服务已开启，并在模型场景中配置 Agent Provider/模型后重试。"
        }
        throw IllegalStateException(guidance)
    }

    private suspend fun callOmniFlowTool(
        context: Context,
        tool: DeviceTool,
        arguments: Map<String, Any?>,
        modelClient: cn.com.omnimind.bot.omniflow.OmniFlowModelClient,
    ): Map<String, Any?> = when (tool.operation) {
        "run_gui" -> {
            val goal = arguments["goal"]?.toString().orEmpty().trim()
            require(goal.isNotEmpty()) { "omniflow_goal_required" }
            OmniVlmPlugin.execute(
                context = context,
                request = OmniVlmPlugin.Request(
                    goal = goal,
                    stepSkillGuidance = arguments["step_skill_guidance"]?.toString().orEmpty(),
                    deferUserInput = arguments["defer_user_input"] as? Boolean ?: true,
                    maxSteps = (arguments["max_steps"] as? Number)?.toInt()
                        ?: OmniVlmPlugin.DEFAULT_MAX_STEPS,
                ),
                modelClient = modelClient,
            ).payload
        }
        "run_function" -> {
            val functionId = arguments["function_id"]?.toString().orEmpty().trim()
            require(functionId.isNotEmpty()) { "omniflow_function_id_required" }
            val functionArguments = (arguments["arguments"] as? Map<*, *>)
                .orEmpty()
                .entries
                .associate { (key, value) -> key.toString() to value }
            OmniFlow.callTool(
                context = context,
                toolCall = OmniFlow.ToolCall(functionId, functionArguments),
                goal = arguments["goal"]?.toString().orEmpty().ifBlank { functionId },
                source = "mcp",
                runLogToolName = functionId,
                modelClient = modelClient,
            ).payload
        }
        "save_function" -> {
            val runId = arguments["run_id"]?.toString().orEmpty().trim()
            require(runId.isNotEmpty()) { "omniflow_run_id_required" }
            OmniFlowFunctionRegistration.saveRunLog(
                context = context,
                runId = runId,
                agentVisible = true,
                source = "mcp",
                modelClient = modelClient,
            )
        }
        "context_apps_query" -> {
            val query = arguments["query"]?.toString()?.trim().orEmpty()
            val limit = (arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 20
            val items = AgentRuntimeContextRepository(context).queryInstalledApps(
                query = query.ifBlank { null },
                limit = limit,
            )
            mapOf(
                "query" to query,
                "limit" to limit,
                "count" to items.size,
                "items" to items.map { item ->
                    mapOf("appName" to item.appName, "packageName" to item.packageName)
                },
            )
        }
        "file_transfer" -> McpToolExecutors.executeFileTransfer(arguments)
        "schedule_task_create" -> WorkspaceScheduledTaskScheduler(context).upsertTask(arguments)
        "schedule_task_list" -> {
            val tasks = WorkspaceScheduledTaskScheduler(context).listTasks()
            val limit = (arguments["limit"] as? Number)?.toInt()?.coerceIn(1, 100) ?: 100
            mapOf("count" to minOf(limit, tasks.size), "items" to tasks.take(limit))
        }
        "schedule_task_update" -> WorkspaceScheduledTaskScheduler(context).updateTask(arguments)
        "schedule_task_delete" -> mapOf(
            "taskId" to arguments["taskId"].toString(),
            "deleted" to WorkspaceScheduledTaskScheduler(context).deleteTask(
                arguments["taskId"]?.toString().orEmpty()
            ),
        )
        "alarm_reminder_create" -> AgentAlarmToolService(context).createReminder(
            AgentAlarmCreateRequest(
                mode = arguments["mode"]?.toString().orEmpty(),
                title = arguments["title"]?.toString().orEmpty(),
                triggerAt = arguments["triggerAt"]?.toString().orEmpty(),
                message = arguments["message"]?.toString(),
                timezone = arguments["timezone"]?.toString(),
                allowWhileIdle = arguments["allowWhileIdle"] as? Boolean ?: true,
                skipUi = arguments["skipUi"] as? Boolean ?: false,
            )
        )
        "alarm_reminder_list" -> {
            val items = AgentAlarmToolService(context).listExactReminders()
            mapOf("count" to items.size, "items" to items)
        }
        "alarm_reminder_delete" -> AgentAlarmToolService(context).deleteExactReminder(
            arguments["alarmId"]?.toString().orEmpty()
        )
        else -> OmniFlow.callTool(
            context = context,
            toolCall = OmniFlow.ToolCall(tool.operation, arguments),
            source = "mcp",
            modelClient = modelClient,
        ).payload
    }

    private fun successResult(result: Map<String, Any?>): CallToolResult = CallToolResult(
        content = listOf(TextContent(McpJson.encodeToString(JsonObject(result.toJson())))),
        isError = false,
        structuredContent = JsonObject(result.toJson()),
    )

    private fun errorResult(error: Throwable): CallToolResult {
        val result = mapOf(
            "success" to false,
            "error" to (error.message ?: error::class.simpleName.orEmpty()),
        )
        return CallToolResult(
            content = listOf(TextContent(result["error"].toString())),
            isError = true,
            structuredContent = JsonObject(result.toJson()),
        )
    }

    private fun schema(type: String, description: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "description" to JsonPrimitive(description),
        ),
    )

    private fun Map<String, JsonElement>.toKotlinMap(): Map<String, Any?> = entries.associate { (key, value) ->
        key to value.toKotlinValue()
    }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> entries.associate { (key, value) -> key to value.toKotlinValue() }
        is JsonArray -> map { it.toKotlinValue() }
        is JsonPrimitive -> when {
            isString -> contentOrNull
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> contentOrNull
        }
    }

}
