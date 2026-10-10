package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProviderLaunchCatalogTest {
    private val official = ModelProviderProfile(
        id = OmniOfficialProvider.PROFILE_ID, name = "Official", baseUrl = "https://gateway.example",
    )
    private val models = listOf(
        ProviderModelOption(id = "deepseek-v4-flash-0731"),
        ProviderModelOption(id = "qwen3.8-flash"),
    )

    @Test fun officialLaunchUsesEntireProvisionedCatalogWithoutByokCache() {
        assertEquals(models, ModelProviderConfigStore.cachedModels(
            official, PlatformAiProvisioningStatus(ready = true, models = models),
        ) { error("Official discovery must not read BYOK preferences") })
    }

    @Test fun unavailableOfficialCatalogDoesNotReusePersistedOrUnreadyModels() {
        assertTrue(ModelProviderConfigStore.cachedModels(
            official, PlatformAiProvisioningStatus(ready = false, models = models),
        ) { error("Do not fall back to a stale official preference bucket") }.isEmpty())
    }

    @Test fun customProviderKeepsEndpointAndRevisionScopedCatalog() {
        val profile = ModelProviderProfile(id = "custom", name = "Custom", baseUrl = "https://custom.example/v1", revision = 4L)
        val json = """{"custom":{"apiBase":"https://custom.example/v1","profileRevision":4,"models":[{"id":"custom-model"}]}}"""
        val status = PlatformAiProvisioningStatus(ready = true, models = models)
        assertEquals(listOf("custom-model"), ModelProviderConfigStore.cachedModels(profile, status) { json }.map { it.id })
        assertTrue(ModelProviderConfigStore.cachedModels(profile.copy(revision = 5L), status) { json }.isEmpty())
        assertTrue(ModelProviderConfigStore.cachedModels(profile.copy(baseUrl = "https://other.example/v1"), status) { json }.isEmpty())
        assertTrue(ModelProviderConfigStore.cachedModels(profile, status) { null }.isEmpty())
    }

    private fun token(subject: String = "one", session: String = "session-one", expiry: Long = 1000) =
        "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"iss":"account","sub":"$subject","sid":"$session","exp":$expiry}""".toByteArray(),
        ) + ".signature"

    @Test fun coldLaunchRestoresSameSessionCatalogAcrossTokenRotation() {
        val scope = OfficialProviderModelCache.scope(token(), official.baseUrl)!!
        val rotatedScope = OfficialProviderModelCache.scope(token(expiry = 2000), official.baseUrl)
        assertEquals(scope, rotatedScope)
        val snapshot = OfficialProviderModelCache.encode(scope, models)
        assertEquals(models, ModelProviderConfigStore.cachedModels(
            official, PlatformAiProvisioningStatus(), snapshot, rotatedScope,
        ) { error("Cold official launch must not use BYOK storage") })
    }

    @Test fun persistedOfficialCatalogCannotCrossAccountsSessionsOrGateways() {
        val scope = OfficialProviderModelCache.scope(token(), official.baseUrl)!!
        val snapshot = OfficialProviderModelCache.encode(scope, models)
        val otherScopes = listOf(
            OfficialProviderModelCache.scope(token(subject = "two"), official.baseUrl),
            OfficialProviderModelCache.scope(token(session = "new-login"), official.baseUrl),
            OfficialProviderModelCache.scope(token(), "https://other.example"),
            OfficialProviderModelCache.scope(null, official.baseUrl),
        )
        otherScopes.forEach { assertTrue(OfficialProviderModelCache.decode(snapshot, it).isEmpty()) }
        assertTrue(OfficialProviderModelCache.decode("invalid-json", scope).isEmpty())
    }

    @Test fun currentOfficialCatalogReplacesOfflineSnapshotEvenWhenModelsWereRemoved() {
        val scope = OfficialProviderModelCache.scope(token(), official.baseUrl)!!
        assertEquals(models.take(1), ModelProviderConfigStore.cachedModels(
            official, PlatformAiProvisioningStatus(ready = true, models = models.take(1)),
            OfficialProviderModelCache.encode(scope, models), scope,
        ) { error("Official catalog must not use BYOK storage") })
    }
}
