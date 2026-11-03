package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.StringReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryMessageJsonReaderTest {
    private val gson = Gson()
    private suspend fun read(raw: String): JsonObject =
        HistoryMessageJsonReader(StringReader(raw), currentCoroutineContext()).read()

    @Test fun `small envelope retains text attachments identities flags and usage`() = runBlocking {
        val payload = mapOf(
            "id" to "m1", "type" to 1, "user" to 2, "isError" to false,
            "content" to mapOf("id" to "m1", "text" to "中文🌊\n\\\" : , }",
                "attachments" to listOf(mapOf("path" to "/workspace/a.txt", "name" to "a.txt"))),
            "streamMeta" to mapOf("sessionId" to "s1", "turnId" to "t1", "seq" to 2),
            "turnUsage" to mapOf("inputTokens" to 120, "outputTokens" to 30),
        )
        assertEquals(gson.toJsonTree(payload), read(gson.toJson(payload)))
    }

    @Test fun `huge strings retain bounded valid unicode previews and later ACP identities`() = runBlocking {
        val original = "text \\" + "🌊\n\t\"中文".repeat(80_000)
        val projected = read(gson.toJson(mapOf(
            "content" to mapOf("text" to original, "id" to "m-late"),
            "streamMeta" to mapOf("hugeExtension" to original, "sessionId" to "s-late", "turnId" to "t-late"),
            "id" to "m-late", "type" to 1, "user" to 2,
        )))
        val preview = projected.getAsJsonObject("content")["text"].asString
        assertTrue(preview.startsWith("text \\"))
        assertTrue(preview.endsWith("完整内容见附件）"))
        assertTrue(preview.length <= 8192)
        assertEquals(preview, AgentTextSanitizer.sanitizeUtf16(preview))
        assertEquals("m-late", projected["id"].asString)
        assertEquals("s-late", projected.getAsJsonObject("streamMeta")["sessionId"].asString)
        assertEquals("t-late", projected.getAsJsonObject("streamMeta")["turnId"].asString)
        assertFalse(projected.getAsJsonObject("streamMeta").has("hugeExtension"))
    }

    @Test fun `thinking card is projected inside content without changing its state`() = runBlocking {
        val projected = read(gson.toJson(mapOf("type" to 2, "user" to 3, "content" to mapOf(
            "cardData" to mapOf("thinkingContent" to "thought".repeat(100_000), "type" to "deep_thinking",
                "taskID" to "t1", "stage" to 1, "isLoading" to true, "startTime" to 123), "id" to "thinking-1"))))
        val card = projected.getAsJsonObject("content").getAsJsonObject("cardData")
        assertTrue(card["thinkingContent"].asString.length <= 8192)
        assertEquals("t1", card["taskID"].asString)
        assertEquals(1, card["stage"].asInt)
        assertTrue(card["isLoading"].asBoolean)
    }

    @Test fun `many extensions cannot consume unbounded storage or hide later identities`() = runBlocking {
        val metadata = linkedMapOf<String, Any?>()
        repeat(2000) { metadata["extension-$it"] = "x".repeat(1000) }
        metadata["sessionId"] = "s-final"
        metadata["turnId"] = "t-final"
        val projected = read(gson.toJson(mapOf("streamMeta" to metadata)))
        assertTrue(projected.toString().length < 70 * 1024)
        assertEquals("s-final", projected.getAsJsonObject("streamMeta")["sessionId"].asString)
        assertEquals("t-final", projected.getAsJsonObject("streamMeta")["turnId"].asString)
    }

    @Test fun `deep extensions and huge keys are scanned without object tree expansion`() = runBlocking {
        val raw = "{\"" + "key".repeat(100_000) + "\":0,\"extension\":" + "[".repeat(4000) + "0" +
            "]".repeat(4000) + ",\"id\":\"still-here\"}"
        assertEquals("{\"id\":\"still-here\"}", read(raw).toString())
    }

    @Test fun `unicode and escaped previews obey the encoded byte budget`() = runBlocking {
        val content = linkedMapOf<String, Any?>()
        repeat(100) { content["extra-$it"] = "中\u0000<".repeat(2000) }
        content["id"] = "m1"
        val projected = read(gson.toJson(mapOf("content" to content, "id" to "m1")))
        assertTrue(projected.toString().toByteArray(Charsets.UTF_8).size < 70 * 1024)
        assertEquals("m1", projected["id"].asString)
    }

    @Test fun `identity allowance is shared across the entire record and never truncates an id`() = runBlocking {
        val largeId = "中".repeat(8190)
        try {
            read(gson.toJson(mapOf("id" to largeId, "content" to mapOf("id" to largeId))))
            fail("Oversized identities require a complete-record view, not a changed id")
        } catch (_: HistoryDisplayMetadataTooLarge) { }
    }

    @Test fun `malformed structural json and string escapes fail explicitly`() = runBlocking {
        for (raw in listOf("[]", "{\"content\":{\"text\":\"x\"}", "{\"id\":\"x\",}", "{}garbage",
            "{\"content\":{\"text\":\"\\q\"}}", "{\"content\":{\"text\":\"\\uZZZZ\"}}")) {
            try {
                read(raw)
                fail("Malformed history must not silently produce a preview")
            } catch (_: IllegalStateException) { }
        }
    }
}
