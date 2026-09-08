package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.tool.handlers.resolveSubagentConcurrency
import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentConcurrencyTest {
    @Test
    fun `omitted concurrency runs every requested subtask without a static default`() {
        assertEquals(5, resolveSubagentConcurrency(requested = null, taskCount = 5))
    }

    @Test
    fun `explicit concurrency is passed through without an application maximum`() {
        assertEquals(23, resolveSubagentConcurrency(requested = 23, taskCount = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive requested concurrency is rejected as an invalid parameter`() {
        resolveSubagentConcurrency(requested = 0, taskCount = 2)
    }
}
