package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.util.Log
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityModule
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.mcp.RemoteMcpCallResult
import cn.com.omnimind.bot.mcp.RemoteMcpClient
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import cn.com.omnimind.bot.mcp.RemoteMcpTransport
import com.agentclientprotocol.model.McpServer
import com.ai.assistance.operit.terminal.TerminalManager
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP resources supplied by one ACP session.
 *
 * This object owns every connection and one capability module. Definitions
 * and handlers therefore share the same ACP Session lifetime instead of using
 * the process-global remote MCP discovery cache.
 */
internal class XiaowanMcpSession private constructor(
    val capabilityModule: AgentCapabilityModule?,
    private val connections: List<XiaowanMcpServerConnection>,
) {
    suspend fun close() {
        connections.forEach { connection ->
            runCatching { connection.close() }
        }
    }

    companion object {
        suspend fun open(
            context: Context,
            scope: CoroutineScope,
            sessionId: String,
            cwd: String,
            servers: List<McpServer>,
        ): XiaowanMcpSession {
            if (servers.isEmpty()) return XiaowanMcpSession(null, emptyList())

            val ready = coroutineScope {
                servers.mapIndexed { index, server ->
                    async {
                        val connection = createXiaowanMcpConnection(
                            context = context,
                            scope = scope,
                            sessionId = sessionId,
                            cwd = cwd,
                            index = index,
                            server = server,
                        )
                        try {
                            connection.start()
                            connection to connection.listTools()
                        } catch (error: CancellationException) {
                            runCatching { connection.close() }
                            throw error
                        } catch (error: Throwable) {
                            runCatching { connection.close() }
                            // One unavailable optional server must not make the
                            // Agent session unusable. Other supplied servers still
                            // contribute tools, while the failure remains visible
                            // in diagnostics and can be retried in a new session.
                            Log.w(
                                TAG,
                                "MCP connect failed session=$sessionId server=${server.name}",
                                error,
                            )
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val opened = ready.map { (connection, _) -> connection }
            val discovered = ready.flatMap { (connection, tools) ->
                tools.map { tool -> XiaowanMcpDiscoveredTool(connection, tool) }
            }

            val bindings = buildXiaowanMcpToolBindings(discovered)
            val module = bindings.takeIf(List<*>::isNotEmpty)?.let {
                XiaowanMcpCapabilityModule(it)
            }
            return XiaowanMcpSession(module, opened)
        }

        private const val TAG = "XiaowanMcpSession"
    }
}

internal interface XiaowanMcpServerConnection {
    val connectionId: String
    val serverName: String

    suspend fun start()
    suspend fun listTools(): List<RemoteMcpToolDescriptor>
    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): RemoteMcpCallResult
    suspend fun close()
}

internal data class XiaowanMcpDiscoveredTool(
    val connection: XiaowanMcpServerConnection,
    val descriptor: RemoteMcpToolDescriptor,
)

internal data class XiaowanMcpToolBinding(
    val modelToolName: String,
    val connection: XiaowanMcpServerConnection,
    val descriptor: RemoteMcpToolDescriptor,
)

internal fun buildXiaowanMcpToolBindings(
    discovered: List<XiaowanMcpDiscoveredTool>,
): List<XiaowanMcpToolBinding> {
    val occupied = linkedSetOf<String>()
    return discovered.map { item ->
        val server = sanitizeMcpToolSegment(item.connection.serverName, maxLength = 16)
        val tool = sanitizeMcpToolSegment(item.descriptor.toolName, maxLength = 25)
        val hash = stableMcpNameHash(
            "${item.connection.connectionId}\u0000${item.descriptor.toolName}"
        )
        val base = "mcp__${server}__${tool}__$hash"
        var candidate = base
        var suffix = 2
        while (!occupied.add(candidate)) {
            candidate = "${base.take(61)}_$suffix"
            suffix += 1
        }
        XiaowanMcpToolBinding(candidate, item.connection, item.descriptor)
    }
}

internal class XiaowanMcpCapabilityModule(
    bindings: List<XiaowanMcpToolBinding>,
) : AgentCapabilityModule {
    private val handler = XiaowanMcpToolHandler(bindings.associateBy { it.modelToolName })

    override val handlers: List<ToolHandler> = listOf(handler)

    override val toolDefinitions: List<AgentCapabilityToolDefinition> = bindings.map { binding ->
        AgentCapabilityToolDefinition(
            name = binding.modelToolName,
            displayName = binding.descriptor.toolName,
            description = binding.descriptor.description,
            parameters = mapToJsonObject(binding.descriptor.inputSchema),
            toolType = "mcp",
            serverName = binding.connection.serverName,
        )
    }
}

private class XiaowanMcpToolHandler(
    private val bindings: Map<String, XiaowanMcpToolBinding>,
) : ToolHandler {
    override val toolNames: Set<String> = bindings.keys

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        val binding = bindings[toolName]
            ?: return ToolExecutionResult.Error(toolName, "Unknown MCP tool: $toolName")
        return try {
            val title = args["tool_title"]
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.trim()
                .orEmpty()
            callback.onToolCallProgress(
                toolName,
                title.ifBlank {
                    "Calling ${binding.connection.serverName}/${binding.descriptor.toolName}"
                },
            )
            val result = binding.connection.callTool(
                toolName = binding.descriptor.toolName,
                arguments = jsonObjectToMap(args).filterKeys { it != "tool_title" },
            )
            ToolExecutionResult.McpResult(
                toolName = toolName,
                serverName = binding.connection.serverName,
                summaryText = result.summaryText,
                previewJson = result.previewJson,
                rawResultJson = result.rawResultJson,
                success = result.success,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolExecutionResult.Error(
                toolName,
                error.message ?: "MCP tool call failed",
            )
        }
    }

    // Connections belong to XiaowanMcpSession, not to the per-turn router.
    override suspend fun dispose() = Unit
}

private fun createXiaowanMcpConnection(
    context: Context,
    scope: CoroutineScope,
    sessionId: String,
    cwd: String,
    index: Int,
    server: McpServer,
): XiaowanMcpServerConnection {
    val connectionId = "xiaowan-${stableMcpNameHash(sessionId)}-$index-${UUID.randomUUID()}"
    return when (server) {
        is McpServer.Http -> XiaowanHttpMcpConnection(
            RemoteMcpServerConfig(
                id = connectionId,
                name = server.name,
                endpointUrl = server.url,
                headers = server.headers.associate { it.name to it.value },
                transport = RemoteMcpTransport.HTTP,
            )
        )
        is McpServer.Sse -> XiaowanHttpMcpConnection(
            RemoteMcpServerConfig(
                id = connectionId,
                name = server.name,
                endpointUrl = server.url,
                headers = server.headers.associate { it.name to it.value },
                transport = RemoteMcpTransport.SSE,
            )
        )
        is McpServer.Stdio -> XiaowanStdioMcpConnection(
            context = context,
            scope = scope,
            connectionId = connectionId,
            cwd = cwd,
            server = server,
        )
    }
}

private class XiaowanHttpMcpConnection(
    private val config: RemoteMcpServerConfig,
) : XiaowanMcpServerConnection {
    override val connectionId: String = config.id
    override val serverName: String = config.name

    override suspend fun start() = Unit

    override suspend fun listTools(): List<RemoteMcpToolDescriptor> =
        RemoteMcpClient.listTools(config)

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): RemoteMcpCallResult = RemoteMcpClient.callTool(config, toolName, arguments)

    override suspend fun close() {
        RemoteMcpClient.close(config)
    }
}

private class XiaowanStdioMcpConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    override val connectionId: String,
    private val cwd: String,
    private val server: McpServer.Stdio,
) : XiaowanMcpServerConnection {
    override val serverName: String = server.name

    private val requestMutex = Mutex()
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var stdoutJob: Job? = null
    private var stderrJob: Job? = null
    private var stdoutLines: Channel<String>? = null

    override suspend fun start() {
        if (process != null) return
        val command = buildString {
            append("cd -- ")
            append(shellQuoteMcp(cwd))
            append(" && exec ")
            append(shellQuoteMcp(server.command))
            server.args.forEach { argument ->
                append(' ')
                append(shellQuoteMcp(argument))
            }
        }
        val started = TerminalManager.getInstance(context).startLongLivedProcess(
            command = command,
            executorKey = connectionId,
            extraEnvironment = server.env
                .filter { it.name.isNotBlank() }
                .associate { it.name to it.value },
            redirectErrorStream = false,
        )
        process = started
        reader = started.inputStream.bufferedReader(StandardCharsets.UTF_8)
        writer = started.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        val lines = Channel<String>(Channel.UNLIMITED)
        stdoutLines = lines
        stdoutJob = scope.launch(Dispatchers.IO) {
            try {
                reader?.useLines { output ->
                    output.forEach { line -> lines.send(line) }
                }
            } finally {
                lines.close()
            }
        }
        stderrJob = scope.launch(Dispatchers.IO) {
            started.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        Log.d(TAG, "stdio server=$serverName: ${line.take(500)}")
                    }
                }
            }
        }

        request(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", RemoteMcpClient.DEFAULT_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {})
                })
                put("clientInfo", buildJsonObject {
                    put("name", "omnibot-android")
                    put("version", "1.0")
                })
            },
        )
        notify("notifications/initialized", buildJsonObject {})
    }

    override suspend fun listTools(): List<RemoteMcpToolDescriptor> {
        val result = request("tools/list", buildJsonObject {})
        val tools = (result as? JsonObject)?.get("tools") as? JsonArray ?: JsonArray(emptyList())
        return tools.mapNotNull { element ->
            val raw = element as? JsonObject ?: return@mapNotNull null
            val name = raw["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            RemoteMcpToolDescriptor(
                serverId = connectionId,
                serverName = serverName,
                toolName = name,
                description = raw["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = jsonObjectToMap(
                    raw["inputSchema"] as? JsonObject ?: buildJsonObject {}
                ),
            )
        }
    }

    override suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any?>,
    ): RemoteMcpCallResult {
        val result = request(
            "tools/call",
            buildJsonObject {
                put("name", toolName)
                put("arguments", mapToJsonObject(arguments))
            },
        )
        return remoteMcpCallResult(result)
    }

    override suspend fun close() {
        requestMutex.withLock {
            stdoutJob?.cancel()
            stdoutJob = null
            stderrJob?.cancel()
            stderrJob = null
            stdoutLines?.close()
            stdoutLines = null
            val active = process
            process = null
            runCatching { writer?.close() }
            runCatching { reader?.close() }
            writer = null
            reader = null
            if (active != null) {
                runCatching { active.destroy() }
                val exited = runCatching { active.waitFor(500, TimeUnit.MILLISECONDS) }
                    .getOrDefault(false)
                if (!exited) runCatching { active.destroyForcibly() }
            }
        }
    }

    private suspend fun request(method: String, params: JsonObject): JsonElement {
        val requestId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("method", method)
            put("params", params)
        }
        return requestMutex.withLock {
            withTimeout<JsonElement>(RPC_TIMEOUT_MS) {
                val activeWriter = writer ?: error("MCP stdio writer is closed")
                val activeLines = stdoutLines ?: error("MCP stdio reader is closed")
                withContext(Dispatchers.IO) {
                    activeWriter.write(payload.toString())
                    activeWriter.newLine()
                    activeWriter.flush()
                }
                var matchedResult: JsonElement? = null
                while (matchedResult == null) {
                    val line = activeLines.receiveCatching().getOrNull()
                        ?: error("MCP stdio server exited before responding to $method")
                    val response = runCatching {
                        MCP_JSON.parseToJsonElement(line) as? JsonObject
                    }.getOrNull() ?: continue
                    val responseId = response["id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                    if (responseId != requestId) continue
                    val error = response["error"] as? JsonObject
                    if (error != null) {
                        val message = error["message"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: "Unknown MCP stdio error"
                        throw IllegalStateException(message)
                    }
                    matchedResult = response["result"] ?: JsonNull
                }
                checkNotNull(matchedResult)
            }
        }
    }

    private suspend fun notify(method: String, params: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        requestMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeWriter = writer ?: error("MCP stdio writer is closed")
                activeWriter.write(payload.toString())
                activeWriter.newLine()
                activeWriter.flush()
            }
        }
    }

    companion object {
        private const val TAG = "XiaowanStdioMcp"
        private const val RPC_TIMEOUT_MS = 40_000L
        private val MCP_JSON = Json { ignoreUnknownKeys = true }
    }
}

