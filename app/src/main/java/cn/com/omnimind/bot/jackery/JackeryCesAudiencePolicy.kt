package cn.com.omnimind.bot.jackery

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Audience restrictions are edition capabilities, not a second ACP lifecycle. */
object JackeryCesAudiencePolicy {
    private val mutations = setOf(
        "saveModelProviderProfile", "deleteModelProviderProfile", "setEditingModelProviderProfile",
        "saveModelProviderConfig", "clearModelProviderConfig", "saveSceneModelBinding", "clearSceneModelBinding",
        "saveSceneModelOverride", "clearSceneModelOverride", "saveSceneOperationConfig", "saveSceneVoiceConfig",
        "saveAgentSoulSetting", "saveChatPromptSetting", "agentSkillInstall", "agentSkillDelete",
        "agentSkillSetEnabled", "agentSkillInstallBuiltin", "agentSkillSyncOfficialRepository",
    )
    fun blocksMutation(method: String): Boolean = method in mutations

    fun searchTools(definitions: List<JsonObject>, approvedNames: Set<String>): List<JsonObject> =
        definitions.filter { definition ->
            val function = definition["function"] as? JsonObject ?: return@filter false
            fun value(name: String) = (function[name] as? JsonPrimitive)?.contentOrNull
            value("toolType") == "mcp" && value("displayName") in approvedNames
        }
}
