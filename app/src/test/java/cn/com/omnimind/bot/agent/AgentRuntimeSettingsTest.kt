package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimeSettingsTest {
    @Test
    fun `settings round trip preserves configured values`() {
        val original = AgentRuntimeSettings(
            maxModelRounds = 12,
            maxCompletionTokens = 8192,
            streamIdleTimeoutMs = 30_000,
            maxToolResultChars = 24_000,
            terminalTimeoutSeconds = 900,
            browserActionTimeoutMs = 45_000,
            fileReadMaxChars = 100_000,
            fileListLimit = 500,
            fileListMaxDepth = 12,
            skillsListLimit = 100,
        )

        assertEquals(original, AgentRuntimeSettings.fromJson(AgentRuntimeSettingsStore.toJson(original)))
    }

    @Test
    fun `non-positive settings mean unlimited or unset`() {
        val settings = AgentRuntimeSettings.fromJson(
            """
            {
              "maxModelRounds": 0,
              "maxCompletionTokens": -1,
              "streamIdleTimeoutMs": 0,
              "fileReadMaxChars": -10,
              "browserActionTimeoutMs": 0
            }
            """.trimIndent()
        )

        assertNull(settings.maxModelRounds)
        assertNull(settings.maxCompletionTokens)
        assertNull(settings.streamIdleTimeoutMs)
        assertNull(settings.fileReadMaxChars)
        assertNull(settings.browserActionTimeoutMs)
    }
}
