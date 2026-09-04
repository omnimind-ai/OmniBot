package cn.com.omnimind.bot.plugin.official.agentweb

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginActionDefinition
import cn.com.omnimind.bot.plugin.OmniPluginActionGroup
import cn.com.omnimind.bot.plugin.OmniPluginActionHandler
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginKind
import cn.com.omnimind.bot.plugin.OmniPluginProvider
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Built-in plugin boundary for validated local Agent Web surfaces. */
internal class AgentWebPluginProvider(private val context: Context) : OmniPluginProvider {
    override val descriptor = OmniPluginDescriptor(
        id = ID,
        name = "Agent Web",
        version = "1.0.0",
        description = "Open and manage validated local Web UIs for installed Agent runtimes.",
        publisher = "OmniMind",
        kind = OmniPluginKind.BUNDLED_MODULE,
        capabilities = listOf(
            "Kimi Code Web",
            "DeepSeek Harness Web",
            "Validated loopback handoff",
        ),
        required = true,
        presentation = buildJsonObject {
            put("visibility", "hidden")
        },
    )

    override fun create(): OmniPlugin = AgentWebPlugin(context.applicationContext)

    companion object {
        const val ID = "com.omnimind.agent-web"
    }
}

private class AgentWebPlugin(context: Context) : OmniPlugin {
    private val controller: AgentWebController = AgentWebRuntimeManager(
        gateway = AndroidAgentWebRuntimeGateway(context),
        configurationProvider = AndroidAgentWebConfigurationProvider(),
    )

    override fun contribution() = OmniPluginContribution(
        toolGroups = listOf(
            OmniPluginToolGroup(
                definitions = AgentWebTools.definitions(),
                handlerFactory = { AgentWebToolHandler(controller) },
            ),
        ),
        actionGroups = listOf(
            OmniPluginActionGroup(
                definitions = AgentWebActions.definitions(),
                handlerFactory = { AgentWebActionHandler(controller) },
            ),
        ),
    )
}

internal object AgentWebTools {
    const val OPEN_KIMI = "open_kimi_web"
    const val KIMI_STATUS = "get_kimi_web_status"
    const val STOP_KIMI = "stop_kimi_web"
    const val OPEN_DEEPSEEK = "open_deepseek_harness_web"
    const val DEEPSEEK_STATUS = "get_deepseek_harness_web_status"
    const val STOP_DEEPSEEK = "stop_deepseek_harness_web"

    val names = linkedSetOf(
        OPEN_KIMI,
        KIMI_STATUS,
        STOP_KIMI,
        OPEN_DEEPSEEK,
        DEEPSEEK_STATUS,
        STOP_DEEPSEEK,
    )

    fun definitions(): List<OmniPluginToolDefinition> = listOf(
        definition(
            name = OPEN_KIMI,
            displayName = "Open Kimi Code Web",
            description = "Open the installed Kimi Code Web UI in the system browser. Call only " +
                "after the user explicitly asks to open Kimi Web. The authenticated URL remains " +
                "inside the native host and is never returned.",
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
        ),
        definition(
            name = KIMI_STATUS,
            displayName = "Get Kimi Code Web status",
            description = "Check whether the managed Kimi Code Web process is starting or running.",
        ),
        definition(
            name = STOP_KIMI,
            displayName = "Stop Kimi Code Web",
            description = "Stop the managed Kimi Code Web process only after the user " +
                "explicitly asks to stop or close it.",
        ),
        definition(
            name = OPEN_DEEPSEEK,
            displayName = "Open DeepSeek Harness Web",
            description = "Open the installed DeepSeek Harness Web UI in the system browser. " +
                "Call only after the user explicitly asks to open it. The loopback URL " +
                "remains inside the native host and is never returned.",
            efforts = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
        ),
        definition(
            name = DEEPSEEK_STATUS,
            displayName = "Get DeepSeek Harness Web status",
            description = "Check whether the managed DeepSeek Harness Web process is starting or running.",
        ),
        definition(
            name = STOP_DEEPSEEK,
            displayName = "Stop DeepSeek Harness Web",
            description = "Stop the managed DeepSeek Harness Web process only after the user " +
                "explicitly asks to stop or close it.",
        ),
    )

