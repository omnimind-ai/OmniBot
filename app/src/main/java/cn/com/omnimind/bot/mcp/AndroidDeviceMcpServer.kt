package cn.com.omnimind.bot.mcp

import android.content.Context
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRegistration
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.omniflow.asOmniFlowModelClient
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.official.OmniVlmLiteProvider
import cn.com.omnimind.bot.plugin.sandbox.SandboxConnectorContract
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginCommand
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginPool
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginShortcutManager
import cn.com.omnimind.bot.plugin.sandbox.SandboxProjectManifest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
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
            name = "plugin_list",
            operation = "plugin_list",
            description = "List installed and enabled OpenOmniBot plugins.",
        ),
        DeviceTool(
            name = "plugin_project_contract",
            operation = "plugin_project_contract",
            description = "Read the official connector contract for creating an OpenOmniBot plugin project.",
        ),
        DeviceTool(
            name = "plugin_project_check",
            operation = "plugin_project_check",
            description = "Validate a plugin project in the shared workspace before publishing it.",
            properties = pluginProjectProperties(),
            required = listOf("path", "manifest"),
        ),
        DeviceTool(
            name = "plugin_project_publish",
            operation = "plugin_project_publish",
            description = "Validate, publish, install, and enable a plugin project from the shared workspace.",
            properties = pluginProjectProperties(),
            required = listOf("path", "manifest"),
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
            instructions = "Use the official OmniBot MCP server to access Android GUI, Functions, files, app context, and plugin capabilities.",
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
        "plugin_list" -> {
            val states = OmniPluginHost.get(context).list()
            mapOf(
                "count" to states.size,
                "plugins" to states.map { state ->
                    mapOf(
                        "id" to state.descriptor.id,
                        "name" to state.descriptor.name,
                        "installed" to state.installed,
                        "enabled" to state.enabled,
                        "version" to state.descriptor.version,
                    )
                },
            )
        }
        "plugin_project_contract" -> SandboxConnectorContract.payload()
        "plugin_project_check" -> {
            val project = resolvePluginProject(context, arguments)
            SandboxPluginPool(context).execute(
                SandboxPluginCommand.CheckProject(project.directory, project.manifest),
            ).requireSuccess().payload
        }
        "plugin_project_publish" -> {
            val project = resolvePluginProject(context, arguments)
            val published = SandboxPluginPool(context).execute(
                SandboxPluginCommand.PublishProject(project.directory, project.manifest),
            ).requireSuccess()
            val pluginId = published.payload.getValue("pluginId") as String
            val host = OmniPluginHost.get(context)
            val current = host.list().firstOrNull { it.descriptor.id == pluginId }
            val state = if (current?.installed == true) {
                host.update(pluginId)
            } else {
                host.install(pluginId)
            }
            if (!state.enabled) host.setEnabled(pluginId, true)
            val shortcut = SandboxPluginShortcutManager(context).pinOrUpdate(pluginId)
            buildMap {
                putAll(published.payload)
                put("name", project.manifest.name)
                put("dashboardRoute", "/home/plugin_dashboard?pluginId=$pluginId")
                put("shortcut", shortcut.toMap())
            }
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

    private fun pluginProjectProperties(): Map<String, JsonObject> = mapOf(
        "path" to schema("string", "Project path under /workspace."),
        "manifest" to JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "description" to JsonPrimitive(
                    "Official SandboxProjectManifest JSON object."
                ),
            )
        ),
    )

    private data class ResolvedPluginProject(
        val directory: java.io.File,
        val manifest: SandboxProjectManifest,
    )

    private fun resolvePluginProject(
        context: Context,
        arguments: Map<String, Any?>,
    ): ResolvedPluginProject {
        val path = arguments["path"]?.toString()?.trim().orEmpty()
        require(path.isNotBlank()) { "plugin project path is required" }
        val manifestValue = arguments["manifest"]
            ?: throw IllegalArgumentException("plugin project manifest is required")
        val manifestJson = manifestValue.toJsonElement()
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromJsonElement<SandboxProjectManifest>(manifestJson)
        val workspaceManager = AgentWorkspaceManager(context)
        val workspace = workspaceManager.buildWorkspaceDescriptor(null, "mcp-plugin")
        val directory = workspaceManager.resolvePath(path, workspace)
        require(directory.isDirectory) { "plugin project directory does not exist: $path" }
        return ResolvedPluginProject(directory, manifest)
    }

    private fun Map<String, JsonElement>.toKotlinMap(): Map<String, Any?> = entries.associate { (key, value) ->
        key to value.toKotlinValue()
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
            key.toString() to value.toJsonElement()
        })
        is List<*> -> JsonArray(map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Byte -> JsonPrimitive(this)
        is Short -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
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
