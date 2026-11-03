package cn.com.omnimind.baselib.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAccessRoutingTest {
    @Test
    fun signedOutInstallKeepsOpenSourceByokBehavior() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = false,
            cachedMode = AiAccessMode.PLATFORM,
            platformGatewayUrl = "https://brand.example/ai",
            accessToken = "account-jwt",
        )

        assertEquals(AiAccessMode.BYOK, access.mode)
        assertTrue(!access.usesPlatform)
    }

    @Test
    fun signedOutByokRemainsAvailableWhenCloudPolicyBlocksOfficialServices() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = false,
            cachedMode = null,
            platformGatewayUrl = "https://brand.example/ai",
            accessToken = null,
            cloudServiceAccess = CloudServiceAccessState(
                allowed = false,
                policyKnown = true,
                minimumVersion = "0.5.7",
                message = "upgrade required",
            ),
        )

        assertEquals(AiAccessMode.BYOK, access.mode)
        assertTrue(!access.usesPlatform)
        assertEquals(null, access.unavailableReason)
    }

    @Test
    fun signedInOfficialAccessIsBlockedByCloudVersionPolicy() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = null,
            platformGatewayUrl = "https://brand.example/ai",
            accessToken = "account-jwt",
            cloudServiceAccess = CloudServiceAccessState(
                allowed = false,
                policyKnown = true,
                currentVersion = "0.5.6.15",
                minimumVersion = "0.5.7",
                message = "upgrade required",
            ),
        )

        assertEquals(AiAccessMode.PLATFORM, access.mode)
        assertTrue(!access.usesPlatform)
        assertEquals("upgrade required", access.unavailableReason)
        assertEquals("https://brand.example/ai", access.platformGatewayUrl)
        assertEquals(null, access.bearerToken)
    }

    @Test
    fun unknownCloudPolicyPreservesOnlySafeProviderMetadata() {
        for (gateway in listOf(
            "https://brand.example/ai/",
            "http://gateway.example/ai",
            "https://user:password@brand.example/ai",
            "https://brand.example/ai?token=secret",
        )) {
            val access = AiRequestAccessResolver.resolve(
                accountConfigured = true,
                signedIn = true,
                cachedMode = null,
                platformGatewayUrl = gateway,
                accessToken = "must-not-be-exposed",
                cloudServiceAccess = CloudServiceAccessState(allowed = false, policyKnown = false),
            )

            assertEquals(AiAccessMode.PLATFORM, access.mode)
            assertTrue(!access.usesPlatform)
            assertEquals(null, access.bearerToken)
            assertEquals(
                if (gateway == "https://brand.example/ai/") "https://brand.example/ai" else null,
                access.platformGatewayUrl,
            )
            assertTrue(!access.unavailableReason.isNullOrBlank())
        }
    }

    @Test
    fun signedInAccountExposesOfficialAccessWithoutLegacyModeChoice() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = null,
            platformGatewayUrl = "https://brand.example/ai",
            accessToken = "account-jwt",
        )

        assertEquals(AiAccessMode.PLATFORM, access.mode)
        assertTrue(access.usesPlatform)
    }

    @Test
    fun signedInAccountDoesNotReplaceAByokRoute() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = AiAccessMode.PLATFORM,
            platformGatewayUrl = "https://brand.example/ai/",
            accessToken = "account-jwt",
        )
        val route = AiRequestTransportPolicy.apply(
            access = access,
            byokRoute = AiTransportRoute(
                apiBase = "https://third-party.example/v1",
                apiKey = "user-byok-secret",
                customHeaders = mapOf(
                    "Authorization" to "Bearer user-override",
                    "X-Provider" to "private-provider",
                ),
                protocolType = "anthropic",
                wireApi = "responses",
                routeTag = "custom_openai_compat",
            ),
        )

        assertEquals("https://third-party.example/v1", route.apiBase)
        assertEquals("user-byok-secret", route.apiKey)
        assertEquals("Bearer user-override", route.customHeaders["Authorization"])
        assertEquals("anthropic", route.protocolType)
        assertEquals("responses", route.wireApi)
        assertEquals("custom_openai_compat", route.routeTag)
    }

    @Test
    fun explicitlySelectedOfficialRouteDropsEveryByokOverride() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = AiAccessMode.BYOK,
            platformGatewayUrl = "https://brand.example/ai/",
            accessToken = "account-jwt",
        )
        val route = AiRequestTransportPolicy.apply(
            access = access,
            byokRoute = AiTransportRoute(
                apiBase = "https://brand.example/ai",
                apiKey = "must-not-survive",
                customHeaders = mapOf("X-Provider" to "must-not-survive"),
                protocolType = "anthropic",
                wireApi = "responses",
                routeTag = AiRequestTransportPolicy.PLATFORM_ROUTE_TAG,
            ),
        )

        assertEquals("https://brand.example/ai", route.apiBase)
        assertEquals("account-jwt", route.apiKey)
        assertTrue(route.customHeaders.isEmpty())
        assertEquals("openai_compatible", route.protocolType)
        assertEquals("chat_completions", route.wireApi)
        assertTrue(AiRequestTransportPolicy.isPlatformRoute(route.routeTag))
    }

    @Test
    fun byokModeLeavesDeviceRouteUntouched() {
        val byok = AiTransportRoute(
            apiBase = "https://third-party.example/v1",
            apiKey = "user-byok-secret",
            customHeaders = mapOf("X-Provider" to "custom"),
            protocolType = "anthropic",
            wireApi = "chat_completions",
            routeTag = "custom_openai_compat",
        )

        val route = AiRequestTransportPolicy.apply(
            access = AiRequestAccess(mode = AiAccessMode.BYOK),
            byokRoute = byok,
        )

        assertEquals(byok, route)
    }

    @Test
    fun platformModeRejectsCleartextGatewayBeforeAttachingAccountToken() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = AiAccessMode.PLATFORM,
            platformGatewayUrl = "http://gateway.example.com",
            accessToken = "account-jwt",
        )

        assertTrue(!access.usesPlatform)
        assertEquals(null, access.bearerToken)
        assertTrue(!access.unavailableReason.isNullOrBlank())
    }

    @Test
    fun loopbackHttpIsAvailableOnlyWhenExplicitlyEnabledForDebug() {
        val production = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = AiAccessMode.PLATFORM,
            platformGatewayUrl = "http://127.0.0.1:8080",
            accessToken = "account-jwt",
        )
        val debug = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = AiAccessMode.PLATFORM,
            platformGatewayUrl = "http://127.0.0.1:8080",
            accessToken = "account-jwt",
            allowInsecureLoopback = true,
        )

        assertTrue(!production.usesPlatform)
        assertTrue(debug.usesPlatform)
    }
}
