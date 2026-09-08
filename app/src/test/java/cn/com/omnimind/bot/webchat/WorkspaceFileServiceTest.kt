package cn.com.omnimind.bot.webchat

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileServiceTest {
    @Test
    fun `workspace listing has no implicit item or recursive depth cap`() {
        val root = Files.createTempDirectory("workspace-list-unbounded").toFile()
        try {
            repeat(1_001) { index ->
                root.resolve("entry-$index.txt").writeText("$index")
            }
            var nested = root
            repeat(8) { index ->
                nested = nested.resolve("level-$index").apply { mkdir() }
            }
            val deepest = nested.resolve("deepest.txt").apply { writeText("complete") }

            val direct = selectWorkspaceFiles(
                directory = root,
                recursive = false,
                maxDepth = null,
                limit = null
            )
            val recursive = selectWorkspaceFiles(
                directory = root,
                recursive = true,
                maxDepth = null,
                limit = null
            )

            assertEquals(1_002, direct.size)
            assertTrue(recursive.any { it.canonicalFile == deepest.canonicalFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `workspace text read keeps content beyond the former default cap unless requested`() {
        val content = "x".repeat(64_001)

        val complete = sliceWorkspaceFileText(
            content = content,
            maxChars = null,
            offset = 0,
            lineStart = null,
            lineCount = null
        )
        val explicitlyLimited = sliceWorkspaceFileText(
            content = content,
            maxChars = 12,
            offset = 0,
            lineStart = null,
            lineCount = null
        )

        assertEquals(64_001, complete.content.length)
        assertTrue(!complete.truncated)
        assertEquals("x".repeat(12), explicitlyLimited.content)
        assertTrue(explicitlyLimited.truncated)
    }
}
