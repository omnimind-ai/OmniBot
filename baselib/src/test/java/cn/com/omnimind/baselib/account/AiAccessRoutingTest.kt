package cn.com.omnimind.baselib.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun platformModeRequiresSynchronizedAccountChoice() {
        val access = AiRequestAccessResolver.resolve(
            accountConfigured = true,
            signedIn = true,
            cachedMode = null,
            platformGatewayUrl = "https://brand.example/ai",
            accessToken = "account-jwt",
        )

        assertNull(access.mode)
        assertTrue(!access.unavailableReason.isNullOrBlank())
    }

    @Test
    fun platformModeDropsEveryByokCredentialAndProtocolOverride() {
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

        assertEquals("https://brand.example/ai", route.apiBase)
        assertEquals("account-jwt", route.apiKey)
        assertTrue(route.customHeaders.isEmpty())
        assertEquals("openai_compatible", route.protocolType)
        assertEquals("chat_completions", route.wireApi)
        assertEquals("platform_gateway", route.routeTag)
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
        assertNull(access.bearerToken)
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
