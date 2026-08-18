package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.shizuku.PrivilegedActionPolicy
import cn.com.omnimind.baselib.shizuku.ShizukuBackend
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.mcp.RemoteMcpDiscoveredServer
import cn.com.omnimind.bot.mcp.RemoteMcpToolDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class AgentToolRegistry(
    private val context: Context,
    discoveredServers: List<RemoteMcpDiscoveredServer>,
    conversationMode: String = AgentConversationModePolicy.NORMAL_MODE,
    terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine,
    pluginToolDefinitions: List<OmniPluginToolDefinition> = emptyList(),
    userMessage: String? = null,
    toolRoutingMode: AgentToolRoutingMode = AgentToolRoutingMode.DEFAULT,
    // Keep the visual-operation entry visible so the Agent can explain how
    // to enable it. VlmToolHandler gates execution until OmniFlow is enabled.
    includeVlmTool: Boolean = true,
) : AgentToolCatalog {
    data class RuntimeToolDescriptor(
        val name: String,
        val displayName: String,
        val toolType: String,
        val serverName: String? = null,
        val remoteTool: RemoteMcpToolDescriptor? = null
    )

    private val tag = "AgentToolRegistry"
    private val toolSchemas = linkedMapOf<String, JsonObject>()
    private val runtimeDescriptors = linkedMapOf<String, RuntimeToolDescriptor>()
    override val toolsForModel: List<ChatCompletionTool>

    init {
        val locale = AppLocaleManager.resolvePromptLocale(context)
        val shizukuStatus = ShizukuCapabilityManager.get(context).getStatus()
        val runtimeDefinitions = mutableListOf<JsonObject>()
        runtimeDefinitions.addAll(
            AgentToolDefinitions.staticTools(
                locale = locale,
                terminalDistribution = terminalDistribution,
                includeVlmTool = includeVlmTool,
            )
        )
        if (shizukuStatus.isGranted()) {
            val privilegedVisibleActions = shizukuStatus.availableActions.ifEmpty {
                PrivilegedActionPolicy.visibleAgentActions(
                    if (shizukuStatus.backend == ShizukuBackend.ROOT) {
                        ShizukuBackend.ROOT
                    } else {
                        ShizukuBackend.ADB
                    }
                )
            }
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedActionTool(
                    visibleActions = privilegedVisibleActions,
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStartTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionExecTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionReadTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
            runtimeDefinitions.add(
                AgentToolDefinitions.androidPrivilegedSessionStopTool(
                    backend = shizukuStatus.backend,
                    locale = locale
                )
            )
        }
        runtimeDefinitions.addAll(AgentToolDefinitions.memoryTools(locale))
        runtimeDefinitions.addAll(AgentToolDefinitions.subagentTools(locale))
        if (pluginToolDefinitions.isNotEmpty()) {
            val occupiedNames = runtimeDefinitions.mapNotNullTo(linkedSetOf()) { definition ->
                (definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
            pluginToolDefinitions.forEach { pluginTool ->
                require(pluginTool.name !in occupiedNames) {
                    "Plugin tool conflicts with an existing tool: ${pluginTool.name}"
                }
                occupiedNames += pluginTool.name
                runtimeDefinitions += AgentToolDefinitions.decorateToolDefinition(
                    buildJsonObject {
                        put("type", JsonPrimitive("function"))
                        put("function", buildJsonObject {
                            put("name", JsonPrimitive(pluginTool.name))
                            put("displayName", JsonPrimitive(pluginTool.displayName))
                            put("toolType", JsonPrimitive("plugin"))
                            pluginTool.ownerPluginId?.let {
                                put("serverName", JsonPrimitive(it))
                            }
                            put("description", JsonPrimitive(pluginTool.description))
                            put("parameters", pluginTool.parameters)
                        })
                    },
                    locale,
                    terminalDistribution
                )
            }
        }
        discoveredServers
            .flatMap { it.tools }
            .sortedBy { it.encodedToolName.lowercase() }
            .forEach { tool ->
            runtimeDefinitions.add(toDynamicMcpToolDefinition(tool, locale))
        }

        val conversationDefinitions = AgentConversationModePolicy
            .filterToolDefinitionsForConversationMode(runtimeDefinitions, conversationMode)
            .sortedBy { definition ->
                ((definition["function"] as? JsonObject)
                    ?.get("name") as? JsonPrimitive)
                    ?.contentOrNull
                    ?.lowercase()
                    .orEmpty()
            }
        val visibleToolNames = userMessage?.let { message ->
            AgentToolVisibilitySelector.select(
                userMessage = message,
                routingMode = toolRoutingMode,
                candidates = conversationDefinitions.mapNotNull { definition ->
                    val function = definition["function"] as? JsonObject
                        ?: return@mapNotNull null
                    val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (name.isBlank()) return@mapNotNull null
                    val toolType = function["toolType"]?.jsonPrimitive?.contentOrNull?.trim()
                        .orEmpty()
                    AgentToolVisibilitySelector.ToolCandidate(
                        name = name,
                        displayName = function["displayName"]?.jsonPrimitive?.contentOrNull
                            .orEmpty(),
                        description = function["description"]?.jsonPrimitive?.contentOrNull
                            .orEmpty(),
                        owner = function["serverName"]?.jsonPrimitive?.contentOrNull,
                        dynamic = toolType == "plugin" || toolType == "mcp",
                    )
                },
            )
        }
        val filteredDefinitions = if (visibleToolNames == null) {
            conversationDefinitions
        } else {
            conversationDefinitions.filter { definition ->
                val toolName = (definition["function"] as? JsonObject)
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                toolName in visibleToolNames
            }
        }

        toolsForModel = filteredDefinitions.mapNotNull { definition ->
            val function = definition["function"] as? JsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val parameters = canonicalizeJson(
                (function["parameters"] as? JsonObject) ?: JsonObject(emptyMap())
            ) as JsonObject
            val displayName = function["displayName"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: name
            val toolType = function["toolType"]?.jsonPrimitive?.contentOrNull?.trim()
                .takeUnless { it.isNullOrBlank() } ?: "builtin"
            val serverName = function["serverName"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }

            toolSchemas[name] = parameters
            runtimeDescriptors[name] = RuntimeToolDescriptor(
                name = name,
                displayName = displayName,
                toolType = toolType,
                serverName = serverName,
                remoteTool = findRemoteTool(name, discoveredServers)
            )
            ChatCompletionTool(
                function = ChatCompletionFunction(
                    name = name,
                    description = description,
                    parameters = parameters
                )
            )
        }

        // Debug dump: full registered tool list to verify which ones the LLM actually receives.
        OmniLog.i(
            tag,
            "registered_tools count=${toolsForModel.size} " +
                "conversationMode=$conversationMode " +
                "toolRoutingMode=$toolRoutingMode " +
                "subagent_present=${"subagent_dispatch" in runtimeDescriptors.keys} " +
                "memory_load_present=${"memory_load" in runtimeDescriptors.keys} " +
                "names=[${runtimeDescriptors.keys.joinToString(",")}]"
        )
    }

    override fun runtimeDescriptor(toolName: String): RuntimeToolDescriptor {
        return runtimeDescriptors[toolName] ?: RuntimeToolDescriptor(
            name = toolName,
            displayName = toolName,
            toolType = "builtin"
        )
    }

    private fun canonicalizeJson(value: JsonElement): JsonElement {
        return when (value) {
            is JsonObject -> JsonObject(
                value.toSortedMap().mapValues { (_, child) -> canonicalizeJson(child) }
            )
            is JsonArray -> JsonArray(value.map(::canonicalizeJson))
            else -> value
        }
    }

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        val schema = toolSchemas[toolName] ?: return
        validateWithSchema(toolName, schema, arguments)
    }

    private fun validateWithSchema(
        toolName: String,
        schema: JsonObject,
        arguments: JsonObject
    ) {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (type.isNotBlank() && type != "object") {
            throw IllegalArgumentException("Tool $toolName schema type must be object")
        }
        val properties = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val requiredFields = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        requiredFields.forEach { field ->
            if (arguments[field] == null || arguments[field] is JsonNull) {
                throw IllegalArgumentException("Tool $toolName missing required argument: $field")
            }
        }
        arguments.entries.forEach { (field, value) ->
            val propertySchema = properties[field] as? JsonObject ?: return@forEach
            validateFieldType(toolName, field, value, propertySchema)
        }
    }

    private fun validateFieldType(
        toolName: String,
        field: String,
        value: JsonElement,
        propertySchema: JsonObject
    ) {
        val expectedType = propertySchema["type"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!expectedType.isNullOrBlank() && !matchesType(expectedType, value)) {
            throw IllegalArgumentException(
                "Tool $toolName argument $field expected $expectedType but got ${describeType(value)}"
            )
        }
        val enumValues = (propertySchema["enum"] as? JsonArray).orEmpty()
        if (enumValues.isNotEmpty()) {
            val raw = (value as? JsonPrimitive)?.contentOrNull
            if (raw == null || enumValues.none { it.jsonPrimitive.contentOrNull == raw }) {
                throw IllegalArgumentException(
                    "Tool $toolName argument $field must be one of ${
                        enumValues.joinToString(",") { it.toString() }
                    }"
                )
            }
        }
    }

    private fun matchesType(expectedType: String, value: JsonElement): Boolean {
        return when (expectedType) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> true
        }
    }

    private fun describeType(value: JsonElement): String {
        return when (value) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> when {
                value.isString -> "string"
                value.booleanOrNull != null -> "boolean"
                value.intOrNull != null -> "integer"
                value.doubleOrNull != null -> "number"
                else -> "primitive"
            }
        }
    }

    private fun findRemoteTool(
        toolName: String,
        discoveredServers: List<RemoteMcpDiscoveredServer>
    ): RemoteMcpToolDescriptor? {
        return discoveredServers.asSequence()
            .flatMap { it.tools.asSequence() }
            .firstOrNull { it.encodedToolName == toolName }
    }

    private fun toDynamicMcpToolDefinition(
        tool: RemoteMcpToolDescriptor,
        locale: cn.com.omnimind.baselib.i18n.PromptLocale
    ): JsonObject {
        return AgentToolDefinitions.decorateToolDefinition(buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive(tool.encodedToolName))
                put("displayName", JsonPrimitive(tool.toolName))
                put("toolType", JsonPrimitive("mcp"))
                put("serverName", JsonPrimitive(tool.serverName))
                put(
                    "description",
                    JsonPrimitive(
                        tool.description.ifBlank {
                            LocalizedText(
                                zhCN = "调用远端 MCP 工具。",
                                enUS = "Call a remote MCP tool."
                            ).resolve(locale)
                        }
                    )
                )
                put("parameters", mapToJsonElement(tool.inputSchema))
            })
        }, locale)
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
