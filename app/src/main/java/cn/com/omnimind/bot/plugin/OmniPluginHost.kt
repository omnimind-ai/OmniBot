package cn.com.omnimind.bot.plugin

import android.content.Context
import cn.com.omnimind.bot.agent.AgentToolDefinitions
import cn.com.omnimind.bot.plugin.official.OmniVlmLiteProvider
import cn.com.omnimind.bot.plugin.official.OfficialOmniPluginProviders

class OmniPluginHost private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val platform = OmniPluginPlatform(
        providerSource = {
            OmniPluginProviderRegistry.createProviders(applicationContext)
        },
        stateStore = SharedPreferencesOmniPluginStateStore(applicationContext),
        reservedToolNames =
            AgentToolDefinitions.reservedToolNames() + PluginDiscoveryToolHandler.TOOL_NAMES,
        defaultEnabledPluginIds = setOf(OmniVlmLiteProvider.ID),
    )

    suspend fun list(): List<OmniPluginState> {
        OfficialOmniPluginProviders.refreshCatalog(applicationContext)
        return platform.list()
    }

    suspend fun install(pluginId: String): OmniPluginState {
        OfficialOmniPluginProviders.refreshCatalog(applicationContext, force = true)
        return platform.install(pluginId)
    }

    suspend fun update(pluginId: String): OmniPluginState {
        OfficialOmniPluginProviders.refreshCatalog(applicationContext, force = true)
        return platform.update(pluginId)
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): OmniPluginState {
        return platform.setEnabled(pluginId, enabled)
    }

    suspend fun uninstall(pluginId: String) = platform.uninstall(pluginId)

    suspend fun openSession(): OmniPluginSession {
        val pluginSession = platform.openSession()
        val discoveryHandler = PluginDiscoveryToolHandler(applicationContext, ::list)
        return OmniPluginSession(
            toolDefinitions =
                PluginDiscoveryToolHandler.definitions() + pluginSession.toolDefinitions,
            toolHandlers = listOf(discoveryHandler) + pluginSession.toolHandlers,
        )
    }

    companion object {
        @Volatile
        private var instance: OmniPluginHost? = null

        fun get(context: Context): OmniPluginHost {
            return instance ?: synchronized(this) {
                instance ?: OmniPluginHost(context).also { instance = it }
            }
        }
    }
}
