package cn.com.omnimind.bot.plugin

import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.llm.OfficialVlmOperationRouteResolver
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.bot.BuildConfig

/**
 * GUI-scene VLM readiness projection shared by the Flutter PluginPlatform
 * channel and the native plugin pages. Reads the existing stores only.
 */
internal data class PluginVlmReadiness(
    val debugBuild: Boolean,
    val providerConfigured: Boolean,
    val providerName: String,
    val model: String,
)

internal fun readPluginVlmReadiness(): PluginVlmReadiness {
    val binding = SceneModelBindingStore.getBinding(SceneOperationConfigStore.SCENE_ID)
    val boundProfile = binding
        ?.providerProfileId
        ?.let(ModelProviderConfigStore::getProfile)
        ?.takeIf { it.isConfigured() }
    val officialConfig = OfficialVlmOperationConfigStore.getConfig()
    val configured = boundProfile != null || officialConfig.isConfigured()
    return PluginVlmReadiness(
        debugBuild = BuildConfig.DEBUG,
        providerConfigured = configured,
        providerName = when {
            boundProfile != null -> boundProfile.name
            officialConfig.isConfigured() -> OfficialVlmOperationRouteResolver.PROFILE_NAME
            else -> ""
        },
        model = when {
            boundProfile != null -> binding.modelId
            officialConfig.isConfigured() -> officialConfig.model
            else -> ""
        },
    )
}
