package cn.com.omnimind.bot.agent

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AgentWorkspaceAttachmentSupportTest {
    @Test
    fun resolvesPersistedAcpWorkspaceReferenceBeforeReading() {
        val directory = java.nio.file.Files.createTempDirectory("attachment-roundtrip").toFile()
        try {
            val source = File(directory, "invoice.pdf").apply { writeText("invoice-body") }
            val workspace = mock(AgentWorkspaceManager::class.java)
            val reference = "/workspace/.omnibot/attachments/invoice.pdf"
            `when`(workspace.androidPathForShell(reference)).thenReturn(source)
            assertEquals("invoice-body", resolveAgentAttachmentFile(reference, workspace).readText())
            assertEquals(source, resolveAgentAttachmentFile(source.absolutePath, workspace))
        } finally {
            directory.deleteRecursively()
        }
    }
    @Test
    fun readsSmallAttachment() {
        val file = File.createTempFile("agent-attachment", ".bin")
        try {
            val expected = byteArrayOf(1, 2, 3, 4)
            file.writeBytes(expected)

            assertArrayEquals(expected, readAgentAttachmentBytes(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun readsAttachmentAboveTheFormerApplicationQuota() {
        val file = File.createTempFile("agent-attachment-large", ".bin")
        try {
            val length = 20L * 1024L * 1024L + 1L
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(length)
            }

            assertEquals(length, readAgentAttachmentBytes(file).size.toLong())
        } finally {
            file.delete()
        }
    }
}
