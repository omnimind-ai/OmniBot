package cn.com.omnimind.baselib.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialEndpointSecurityTest {
    @Test
    fun acceptsOnlyCleanHttpsBaseUrlsInProduction() {
        assertEquals(
            "https://account.example.com",
            OfficialEndpointSecurity.normalizeBaseUrl(
                raw = "https://account.example.com/",
                label = "account",
            ),
        )

        listOf(
            "http://account.example.com",
            "https://user:password@account.example.com",
            "https://account.example.com?token=embedded",
            "https://account.example.com#fragment",
            "not a URL",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                OfficialEndpointSecurity.normalizeBaseUrl(endpoint, "account")
            }
            assertFalse(OfficialEndpointSecurity.isAllowed(endpoint))
        }
    }

    @Test
    fun debugLoopbackExceptionDoesNotPermitLanHosts() {
        assertEquals(
            "http://127.0.0.1:8080",
            OfficialEndpointSecurity.normalizeBaseUrl(
                raw = "http://127.0.0.1:8080",
                label = "account",
                allowInsecureLoopback = true,
            ),
        )
        assertFalse(
            OfficialEndpointSecurity.isAllowed(
                "http://192.168.1.20:8080",
                allowInsecureLoopback = true,
            )
        )
    }
}
