package cn.com.omnimind.bot.agent

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentWorkspaceAttachmentSupportTest {
    @Test
    fun readsSmallAttachmentWithoutLoadingAnUnboundedFile() {
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
    fun rejectsAttachmentLargerThanAcpInputLimit() {
        val file = File.createTempFile("agent-attachment-large", ".bin")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(MAX_AGENT_ATTACHMENT_BYTES + 1L)
            }

            assertThrows(AgentAttachmentPreparationException::class.java) {
                readAgentAttachmentBytes(file)
            }
        } finally {
            file.delete()
        }
    }
}
