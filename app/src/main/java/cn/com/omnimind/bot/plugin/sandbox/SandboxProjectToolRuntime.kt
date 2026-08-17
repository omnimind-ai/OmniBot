package cn.com.omnimind.bot.plugin.sandbox

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object SandboxConnectorContract {
    const val REFERENCE_RULE =
        "executor.connector must reference a connectors[].id; it is not a connector type"

    val supportedActions: Map<String, List<String>> = linkedMapOf(
        "sqlite" to listOf("insert", "query", "update", "delete"),
        "xiaowan" to listOf("invoke"),
        "http_json" to listOf("get"),
    )

    const val MINIMAL_XIAOWAN_EXAMPLE =
        "{\"connectors\":[{\"id\":\"assistant\",\"type\":\"xiaowan\",\"config\":{}}]," +
            "\"tools\":[{\"executor\":{\"connector\":\"assistant\",\"action\":\"invoke\"," +
            "\"config\":{\"instruction\":\"Complete the requested task.\"}}}]}"

    fun payload(): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 1,
        "referenceRule" to REFERENCE_RULE,
        "connectorTypes" to supportedActions.map { (type, actions) ->
            mapOf(
                "type" to type,
                "actions" to actions,
                "permission" to when (type) {
                    "sqlite" -> "database"
                    "http_json" -> "network"
                    else -> "xiaowan or ai"
                },
            )
        },
        "xiaowanDefaults" to mapOf(
            "reasoning_effort" to XiaowanChatCompletionRequestFactory.DEFAULT_REASONING_EFFORT,
            "max_tokens" to XiaowanChatCompletionRequestFactory.DEFAULT_MAX_TOKENS,
            "temperature" to XiaowanChatCompletionRequestFactory.DEFAULT_TEMPERATURE,
        ),
        "dashboardBridge" to mapOf(
            "method" to "window.omni.tools.call",
            "toolName" to "Use the unprefixed toolkit.json tools[].name",
            "arguments" to "Must satisfy that tool's parameters schema",
            "rule" to "Dashboard reads and writes must use the same declared tools as Xiaowan",
            "legacyDatabaseBridge" to
                "Rejected by project_check for new or republished source",
        ),
        "minimalXiaowanExample" to MINIMAL_XIAOWAN_EXAMPLE,
    )

    fun unknownReference(toolName: String, reference: String): String =
        "Project tool $toolName references unknown connector id '$reference'. " +
            "$REFERENCE_RULE. Supported connector types and actions: " +
            supportedActions.entries.joinToString { "${it.key}=${it.value}" } +
            ". Minimal example: $MINIMAL_XIAOWAN_EXAMPLE"

    fun unsupportedType(type: String): String =
        "Unsupported project connector type '$type'. Supported connector types and actions: " +
            supportedActions.entries.joinToString { "${it.key}=${it.value}" } +
            ". $REFERENCE_RULE"
}

internal object SandboxProjectToolPolicy {
    const val SQLITE_INSERT = "sqlite.insert"
    const val SQLITE_QUERY = "sqlite.query"
    const val SQLITE_UPDATE = "sqlite.update"
    const val SQLITE_DELETE = "sqlite.delete"
    const val AI_GENERATE = "ai.generate"
    const val XIAOWAN_INVOKE = "xiaowan.invoke"

    private const val PROJECT_ID_PREFIX = "local.project."
    private const val MAX_TOOLS = 32
    private const val MAX_CONNECTORS = 16
    private val toolName = Regex("^[a-z][a-z0-9_]{1,39}$")
    private val connectorId = Regex("^[a-z][a-z0-9_]{1,39}$")

