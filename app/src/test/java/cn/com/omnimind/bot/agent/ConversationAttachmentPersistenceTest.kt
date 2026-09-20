package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ConversationAttachmentPersistenceTest {
    @Test fun `stale snapshot after cache eviction retains durable bytes across reload`() {
        val root = Files.createTempDirectory("attachment-history").toFile()
        try {
            val cache = root.resolve("picker.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val workspace = root.resolve("workspace").apply { mkdirs() }
            val incoming = listOf(mapOf<String, Any?>("id" to "image-1", "path" to cache.path))
            var copies = 0
            val saved = persistConversationAttachments(incoming, emptyList()) { items ->
                copies++
                val target = workspace.resolve("image.png")
                cache.copyTo(target)
                listOf(items.single() + mapOf("path" to target.path,
                    "workspacePath" to "/workspace/image.png", "promptPath" to "/workspace/image.png"))
            }
            assertTrue(cache.delete())
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val reloaded: List<Map<String, Any?>> = Gson().fromJson(Gson().toJson(saved), type)
            val refreshed = persistConversationAttachments(incoming, reloaded) {
                error("Must not reread deleted picker cache")
            }
            assertEquals(1, copies)
            assertEquals(saved, refreshed)
            assertArrayEquals(byteArrayOf(1, 2, 3),
                readAgentAttachmentBytes(java.io.File(refreshed.single()["path"].toString())))
            assertTrue(persistConversationAttachments(emptyList(), reloaded) { it }.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `new attachment id never borrows old bytes by matching filename`() {
        val prior = listOf(mapOf<String, Any?>("id" to "old", "name" to "image.png",
            "path" to "/data/old", "workspacePath" to "/workspace/old"))
        val incoming = listOf(mapOf<String, Any?>("id" to "new", "name" to "image.png", "path" to "/cache/new"))
        var called = false
        val result = persistConversationAttachments(incoming, prior) { called = true; it }
        assertTrue(called)
        assertEquals("/cache/new", result.single()["path"])
    }

    @Test fun `missing owned file fails but remote workspace reference is left to Harness`() {
        val root = Files.createTempDirectory("attachment-validation").toFile()
        try {
            val file = root.resolve("photo.png").apply { writeText("test") }
            validateOwnedWorkspaceAttachment(file.path, root)
            file.delete()
            assertThrows(AgentAttachmentPreparationException::class.java) {
                validateOwnedWorkspaceAttachment(file.path, root)
            }
            validateOwnedWorkspaceAttachment("/workspace/remote.png", root)
        } finally { root.deleteRecursively() }
    }
}
