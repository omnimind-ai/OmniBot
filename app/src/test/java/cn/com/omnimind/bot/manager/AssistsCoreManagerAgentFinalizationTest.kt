package cn.com.omnimind.bot.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistsCoreManagerAgentFinalizationTest {
    @Test
    fun `keeps streamed assistant text when agent errors after visible output`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "已生成正文😀",
            error = "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals("已生成正文😀", resolution.text)
        assertFalse(resolution.persistAsError)
    }

    @Test
    fun `falls back to error details when no assistant text was streamed`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "",
            error = "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals(
            "Agent execution failed: length=140; regionStart=0; bytePairLength=138",
            resolution.text
        )
        assertTrue(resolution.persistAsError)
    }

    @Test
    fun `uses localized fallback when streamed text and error details are blank`() {
        val resolution = resolveAgentFinalErrorResolution(
            streamed = "",
            error = "",
            localizedFallback = "暂时无法生成回复，请重试。"
        )

        assertEquals("暂时无法生成回复，请重试。", resolution.text)
        assertTrue(resolution.persistAsError)
    }

}
