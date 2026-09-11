package cn.com.omnimind.bot.agent.tool.handlers

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileListTraversalTest {
    @Test fun `recursive limit counts entries excluding the root`() = runBlocking {
        val root = Files.createTempDirectory("oob-list").toFile()
        try {
            File(root, "图片 with spaces.xml").writeText("first")
            File(root, "second.txt").writeText("second")
            assertEquals(1, listWorkspaceFiles(root, true, null, 1).size)
            assertEquals(2, listWorkspaceFiles(root, true, null, 2).size)
            assertEquals(2, listWorkspaceFiles(root, true, null, null).size)
            assertFalse(listWorkspaceFiles(root, true, null, null).contains(root))
        } finally { root.deleteRecursively() }
    }

    @Test fun `depth and nonrecursive listing retain their documented scope`() = runBlocking {
        val root = Files.createTempDirectory("oob-list").toFile()
        try {
            File(root, "folder").mkdir()
            File(root, "folder/nested.txt").writeText("nested")
            File(root, "top.txt").writeText("top")
            assertEquals(2, listWorkspaceFiles(root, true, 1, null).size)
            assertEquals(3, listWorkspaceFiles(root, true, 2, null).size)
            assertEquals(1, listWorkspaceFiles(root, false, null, 1).size)
            repeat(40) { assertEquals(3, listWorkspaceFiles(root, true, null, null).size) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `cancelled list throws even for an empty directory`() = runBlocking {
        val root = Files.createTempDirectory("oob-list").toFile()
        try {
            for (recursive in listOf(false, true)) {
                var returnedResult = false
                val job = async {
                    currentCoroutineContext().cancel()
                    listWorkspaceFiles(root, recursive, null, null)
                    returnedResult = true
                }
                try { job.await() } catch (_: CancellationException) { }
                assertFalse("Cancelled traversal returned a result", returnedResult)
            }
            assertTrue(listWorkspaceFiles(root, true, null, 1).isEmpty())
        } finally { root.deleteRecursively() }
    }
}