    private fun definition(
        name: String,
        displayName: String,
        description: String,
        efforts: List<String> = emptyList(),
    ) = OmniPluginToolDefinition(
        name = name,
        displayName = displayName,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                if (efforts.isNotEmpty()) {
                    put("reasoning_effort", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional reasoning effort for the new Web process.")
                        put("enum", buildJsonArray {
                            efforts.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
            put("additionalProperties", false)
        },
    )
}

internal object AgentWebActions {
    const val OPEN_KIMI = AgentWebTools.OPEN_KIMI
    const val OPEN_DEEPSEEK = AgentWebTools.OPEN_DEEPSEEK

    val ids = linkedSetOf(OPEN_KIMI, OPEN_DEEPSEEK)

    fun definitions(): List<OmniPluginActionDefinition> = listOf(
        action(
            id = OPEN_KIMI,
            displayName = "Kimi Code Web",
            description = "Open the local Kimi Code browser interface.",
            packageId = AgentWebService.KIMI.packageId,
            agentId = "kimi-code-acp",
            quickLaunchOrder = 0,
            labelZh = "Kimi Code Web",
            labelEn = "Kimi Code Web",
            shortLabelZh = "Kimi Web",
            shortLabelEn = "Kimi Web",
            descriptionZh = "使用统一 Provider 和模型，在系统浏览器中打开本机 Web 界面",
            descriptionEn = "Open the local Web UI with the shared Provider and model",
        ),
        action(
            id = OPEN_DEEPSEEK,
            displayName = "DeepSeek Harness Web",
            description = "Open the local DeepSeek Harness browser interface.",
            packageId = AgentWebService.DEEPSEEK_HARNESS.packageId,
            agentId = "deepseek-harness-acp",
            quickLaunchOrder = 1,
            labelZh = "DeepSeek Harness Web",
            labelEn = "DeepSeek Harness Web",
            shortLabelZh = "DSH Web",
            shortLabelEn = "DSH Web",
            descriptionZh = "使用统一 Provider 和模型，在系统浏览器中打开本机 Web 界面",
            descriptionEn = "Open the local Web UI with the shared Provider and model",
        ),
    )

    private fun action(
        id: String,
        displayName: String,
        description: String,
        packageId: String,
        agentId: String,
        quickLaunchOrder: Int,
        labelZh: String,
        labelEn: String,
        shortLabelZh: String,
        shortLabelEn: String,
        descriptionZh: String,
        descriptionEn: String,
    ) = OmniPluginActionDefinition(
        id = id,
        displayName = displayName,
        description = description,
        presentation = buildJsonObject {
            put("placement", "agent_settings")
            put("placements", buildJsonArray {
                add(JsonPrimitive("agent_settings"))
                add(JsonPrimitive("home_drawer_quick_launch"))
            })
            put("icon", "globe")
            put("packageId", packageId)
            put("agentId", agentId)
            put("quickLaunchOrder", quickLaunchOrder)
            put("label", localized(labelZh, labelEn))
            put("shortLabel", localized(shortLabelZh, shortLabelEn))
            put("description", localized(descriptionZh, descriptionEn))
        },
    )

    private fun localized(zh: String, en: String) = buildJsonObject {
        put("zh", zh)
        put("en", en)
    }
}

private class AgentWebActionHandler(
    private val controller: AgentWebController,
) : OmniPluginActionHandler {
    override val actionIds: Set<String> = AgentWebActions.ids

    override suspend fun execute(actionId: String, args: JsonObject): JsonObject {
        val service = when (actionId) {
            AgentWebActions.OPEN_KIMI -> AgentWebService.KIMI
            AgentWebActions.OPEN_DEEPSEEK -> AgentWebService.DEEPSEEK_HARNESS
            else -> error("Unsupported Agent Web action: $actionId")
        }
        return controller.open(
            service = service,
            reasoningEffort = args.reasoningEffort(),
        ).toJson()
    }
}

private class AgentWebToolHandler(
    private val controller: AgentWebController,
) : ToolHandler {
    override val toolNames: Set<String> = AgentWebTools.names

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
            return ToolExecutionResult.Error(toolName, "Unsupported Agent Web tool")
        }
        return try {
            toolHandle.throwIfStopRequested()
            val result = when (toolName) {
                AgentWebTools.OPEN_KIMI -> controller.open(
                    AgentWebService.KIMI,
                    args.reasoningEffort(),
                )
                AgentWebTools.KIMI_STATUS -> controller.status(AgentWebService.KIMI)
                AgentWebTools.STOP_KIMI -> controller.stop(AgentWebService.KIMI)
                AgentWebTools.OPEN_DEEPSEEK -> controller.open(
                    AgentWebService.DEEPSEEK_HARNESS,
                    args.reasoningEffort(),
                )
                AgentWebTools.DEEPSEEK_STATUS ->
                    controller.status(AgentWebService.DEEPSEEK_HARNESS)
                AgentWebTools.STOP_DEEPSEEK ->
                    controller.stop(AgentWebService.DEEPSEEK_HARNESS)
                else -> error("Unsupported Agent Web tool: $toolName")
            }
            val encoded = result.toJson().toString()
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = result.summary(),
                previewJson = encoded,
                rawResultJson = encoded,
                success = result.success,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolExecutionResult.Error(
                toolName,
                error.message.orEmpty().ifBlank { error.javaClass.simpleName },
            )
        }
    }
}

private fun JsonObject.reasoningEffort(): String? =
    get("reasoning_effort")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private fun AgentWebOperationResult.summary(): String = when (code) {
    AgentWebResultCode.OPENED -> "已在系统浏览器打开 ${service.displayName}"
    AgentWebResultCode.RUNNING -> "${service.displayName} 正在运行"
    AgentWebResultCode.STARTING -> "${service.displayName} 正在启动"
    AgentWebResultCode.STOPPED -> "已停止 ${service.displayName}"
    AgentWebResultCode.NOT_RUNNING -> "${service.displayName} 未在运行"
    AgentWebResultCode.RUNTIME_MISSING -> "${service.displayName} 尚未安装"
    AgentWebResultCode.PROVIDER_REQUIRED -> "尚未配置统一 Dispatch Provider"
    AgentWebResultCode.MODEL_REQUIRED -> "尚未选择统一 Dispatch 模型"
    AgentWebResultCode.UNSUPPORTED_PROVIDER -> "当前 Provider 不受 ${service.displayName} 支持"
    AgentWebResultCode.START_FAILED -> "${service.displayName} 启动失败"
    AgentWebResultCode.STOP_FAILED -> "${service.displayName} 停止失败"
    AgentWebResultCode.URL_TIMEOUT -> "等待 ${service.displayName} 就绪超时"
    AgentWebResultCode.BROWSER_UNAVAILABLE -> "没有可用的系统浏览器"
}
