package cn.com.omnimind.bot.omniflow

import android.content.Context

object OmniFlowPluginRuntime {
    private val shared = OmniFlowPluginRuntimeController(DefaultOmniFlowPluginBackend)

    fun install(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider(),
    ) = shared.install(platform, runtimeProvider)

    suspend fun enable(context: Context) = shared.enable(context)

    suspend fun disable() = shared.disable()

    suspend fun uninstall() = shared.uninstall()

    fun isEnabled(): Boolean = shared.isEnabled()
}

internal class OmniFlowPluginRuntimeController(
    private val backend: OmniFlowPluginBackend,
) {
    @Volatile
    private var installed = false

    @Volatile
    private var enabled = false

    fun install(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    ) {
        backend.configure(platform, runtimeProvider)
        installed = true
        enabled = false
    }

    suspend fun enable(context: Context) {
        check(installed) { "omniflow_plugin_not_installed" }
        if (enabled) return
        backend.prepareAndStart(context)
        enabled = true
    }

    suspend fun disable() {
        if (!installed) return
        enabled = false
        backend.shutdown()
    }

    suspend fun uninstall() {
        disable()
        installed = false
    }

    fun isEnabled(): Boolean = installed && enabled
}

internal interface OmniFlowPluginBackend {
    fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    )

    suspend fun prepareAndStart(context: Context)

    suspend fun shutdown()
}

private object DefaultOmniFlowPluginBackend : OmniFlowPluginBackend {
    override fun configure(
        platform: OmniFlowPlatform,
        runtimeProvider: OmniFlowRuntimeProvider,
    ) = OmniFlow.configure(platform, runtimeProvider)

    override suspend fun prepareAndStart(context: Context) {
        // Plugin registration must not probe or repair Python.  The plugin is
        // installed and its ACP tool definitions are available immediately;
        // OmniFlow.callTool awaits the resident runtime only when a tool is
        // actually selected.  This keeps ordinary chat independent from
        // numpy/APKINDEX/network work during application startup.
    }

    override suspend fun shutdown() = OmniFlow.shutdown()
}
