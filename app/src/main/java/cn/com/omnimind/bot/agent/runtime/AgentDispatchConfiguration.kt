package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.SceneModelBindingStore

/**
 * Read-only view of the Provider and model selected for Dispatch execution.
 *
 * ACP adapters and out-of-band plugin capabilities must resolve this state in
 * exactly the same way. Keeping the lookup here prevents a local runtime from
 * silently reviving a stale Provider or choosing its own default model.
 */
internal object AgentDispatchConfiguration {
    private const val DISPATCH_SCENE_ID = "scene.dispatch.model"

    fun providerProfile(): ModelProviderProfile? = runCatching {
        val binding = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
        val configuredProfile = binding
            ?.providerProfileId
            ?.let(ModelProviderConfigStore::getProfile)
        resolveDispatchAgentProviderProfile(
            boundProviderProfileId = binding?.providerProfileId,
            configuredProfile = configuredProfile,
            editingProfile = ModelProviderConfigStore.getEditingProfile(),
            officialProfile = PlatformAiProvisioner.officialProfileOrNull(),
        )
    }.getOrNull()

    fun providerCredentials(): AgentProviderCredentials? = providerProfile()?.let { profile ->
        val apiKey = resolveAgentProviderApiKey(
            profile = profile,
            officialBearerToken = OmniAccount.currentAiRequestAccess().bearerToken,
        ) ?: return@let null
        AgentProviderCredentials(
            baseUrl = profile.baseUrl,
            apiKey = apiKey,
            wireApi = profile.wireApi,
            customHeaders = profile.customHeaders,
            protocolType = profile.protocolType,
            supportsNamespaceTools = OmniOfficialProvider.isOfficialProfile(profile.id),
        ).normalized()
    }

    fun modelId(): String? = runCatching {
        val binding = SceneModelBindingStore.getBinding(DISPATCH_SCENE_ID)
        binding?.let {
            resolveAgentProviderProfile(
                boundProviderProfileId = it.providerProfileId,
                configuredProfile = ModelProviderConfigStore.getProfile(it.providerProfileId),
                officialProfile = PlatformAiProvisioner.officialProfileOrNull(),
            )
        }?.takeIf { it.baseUrl.isNotBlank() }
            ?: return@runCatching null
        resolveSharedAgentModel(
            boundProviderProfileId = binding.providerProfileId,
            boundModel = binding.modelId,
        )
    }.getOrNull()
}
