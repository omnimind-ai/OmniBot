package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dart `_ContextThresholdSheet._parseInput` (5e-6). */
class ContextThresholdTest {
    private fun error(raw: String) = (parseContextThreshold(raw).exceptionOrNull() as ThresholdInputException).error

    @Test
    fun `a positive integer is accepted after trimming`() {
        assertEquals(128_000, parseContextThreshold(" 128000 ").getOrThrow())
    }

    @Test
    fun `empty, non-integer and non-positive input are refused`() {
        assertEquals(ThresholdInputError.Empty, error("  "))
        assertEquals(ThresholdInputError.NotInteger, error("12.5"))
        assertEquals(ThresholdInputError.NotInteger, error("12k"))
        assertEquals(ThresholdInputError.NotPositive, error("0"))
        assertEquals(ThresholdInputError.NotPositive, error("-5"))
    }
}
