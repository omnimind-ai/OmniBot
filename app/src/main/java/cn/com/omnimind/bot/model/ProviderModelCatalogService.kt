package cn.com.omnimind.bot.model

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderModelOption

/** Shared discovery boundary; a response belongs to the captured Provider revision. */
internal class ProviderModelCatalogService(private val context: Context) {
    suspend fun fetch(
        profileId: String?,
        capability: String? = null,
        forceRefresh: Boolean = false,
        expectedRevision: Long?,
        expectedBaseUrl: String,
        apiBase: String = "",
        apiKey: String? = null,
        customHeaders: Map<String, String>? = null,
    ): List<ProviderModelOption> {
        if (OmniOfficialProvider.isOfficialProfile(profileId)) {
            return if (forceRefresh) PlatformAiProvisioner.refreshAndGetModels(capability)
                else PlatformAiProvisioner.ensureReadyAndGetModels(capability)
        }
        val profile = profileId?.let(ModelProviderConfigStore::getProfile)
            ?: ModelProviderConfigStore.getEditingProfile()
        require(expectedRevision != null && expectedRevision >= 0L) { "provider profile revision is required" }
        require(expectedBaseUrl.isNotEmpty()) { "provider profile endpoint is required" }
        require(profile.revision == expectedRevision &&
            ModelProviderConfigStore.sameCanonicalEndpoint(profile.baseUrl, expectedBaseUrl)) {
            "provider profile changed"
        }
        val base = apiBase.ifEmpty { profile.baseUrl }
        val models = HttpController.fetchProviderModels(
            apiBase = base,
            apiKey = apiKey ?: profile.apiKey,
            customHeaders = customHeaders ?: profile.customHeaders,
            protocolType = profile.protocolType,
            wireApi = profile.wireApi,
        )
        val current = profileId?.let(ModelProviderConfigStore::getProfile)
        require(current != null && current.revision == expectedRevision &&
            ModelProviderConfigStore.sameCanonicalEndpoint(current.baseUrl, expectedBaseUrl)) {
            "provider profile changed"
        }
        if (apiKey == null && customHeaders == null &&
            ModelProviderConfigStore.sameCanonicalEndpoint(base, profile.baseUrl)) {
            ModelProviderConfigStore.rememberModels(context, profile, models)
        }
        return models
    }
}
