package cn.com.omnimind.baselib.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ContentEndpointSecurityTest {
    @Test
    fun `https and wss are accepted without relying on a credential`() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            ContentEndpointSecurity.requireSafe("https://example.com/v1/chat/completions"),
        )
        assertEquals(
            "wss://example.com/codex",
            ContentEndpointSecurity.requireSafe("wss://example.com/codex"),
        )
    }

    @Test
    fun `plaintext public and lan endpoints are rejected even without a credential`() {
        listOf(
            "http://example.com/v1/chat/completions",
            "ws://192.168.1.10:18789",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                ContentEndpointSecurity.requireSafe(endpoint)
            }
        }
    }

    @Test
    fun `debug exemption is limited to literal loopback`() {
        assertEquals(
            "http://127.0.0.1:8080/mcp",
            ContentEndpointSecurity.requireSafe(
                "http://127.0.0.1:8080/mcp",
                allowInsecureLoopback = true,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ContentEndpointSecurity.requireSafe(
                "http://192.168.1.20:8080/mcp",
                allowInsecureLoopback = true,
            )
        }
        listOf(
            "http://localhost:8080/mcp",
            "http://localhost.example:8080/mcp",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                ContentEndpointSecurity.requireSafe(
                    endpoint,
                    allowInsecureLoopback = true,
                )
            }
        }
        assertFalse(CredentialEndpointSecurity.isLiteralLoopback("localhost"))
    }

    @Test
    fun `empty relative and malformed endpoints fail closed`() {
        listOf(
            "",
            "   ",
            "/v1/chat/completions",
            "example.com/v1/chat/completions",
            "not a URL",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                ContentEndpointSecurity.requireSafe(endpoint)
            }
        }
    }
}
