package cn.com.omnimind.bot.mcp

import android.content.Context
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
    private data class DeviceTool(
        val name: String,
        val operation: String,
        val description: String,
        val properties: Map<String, JsonObject> = emptyMap(),
        val required: List<String> = emptyList(),
    )

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
        ),
        DeviceTool(
            name = "register_function",
            operation = "save_function",
            description = "Register one successful OmniFlow RunLog as a reusable Function.",
            properties = mapOf(
                "run_id" to schema("string", "Successful RunLog id returned by run_gui."),
            ),
            required = listOf("run_id"),
        ),
    )

    internal val publicToolNames: Set<String> = omniFlowTools.mapTo(linkedSetOf()) { it.name }

    fun create(
        context: Context,
        scope: CoroutineScope,
    ): Server {
        val modelClient = HttpAgentLlmClient(scope).asOmniFlowModelClient()
        return Server(
            serverInfo = Implementation(
                name = "openomnibot-android-device",
                version = "1.0.0",
                title = "OpenOmniBot Android Device",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
            instructions = "Run Android GUI tasks, replay registered Functions, or register a successful run.",
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
                        ensureOmniFlowReady(context)
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
        ensureDefaultPluginEnabled(
            isEnabled = OmniFlowPluginRuntime::isEnabled,
            inspect = {
                host.list()
                    .firstOrNull { it.descriptor.id == OmniVlmLiteProvider.ID }
                    ?.let { DefaultPluginStatus(installed = it.installed, enabled = it.enabled) }
            },
            install = { host.install(OmniVlmLiteProvider.ID) },
            enable = { host.setEnabled(OmniVlmLiteProvider.ID, true) },
        )
    }

    internal data class DefaultPluginStatus(
        val installed: Boolean,
        val enabled: Boolean,
    )

    internal suspend fun ensureDefaultPluginEnabled(
        isEnabled: () -> Boolean,
        inspect: suspend () -> DefaultPluginStatus?,
        install: suspend () -> Unit,
        enable: suspend () -> Unit,
    ) {
        if (isEnabled()) return
        val status = inspect()
        when {
            status?.enabled == true -> Unit
            status?.installed == true -> enable()
            else -> install()
        }
        require(isEnabled()) { "omniflow_plugin_not_enabled" }
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
