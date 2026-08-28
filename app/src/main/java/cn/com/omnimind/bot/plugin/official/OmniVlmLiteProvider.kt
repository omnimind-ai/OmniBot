package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.omniflow.OmniFlowAppPlatform
import cn.com.omnimind.bot.omniflow.OmniFlow
import cn.com.omnimind.bot.omniflow.OmniFlowPluginRuntime
import cn.com.omnimind.bot.omniflow.OmniFlowRuntimeProvider
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleAdapter
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleDefinition
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundlePrepareMode
import cn.com.omnimind.bot.plugin.runtime.RuntimeSkillBundleManager

class OmniVlmLiteProvider(
    context: Context,
    definition: RuntimeBundleDefinition,
) : RuntimeBundleAdapter {
    private val appContext = context.applicationContext
    private val runtimeProvider = OmniFlowRuntimeProvider()
    private val platform = OmniFlowAppPlatform(
        RuntimeSkillBundleManager(
            context = appContext,
            spec = definition.runtimeSkill,
            allowPackagedFallback = BuildConfig.ALLOW_PACKAGED_PLUGIN_FALLBACK,
            preferPackagedFallback = BuildConfig.PREFER_PACKAGED_OMNIFLOW_RUNTIME,
        )
    )

    override suspend fun prepare(mode: RuntimeBundlePrepareMode) {
        OmniFlowPluginRuntime.install(platform, runtimeProvider)
        when (mode) {
            RuntimeBundlePrepareMode.INSTALL -> runtimeProvider.install(appContext, platform)
            RuntimeBundlePrepareMode.UPDATE -> {
                // The component directory is replaced atomically, but the
                // resident Python bridge is a long-lived process.  Stop it
                // before switching the runtime files so an update cannot
                // continue executing modules/checkpoints from the previous
                // OmniFlow/OmniTransfer bundle.
                OmniFlow.shutdown()
                runtimeProvider.update(appContext, platform)
            }
        }
    }

    override suspend fun remove() {
        OmniFlowPluginRuntime.uninstall()
        runtimeProvider.reclaim(appContext, platform)
    }

    override fun open(): OmniPlugin {
        OmniFlowPluginRuntime.install(platform, runtimeProvider)
        return object : OmniPlugin {
            override fun contribution(): OmniPluginContribution =
                OmniPluginContribution(
                    toolGroups = listOf(
                        OmniPluginToolGroup(
                            definitions = OmniFlowManagementTools.definitions(),
                            handlerFactory = { OmniFlowManagementToolHandler(appContext) },
                        ),
                    )
                )

            override suspend fun onEnable() {
                OmniFlowPluginRuntime.enable(appContext)
                // MCP is started lazily by the official ACP adapter or by the
                // local-service settings page.  Starting a Ktor socket while
                // the application is restoring plugins can race a previous
                // process and take down the whole Android process on bind
                // failure.
            }

            override suspend fun onDisable() {
                OmniFlowPluginRuntime.disable()
            }
        }
    }

    companion object {
        const val ID = "com.omnimind.omni-vlm-lite"
        const val ADAPTER_ID = "omniflow_android_gui"
    }
}
