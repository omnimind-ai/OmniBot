package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpModelsSecurityTest {
    @Test
    fun payloadReportsCredentialStatusWithoutReturningSecret() {
        val payload = RemoteMcpServerConfig(
            id = "server",
            name = "Remote",
            endpointUrl = "https://mcp.example.com/sse",
            bearerToken = "sensitive-value",
        ).toMap()

        assertEquals("", payload["bearerToken"])
        assertTrue(payload["hasBearerToken"] == true)
        assertFalse(payload.values.any { it == "sensitive-value" })
    }

    @Test
    fun clearIntentIsAcceptedOnlyAsAnExplicitFlag() {
        val config = RemoteMcpServerConfig.fromMap(
            mapOf(
                "name" to "Remote",
                "endpointUrl" to "https://mcp.example.com/sse",
                "bearerToken" to "",
                "clearBearerToken" to true,
            )
        )

        assertEquals("", config.bearerToken)
        assertTrue(config.clearBearerToken)
    }

    @Test
    fun blankReplacementRetainsCredentialUnlessClearIsExplicit() {
        assertEquals(
            "existing",
            RemoteMcpConfigStore.resolveBearerTokenForUpdate(
                existing = "existing",
                replacement = "",
                clear = false,
            ),
        )
        assertEquals(
            "",
            RemoteMcpConfigStore.resolveBearerTokenForUpdate(
                existing = "existing",
                replacement = "",
                clear = true,
            ),
        )
        assertEquals(
            "replacement",
            RemoteMcpConfigStore.resolveBearerTokenForUpdate(
                existing = "existing",
                replacement = " replacement ",
                clear = true,
            ),
        )
    }

    @Test
    fun consentIsBoundToEndpointOriginAndCurrentGeneration() {
        val config = RemoteMcpServerConfig(
            id = "server",
            name = "Remote",
            endpointUrl = "https://mcp.example.com/sse",
            generation = 3L,
            consentVersion = 1,
            consentOrigin = "https://mcp.example.com:443",
            consentRevision = 3L,
        )

        assertTrue(RemoteMcpConfigStore.hasCurrentConsent(config))
        assertTrue(
            RemoteMcpConfigStore.matchesExpected(
                config,
                "https://mcp.example.com/sse",
                3L,
            )
        )
        assertFalse(
            RemoteMcpConfigStore.matchesExpected(
                config,
                "https://other.example.com/sse",
                3L,
            )
        )
        assertFalse(
            RemoteMcpConfigStore.matchesExpected(
                config,
                "https://mcp.example.com/sse",
                2L,
            )
        )
        assertFalse(RemoteMcpConfigStore.hasCurrentConsent(config.copy(generation = 4L)))
    }

    @Test
    fun flutterPayloadNeverExposesConsentOrigin() {
        val payload = RemoteMcpServerConfig(
            id = "server",
            name = "Remote",
            endpointUrl = "https://mcp.example.com/sse",
            generation = 3L,
            consentVersion = 1,
            consentOrigin = "https://mcp.example.com:443",
            consentRevision = 3L,
        ).toMap()

        assertFalse(payload.containsKey("consentOrigin"))
        assertEquals(3L, payload["generation"])
        assertEquals(1, payload["consentVersion"])
        assertEquals(3L, payload["consentRevision"])
    }
}
