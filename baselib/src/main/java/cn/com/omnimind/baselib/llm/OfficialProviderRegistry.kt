package cn.com.omnimind.baselib.llm

data class OfficialProviderDefinition(
    val key: String,
    val profileId: String,
    val displayName: String,
    val baseUrl: String,
    val protocolType: String,
    val wireApi: String,
    val officialProfileFactory: () -> ModelProviderProfile,
    val officialBaseUrlMatcher: (String?) -> Boolean
) {
    fun officialProfile(): ModelProviderProfile = officialProfileFactory()
    fun matchesBaseUrl(value: String?): Boolean = officialBaseUrlMatcher(value)
}

object OfficialProviderRegistry {
    private val providers = listOf(
        OfficialProviderDefinition(
            key = DeepSeekProvider.PROTOCOL_TYPE,
            profileId = DeepSeekProvider.OFFICIAL_PROFILE_ID,
            displayName = DeepSeekProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = DeepSeekProvider.OFFICIAL_BASE_URL,
            protocolType = DeepSeekProvider.PROTOCOL_TYPE,
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
            officialProfileFactory = DeepSeekProvider::officialProfile,
            officialBaseUrlMatcher = DeepSeekProvider::isOfficialBaseUrl
        ),
        OfficialProviderDefinition(
            key = MimoProvider.SOURCE_TYPE,
            profileId = MimoProvider.OFFICIAL_PROFILE_ID,
            displayName = MimoProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = MimoProvider.OFFICIAL_BASE_URL,
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
            officialProfileFactory = MimoProvider::officialProfile,
            officialBaseUrlMatcher = MimoProvider::isOfficialBaseUrl
        ),
        OfficialProviderDefinition(
            key = MoonshotProvider.SOURCE_TYPE,
            profileId = MoonshotProvider.OFFICIAL_PROFILE_ID,
            displayName = MoonshotProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = MoonshotProvider.OFFICIAL_BASE_URL,
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
            officialProfileFactory = MoonshotProvider::officialProfile,
            officialBaseUrlMatcher = MoonshotProvider::isOfficialBaseUrl
        ),
        OfficialProviderDefinition(
            key = MiniMaxProvider.SOURCE_TYPE,
            profileId = MiniMaxProvider.OFFICIAL_PROFILE_ID,
            displayName = MiniMaxProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = MiniMaxProvider.OFFICIAL_BASE_URL,
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
            officialProfileFactory = MiniMaxProvider::officialProfile,
            officialBaseUrlMatcher = MiniMaxProvider::isOfficialBaseUrl
        ),
        OfficialProviderDefinition(
            key = BailianProvider.SOURCE_TYPE,
            profileId = BailianProvider.OFFICIAL_PROFILE_ID,
            displayName = BailianProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = BailianProvider.OFFICIAL_BASE_URL,
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
            officialProfileFactory = BailianProvider::officialProfile,
            officialBaseUrlMatcher = BailianProvider::isOfficialBaseUrl
        ),
        OfficialProviderDefinition(
            key = LocalModelProvider.SOURCE_TYPE,
            profileId = LocalModelProvider.OFFICIAL_PROFILE_ID,
            displayName = LocalModelProvider.OFFICIAL_PROFILE_NAME,
            baseUrl = "local://inference",
            protocolType = "local_inference",
            wireApi = "local_completion",
            officialProfileFactory = LocalModelProvider::createLocalProfile,
            officialBaseUrlMatcher = { value -> value?.trim()?.startsWith("local://") == true }
        )
    )

    fun definitions(): List<OfficialProviderDefinition> = providers
    fun officialProfiles(): List<ModelProviderProfile> = providers.map { it.officialProfile() }

    fun findByKey(value: String?): OfficialProviderDefinition? {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return providers.firstOrNull { it.key == normalized }
    }

    fun findByProfileId(value: String?): OfficialProviderDefinition? {
        val normalized = value?.trim().orEmpty()
        return providers.firstOrNull { it.profileId == normalized }
    }

    fun findByBaseUrl(value: String?): OfficialProviderDefinition? =
        providers.firstOrNull { it.matchesBaseUrl(value) }

    fun normalizeSourceType(
        sourceType: String?,
        profileId: String?,
        baseUrl: String?
    ): String {
        val normalized = sourceType?.trim()?.lowercase().orEmpty()
        findByKey(normalized)?.let { return it.key }
        findByProfileId(profileId)?.let { return it.key }
        findByBaseUrl(baseUrl)?.let { return it.key }
        return "custom"
    }
}
