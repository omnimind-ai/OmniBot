package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.toolResultAcpPayload
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolImageAcpPayloadTest {
    @Test
    fun `file read projects an existing artifact without duplicating original image bytes`() {
        val image = "data:image/png;base64," + "abcd".repeat(6_000_000)
        val artifact = ArtifactRef(
            id = "photo", uri = "omnibot://workspace/photo.png", title = "photo.png",
            mimeType = "image/png", size = 18_000_000, sourceTool = "file_read",
            workspacePath = "/workspace/photo.png", androidPath = "/data/user/0/test/workspace/photo.png",
            previewKind = "image",
        )
        val result = ToolExecutionResult.ContextResult(
            toolName = "file_read", summaryText = "Read image", previewJson = "{}", rawResultJson = "{}",
            imageDataUrl = image, artifacts = listOf(artifact),
        )
        val payload = toolResultAcpPayload(result)
        assertEquals(artifact.androidPath, payload["imageUrl"]?.jsonPrimitive?.content)
        assertFalse(payload.containsKey("imageDataUrl"))
        assertTrue(payload.toString().length < 4000)
        assertSame(image, result.imageDataUrl)
        assertTrue(payload.containsKey("artifacts"))
    }

    @Test
    fun `image without local file retains inline presentation`() {
        val result = ToolExecutionResult.ContextResult(
            toolName = "browser_use", summaryText = "Screenshot", previewJson = "{}", rawResultJson = "{}",
            imageDataUrl = "data:image/png;base64,YWJj",
        )
        assertEquals(result.imageDataUrl, toolResultAcpPayload(result)["imageDataUrl"]?.jsonPrimitive?.content)
    }
}
