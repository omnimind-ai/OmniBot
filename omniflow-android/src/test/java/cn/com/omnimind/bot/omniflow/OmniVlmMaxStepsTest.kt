package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniVlmMaxStepsTest {
    @Test
    fun `VLM does not inject a host step limit by default`() {
        val request = OmniVlmPlugin.Request(goal = "open settings")

        assertEquals(null, request.maxSteps)
        assertEquals(false, request.runGuiArguments().containsKey("max_steps"))
    }
}
