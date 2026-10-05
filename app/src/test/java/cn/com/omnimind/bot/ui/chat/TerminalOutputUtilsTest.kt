package cn.com.omnimind.bot.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `terminal_output_utils_test.dart`. */
class TerminalOutputUtilsTest {
    @Test
    fun `terminal detail output preserves every persisted line`() {
        val output = List(1_001) { index -> "line-$index ${"x".repeat(80)}" }.joinToString("\n")

        val displayed = TerminalOutputUtils.buildDisplayOutput(
            terminalOutput = output,
            rawResultJson = "",
            resultPreviewJson = "",
        )

        assertTrue(displayed.contains("line-0"))
        assertTrue(displayed.contains("line-1000"))
        assertEquals(output.length, displayed.length)
    }

    @Test
    fun `terminal detail transcript receives complete output`() {
        val output = List(1_001) { index -> "line-$index" }.joinToString("\n")

        val transcript = buildAgentToolTranscript(
            mapOf(
                "toolType" to "terminal",
                "toolName" to "terminal_execute",
                "terminalOutput" to output,
            ),
        )

        assertTrue(transcript.outputText.contains("line-0"))
        assertTrue(transcript.outputText.contains("line-1000"))
        assertEquals(output.length, transcript.outputText.length)
    }
}
