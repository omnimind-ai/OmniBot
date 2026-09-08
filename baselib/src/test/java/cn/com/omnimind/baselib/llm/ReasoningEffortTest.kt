package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningEffortTest {
    @Test
    fun `canonicalizes legacy aliases without accepting unknown provider ids`() {
        assertEquals(ReasoningEffort.NONE, ReasoningEffort.normalize("no"))
        assertEquals(ReasoningEffort.NONE, ReasoningEffort.normalize("off"))
        assertEquals(ReasoningEffort.MEDIUM, ReasoningEffort.normalize("med"))
        assertEquals(ReasoningEffort.XHIGH, ReasoningEffort.normalize("x-high"))
        assertNull(ReasoningEffort.normalize("provider-specific-level"))
    }
}
