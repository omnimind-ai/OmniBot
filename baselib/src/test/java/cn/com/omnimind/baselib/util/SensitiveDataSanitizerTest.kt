package cn.com.omnimind.baselib.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataSanitizerTest {
    @Test
    fun removesCommonCredentialForms() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature"
        val raw = "Authorization: Bearer abc.def.ghi api_key=sk-example12345 token=$jwt"
        val sanitized = SensitiveDataSanitizer.sanitize(raw)

        assertFalse(sanitized.contains("abc.def.ghi"))
        assertFalse(sanitized.contains("sk-example12345"))
        assertFalse(sanitized.contains(jwt))
        assertTrue(sanitized.contains(SensitiveDataSanitizer.redactedMarker()))
    }

    @Test
    fun removesUrlCredentialsAndLimitsLength() {
        val sanitized = SensitiveDataSanitizer.sanitize(
            "https://user:password@example.test/path?access_token=secret-value " + "x".repeat(200),
            maxChars = 80,
        )

        assertFalse(sanitized.contains("password"))
        assertFalse(sanitized.contains("secret-value"))
        assertTrue(sanitized.endsWith("…[truncated]"))
    }

    @Test
    fun removesSessionIdentifiersAndCookies() {
        val sanitized = SensitiveDataSanitizer.sanitize(
            "sessionKey=abc123 device_id=device-123 Cookie: sid=secret; theme=dark"
        )

        assertFalse(sanitized.contains("abc123"))
        assertFalse(sanitized.contains("device-123"))
        assertFalse(sanitized.contains("sid=secret"))
        assertTrue(sanitized.contains(SensitiveDataSanitizer.redactedMarker()))
    }
}
