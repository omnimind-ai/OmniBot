package cn.com.omnimind.baselib.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialEndpointSecurityTest {
    @After
    fun resetDebugPolicy() {
        CredentialEndpointSecurity.configureDebugLoopback(false)
    }

    @Test
    fun credentialsRequireEncryptedTransport() {
        assertEquals(
            "https://provider.example/v1",
            CredentialEndpointSecurity.requireSafe(
                "https://provider.example/v1",
                hasCredential = true,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEndpointSecurity.requireSafe(
                "http://provider.example/v1",
                hasCredential = true,
            )
        }
    }

    @Test
    fun debugExceptionAcceptsOnlyLiteralLoopback() {
        assertEquals(
            "http://127.0.0.1:8080/v1",
            CredentialEndpointSecurity.requireSafe(
                "http://127.0.0.1:8080/v1",
                hasCredential = true,
                allowInsecureLoopback = true,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CredentialEndpointSecurity.requireSafe(
                "http://192.168.1.10:8080/v1",
                hasCredential = true,
                allowInsecureLoopback = true,
            )
        }
        assertTrue(CredentialEndpointSecurity.isLiteralLoopback("::1"))
        assertFalse(CredentialEndpointSecurity.isLiteralLoopback("localhost"))
        assertFalse(CredentialEndpointSecurity.isLiteralLoopback("localhost.example"))
        assertFalse(CredentialEndpointSecurity.isLiteralLoopback("127.999.1.1"))
    }

    @Test
    fun embeddedCredentialsFragmentsAndMalformedUrlsAreRejected() {
        listOf(
            "https://user:password@provider.example/v1",
            "https://provider.example/v1#token",
            "https://provider.example/v1?api_key=embedded-secret",
            "https://provider.example/v1?api%5Fkey=embedded-secret",
            "https://provider.example/v1?foo=1;access-token=embedded-secret",
            "",
            "   ",
            "/v1",
            "//provider.example/v1",
            "not a URL",
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                CredentialEndpointSecurity.requireSafe(url, hasCredential = true)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            CredentialEndpointSecurity.requireSafe(
                "https://provider.example/v1?token=embedded-secret",
                hasCredential = false,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            CredentialEndpointSecurity.requireSafe(
                "http://127.0.0.1:8080/v1",
                hasCredential = true,
                allowInsecureLoopback = false,
            )
        }
    }
}
