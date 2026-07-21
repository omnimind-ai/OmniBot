package cn.com.omnimind.bot.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun createFileCommandRejectsBrokenLinksAndUsesNoClobberRedirection() {
        val command = AlpineFileSystemService.buildCreateFileCommand("/root/new-file")

        assertTrue(command.contains("[ -e \"\$target\" ] || [ -L \"\$target\" ]"))
        assertTrue(command.contains("Target already exists"))
        assertTrue(command.contains("set -C"))
        assertTrue(command.contains(": > \"\$target\""))
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

    @Test
    fun parseListOutputDoesNotAliasInvalidUtf8WithReplacementCharacterName() {
        val invalidName = byteArrayOf(0xFF.toByte())
        val invalidPath = "/root/".toByteArray(StandardCharsets.UTF_8) + invalidName
        val replacementName = "\uFFFD"
        val output = buildString {
            append("0|1|0|1|0|600|1|1|")
            append(encode(invalidPath))
            append('|')
            append(encode(invalidName))
            append("|\n")
            append("0|1|0|1|0|600|1|1|")
            append(encode("/root/$replacementName"))
            append('|')
            append(encode(replacementName))
            append("|\n")
        }

        val entries = AlpineFileSystemService.parseListOutput(output)

        assertEquals(2, entries.size)
        val invalidEntry = entries[0]
        val replacementEntry = entries[1]
        assertFalse(invalidEntry["hasValidUtf8Path"] as Boolean)
        assertEquals("", invalidEntry["path"])
        assertEquals("", invalidEntry["name"])
        assertFalse(invalidEntry["readable"] as Boolean)
        assertFalse(invalidEntry["writable"] as Boolean)
        assertTrue(replacementEntry["hasValidUtf8Path"] as Boolean)
        assertEquals("/root/$replacementName", replacementEntry["path"])
        assertEquals(replacementName, replacementEntry["name"])
        assertNotEquals(invalidEntry["pathToken"], replacementEntry["pathToken"])
    }

    private fun encode(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun encode(value: ByteArray): String {
        return Base64.getEncoder().encodeToString(value)
    }
}
