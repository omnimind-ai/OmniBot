package cn.com.omnimind.bot.agent

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceMemoryShortHistoryIndexTest {
    @Test
    fun `semantic memory index includes every persisted short-memory day`() {
        val directory = Files.createTempDirectory("short-memory-index").toFile()
        try {
            val files = (1..31).map { day ->
                File(directory, "24-01-${day.toString().padStart(2, '0')}.md")
                    .apply { writeText("memory for day $day") }
            }.toTypedArray()

            val indexed = shortMemoryFilesForIndex(files)

            assertEquals(31, indexed.size)
            assertEquals("24-01-31.md", indexed.first().name)
            assertTrue(indexed.any { it.name == "24-01-01.md" })
        } finally {
            directory.deleteRecursively()
        }
    }
}
