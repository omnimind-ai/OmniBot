package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderCustomHeadersTest {

    @Test
    fun decodeProfilesJson_legacyProfilesDefaultToEmptyCustomHeaders() {
        val profiles = ModelProviderConfigStore.decodeProfilesJson(
            """
                [
                  {
                    "id": "profile-1",
                    "name": "Provider 1",
                    "baseUrl": "https://example.com/v1",
                    "apiKey": "sk-test",
                    "protocolType": "openai_compatible",
                    "wireApi": "chat_completions"
                  }
                ]
            """.trimIndent()
        )

        assertEquals(1, profiles.size)
        assertTrue(profiles.first().customHeaders.isEmpty())
    }

    @Test
    fun encodeProfilesJson_roundTripsCustomHeaders() {
        val encoded = ModelProviderConfigStore.encodeProfilesJson(
            listOf(
                ModelProviderProfile(
                    id = "profile-1",
                    name = "Provider 1",
                    baseUrl = "https://example.com/v1",
                    apiKey = "sk-test",
                    customHeaders = linkedMapOf(
                        "HTTP-Referer" to "https://example.com",
                        "X-Title" to "OpenOmniBot"
                    )
                )
            )
        )

        val decoded = ModelProviderConfigStore.decodeProfilesJson(encoded)

        assertEquals(
            linkedMapOf(
                "HTTP-Referer" to "https://example.com",
                "X-Title" to "OpenOmniBot"
            ),
            decoded.first().customHeaders
        )
    }

    @Test
    fun encodeProfilesMetadataJson_excludesApiKeyAndCustomHeaders() {
        val encoded = ModelProviderConfigStore.encodeProfilesMetadataJson(
            listOf(
                ModelProviderProfile(
                    id = "profile-1",
                    name = "Provider 1",
                    baseUrl = "https://example.com/v1",
                    apiKey = "sk-plaintext-must-not-remain",
                    customHeaders = linkedMapOf(
                        "Authorization" to "Bearer custom-secret",
                        "X-Trace-Id" to "trace-1"
                    )
                )
            )
        )

        assertFalse(encoded.contains("sk-plaintext-must-not-remain"))
        assertFalse(encoded.contains("custom-secret"))
        assertFalse(encoded.contains("trace-1"))
        val decoded = ModelProviderConfigStore.decodeProfilesJson(encoded).single()
        assertTrue(decoded.apiKey.isEmpty())
        assertTrue(decoded.customHeaders.isEmpty())
        assertEquals("https://example.com/v1", decoded.baseUrl)
    }

    @Test
    fun mergeProfileSecrets_restoresEncryptedValuesInMemory() {
        val profile = ModelProviderProfile(
            id = "profile-1",
            name = "Provider 1",
            baseUrl = "https://example.com/v1"
        )

        val hydrated = ModelProviderConfigStore.mergeProfileSecrets(
            profile,
            ModelProviderSecrets(
                apiKey = "sk-encrypted",
                customHeaders = linkedMapOf("Authorization" to "Bearer encrypted")
            )
        )

        assertEquals("sk-encrypted", hydrated.apiKey)
        assertEquals("Bearer encrypted", hydrated.customHeaders["Authorization"])
    }

    @Test
    fun mergeHeaders_customHeadersOverrideBuiltInIgnoringCase() {
        val merged = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = linkedMapOf(
                "Authorization" to "Bearer default",
                "Content-Type" to "application/json"
            ),
            custom = linkedMapOf(
                "authorization" to "Bearer custom",
                "X-Trace-Id" to "trace-1"
            )
        )

        assertEquals("Bearer custom", merged["authorization"])
        assertFalse(merged.containsKey("Authorization"))
        assertEquals("application/json", merged["Content-Type"])
        assertEquals("trace-1", merged["X-Trace-Id"])
    }

    @Test
    fun sanitizeCustomHeaders_filtersForbiddenHeadersAndBlankNames() {
        val sanitized = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
            linkedMapOf(
                "" to "skip",
                "Host" to "blocked.example",
                " Content-Length " to "128",
                "X-Trace-Id" to "trace-1"
            )
        )

        assertEquals(mapOf("X-Trace-Id" to "trace-1"), sanitized)
    }

    @Test
    fun sanitizeCustomHeaders_rejectsLineBreaksForOfficialHarnessExport() {
        val sanitized = ProviderCustomHeaderUtils.sanitizeCustomHeaders(
            linkedMapOf(
                "X-Bad-Name\nInjected" to "value",
                "X-Bad-Value" to "safe\r\nInjected: yes",
                "X-Good" to "value",
            )
        )

        assertEquals(mapOf("X-Good" to "value"), sanitized)
    }

    @Test
    fun redactHeadersForLog_masksSensitiveHeaderValues() {
        val redacted = ProviderCustomHeaderUtils.redactHeadersForLog(
            linkedMapOf(
                "Authorization" to "Bearer sk-123",
                "x-api-key" to "secret-key",
                "Cookie" to "a=b",
                "X-Session-Token" to "token-123",
                "HTTP-Referer" to "https://example.com"
            )
        )

        assertEquals("***", redacted["Authorization"])
        assertEquals("***", redacted["x-api-key"])
        assertEquals("***", redacted["Cookie"])
        assertEquals("***", redacted["X-Session-Token"])
        assertEquals("https://example.com", redacted["HTTP-Referer"])
    }
}
