package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.omniflow.RuntimeTool
import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import com.google.gson.Gson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Project package declarations into the existing plugin registry; no copied tool schemas. */
internal fun runtimeToolDefinition(tool: RuntimeTool): OmniPluginToolDefinition = OmniPluginToolDefinition(
    name = tool.name,
    displayName = tool.name,
    description = tool.description,
    parameters = Json.parseToJsonElement(Gson().toJson(tool.inputSchema)).jsonObject,
)
