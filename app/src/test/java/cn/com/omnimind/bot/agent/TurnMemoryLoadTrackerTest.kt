package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.workspace.memory.TurnMemoryLoadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnMemoryLoadTrackerTest {

    @Test
    fun `markLoaded with single id is tracked`() {
        val tracker = TurnMemoryLoadTracker()
        tracker.markLoaded("memory-1")
        assertTrue(tracker.isLoaded("memory-1"))
        assertFalse(tracker.isLoaded("memory-2"))
    }

    @Test
    fun `markLoaded with collection is tracked`() {
        val tracker = TurnMemoryLoadTracker()
        tracker.markLoaded(listOf("a", "b", "c"))
        assertTrue(tracker.isLoaded("a"))
        assertTrue(tracker.isLoaded("b"))
        assertTrue(tracker.isLoaded("c"))
        assertEquals(setOf("a", "b", "c"), tracker.loadedIds())
    }

    @Test
    fun `blank ids are ignored`() {
        val tracker = TurnMemoryLoadTracker()
        tracker.markLoaded("")
        tracker.markLoaded(listOf("", " ", "real"))
        assertEquals(setOf("real"), tracker.loadedIds())
    }

    @Test
    fun `reset clears tracking`() {
        val tracker = TurnMemoryLoadTracker()
        tracker.markLoaded(listOf("a", "b"))
        tracker.reset()
        assertFalse(tracker.isLoaded("a"))
        assertTrue(tracker.loadedIds().isEmpty())
    }

    @Test
    fun `dedup via add semantics`() {
        val tracker = TurnMemoryLoadTracker()
        tracker.markLoaded("dup")
        tracker.markLoaded("dup")
        tracker.markLoaded("dup")
        assertEquals(1, tracker.loadedIds().size)
    }

    @Test
    fun `markLoadedIfAbsent is atomic for duplicate loads`() {
        val tracker = TurnMemoryLoadTracker()
        assertTrue(tracker.markLoadedIfAbsent("same-entry"))
        assertFalse(tracker.markLoadedIfAbsent("same-entry"))
    }
}
