package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortMemoryDeletionTest {
    private val entry = WorkspaceShortMemoryEntry("a", "2026-09-06", "08:00:00", "text", 0)

    @Test fun `snapshot selection preserves original positions and deduplicates requests`() {
        val second = entry.copy(id = "b")
        assertEquals(setOf(1), selectShortMemoryIndexes(listOf(entry, second), listOf(second, second)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `edited text is rejected before deletion`() {
        selectShortMemoryIndexes(listOf(entry.copy(content = "changed")), listOf(entry))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing target rejects the complete batch`() {
        selectShortMemoryIndexes(listOf(entry), listOf(entry, entry.copy(id = "missing")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ambiguous duplicate identity is rejected`() {
        selectShortMemoryIndexes(listOf(entry, entry), listOf(entry))
    }

    private fun remove(text: String, vararg indexes: Int) =
        removeShortMemoryBlocks(text, indexes.toSet()) { it.trim().startsWith("- [") }

    @Test fun `batch deletes original indexes without shifting targets`() {
        assertEquals("- [10:00] keep\n", remove("- [08:00] a\n- [09:00] b\n- [10:00] keep\n", 0, 1))
    }

    @Test fun `deletes only selected duplicate and its continuation`() {
        assertEquals("- [08:00] same\n# summary\nsummary text\n", remove(
            "- [08:00] same\n- [08:00] same\ncontinuation\n# summary\nsummary text\n", 1))
    }

    @Test fun `preserves metadata and unselected multiline blocks`() {
        assertEquals("# day\n- metadata\nmetadata body\n- [09:00] keep\nkeep body\n", remove(
            "# day\n- [08:00] delete\nprivate continuation\n- metadata\nmetadata body\n- [09:00] keep\nkeep body\n", 0))
    }

    @Test fun `empty selection is unchanged`() {
        val text = "# day\n- [08:00] memory\n"
        assertEquals(text, remove(text))
    }

    @Test fun `deleting last entry removes remaining private text`() {
        assertEquals("# day", remove("# day\n- [08:00] delete\nprivate continuation\n", 0))
    }
}
