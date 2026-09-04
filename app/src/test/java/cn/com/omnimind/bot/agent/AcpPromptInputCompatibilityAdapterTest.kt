package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.AcpPromptInputCompatibilityAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpPromptInputCompatibilityAdapterTest {
    @Test
    fun `normalizes ACP text and image blocks for local executor`() {
        val normalized = AcpPromptInputCompatibilityAdapter.normalize(
            mapOf(
                "input" to listOf(
                    mapOf("type" to "text", "text" to "inspect this"),
                    mapOf(
                        "type" to "image",
                        "mimeType" to "image/png",
                        "data" to "cG5n",
                    ),
                )
            )
        )

        assertEquals("inspect this", normalized["text"])
        val attachments = normalized["attachments"] as List<*>
        val image = attachments.single() as Map<*, *>
        assertEquals("data:image/png;base64,cG5n", image["dataUrl"])
        assertTrue(image["isImage"] == true)
        assertTrue(image["sendToModel"] == false)
    }

    @Test
    fun `rejects empty or unrelated input instead of sending an empty prompt`() {
        val args = mapOf("input" to listOf(mapOf("type" to "unknown")))
        assertThrows(IllegalArgumentException::class.java) {
            AcpPromptInputCompatibilityAdapter.normalize(args)
        }
    }

    @Test
    fun `accepts an embedded resource uri as an attachment`() {
        val normalized = AcpPromptInputCompatibilityAdapter.normalize(
            mapOf(
                "input" to listOf(
                    mapOf(
                        "type" to "resource",
                        "resource" to mapOf(
                            "uri" to "content://provider/image.png",
                            "mimeType" to "image/png",
                        ),
                    ),
                ),
            ),
        )

        val attachment = (normalized["attachments"] as List<*>).single() as Map<*, *>
        assertEquals("content://provider/image.png", attachment["path"])
        assertTrue(attachment["isImage"] == true)
    }

    @Test
    fun `rejects malformed content blocks`() {
        assertThrows(IllegalArgumentException::class.java) {
            AcpPromptInputCompatibilityAdapter.normalize(
                mapOf("input" to listOf(mapOf("type" to "image"))),
            )
        }
    }
}
