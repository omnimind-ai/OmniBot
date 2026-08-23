package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniVlmMaxStepsTest {
    @Test
    fun `VLM defaults to the official twenty model steps`() {
        val request = OmniVlmPlugin.Request(goal = "open settings")

        assertEquals(20, request.maxSteps)
        assertEquals(20, request.runGuiArguments()["max_steps"])
    }
}
