package cn.com.omnimind.bot.plugin.official

import android.content.Context
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.plugin.OmniPluginProviderRegistry
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleAdapterRegistry
import cn.com.omnimind.bot.plugin.runtime.RemoteRuntimeBundleCatalogStore
import cn.com.omnimind.bot.plugin.sandbox.SandboxPluginPool
import cn.com.omnimind.bot.plugin.sandbox.SandboxRuntimeBundleAdapter
import java.util.concurrent.atomic.AtomicBoolean

object OfficialOmniPluginProviders {
    private val registered = AtomicBoolean(false)
    private val remoteCatalog = RemoteRuntimeBundleCatalogStore()

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        RuntimeBundleAdapterRegistry.register(OmniVlmLiteProvider.ADAPTER_ID) { context, definition ->
            OmniVlmLiteProvider(context, definition)
        }
        RuntimeBundleAdapterRegistry.register(OmniLinkAgentProvider.ADAPTER_ID) { context, definition ->
            OmniLinkAgentProvider(context, definition)
        }
        RuntimeBundleAdapterRegistry.register(SandboxRuntimeBundleAdapter.ADAPTER_ID) { context, definition ->
            SandboxRuntimeBundleAdapter(context, definition)
        }
        OmniPluginProviderRegistry.registerSource(RUNTIME_BUNDLE_SOURCE) { context ->
            RuntimeBundleAdapterRegistry.createProviders(
                context = context,
                catalog = remoteCatalog.current(context, BuildConfig.OMNIBOT_PROFILE),
            )
        }
        OmniPluginProviderRegistry.registerSource(SANDBOX_USER_POOL_SOURCE) { context ->
            SandboxPluginPool(context).createProviders()
        }
    }

    suspend fun refreshCatalog(context: Context, force: Boolean = false) {
        remoteCatalog.refresh(
            context = context.applicationContext,
            profile = BuildConfig.OMNIBOT_PROFILE,
            force = force,
        )
    }

    private const val RUNTIME_BUNDLE_SOURCE = "official-runtime-bundles"
    private const val SANDBOX_USER_POOL_SOURCE = "sandbox-user-plugin-pool"
}