internal fun remoteMcpCallResult(result: JsonElement): RemoteMcpCallResult {
    val raw = result.toString()
    val resultObject = result as? JsonObject
    val content = resultObject?.get("content") as? JsonArray
    val summary = content
        ?.mapNotNull { item ->
            val block = item as? JsonObject ?: return@mapNotNull null
            if (block["type"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
            block["text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }
        ?.joinToString("\n")
        ?.takeIf(String::isNotBlank)
        ?: raw
    return RemoteMcpCallResult(
        summaryText = summary,
        previewJson = raw,
        rawResultJson = raw,
        success = (resultObject?.get("isError") as? JsonPrimitive)?.contentOrNull != "true",
    )
}

private fun sanitizeMcpToolSegment(value: String, maxLength: Int): String {
    return value
        .trim()
        .map { char ->
            if (char.isAsciiLetterOrDigit() || char == '_' || char == '-') char else '_'
        }
        .joinToString("")
        .trim('_')
        .ifBlank { "unnamed" }
        .take(maxLength)
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun stableMcpNameHash(value: String): String =
    value.hashCode().toUInt().toString(16).padStart(8, '0')

private fun shellQuoteMcp(value: String): String =
    "'${value.replace("'", "'\"'\"'")}'"

private fun mapToJsonObject(value: Map<String, Any?>): JsonObject = JsonObject(
    value.mapValues { (_, child) -> anyToJson(child) }
)

private fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(
        value.entries.associate { (key, child) -> key.toString() to anyToJson(child) }
    )
    is Iterable<*> -> JsonArray(value.map(::anyToJson))
    is Array<*> -> JsonArray(value.map(::anyToJson))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

private fun jsonObjectToMap(value: JsonObject): Map<String, Any?> =
    value.mapValues { (_, child) -> jsonToAny(child) }

private fun jsonToAny(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> jsonObjectToMap(value)
    is JsonArray -> value.map(::jsonToAny)
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.contentOrNull == "true" -> true
        value.contentOrNull == "false" -> false
        value.contentOrNull?.toLongOrNull() != null -> value.content.toLong()
        value.contentOrNull?.toDoubleOrNull() != null -> value.content.toDouble()
        else -> value.contentOrNull
    }
}
