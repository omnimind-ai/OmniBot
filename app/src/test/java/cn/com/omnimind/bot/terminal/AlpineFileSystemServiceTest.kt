package cn.com.omnimind.bot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AlpineFileSystemServiceTest {
    @Test
    fun normalizeAbsolutePathCollapsesDotSegments() {
        assertEquals(
            "/root/.codex/config.toml",
            AlpineFileSystemService.normalizeAbsolutePath(
                "/root/project/../.codex/./config.toml/"
            )
        )
        assertEquals("/", AlpineFileSystemService.normalizeAbsolutePath("/../../"))
    }

    @Test
    fun parseListOutputDecodesNamesAndMetadata() {
        val path = encode("/root/.codex")
        val name = encode(".codex")
        val output = "1|0|0|4096|123456|700|1|1|$path|$name|\n"

        val entries = AlpineFileSystemService.parseListOutput(output)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("/root/.codex", entry["path"])
        assertEquals(".codex", entry["name"])
        assertTrue(entry["isDirectory"] as Boolean)
        assertFalse(entry["isFile"] as Boolean)
        assertEquals("700", entry["mode"])
        assertEquals(4096L, entry["size"])
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
