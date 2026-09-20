package cn.com.omnimind.bot.omniflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionRegistryTest {
    @Test
    fun `repeated stop dispatches cancellation once and next run remains usable`() {
        val registry = ExecutionRegistry()
        var stopCount = 0
        val first = registry.begin("gui-first") { stopCount++ }
        assertTrue(registry.stop("gui-first"))
        assertFalse(registry.stop("gui-first"))
        registry.end(first)
        val second = registry.begin("gui-second") { stopCount++ }
        // Late cleanup of the first task must not unregister the current task.
        registry.end(first)
        assertFalse(registry.stop("gui-first"))
        assertTrue(registry.stop("gui-second"))
        assertEquals(2, stopCount)
        registry.end(second)
        assertFalse(registry.stop())
    }

    @Test
    fun `parent run id stops active recall child`() {
        val registry = ExecutionRegistry()
        var stopped = false
        registry.begin("gui-123-recall") { stopped = true }

        assertTrue(registry.stop("gui-123"))
        assertTrue(stopped)
    }

    @Test
    fun `unrelated run id cannot stop active execution`() {
        val registry = ExecutionRegistry()
        var stopped = false
        registry.begin("gui-123-recall") { stopped = true }

        assertFalse(registry.stop("gui-456"))
        assertFalse(stopped)
    }
}
