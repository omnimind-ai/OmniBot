package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderConfigStoreTest {

    @Test
    fun normalizeBaseUrl_preservesCompatibleModeVersionBase() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ModelProviderConfigStore.normalizeBaseUrl(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/"
            )
        )
    }

    @Test
    fun hasVersionedBasePath_supportsV1AndCompatibleMode() {
        assertTrue(ModelProviderConfigStore.hasVersionedBasePath("https://api.example.com/v1"))
        assertTrue(
            ModelProviderConfigStore.hasVersionedBasePath(
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )
        )
        assertFalse(ModelProviderConfigStore.hasVersionedBasePath("https://api.example.com"))
    }

    @Test
    fun filterDeletedOfficialProfiles_onlyRemovesOfficialProfiles() {
        val profiles = listOf(
            DeepSeekProvider.officialProfile(),
            ModelProviderProfile(id = "custom-provider", name = "Custom")
        )

        val filtered = ModelProviderConfigStore.filterDeletedOfficialProfiles(
            profiles,
            setOf(DeepSeekProvider.OFFICIAL_PROFILE_ID, "custom-provider")
        )

        assertEquals(listOf("custom-provider"), filtered.map { it.id })
    }

    @Test
    fun deletedOfficialProfileIds_roundTripDropsUnknownIds() {
        val encoded = ModelProviderConfigStore.encodeDeletedOfficialProfileIds(
            setOf(
                "missing-provider",
                MoonshotProvider.OFFICIAL_PROFILE_ID,
                DeepSeekProvider.OFFICIAL_PROFILE_ID
            )
        )

        val decoded = ModelProviderConfigStore.decodeDeletedOfficialProfileIds(encoded)

        assertEquals(
            setOf(DeepSeekProvider.OFFICIAL_PROFILE_ID, MoonshotProvider.OFFICIAL_PROFILE_ID),
            decoded
        )
    }

    @Test
    fun providerKeepsCredentialsForExplicitlyConfiguredHttpEndpoint() {
        CredentialEndpointSecurity.configureDebugLoopback(false)
        val hydrated = ModelProviderConfigStore.mergeProfileSecrets(
            ModelProviderProfile(
                id = "xiaowan-http",
                name = "Xiaowan HTTP Provider",
                baseUrl = "http://192.168.1.20:8080/v1",
            ),
            ModelProviderSecrets(
                apiKey = "secret",
                customHeaders = mapOf("X-Custom-Token" to "secret-2"),
            ),
        )

        assertEquals("secret", hydrated.apiKey)
        assertEquals("secret-2", hydrated.customHeaders["X-Custom-Token"])
    }

    @Test
    fun hydrateDropsSensitiveEndpointMetadataWithoutSeparateCredentials() {
        val hydrated = ModelProviderConfigStore.mergeProfileSecrets(
            ModelProviderProfile(
                id = "embedded-secret",
                name = "Embedded secret",
                baseUrl = "https://provider.example/v1?access_token=embedded",
            ),
            null,
        )

        assertEquals("", hydrated.baseUrl)
        assertEquals("", hydrated.apiKey)
        assertTrue(hydrated.customHeaders.isEmpty())
    }

    @Test
    fun damagedProfileJsonContainingSecretNamesIsRejectedInsteadOfRetained() {
        val damaged = """[{"id":"profile-1","apiKey":"plaintext-secret","customHeaders":{ """

        assertEquals(null, ModelProviderConfigStore.sanitizeProfilesMetadataJson(damaged))
    }

    @Test
    fun validProfileJsonIsReencodedWithoutSecretFields() {
        val sanitized = ModelProviderConfigStore.sanitizeProfilesMetadataJson(
            """[{"id":"profile-1","name":"Provider","baseUrl":"https://api.example/v1","apiKey":"plaintext-secret","customHeaders":{"Authorization":"secret"}}]"""
        ).orEmpty()

        assertTrue(sanitized.contains("profile-1"))
        assertFalse(sanitized.contains("plaintext-secret"))
        assertFalse(sanitized.contains("Authorization"))
    }

    @Test
    fun legacyProviderKeepsWorkingWithoutRevisionMetadata() {
        val profile = ModelProviderConfigStore.decodeProfilesJson(
            """[{"id":"legacy","name":"Legacy","baseUrl":"https://api.example.com/v1"}]"""
        ).single()

        assertEquals("https://api.example.com/v1", profile.baseUrl)
        assertTrue(profile.isConfigured())
        assertEquals(1L, profile.revision)
    }

    @Test
    fun providerFetchSnapshotRequiresTheSameCanonicalEndpoint() {
        assertTrue(
            ModelProviderConfigStore.sameCanonicalEndpoint(
                "https://api.example.com/v1/",
                "https://api.example.com/v1"
            )
        )
        assertFalse(
            ModelProviderConfigStore.sameCanonicalEndpoint(
                "https://api.example.com/v1",
                "https://api.example.com:8443/v1"
            )
        )
        assertFalse(
            ModelProviderConfigStore.sameCanonicalEndpoint(
                "https://api.example.com/v1",
                "https://other.example.com/v1"
            )
        )
    }
}