    fun validate(
        pluginId: String,
        toolkit: SandboxProjectToolkit,
        permissions: List<String>,
        schemaSql: String?,
    ) {
        require(toolkit.schemaVersion == 1) {
            "Unsupported project toolkit schema: ${toolkit.schemaVersion}"
        }
        require(toolkit.tools.isNotEmpty()) { "Project toolkit must declare at least one tool" }
        require(toolkit.tools.size <= MAX_TOOLS) {
            "Project toolkit declares ${toolkit.tools.size} tools; the limit is $MAX_TOOLS"
        }
        require(toolkit.connectors.size <= MAX_CONNECTORS) {
            "Project toolkit declares ${toolkit.connectors.size} connectors; the limit is $MAX_CONNECTORS"
        }
        val duplicateConnector = toolkit.connectors.groupBy(SandboxProjectConnector::id)
            .entries.firstOrNull { it.value.size > 1 }
            ?.key
        require(duplicateConnector == null) {
            "Project toolkit declares duplicate connector: $duplicateConnector"
        }
        toolkit.connectors.forEach { connector ->
            require(connectorId.matches(connector.id)) {
                "Invalid project connector id: ${connector.id}"
            }
            require(SandboxProjectConnectorRegistry.supports(connector.type)) {
                SandboxConnectorContract.unsupportedType(connector.type)
            }
        }
        val duplicate = toolkit.tools.groupBy(SandboxProjectTool::name)
            .entries.firstOrNull { it.value.size > 1 }
            ?.key
        require(duplicate == null) { "Project toolkit declares duplicate tool: $duplicate" }
        val schemaTables = schemaSql?.let(SandboxSqlPolicy::createdTables).orEmpty()
        toolkit.tools.forEach { tool ->
            require(toolName.matches(tool.name)) { "Invalid project tool name: ${tool.name}" }
            require(tool.displayName.trim().length in 1..80) {
                "Project tool ${tool.name} must have a 1-80 character displayName"
            }
            require(tool.description.trim().length in 1..500) {
                "Project tool ${tool.name} must have a 1-500 character description"
            }
            require(runtimeName(pluginId, tool).length <= 64) {
                "Project tool ${tool.name} produces a runtime name longer than 64 characters"
            }
            require(tool.parameters["type"]?.jsonPrimitive?.contentOrNull == "object") {
                "Project tool ${tool.name} parameters must be a JSON object schema"
            }
            SandboxProjectConnectorRegistry.validate(
                tool = tool,
                executor = resolveExecutor(toolkit, tool),
                permissions = permissions,
                schemaTables = schemaTables,
            )
        }
    }

