package cn.com.omnimind.baselib.util

import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.http.interceptor.HeaderInterceptor
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OkHttpPrivacyHeadersTest {
    @Test
    fun appVersionHeadersContainNoDeviceMetadata() {
        val headers = OkHttpManager.buildAppVersionHeaders(
            appVersionInfo = mapOf(
                "versionName" to "1.2.3",
                "versionCode" to 42L,
                "platform" to "android",
                "manufacturer" to "sensitive-manufacturer",
                "model" to "sensitive-model",
                "fingerprint" to "sensitive-fingerprint",
                "host" to "sensitive-build-host",
                "user" to "sensitive-build-user"
            ),
            isDebug = false
        )

        assertEquals(
            setOf("App-Version-Name", "App-Version-Code", "App-Platform", "App-IsDebug"),
            headers.keys
        )
        assertFalse(headers.keys.any(HeaderInterceptor::isLegacyDeviceMetadataHeader))
        assertFalse(headers.values.any { it.startsWith("sensitive-") })
    }

    @Test
    fun interceptorRemovesLegacyDeviceHeadersAndPreservesProviderHeaders() {
        val original = Request.Builder()
            .url("https://byok.example.test/v1/chat")
            .header("Authorization", "Bearer user-configured-token")
            .header("X-Provider-Header", "provider-value")
            .header("App-Device-Model", "sensitive-model")
            .header("APP-Other-Info", "fingerprint=sensitive-fingerprint")
            .build()

        val sanitized = HeaderInterceptor.sanitizeRequest(
            originalRequest = original,
            appVersionHeaders = mapOf(
                "App-Version-Name" to "1.2.3",
                "App-Device-Fingerprint" to "must-not-be-added",
                "APP-Other-Info" to "must-not-be-added"
            )
        )

        assertNull(sanitized.header("App-Device-Model"))
        assertNull(sanitized.header("App-Device-Fingerprint"))
        assertNull(sanitized.header("APP-Other-Info"))
        assertEquals("Bearer user-configured-token", sanitized.header("Authorization"))
        assertEquals("provider-value", sanitized.header("X-Provider-Header"))
        assertEquals("1.2.3", sanitized.header("App-Version-Name"))
    }
}
