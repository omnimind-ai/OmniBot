package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebPermissionPolicyTest {
    @Test
    fun permitsOnlyCameraAndMicrophoneForStrictHttpsOrigins() {
        val combined = BrowserWebPermissionPolicy.evaluateWebPermission(
            rawOrigin = "https://Example.com:443/",
            requestedResources = arrayOf(
                BrowserWebPermissionPolicy.RESOURCE_VIDEO_CAPTURE,
                BrowserWebPermissionPolicy.RESOURCE_AUDIO_CAPTURE,
            ),
        )

        assertNotNull(combined)
        assertEquals("https://example.com", combined?.origin?.normalizedOrigin)
        assertEquals(
            listOf(
                BrowserWebPermissionPolicy.CAPABILITY_CAMERA,
                BrowserWebPermissionPolicy.CAPABILITY_MICROPHONE,
            ),
            combined?.capabilities,
        )
        assertNotNull(
            BrowserWebPermissionPolicy.evaluateWebPermission(
                "https://camera.example",
                arrayOf(BrowserWebPermissionPolicy.RESOURCE_VIDEO_CAPTURE),
            ),
        )
        assertNotNull(
            BrowserWebPermissionPolicy.evaluateWebPermission(
                "https://voice.example",
                arrayOf(BrowserWebPermissionPolicy.RESOURCE_AUDIO_CAPTURE),
            ),
        )
    }

    @Test
    fun rejectsEmptyUnknownDrmAndMalformedOriginRequests() {
        val video = BrowserWebPermissionPolicy.RESOURCE_VIDEO_CAPTURE
        listOf<Array<String>?>(
            null,
            emptyArray(),
            arrayOf(""),
            arrayOf("android.webkit.resource.PROTECTED_MEDIA_ID"),
            arrayOf(video, "android.webkit.resource.MIDI_SYSEX"),
        ).forEach { resources ->
            assertNull(
                BrowserWebPermissionPolicy.evaluateWebPermission(
                    "https://example.com",
                    resources,
                ),
            )
        }

        listOf(
            null,
            "",
            "http://example.com",
            "http://127.0.0.1",
            "https://user@example.com",
            "https:///missing-host",
            "https://example.com/path",
            "https://example.com?query=1",
            "https://example.com#fragment",
            "javascript:alert(1)",
        ).forEach { origin ->
            assertNull(
                BrowserWebPermissionPolicy.evaluateWebPermission(origin, arrayOf(video)),
            )
            assertNull(BrowserWebPermissionPolicy.evaluateGeolocation(origin))
        }
    }

    @Test
    fun geolocationIsPerRequestAndNeverAcceptsStaleResponse() {
        val origin = BrowserWebPermissionPolicy.evaluateGeolocation("https://maps.example:8443")
        assertEquals("https://maps.example:8443", origin?.normalizedOrigin)
        assertTrue(
            BrowserWebPermissionPolicy.approvalMatchesCurrentRequest(
                expectedRequestId = "request-1",
                responseRequestId = "request-1",
                expectedTabId = 7,
                currentTabId = 7,
                expectedNavigationGeneration = 3,
                currentNavigationGeneration = 3,
            ),
        )
        assertFalse(
            BrowserWebPermissionPolicy.approvalMatchesCurrentRequest(
                "request-1",
                "old-request",
                7,
                7,
                3,
                3,
            ),
        )
        assertFalse(
            BrowserWebPermissionPolicy.approvalMatchesCurrentRequest(
                "request-1",
                "request-1",
                7,
                7,
                3,
                4,
            ),
        )
        assertFalse(
            BrowserWebPermissionPolicy.approvalMatchesCurrentRequest(
                "request-1",
                "request-1",
                7,
                null,
                3,
                null,
            ),
        )
    }
}
