package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AcpLegacyCompatibilityAdapterTest {
    @Test
    fun `legacy lifecycle methods enter the official session surface`() {
        val cases = mapOf(
            AcpLegacyCompatibilityAdapter.THREAD_START to "session/new",
            AcpLegacyCompatibilityAdapter.THREAD_RESUME to "session/resume",
            AcpLegacyCompatibilityAdapter.THREAD_READ to "session/load",
            AcpLegacyCompatibilityAdapter.THREAD_LIST to "session/list",
            AcpLegacyCompatibilityAdapter.THREAD_ARCHIVE to "session/archive",
            AcpLegacyCompatibilityAdapter.THREAD_UNARCHIVE to "session/unarchive",
            AcpLegacyCompatibilityAdapter.THREAD_NAME_SET to "session/name/set",
            AcpLegacyCompatibilityAdapter.TURN_START to "session/prompt",
            AcpLegacyCompatibilityAdapter.TURN_INTERRUPT to "session/cancel",
        )

        cases.forEach { (legacyMethod, canonicalMethod) ->
            val request = AcpLegacyCompatibilityAdapter.adapt(
                legacyMethod,
                mapOf("threadId" to "session-1"),
            )
            assertEquals(canonicalMethod, request.method)
            assertEquals(legacyMethod, request.legacyMethod)
            assertEquals("session-1", request.args["threadId"])
        }
    }

    @Test
    fun `steering is rejected because ACP has no equivalent`() {
        assertThrows(UnsupportedOperationException::class.java) {
            AcpLegacyCompatibilityAdapter.adapt(
                AcpLegacyCompatibilityAdapter.TURN_STEER,
                mapOf("turnId" to "turn-1"),
            )
        }
    }

    @Test
    fun `canonical methods do not cross the compatibility adapter`() {
        val request = AcpLegacyCompatibilityAdapter.adapt(
            "session/prompt",
            mapOf("sessionId" to "session-1"),
        )

        assertEquals("session/prompt", request.method)
        assertNull(request.legacyMethod)
        assertEquals(false, AcpLegacyCompatibilityAdapter.isLegacyMethod(request.method))
    }
}