    fun resolveExecutor(
        toolkit: SandboxProjectToolkit,
        tool: SandboxProjectTool,
    ): SandboxResolvedToolExecutor {
        val specification = tool.executor
        val connectorReference = specification.connector?.trim()?.takeIf(String::isNotEmpty)
        if (connectorReference != null) {
            require(specification.type == null) {
                "Project tool ${tool.name} cannot declare both connector and legacy type"
            }
            val connector = toolkit.connectors.firstOrNull { it.id == connectorReference }
                ?: throw IllegalArgumentException(
                    SandboxConnectorContract.unknownReference(tool.name, connectorReference),
                )
            val action = specification.action?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Project tool ${tool.name} must declare an action for connector $connectorReference",
                )
            return SandboxResolvedToolExecutor(
                connectorType = connector.type,
                action = action,
                config = JsonObject(connector.config + specification.config),
            )
        }
        require(specification.action == null) {
            "Project tool ${tool.name} declares action without connector"
        }
        return when (val legacyType = specification.type?.trim()) {
            SQLITE_INSERT -> SandboxResolvedToolExecutor("sqlite", "insert", specification.config)
            SQLITE_QUERY -> SandboxResolvedToolExecutor("sqlite", "query", specification.config)
            SQLITE_UPDATE -> SandboxResolvedToolExecutor("sqlite", "update", specification.config)
            SQLITE_DELETE -> SandboxResolvedToolExecutor("sqlite", "delete", specification.config)
            AI_GENERATE, XIAOWAN_INVOKE ->
                SandboxResolvedToolExecutor("xiaowan", "invoke", specification.config)
            else -> throw IllegalArgumentException(
                "Unsupported project tool executor: $legacyType",
            )
        }
    }

    fun validateArguments(tool: SandboxProjectTool, arguments: JsonObject) {
        val schema = tool.parameters
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { value -> value.jsonPrimitive.contentOrNull }
        required.forEach { field ->
            require(arguments[field] != null && arguments[field] !is JsonNull) {
                "Project tool ${tool.name} missing required argument: $field"
            }
        }
        val allowAdditional = schema["additionalProperties"]
            ?.jsonPrimitive
            ?.booleanOrNull != false
        arguments.filterKeys { it != "tool_title" }.forEach { (field, value) ->
            val property = properties[field] as? JsonObject
            require(property != null || allowAdditional) {
                "Project tool ${tool.name} does not declare argument: $field"
            }
            if (property != null) validateArgumentType(tool.name, field, value, property)
        }
    }

    private fun validateArgumentType(
        toolName: String,
        field: String,
        value: JsonElement,
        schema: JsonObject,
    ) {
        val expected = schema["type"]?.jsonPrimitive?.contentOrNull
        val matches = when (expected) {
            null -> true
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> true
        }
        require(matches) {
            "Project tool $toolName argument $field must be ${expected ?: "valid"}"
        }
        val allowedValues = (schema["enum"] as? JsonArray).orEmpty()
        require(allowedValues.isEmpty() || value in allowedValues) {
            "Project tool $toolName argument $field is not an allowed value"
        }
    }

    fun runtimeName(pluginId: String, tool: SandboxProjectTool): String {
        val slug = pluginId.removePrefix(PROJECT_ID_PREFIX).replace('-', '_')
        return "${slug}_${tool.name}"
    }
}

internal data class SandboxResolvedToolExecutor(
    val connectorType: String,
    val action: String,
    val config: JsonObject,
)

