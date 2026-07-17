package cn.com.omnimind.bot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(
            "/root/file ",
            AlpineFileSystemService.normalizeAbsolutePath("/root/file ")
        )
        assertEquals(
            "/root/a\\b",
            AlpineFileSystemService.normalizeAbsolutePath("/root/a\\b")
        )
    }

    @Test
    fun editableUtf8DecoderRejectsBinaryAndMalformedInput() {
        assertEquals(
            "配置内容",
            AlpineFileSystemService.decodeEditableUtf8(
                "配置内容".toByteArray(StandardCharsets.UTF_8)
            )
        )
        assertNull(
            AlpineFileSystemService.decodeEditableUtf8(
                byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00)
            )
        )
        assertNull(
            AlpineFileSystemService.decodeEditableUtf8(
                byteArrayOf(0xC3.toByte(), 0x28)
            )
        )
    }

    @Test
    fun listCommandFollowsOnlyTheRequestedDirectorySymlink() {
        val command = AlpineFileSystemService.buildListCommand("/var/run")

        assertTrue(command.contains("find -H \"\$target\""))
        assertTrue(command.contains("[ -L \"\$item\" ]"))
    }

    @Test
    fun moveCommandRejectsExistingFilesDirectoriesAndBrokenLinks() {
        val command = AlpineFileSystemService.buildMoveCommand(
            "/root/source ",
            "/root/a\\b"
        )

        assertTrue(command.contains("[ -e \"\$target\" ] || [ -L \"\$target\" ]"))
        assertTrue(command.contains("Target already exists"))
        assertTrue(command.contains("source='/root/source '"))
        assertTrue(command.contains("target='/root/a\\b'"))
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
