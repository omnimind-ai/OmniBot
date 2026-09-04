package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventAdapterTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val adapter = AgentEventAdapter(json)

    @Test
    fun `small tool result is returned byte for byte`() {
        val raw = """{"toolName":"memory_search","success":true,"result":"ok"}"""

        assertEquals(raw, adapter.compactToolResultContent(raw, offloadArtifact = null))
    }

    @Test
    fun `large tool result is bounded and points to full artifact`() {
        val raw = """{"toolName":"file_read","success":true,"summary":"done","rawResultJson":"${"x".repeat(20_000)}"}"""
        val artifact = ArtifactRef(
            id = "artifact-1",
            uri = "omnibot://workspace/offloads/result.json",
            title = "result.json",
            mimeType = "application/json",
            size = raw.length.toLong(),
            sourceTool = "file_read",
            workspacePath = "/workspace/offloads/result.json",
            androidPath = "/data/user/0/app/offloads/result.json",
            previewKind = "text"
        )

        val compact = adapter.compactToolResultContent(raw, artifact, maxChars = 12 * 1024)
        val payload = json.parseToJsonElement(compact).jsonObject

        assertTrue(compact.length < 12 * 1024)
        assertEquals("true", payload["outputTruncated"]?.jsonPrimitive?.content)
        assertEquals(raw.length.toString(), payload["originalChars"]?.jsonPrimitive?.content)
        assertEquals(
            artifact.uri,
            payload["fullOutputArtifact"]?.jsonObject?.get("uri")?.jsonPrimitive?.content
        )
        assertTrue(payload["headTail"]?.jsonPrimitive?.content.orEmpty().contains("middle omitted"))
        assertFalse(compact.contains("x".repeat(10_000)))
    }

    @Test
    fun `tool result is not truncated without an explicit setting`() {
        val raw = "x".repeat(20_000)

        assertEquals(raw, adapter.compactToolResultContent(raw, offloadArtifact = null))
    }
}