internal class SandboxProjectToolHandler(
    private val pool: SandboxPluginPool,
    private val pluginId: String,
    toolkit: SandboxProjectToolkit,
) : ToolHandler {
    private data class BoundTool(
        val tool: SandboxProjectTool,
        val executor: SandboxResolvedToolExecutor,
    )

    private val toolsByRuntimeName = toolkit.tools.associate { tool ->
        SandboxProjectToolPolicy.runtimeName(pluginId, tool) to BoundTool(
            tool = tool,
            executor = SandboxProjectToolPolicy.resolveExecutor(toolkit, tool),
        )
    }

    override val toolNames: Set<String> = toolsByRuntimeName.keys

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val bound = toolsByRuntimeName[toolCall.function.name]
            ?: return ToolExecutionResult.Error(
                toolCall.function.name,
                "Unknown project tool",
            )
        return try {
            toolHandle.throwIfStopRequested()
            SandboxProjectToolPolicy.validateArguments(bound.tool, args)
            val payload = SandboxProjectConnectorRegistry.execute(
                pool = pool,
                pluginId = pluginId,
                executor = bound.executor,
                args = args,
            )
            val encoded = payload.toJsonElement().toString()
            ToolExecutionResult.ContextResult(
                toolName = toolCall.function.name,
                summaryText = "${bound.tool.displayName}完成",
                previewJson = encoded,
                rawResultJson = encoded,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ToolExecutionResult.Error(
                toolCall.function.name,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

}

internal object SandboxProjectConnectorRegistry {
    private const val MAX_INSTRUCTION_CHARS = 16_000
    private const val TOOL_TITLE_FIELD = "tool_title"
    private val connectors: Map<String, SandboxProjectConnectorRuntime> = listOf(
        SqliteConnector,
        XiaowanConnector,
        HttpJsonConnector,
    ).associateBy(SandboxProjectConnectorRuntime::type)

    fun supports(type: String): Boolean = type in connectors

    fun validate(
        tool: SandboxProjectTool,
        executor: SandboxResolvedToolExecutor,
        permissions: List<String>,
        schemaTables: Set<String>,
    ) {
        val connector = connectors[executor.connectorType]
            ?: throw IllegalArgumentException(
                SandboxConnectorContract.unsupportedType(executor.connectorType),
            )
        connector.validate(tool, executor.action, executor.config, permissions, schemaTables)
    }

    suspend fun execute(
        pool: SandboxPluginPool,
        pluginId: String,
        executor: SandboxResolvedToolExecutor,
        args: JsonObject,
    ): Map<String, Any?> {
        val connector = connectors[executor.connectorType]
            ?: throw IllegalArgumentException(
                SandboxConnectorContract.unsupportedType(executor.connectorType),
            )
        return connector.execute(
            pool,
            pluginId,
            executor.action,
            executor.config,
            sanitizeArguments(args),
        )
    }

    internal fun sanitizeArguments(args: JsonObject): JsonObject = JsonObject(
        args.filterKeys { it != TOOL_TITLE_FIELD },
    )

    private interface SandboxProjectConnectorRuntime {
        val type: String

        fun validate(
            tool: SandboxProjectTool,
            action: String,
            config: JsonObject,
            permissions: List<String>,
            schemaTables: Set<String>,
        )

        suspend fun execute(
            pool: SandboxPluginPool,
            pluginId: String,
            action: String,
            config: JsonObject,
            args: JsonObject,
        ): Map<String, Any?>
    }

    private object SqliteConnector : SandboxProjectConnectorRuntime {
        override val type: String = "sqlite"
        private val actions = setOf("insert", "query", "update", "delete")

        override fun validate(
            tool: SandboxProjectTool,
            action: String,
            config: JsonObject,
            permissions: List<String>,
            schemaTables: Set<String>,
        ) {
            require(action in actions) { "Unsupported sqlite connector action: $action" }
            require(SandboxProjectPermission.DATABASE in permissions) {
                "Project tool ${tool.name} requires the database permission"
            }
            val table = config.requiredString("table")
            SandboxSqlPolicy.requireIdentifier(table, "tool table")
            require(table in schemaTables) {
                "Project tool ${tool.name} references table not created by schema.sql: $table"
            }
        }

        override suspend fun execute(
            pool: SandboxPluginPool,
            pluginId: String,
            action: String,
            config: JsonObject,
            args: JsonObject,
        ): Map<String, Any?> = when (action) {
            "insert" -> insert(pool, pluginId, config, args)
            "query" -> query(pool, pluginId, config, args)
            "update" -> update(pool, pluginId, config, args)
            "delete" -> delete(pool, pluginId, config, args)
            else -> error("Unsupported sqlite connector action: $action")
        }
    }

    private object XiaowanConnector : SandboxProjectConnectorRuntime {
        override val type: String = "xiaowan"
        private val reasoningEfforts = setOf("none", "low", "medium", "high")

        override fun validate(
            tool: SandboxProjectTool,
            action: String,
            config: JsonObject,
            permissions: List<String>,
            schemaTables: Set<String>,
        ) {
            require(action == "invoke") { "Unsupported Xiaowan connector action: $action" }
            require(
                SandboxProjectPermission.XIAOWAN in permissions ||
                    SandboxProjectPermission.AI in permissions,
            ) { "Project tool ${tool.name} requires the xiaowan permission" }
            val instruction = config.requiredString("instruction")
            require(instruction.length <= MAX_INSTRUCTION_CHARS) {
                "Project tool ${tool.name} instruction exceeds $MAX_INSTRUCTION_CHARS characters"
            }
            config["max_tokens"]?.let { value ->
                require(value.jsonPrimitive.intOrNull?.let { it in 32..4_096 } == true) {
                    "Project tool ${tool.name} max_tokens must be an integer between 32 and 4096"
                }
            }
            config["temperature"]?.let { value ->
                require(value.jsonPrimitive.doubleOrNull?.let { it in 0.0..2.0 } == true) {
                    "Project tool ${tool.name} temperature must be between 0 and 2"
                }
            }
            config["reasoning_effort"]?.let { value ->
                require(value.jsonPrimitive.contentOrNull?.trim()?.lowercase() in reasoningEfforts) {
                    "Project tool ${tool.name} reasoning_effort must be none, low, medium, or high"
                }
            }
        }

        override suspend fun execute(
            pool: SandboxPluginPool,
            pluginId: String,
            action: String,
            config: JsonObject,
            args: JsonObject,
        ): Map<String, Any?> {
            pool.requireAnyPermission(
                pluginId,
                setOf(SandboxProjectPermission.XIAOWAN, SandboxProjectPermission.AI),
            )
            val startedAtNanos = System.nanoTime()
            val turn = HttpAgentLlmClient(CoroutineScope(currentCoroutineContext())).streamTurn(
                buildXiaowanRequest(config, args),
            )
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000
            return mapOf(
                "text" to turn.message.contentText(),
                "model" to turn.resolvedModel,
                "elapsedMs" to elapsedMs,
                "usage" to turn.usage?.let { usage ->
                    mapOf(
                        "promptTokens" to usage.promptTokens,
                        "completionTokens" to usage.completionTokens,
                        "reasoningTokens" to (usage.completionTokensDetails as? JsonObject)
                            ?.get("reasoning_tokens")
                            ?.jsonPrimitive
                            ?.intOrNull,
                        "totalTokens" to usage.totalTokens,
                    )
                },
            )
        }
    }

    private object HttpJsonConnector : SandboxProjectConnectorRuntime {
        override val type: String = "http_json"
        private const val MAX_RESPONSE_BYTES = 256L * 1024L
        private const val DEFAULT_MAX_ITEMS = 100
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        override fun validate(
            tool: SandboxProjectTool,
            action: String,
            config: JsonObject,
            permissions: List<String>,
            schemaTables: Set<String>,
        ) {
            require(action == "get") { "Unsupported http_json connector action: $action" }
            require(SandboxProjectPermission.NETWORK in permissions) {
                "Project tool ${tool.name} requires the network permission"
            }
            val baseUrl = config.requiredString("base_url").toHttpUrlOrNull()
                ?: throw IllegalArgumentException(
                    "Project tool ${tool.name} has an invalid http_json base_url",
                )
            require(baseUrl.scheme == "https") {
                "Project tool ${tool.name} http_json base_url must use HTTPS"
            }
            require(baseUrl.username.isEmpty() && baseUrl.password.isEmpty()) {
                "Project tool ${tool.name} must not embed credentials in base_url"
            }
            require(baseUrl.query == null && baseUrl.fragment == null) {
                "Project tool ${tool.name} http_json base_url cannot contain query or fragment"
            }
            validateRelativePath(config.requiredString("path"), tool.name)
            val query = config["query"] as? JsonObject ?: JsonObject(emptyMap())
            query.forEach { (name, value) ->
                require(QUERY_NAME.matches(name)) {
                    "Project tool ${tool.name} has invalid query parameter: $name"
                }
                require(value is JsonPrimitive && value.isString) {
                    "Project tool ${tool.name} query mappings must be strings"
                }
            }
            config["response_path"]?.let { value ->
                val path = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
                require(path.length <= 256 && RESPONSE_PATH.matches(path)) {
                    "Project tool ${tool.name} has invalid response_path"
                }
            }
            config["max_items"]?.let { value ->
                require(value.jsonPrimitive.intOrNull?.let { it in 1..200 } == true) {
                    "Project tool ${tool.name} max_items must be between 1 and 200"
                }
            }
        }

        override suspend fun execute(
            pool: SandboxPluginPool,
            pluginId: String,
            action: String,
            config: JsonObject,
            args: JsonObject,
        ): Map<String, Any?> {
            pool.requirePermission(pluginId, SandboxProjectPermission.NETWORK)
            val url = buildUrl(config, args)
            requirePublicHost(url.host)
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "OpenOmniBot-Sandbox/1.0")
                .get()
                .build()
            val startedAtNanos = System.nanoTime()
            val response = client.newCall(request).execute()
            response.use { value ->
                require(value.isSuccessful) {
                    "Public data source returned HTTP ${value.code}"
                }
                val contentType = value.body?.contentType()?.toString().orEmpty().lowercase()
                require(contentType.isEmpty() || "json" in contentType) {
                    "Public data source did not return JSON"
                }
                val source = value.body?.source()
                    ?: throw IllegalArgumentException("Public data source returned an empty body")
                val bytes = source.readByteArray(MAX_RESPONSE_BYTES + 1)
                require(bytes.size <= MAX_RESPONSE_BYTES) {
                    "Public data response exceeds $MAX_RESPONSE_BYTES bytes"
                }
                val parsed = runCatching {
                    json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
                }.getOrElse {
                    throw IllegalArgumentException("Public data source returned malformed JSON", it)
                }
                val selected = selectResponse(
                    root = parsed,
                    responsePath = config["response_path"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        .orEmpty(),
                    maxItems = config["max_items"]?.jsonPrimitive?.intOrNull
                        ?: DEFAULT_MAX_ITEMS,
                )
                return mapOf(
                    "data" to selected,
                    "sourceUrl" to url.toString(),
                    "retrievedAt" to Instant.now().toString(),
                    "elapsedMs" to (System.nanoTime() - startedAtNanos) / 1_000_000,
                )
            }
        }

        internal fun buildUrl(config: JsonObject, args: JsonObject): HttpUrl {
            val base = config.requiredString("base_url").toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid http_json base_url")
            val path = config.requiredString("path")
            validateRelativePath(path, "runtime")
            val builder = base.newBuilder()
            path.trim('/').split('/').filter(String::isNotEmpty).forEach(builder::addPathSegment)
            (config["query"] as? JsonObject).orEmpty().forEach { (name, mapping) ->
                val rawValue = mapping.jsonPrimitive.content
                val value = if (rawValue.startsWith("$")) {
                    args[rawValue.removePrefix("$")]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                } else {
                    rawValue
                }
                if (value != null) builder.addQueryParameter(name, value)
            }
            return builder.build()
        }

        internal fun selectResponse(
            root: JsonElement,
            responsePath: String,
            maxItems: Int,
        ): JsonElement {
            val selected = responsePath.split('.')
                .filter(String::isNotEmpty)
                .fold(root) { current, segment ->
                    when (current) {
                        is JsonObject -> current[segment]
                        is JsonArray -> segment.toIntOrNull()?.let(current::getOrNull)
                        else -> null
                    } ?: throw IllegalArgumentException(
                        "Public data response_path was not found: $responsePath",
                    )
                }
            return if (selected is JsonArray && selected.size > maxItems) {
                JsonArray(selected.take(maxItems))
            } else {
                selected
            }
        }

        private fun validateRelativePath(path: String, toolName: String) {
            require(path.startsWith('/') && !path.contains("..") && !path.contains('?') &&
                !path.contains('#') && !path.contains('\\')) {
                "Project tool $toolName http_json path must be an absolute URL path"
            }
        }

        private fun requirePublicHost(host: String) {
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
                "http_json source must resolve only to public network addresses"
            }
        }

        private fun isPublicAddress(address: InetAddress): Boolean {
            if (
                address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) return false
            val bytes = address.address
            if (bytes.size == 4) {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                if (first == 100 && second in 64..127) return false
            }
            if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
            return true
        }

        private val QUERY_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        private val RESPONSE_PATH = Regex("^[A-Za-z0-9_.-]*$")
    }

    internal fun buildXiaowanRequest(
        config: JsonObject,
        args: JsonObject,
    ): ChatCompletionRequest {
        val instruction = config.requiredString("instruction")
        val system = config["system"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prompt = buildString {
            append(instruction)
            if (args.isNotEmpty()) {
                append("\n\nTool arguments:\n")
                append(args)
            }
        }
        val reasoningEffort = config["reasoning_effort"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.lowercase()
            ?: XiaowanChatCompletionRequestFactory.DEFAULT_REASONING_EFFORT
        return XiaowanChatCompletionRequestFactory.create(
            prompt = prompt,
            system = system,
            maxTokens = config["max_tokens"]?.jsonPrimitive?.intOrNull
                ?: XiaowanChatCompletionRequestFactory.DEFAULT_MAX_TOKENS,
            temperature = config["temperature"]?.jsonPrimitive?.doubleOrNull
                ?: XiaowanChatCompletionRequestFactory.DEFAULT_TEMPERATURE,
            reasoningEffort = reasoningEffort,
        )
    }

    private fun insert(
        pool: SandboxPluginPool,
        pluginId: String,
        config: JsonObject,
        args: JsonObject,
    ): Map<String, Any?> =
        pool.execute(
            SandboxPluginCommand.Insert(
                pluginId = pluginId,
                table = config.requiredString("table"),
                values = args.toNativeMap(),
            ),
        ).requireSuccess().payload

    private fun query(
        pool: SandboxPluginPool,
        pluginId: String,
        config: JsonObject,
        args: JsonObject,
    ): Map<String, Any?> {
        val values = args.toNativeMap().toMutableMap()
        val limit = (values.remove("_limit") as? Number)?.toInt() ?: 100
        val orderBy = values.remove("_order_by")?.toString()?.trim()?.takeIf(String::isNotEmpty)
        return pool.execute(
            SandboxPluginCommand.Query(
                pluginId = pluginId,
                table = config.requiredString("table"),
                where = values,
                orderBy = orderBy,
                limit = limit,
            ),
        ).requireSuccess().payload
    }

    private fun update(
        pool: SandboxPluginPool,
        pluginId: String,
        config: JsonObject,
        args: JsonObject,
    ): Map<String, Any?> {
        val values = args.toNativeMap().toMutableMap()
        val id = values.remove("id") ?: throw IllegalArgumentException("id is required")
        return pool.execute(
            SandboxPluginCommand.Update(
                pluginId = pluginId,
                table = config.requiredString("table"),
                id = id,
                values = values,
            ),
        ).requireSuccess().payload
    }

    private fun delete(
        pool: SandboxPluginPool,
        pluginId: String,
        config: JsonObject,
        args: JsonObject,
    ): Map<String, Any?> {
        val id = args.toNativeMap()["id"]
            ?: throw IllegalArgumentException("id is required")
        return pool.execute(
            SandboxPluginCommand.Delete(
                pluginId = pluginId,
                table = config.requiredString("table"),
                id = id,
            ),
        ).requireSuccess().payload
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Tool executor config requires $key")

    private fun JsonObject.toNativeMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key to value.toNativeValue() }

    private fun JsonElement.toNativeValue(): Any? = when (this) {
        JsonNull -> null
        is JsonObject -> toNativeMap()
        is JsonArray -> map { element -> element.toNativeValue() }
        is JsonPrimitive -> when {
            isString -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }

}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (key, value) ->
        key.toString() to value.toJsonElement()
    })
    is Iterable<*> -> JsonArray(map { value -> value.toJsonElement() })
    is Array<*> -> JsonArray(map { value -> value.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

internal fun SandboxProjectTool.definition(pluginId: String): OmniPluginToolDefinition =
    OmniPluginToolDefinition(
        name = SandboxProjectToolPolicy.runtimeName(pluginId, this),
        displayName = displayName,
        description = description,
        parameters = parameters,
    )
