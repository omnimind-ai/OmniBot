package cn.com.omnimind.bot.plugin.sandbox

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleAdapter
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleDefinition
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundlePrepareMode
import cn.com.omnimind.bot.plugin.runtime.RuntimeSkillBundleManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

class SandboxRuntimeBundleAdapter(
    context: Context,
    definition: RuntimeBundleDefinition,
) : RuntimeBundleAdapter {
    private val appContext = context.applicationContext
    private val skillManager = RuntimeSkillBundleManager(appContext, definition.runtimeSkill)
    private val bundle = SandboxBundleDefinition.load(
        context = appContext,
        assetPath = "${definition.runtimeSkill.packagedAssetPath}/bundle.json",
    )

    override suspend fun prepare(mode: RuntimeBundlePrepareMode) {
        skillManager.resolvePackaged(refresh = mode == RuntimeBundlePrepareMode.UPDATE)
        skillManager.setEnabled(false)
    }

    override suspend fun remove() = skillManager.reclaim()

    override fun open(): OmniPlugin = object : OmniPlugin {
        override fun contribution(): OmniPluginContribution = OmniPluginContribution(
            toolGroups = listOf(
                OmniPluginToolGroup(
                    definitions = bundle.tools.map(SandboxBundleTool::definition),
                    handlerFactory = { SandboxBundleToolHandler(appContext, bundle.tools) },
                ),
            ),
        )

        override suspend fun onEnable() {
            skillManager.resolvePackaged(refresh = false)
            skillManager.setEnabled(true)
        }

        override suspend fun onDisable() = skillManager.setEnabled(false)
    }

    companion object {
        const val ADAPTER_ID = "sandbox_bundle"
    }
}

@Serializable
internal data class SandboxBundleDefinition(
    val schemaVersion: Int = 0,
    val tools: List<SandboxBundleTool> = emptyList(),
) {
    fun validated(): SandboxBundleDefinition {
        require(schemaVersion == 1) { "Unsupported sandbox bundle schema: $schemaVersion" }
        require(tools.isNotEmpty()) { "Sandbox bundle must declare at least one tool" }
        val duplicate = tools.groupBy(SandboxBundleTool::name)
            .entries.firstOrNull { it.value.size > 1 }
            ?.key
        require(duplicate == null) { "Duplicate sandbox bundle tool: $duplicate" }
        tools.forEach(SandboxBundleTool::validated)
        return this
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context, assetPath: String): SandboxBundleDefinition {
            val source = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            return parse(source)
        }

        internal fun parse(source: String): SandboxBundleDefinition =
            json.decodeFromString<SandboxBundleDefinition>(source).validated()
    }
}

@Serializable
internal data class SandboxBundleTool(
    val name: String = "",
    val displayName: String = "",
    val description: String = "",
    val executor: String = "",
    val parameters: JsonObject = JsonObject(emptyMap()),
) {
    fun validated() {
        require(TOOL_NAME.matches(name)) { "Invalid sandbox bundle tool name: $name" }
        require(displayName.isNotBlank()) { "Sandbox bundle tool $name has no display name" }
        require(description.isNotBlank()) { "Sandbox bundle tool $name has no description" }
        require(executor in SUPPORTED_EXECUTORS) {
            "Unsupported sandbox bundle executor: $executor"
        }
    }

    fun definition(): OmniPluginToolDefinition = OmniPluginToolDefinition(
        name = name,
        displayName = displayName,
        description = description,
        parameters = parameters,
    )

    companion object {
        const val PROJECT_CONTRACT_EXECUTOR = "plugin.pool.contract"
        const val CHECK_PROJECT_EXECUTOR = "plugin.pool.check"
        const val PUBLISH_PROJECT_EXECUTOR = "plugin.pool.publish"
        private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{2,63}$")
        private val SUPPORTED_EXECUTORS = setOf(
            PROJECT_CONTRACT_EXECUTOR,
            CHECK_PROJECT_EXECUTOR,
            PUBLISH_PROJECT_EXECUTOR,
        )
    }
}

private class SandboxBundleToolHandler(
    context: Context,
    private val tools: List<SandboxBundleTool>,
) : ToolHandler {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val helper = SharedHelper(appContext, json)
    private val toolsByName = tools.associateBy(SandboxBundleTool::name)
    private val pool = SandboxPluginPool(appContext)

    override val toolNames: Set<String> = toolsByName.keys

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val tool = toolsByName[toolCall.function.name]
            ?: return ToolExecutionResult.Error(
                toolCall.function.name,
                "Unsupported sandbox bundle tool",
            )
        return runCatching {
            toolHandle.throwIfStopRequested()
            val result = when (tool.executor) {
                SandboxBundleTool.PROJECT_CONTRACT_EXECUTOR -> SandboxConnectorContract.payload()
                SandboxBundleTool.CHECK_PROJECT_EXECUTOR -> checkProject(args, env)
                SandboxBundleTool.PUBLISH_PROJECT_EXECUTOR -> publishProject(args, env)
                else -> error("Unsupported sandbox bundle executor: ${tool.executor}")
            }
            val encoded = helper.mapToJsonElement(result).toString()
            ToolExecutionResult.ContextResult(
                toolName = tool.name,
                summaryText = when (tool.executor) {
                    SandboxBundleTool.PROJECT_CONTRACT_EXECUTOR -> "已读取项目 Connector 契约"
                    SandboxBundleTool.CHECK_PROJECT_EXECUTOR -> "项目检查通过，可以发布"
                    SandboxBundleTool.PUBLISH_PROJECT_EXECUTOR ->
                        "已发布并启用 ${result["name"]} 插件"
                    else -> "项目操作已完成"
                },
                previewJson = encoded,
                rawResultJson = encoded,
            )
        }.getOrElse { error ->
            ToolExecutionResult.Error(
                tool.name,
                error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun checkProject(
        args: JsonObject,
        env: AgentExecutionEnvironment,
    ): Map<String, Any?> {
        val manifest = args.requiredManifest()
        val sourceDirectory = env.workspaceManager.resolvePath(
            inputPath = args.requiredString("path"),
            workspace = env.workspaceDescriptor,
        )
        return pool.execute(
            SandboxPluginCommand.CheckProject(sourceDirectory, manifest),
        ).requireSuccess().payload + mapOf("name" to manifest.name)
    }

    private suspend fun publishProject(
        args: JsonObject,
        env: AgentExecutionEnvironment,
    ): Map<String, Any?> {
        val manifest = args.requiredManifest()
        val sourceDirectory = env.workspaceManager.resolvePath(
            inputPath = args.requiredString("path"),
            workspace = env.workspaceDescriptor,
        )
        val published = pool.execute(
            SandboxPluginCommand.PublishProject(sourceDirectory, manifest),
        ).requireSuccess()
        val pluginId = published.payload.getValue("pluginId") as String
        val host = OmniPluginHost.get(appContext)
        val current = host.list().firstOrNull { it.descriptor.id == pluginId }
        val state = if (current?.installed == true) {
            host.update(pluginId)
        } else {
            host.install(pluginId)
        }
        if (!state.enabled) host.setEnabled(pluginId, true)
        return buildMap {
            putAll(published.payload)
            put("name", manifest.name)
        }
    }

    private fun JsonObject.requiredManifest(): SandboxProjectManifest {
        val element = get("manifest") as? JsonObject
            ?: throw IllegalArgumentException("manifest must be an object")
        return json.decodeFromJsonElement(element)
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("$key is required")
}
