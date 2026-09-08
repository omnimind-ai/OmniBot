package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class McpFileInboxTest {
    @Test
    fun `inbox keeps more than the former twenty files and does not expire old user files`() {
        val records = inboxRecords()
        val files = (1..21).map { index ->
            Files.createTempFile("mcp-file-inbox-$index-", ".txt").toFile()
        }
        try {
            records.clear()
            val oldCreatedAt = System.currentTimeMillis() - 3 * 60 * 60 * 1000L
            files.forEachIndexed { index, file ->
                val id = "file-${index + 1}"
                records[id] = McpFileRecord(
                    id = id,
                    fileName = file.name,
                    mimeType = "text/plain",
                    sizeBytes = file.length(),
                    path = file.absolutePath,
                    createdAt = oldCreatedAt + index,
                    downloadToken = "",
                    tokenExpiresAt = 0L,
                )
            }

            val visible = McpFileInbox.list()

            assertEquals(21, visible.size)
            assertEquals((1..21).map { "file-$it" }.toSet(), visible.map { it.id }.toSet())
        } finally {
            records.clear()
            files.forEach { file -> file.delete() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun inboxRecords(): ConcurrentHashMap<String, McpFileRecord> {
        val field = McpFileInbox::class.java.getDeclaredField("records")
        field.isAccessible = true
        return field.get(McpFileInbox) as ConcurrentHashMap<String, McpFileRecord>
    }
}
