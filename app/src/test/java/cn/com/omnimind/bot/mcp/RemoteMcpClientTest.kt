package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpClientTest {
    @Test
    fun resolvesRelativeSseEndpointOnSameOrigin() {
        assertEquals(
            "https://mcp.example.test/messages?id=1",
            RemoteMcpClient.resolveAgainstBase(
                "https://mcp.example.test/sse",
                "/messages?id=1",
            ),
        )
    }

    @Test
    fun rejectsCrossOriginSseEndpoint() {
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.resolveAgainstBase(
                "https://mcp.example.test/sse",
                "https://attacker.example/messages",
            )
        }
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.resolveAgainstBase(
                "https://mcp.example.test/sse",
                "//attacker.example/messages",
            )
        }
    }

    @Test
    fun rejectsSchemeOrPortChange() {
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.resolveAgainstBase(
                "https://mcp.example.test/sse",
                "http://mcp.example.test/messages",
            )
        }
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.resolveAgainstBase(
                "https://mcp.example.test/sse",
                "https://mcp.example.test:8443/messages",
            )
        }
    }

    @Test
    fun userContentRequiresHttpsEvenWithoutBearerExceptForExplicitDebugLoopback() {
        CredentialEndpointSecurity.configureDebugLoopback(false)
        assertEquals(
            "https://mcp.example.test/sse",
            RemoteMcpClient.validateEndpointSecurity(
                endpointUrl = "https://mcp.example.test/sse",
                bearerToken = "secret",
            ),
        )
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.validateEndpointSecurity(
                endpointUrl = "http://127.0.0.1:8080/sse",
                bearerToken = "",
            )
        }
        CredentialEndpointSecurity.configureDebugLoopback(true)
        assertEquals(
            "http://127.0.0.1:8080/sse",
            RemoteMcpClient.validateEndpointSecurity(
                endpointUrl = "http://127.0.0.1:8080/sse",
                bearerToken = "secret",
            ),
        )
        CredentialEndpointSecurity.configureDebugLoopback(false)
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.validateEndpointSecurity(
                endpointUrl = "http://mcp.example.test/sse",
                bearerToken = "",
            )
        }
    }

    @Test
    fun endpointUrlCredentialsAreRejected() {
        assertThrows(SecurityException::class.java) {
            RemoteMcpClient.validateEndpointSecurity(
                endpointUrl = "https://user:password@mcp.example.test/sse",
                bearerToken = "",
            )
        }
    }

    @Test
    fun transportMetadataIsRemovedRecursivelyBeforeModelOrHistorySerialization() {
        val token = "temporary-download-secret"
        val raw = mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to "File ready")),
            "_meta" to mapOf(
                "downloadHeaders" to mapOf("X-OmniBot-File-Token" to token),
            ),
            "nested" to listOf(
                mapOf(
                    "safe" to true,
                    "_meta" to mapOf("authorization" to token),
                )
            ),
        )

        val result = RemoteMcpClient.buildModelSafeCallResult(raw)
        val persistedOrModelVisible = listOf(
            result.summaryText,
            result.previewJson,
            result.rawResultJson,
        ).joinToString("\n")

        assertFalse(persistedOrModelVisible.contains("_meta"))
        assertFalse(persistedOrModelVisible.contains("X-OmniBot-File-Token"))
        assertFalse(persistedOrModelVisible.contains(token))
        assertTrue(persistedOrModelVisible.contains("File ready"))
        assertTrue(result.rawResultJson.contains("safe"))
    }
}
