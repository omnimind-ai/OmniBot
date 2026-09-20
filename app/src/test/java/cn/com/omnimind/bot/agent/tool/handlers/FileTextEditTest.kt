package cn.com.omnimind.bot.agent.tool.handlers

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FileTextEditTest {
    @Test fun `no change fails without rewriting then corrected edit succeeds`() {
        val file = File.createTempFile("file-edit", ".txt")
        try {
            file.writeText("old old")
            assertTrue(file.setLastModified(1_600_000_000_000L))
            val timestamp = file.lastModified()
            try {
                applyFileTextEdit(file, "old", "old", false)
                fail("Identical replacement must not report an update")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("文件未变化"))
            }
            assertEquals("old old", file.readText())
            assertEquals(timestamp, file.lastModified())
            applyFileTextEdit(file, "old", "new", false)
            assertEquals("new old", file.readText())
            applyFileTextEdit(file, "old", "", true)
            assertEquals("new ", file.readText())
        } finally { file.delete() }
    }

    @Test fun `replace all changes every match and missing text preserves file`() {
        val file = File.createTempFile("file-edit", ".txt")
        try {
            file.writeText("a a")
            applyFileTextEdit(file, "a", "b", true)
            assertEquals("b b", file.readText())
            try {
                applyFileTextEdit(file, "missing", "value", false)
                fail("Missing original must fail")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("未找到"))
            }
            assertEquals("b b", file.readText())
        } finally { file.delete() }
    }
}
