package cn.com.omnimind.bot.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentImageAttachmentSupportTest {
    @After
    fun tearDown() {
        AgentImageAttachmentSupport.resetBackendForTests()
    }

    @Test
    fun `prepareAttachments retains the original image for the model and history`() {
        AgentImageAttachmentSupport.backend = object : AgentImageAttachmentSupport.Backend {
            override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String {
                return "data:image/png;base64,ORIGINAL"
            }
        }

        val prepared = AgentImageAttachmentSupport.prepareAttachments(
            listOf(
                mapOf(
                    "path" to "/tmp/screenshot.png",
                    "name" to "screenshot.png",
                    "mimeType" to "image/png",
                    "isImage" to true
                )
            )
        )

        assertEquals(1, prepared.modelAttachments.size)
        assertEquals(1, prepared.historyAttachments.size)
        assertEquals(1, prepared.runtimeAttachments.size)
        assertEquals(
            "data:image/png;base64,ORIGINAL",
            prepared.modelAttachments.single()["dataUrl"]
        )
        assertEquals(
            "data:image/png;base64,ORIGINAL",
            prepared.runtimeAttachments.single()["dataUrl"]
        )
        assertEquals(
            "data:image/png;base64,ORIGINAL",
            prepared.historyAttachments.single()["dataUrl"]
        )
        assertEquals("/tmp/screenshot.png", prepared.historyAttachments.single()["path"])
    }

    @Test
    fun `buildFileReadImageResult returns the original image outside payload json`() {
        AgentImageAttachmentSupport.backend = object : AgentImageAttachmentSupport.Backend {
            override fun readFileAsDataUrl(file: File, mimeTypeHint: String?): String {
                return "data:image/png;base64,ORIGINAL"
            }
        }

        val result = AgentImageAttachmentSupport.buildFileReadImageResult(
            file = File("/tmp/photo.png"),
            shellPath = "/workspace/photo.png",
            mimeTypeHint = "image/png",
            uri = "omnibot://workspace/photo.png",
            sizeBytes = 4096L
        )

        assertNotNull(result)
        assertEquals("data:image/png;base64,ORIGINAL", result?.imageDataUrl)
        assertFalse(result?.payload.toString().orEmpty().contains("base64"))
    }

    @Test
    fun `prepareAttachments preserves image data while normalizing transport whitespace`() {
        val prepared = AgentImageAttachmentSupport.prepareAttachments(
            listOf(
                mapOf(
                    "dataUrl" to "data:image/png;base64,OR\nIG IN\tAL",
                    "mimeType" to "image/png",
                    "isImage" to true
                )
            )
        )

        assertEquals(
            "data:image/png;base64,ORIGINAL",
            prepared.modelAttachments.single()["dataUrl"]
        )
        assertEquals(
            "data:image/png;base64,ORIGINAL",
            prepared.historyAttachments.single()["dataUrl"]
        )
    }

    @Test
    fun `prepareAttachments retains every selected image without a host count cap`() {
        val attachments = (1..64).map { index ->
            mapOf<String, Any?>(
                "name" to "image-$index.png",
                "dataUrl" to "data:image/png;base64,IMAGE$index",
                "mimeType" to "image/png",
                "isImage" to true,
            )
        }

        val prepared = AgentImageAttachmentSupport.prepareAttachments(attachments)

        assertEquals(64, prepared.modelAttachments.size)
        assertEquals(64, prepared.runtimeAttachments.size)
        assertEquals(64, prepared.historyAttachments.size)
        assertEquals("data:image/png;base64,IMAGE1", prepared.modelAttachments.first()["dataUrl"])
        assertEquals("data:image/png;base64,IMAGE64", prepared.historyAttachments.last()["dataUrl"])
    }

    @Test
    fun `resolveImageAttachmentUrl strips whitespace from stored data urls`() {
        val resolved = AgentImageAttachmentSupport.resolveImageAttachmentUrl(
            mapOf(
                "dataUrl" to "data:image/jpeg;base64,AB\nCD\r EF\tGH",
                "mimeType" to "image/jpeg",
                "isImage" to true
            )
        )

        assertEquals("data:image/jpeg;base64,ABCDEFGH", resolved)
        assertFalse(resolved.contains('\n'))
        assertFalse(resolved.contains('\r'))
        assertTrue(resolved.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `prepareAttachments keeps non-image files out of model attachments`() {
        val prepared = AgentImageAttachmentSupport.prepareAttachments(
            listOf(
                mapOf(
                    "path" to "/tmp/notes.md",
                    "name" to "notes.md",
                    "mimeType" to "text/markdown",
                    "isImage" to false,
                    "promptPath" to "/workspace/shared/notes.md",
                    "sendToModel" to false
                )
            )
        )

        assertEquals(0, prepared.modelAttachments.size)
        assertEquals(1, prepared.runtimeAttachments.size)
        assertEquals(1, prepared.historyAttachments.size)
        assertEquals(false, prepared.runtimeAttachments.single()["sendToModel"])
        assertEquals(
            "/workspace/shared/notes.md",
            prepared.runtimeAttachments.single()["promptPath"]
        )
    }
}
