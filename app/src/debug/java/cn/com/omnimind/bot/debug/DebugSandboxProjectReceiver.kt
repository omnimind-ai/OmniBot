package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentStreamRequestException
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.OmniAgentExecutor
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginCommand
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginPool
import cn.com.omnimind.bot.plugin.sandbox.SandboxProjectManifest
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class DebugSandboxProjectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            val payload = runCatching {
                when (intent?.getStringExtra("operation")?.trim()) {
                    "publish" -> publish(appContext, intent)
                    "invoke" -> invoke(appContext, intent)
                    "agent" -> runAgent(appContext, intent)
                    else -> error("operation must be publish, invoke, or agent")
                }
            }.getOrElse { error ->
                mapOf(
                    "success" to false,
                    "errorType" to error.javaClass.name,
                    "errorMessage" to (error.message ?: error.javaClass.simpleName),
                )
            }
            File(appContext.filesDir, RESULT_FILE).writeText(gson.toJson(payload))
            pendingResult.finish()
        }
    }

    private suspend fun publish(context: Context, intent: Intent): Map<String, Any?> {
        val workspace = AgentWorkspaceManager(context).skillsRoot().parentFile?.parentFile
            ?: error("workspace is unavailable")
        val source = File(workspace, intent.requiredExtra("sourcePath")).canonicalFile
        require(source.toPath().startsWith(workspace.canonicalFile.toPath())) {
            "sourcePath escapes the workspace"
        }
        val manifest = json.decodeFromString<SandboxProjectManifest>(
            intent.requiredDecodedExtra("manifestBase64"),
        )
        val pool = SandboxPluginPool(context)
        val checked = pool.execute(SandboxPluginCommand.CheckProject(source, manifest))
            .requireSuccess()
        val published = pool.execute(SandboxPluginCommand.PublishProject(source, manifest))
            .requireSuccess()
        val pluginId = published.payload.getValue("pluginId") as String
        val host = OmniPluginHost.get(context)
        val current = host.list().firstOrNull { it.descriptor.id == pluginId }
        val state = if (current?.installed == true) host.update(pluginId) else host.install(pluginId)
        if (!state.enabled) host.setEnabled(pluginId, true)
        return mapOf(
            "success" to true,
            "operation" to "publish",
            "pluginId" to pluginId,
            "checked" to checked.payload,
            "published" to published.payload,
            "installed" to true,
            "enabled" to true,
        )
    }

    private suspend fun invoke(context: Context, intent: Intent): Map<String, Any?> {
        val pluginId = intent.requiredExtra("pluginId")
        val toolName = intent.requiredExtra("toolName")
        val arguments = json.decodeFromString<JsonObject>(
            intent.requiredDecodedExtra("argumentsBase64"),
        )
        val result = SandboxPluginPool(context).executeTool(pluginId, toolName, arguments)
        return mapOf(
            "success" to true,
            "operation" to "invoke",
            "pluginId" to pluginId,
            "toolName" to toolName,
            "result" to result,
        )
    }

    private suspend fun runAgent(context: Context, intent: Intent): Map<String, Any?> {
        val message = if (intent.hasExtra("messageBase64")) {
            intent.requiredDecodedExtra("messageBase64")
        } else {
            intent.requiredExtra("message")
        }
        val timeoutMillis = intent.getLongExtra("timeoutMillis", AGENT_TIMEOUT_MILLIS)
            .coerceIn(10_000L, AGENT_TIMEOUT_MILLIS)
        val events = mutableListOf<Map<String, Any?>>()
        val callback = object : AgentCallback {
            override suspend fun onThinkingStart() {
                events += mapOf("type" to "thinking_start")
            }

            override suspend fun onThinkingUpdate(thinking: String) = Unit

            override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) {
                events += mapOf(
                    "type" to "tool_start",
                    "toolName" to toolName,
                    "arguments" to arguments.toString(),
                )
            }

            override suspend fun onToolCallProgress(
                toolName: String,
                progress: String,
                extras: Map<String, Any?>,
            ) = Unit

            override suspend fun onToolCallComplete(
                toolName: String,
                result: ToolExecutionResult,
            ) {
                events += mapOf(
                    "type" to "tool_complete",
                    "toolName" to toolName,
                    "result" to result.toDebugPayload(),
                )
            }

            override suspend fun onChatMessage(message: String) {
                events += mapOf("type" to "chat", "message" to message)
            }

            override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) {
                events += mapOf(
                    "type" to "clarify",
                    "question" to question,
                    "missingFields" to missingFields,
                )
            }

            override suspend fun onComplete(result: AgentResult) = Unit

            override suspend fun onError(error: String) {
                events += mapOf("type" to "error", "message" to error)
            }

            override suspend fun onPermissionRequired(missing: List<String>) {
                events += mapOf("type" to "permission_required", "missing" to missing)
            }
        }
        val scheduleBridge = object : AgentScheduleToolBridge {
            override suspend fun createTask(arguments: Map<String, Any?>): Map<String, Any?> =
                error("Scheduled tasks are unavailable in this debug smoke")

            override suspend fun listTasks(): List<Map<String, Any?>> = emptyList()

            override suspend fun updateTask(arguments: Map<String, Any?>): Map<String, Any?> =
                error("Scheduled tasks are unavailable in this debug smoke")

            override suspend fun deleteTask(arguments: Map<String, Any?>): Map<String, Any?> =
                error("Scheduled tasks are unavailable in this debug smoke")
        }
        val startedAt = System.currentTimeMillis()
        val result = withTimeout(timeoutMillis) {
            OmniAgentExecutor(context, scope, scheduleBridge).processUserMessage(
                userMessage = message,
                conversationHistory = emptyList(),
                runtimeContextRepository = AgentRuntimeContextRepository(context),
                attachments = emptyList(),
                conversationId = null,
                conversationMode = AgentConversationModePolicy.NORMAL_MODE,
                modelOverride = null,
                reasoningEffort = "none",
                terminalEnvironment = emptyMap(),
                callback = callback,
            )
        }
        return mapOf(
            "success" to (result is AgentResult.Success),
            "operation" to "agent",
            "message" to message,
            "elapsedMs" to (System.currentTimeMillis() - startedAt),
            "events" to events,
            "result" to result.toDebugPayload(),
        )
    }

    private fun ToolExecutionResult.toDebugPayload(): Map<String, Any?> = when (this) {
        is ToolExecutionResult.ChatMessage -> mapOf("type" to "chat", "message" to message)
        is ToolExecutionResult.Clarify -> mapOf(
            "type" to "clarify",
            "question" to question,
            "missingFields" to missingFields,
        )
        is ToolExecutionResult.Error -> mapOf(
            "type" to "error",
            "toolName" to toolName,
            "message" to message,
        )
        is ToolExecutionResult.PermissionRequired -> mapOf(
            "type" to "permission_required",
            "missing" to missing,
        )
        is ToolExecutionResult.ContextResult -> mapOf(
            "type" to "context",
            "toolName" to toolName,
            "success" to success,
            "summaryText" to summaryText,
            "previewJson" to previewJson,
        )
        is ToolExecutionResult.ScheduleResult -> mapOf(
            "type" to "schedule",
            "toolName" to toolName,
            "success" to success,
            "summaryText" to summaryText,
            "previewJson" to previewJson,
        )
        is ToolExecutionResult.McpResult -> mapOf(
            "type" to "mcp",
            "toolName" to toolName,
            "success" to success,
            "summaryText" to summaryText,
            "previewJson" to previewJson,
        )
        is ToolExecutionResult.MemoryResult -> mapOf(
            "type" to "memory",
            "toolName" to toolName,
            "success" to success,
            "summaryText" to summaryText,
            "previewJson" to previewJson,
        )
        is ToolExecutionResult.TerminalResult -> mapOf(
            "type" to "terminal",
            "toolName" to toolName,
            "success" to success,
            "summaryText" to summaryText,
            "previewJson" to previewJson,
        )
        is ToolExecutionResult.Interrupted -> mapOf(
            "type" to "interrupted",
            "toolName" to toolName,
            "summaryText" to summaryText,
        )
    }

    private fun AgentResult.toDebugPayload(): Map<String, Any?> = when (this) {
        is AgentResult.Success -> mapOf(
            "type" to "success",
            "content" to response.content,
            "finishReason" to response.finishReason,
            "promptTokens" to latestPromptTokens,
            "completionTokens" to completionTokens,
            "cachedTokens" to cachedTokens,
            "totalTokens" to totalTokens,
            "executedTools" to executedTools.map { it.toDebugPayload() },
        )
        is AgentResult.Error -> mapOf(
            "type" to "error",
            "message" to message,
            "exception" to exception?.javaClass?.name,
            "causes" to exception.debugCauseChain(),
        )
    }

    private fun Throwable?.debugCauseChain(): List<Map<String, Any?>> =
        generateSequence(this) { it.cause }
            .take(8)
            .map { error ->
                buildMap {
                    put("type", error.javaClass.name)
                    put("message", error.message)
                    if (error is AgentStreamRequestException) {
                        put("statusCode", error.statusCode)
                        put("reason", error.reason)
                        put("responseBody", error.responseBody)
                    }
                }
            }
            .toList()

    private fun Intent.requiredExtra(name: String): String =
        getStringExtra(name)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$name is required")

    private fun Intent.requiredDecodedExtra(name: String): String =
        String(Base64.decode(requiredExtra(name), Base64.DEFAULT), Charsets.UTF_8)

    private companion object {
        const val RESULT_FILE = "debug-sandbox-project-result.json"
        const val AGENT_TIMEOUT_MILLIS = 120_000L
        val json = Json { ignoreUnknownKeys = true }
        val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
