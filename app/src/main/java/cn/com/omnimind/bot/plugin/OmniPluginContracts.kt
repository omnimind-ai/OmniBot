package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.serialization.json.JsonObject

object OmniPluginContract {
    const val CURRENT_INTERFACE_VERSION = 1
}

enum class OmniPluginKind(val wireName: String) {
    BUNDLED_MODULE("bundled_module"),
    RUNTIME_BUNDLE("runtime_bundle"),
    COMPANION_APP("companion_app")
}

data class OmniPluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val interfaceVersion: Int = OmniPluginContract.CURRENT_INTERFACE_VERSION,
    val description: String,
    val publisher: String,
    val kind: OmniPluginKind = OmniPluginKind.RUNTIME_BUNDLE,
    val downloadSizeBytes: Long = 0,
    val capabilities: List<String> = emptyList(),
    val required: Boolean = false,
    /** Install the runtime on first startup, without enabling its tools. */
    val installByDefault: Boolean = false,
    val settingsSchema: JsonObject = JsonObject(emptyMap()),
    val presentation: JsonObject = JsonObject(emptyMap())
)

data class OmniPluginToolDefinition(
    val name: String,
    val displayName: String,
    val description: String,
    val parameters: JsonObject,
    val ownerPluginId: String? = null
)

data class OmniPluginToolGroup(
    val definitions: List<OmniPluginToolDefinition>,
    val handlerFactory: () -> ToolHandler
)

/**
 * A user-facing operation contributed by a plugin.
 *
 * Actions are intentionally separate from Agent tool calls: a settings page
 * can invoke one without manufacturing an ACP turn, while the plugin may
 * still expose the same business operation through a normal tool adapter.
 */
data class OmniPluginActionDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val presentation: JsonObject = JsonObject(emptyMap()),
    val ownerPluginId: String? = null,
)

interface OmniPluginActionHandler {
    val actionIds: Set<String>

    fun canHandle(actionId: String): Boolean = actionId in actionIds

    suspend fun execute(actionId: String, args: JsonObject): JsonObject

    suspend fun dispose() = Unit
}

data class OmniPluginActionGroup(
    val definitions: List<OmniPluginActionDefinition>,
    val handlerFactory: () -> OmniPluginActionHandler,
)

data class OmniPluginContribution(
    val toolGroups: List<OmniPluginToolGroup> = emptyList(),
    val actionGroups: List<OmniPluginActionGroup> = emptyList(),
)

interface OmniPlugin {
    fun contribution(): OmniPluginContribution = OmniPluginContribution()

    suspend fun onEnable() = Unit

    suspend fun onDisable() = Unit
}

interface OmniPluginProvider {
    val descriptor: OmniPluginDescriptor

    suspend fun install() = Unit

    suspend fun update() = install()

    suspend fun uninstall() = Unit

    fun create(): OmniPlugin
}

data class OmniPluginStoredState(
    val pluginId: String,
    val enabled: Boolean,
    val installPending: Boolean = false,
)

interface OmniPluginStateStore {
    fun read(): List<OmniPluginStoredState>

    fun readWithDefaults(
        defaults: List<OmniPluginStoredState>
    ): List<OmniPluginStoredState> = read()

    fun write(states: List<OmniPluginStoredState>)
}

data class OmniPluginState(
    val descriptor: OmniPluginDescriptor,
    val installed: Boolean,
    val enabled: Boolean,
    val compatible: Boolean,
    val errorMessage: String? = null
)
