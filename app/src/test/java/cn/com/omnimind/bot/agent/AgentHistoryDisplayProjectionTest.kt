package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryDisplayProjectionTest {
    private val gson = Gson()
    private fun entry(id: Long = 1) = AgentConversationEntry(
        id = id, conversationId = 15, conversationMode = "agent", entryId = "tool-$id",
        entryType = "tool_event", status = "success", summary = "read complete", payloadJson = "",
        createdAt = id, updatedAt = id,
    )

    @Test fun `tool fields share an encoded UTF8 budget and retain trailing identities`() = runBlocking {
        val payload = linkedMapOf<String, Any?>()
        listOf("argsJson", "resultPreviewJson", "rawResultJson", "terminalOutput", "summary",
            "question", "reasoningContent", "reasoning_content", "progress", "subagentStatusText").forEach {
            payload[it] = "中".repeat(8000)
        }
        payload["toolCallId"] = "call-final"
        payload["sessionId"] = "session-final"
        payload["turnId"] = "turn-final"
        val projected = AgentHistoryDisplayProjection.readPayload(StringReader(gson.toJson(payload)))
        assertTrue(gson.toJson(projected).toByteArray(Charsets.UTF_8).size < 66 * 1024)
        assertEquals("call-final", projected["toolCallId"]!!.asString)
        assertEquals("session-final", projected["sessionId"]!!.asString)
        assertEquals("turn-final", projected["turnId"]!!.asString)
    }

    @Test fun `oversized tool identities require fallback instead of being omitted or changed`() = runBlocking {
        val payloads = listOf(
            mapOf("toolCallId" to "x".repeat(9000)),
            mapOf("toolCallId" to "中".repeat(8000), "sessionId" to "中".repeat(8000)),
        )
        for (payload in payloads) {
            try {
                AgentHistoryDisplayProjection.readPayload(StringReader(gson.toJson(payload)))
                fail("Oversized tool identities must use the complete-record fallback")
            } catch (_: HistoryDisplayMetadataTooLarge) { }
        }
    }

    @Test fun `small fields preserve escaped strings nested values and Unicode`() = runBlocking {
        val expected = mapOf(
            "toolCallId" to "工具🌊", "rawResultJson" to "{\"a\":\"escaped \\\"quote\"}",
            "terminalOutput" to "line\nnext\\path, : } ]", "success" to true,
            "artifacts" to listOf(mapOf("title" to "中文", "actions" to listOf("read"))),
        )
        val actual = AgentHistoryDisplayProjection.readPayload(StringReader(gson.toJson(expected)))
        assertEquals(gson.toJsonTree(expected), gson.toJsonTree(actual))
    }

    @Test fun `large and unknown fields do not hide later identity or small previews`() = runBlocking {
        val large = "escaped \\\"value,}:🌊".repeat(40_000)
        val raw = gson.toJson(linkedMapOf(
            "rawResultJson" to large, "unknownExtension" to listOf(mapOf("large" to large)),
            "toolCallId" to "call-late", "sessionId" to "session-late", "turnId" to "turn-late",
            "argsJson" to "{\"path\":\"a.txt\"}", "terminalOutput" to "small output",
        ))
        val actual = AgentHistoryDisplayProjection.readPayload(StringReader(raw))
        assertEquals("call-late", actual["toolCallId"]!!.asString)
        assertEquals("session-late", actual["sessionId"]!!.asString)
        assertEquals("turn-late", actual["turnId"]!!.asString)
        assertEquals("small output", actual["terminalOutput"]!!.asString)
        assertEquals("{\"path\":\"a.txt\"}", actual["argsJson"]!!.asString)
        assertTrue(actual["rawResultJson"]!!.asString.contains("完整工具记录"))
        assertFalse(actual.containsKey("unknownExtension"))
        assertTrue(gson.toJson(actual).length < 1024)
    }

    @Test fun `offload is byte exact content addressed and never changes canonical entry`() = runBlocking {
        withDirectory { directory ->
            val source = entry()
            val raw = gson.toJson(mapOf("rawResultJson" to "中文🌊".repeat(20_000), "toolCallId" to "call-1"))
            val bytes = raw.toByteArray()
            suspend fun project() = AgentHistoryDisplayProjection.offload(source, directory) { it.write(bytes) }
            val result = project()
            assertArrayEquals(bytes, result.fullPayloadFile!!.readBytes())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals("1-$hash.json", result.fullPayloadFile.name)
            assertEquals(result.fullPayloadFile, project().fullPayloadFile)
            assertEquals(1, directory.listFiles()!!.size)
            assertEquals("", source.payloadJson)
            assertEquals(source.entryId, result.entry.entryId)
            assertEquals(source.status, result.entry.status)
        }
    }

    @Test fun `display budget cannot crowd out trailing ACP identities`() = runBlocking {
        val payload = linkedMapOf<String, Any?>()
        listOf("argsJson", "resultPreviewJson", "rawResultJson", "terminalOutput", "reasoning_content",
            "reasoningContent", "progress", "subagentStatusText", "summary", "question").forEach {
            payload[it] = "x".repeat(8000)
        }
        payload["sessionId"] = "session-538"
        payload["turnId"] = "turn-538"
        payload["toolCallId"] = "call-538"
        val result = AgentHistoryDisplayProjection.readPayload(StringReader(gson.toJson(payload)))
        assertEquals("session-538", result["sessionId"]!!.asString)
        assertEquals("turn-538", result["turnId"]!!.asString)
        assertEquals("call-538", result["toolCallId"]!!.asString)
        assertTrue(gson.toJson(result).length < 70 * 1024)
    }

    @Test fun `write failure cannot publish a partial artifact or substitute a preview`() = runBlocking {
        withDirectory { directory ->
            try {
                AgentHistoryDisplayProjection.offload(entry(), directory) {
                    it.write("partial".toByteArray())
                    throw IOException("disk full")
                }
                fail("storage failure must propagate")
            } catch (expected: IOException) { assertEquals("disk full", expected.message) }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun `a page of large tool records retains only bounded display payloads`() = runBlocking {
        withDirectory { directory ->
            val chunk = ByteArray(32768) { 'x'.code.toByte() }
            // 192 MiB of full records: this test also runs in a JVM capped at 128 MiB.
            val page = (1L..16L).map { id ->
                AgentHistoryDisplayProjection.offload(entry(id), directory) { output ->
                    output.write("{\"rawResultJson\":\"".toByteArray())
                    repeat(384) { output.write(chunk) }
                    output.write("\",\"toolCallId\":\"call-$id\",\"status\":\"success\"}".toByteArray())
                }
            }
            assertTrue(page.sumOf { it.entry.payloadJson.length } < 16 * 1024)
            page.forEachIndexed { index, result ->
                assertTrue(result.fullPayloadFile!!.length() > 12 * 1024 * 1024)
                assertEquals("call-${index + 1}", gson.fromJson(result.entry.payloadJson, Map::class.java)["toolCallId"])
            }
        }
    }

    @Test fun `malformed outer payload is rejected rather than silently accepted`() = runBlocking {
        for (raw in listOf("[]", "{\"toolCallId\":\"x\"", "{\"toolCallId\":\"x\",}", "{}garbage")) {
            try {
                AgentHistoryDisplayProjection.readPayload(StringReader(raw))
                fail("invalid payload must fail: $raw")
            } catch (_: IllegalStateException) { }
        }
    }

    @Test fun `deep extension structures never enter the Gson object tree`() = runBlocking {
        val raw = "{\"artifacts\":" + "[".repeat(4000) + "0" + "]".repeat(4000) +
            ",\"toolCallId\":\"call-after-depth\"}"
        val result = AgentHistoryDisplayProjection.readPayload(StringReader(raw))
        assertFalse(result.containsKey("artifacts"))
        assertEquals("call-after-depth", result["toolCallId"]!!.asString)
    }

    @Test fun `large text and thinking records are streamed to full artifacts with bounded envelopes`() = runBlocking {
        withDirectory { directory ->
            val chunk = ByteArray(32768) { 'x'.code.toByte() }
            val projected = (1L..4L).map { id ->
                val thinking = id % 2L == 0L
                val source = entry(id).copy(entryType = if (thinking) "ui_card" else "assistant_message")
                val prefix = if (thinking) "{\"content\":{\"cardData\":{\"thinkingContent\":\""
                    else "{\"content\":{\"text\":\""
                val suffix = if (thinking) "\",\"type\":\"deep_thinking\",\"taskID\":\"turn-$id\",\"stage\":4}},"
                    else "\"},"
                val result = AgentHistoryDisplayProjection.offload(source, directory) { output ->
                    output.write(prefix.toByteArray())
                    // 128 MiB of source records, with no giant source String.
                    repeat(1024) { output.write(chunk) }
                    output.write((suffix + "\"id\":\"item-$id\",\"streamMeta\":{\"sessionId\":\"session-$id\",\"turnId\":\"turn-$id\"}}").toByteArray())
                }
                assertEquals("", source.payloadJson)
                assertTrue(result.fullPayloadFile!!.length() > 32 * 1024 * 1024)
                assertTrue(result.entry.payloadJson.length < 12 * 1024)
                val payload = gson.fromJson(result.entry.payloadJson, com.google.gson.JsonObject::class.java)
                assertEquals("session-$id", payload.getAsJsonObject("streamMeta")["sessionId"].asString)
                result
            }
            assertTrue(projected.sumOf { it.entry.payloadJson.length } < 48 * 1024)
        }
    }

    @Test fun `full record attachment marks display copies without modifying canonical messages`() {
        val original = mapOf<String, Any?>(
            "id" to "thinking-1", "streamMeta" to mapOf("sessionId" to "s", "turnId" to "t"),
            "content" to mapOf("id" to "thinking-1", "cardData" to mapOf("type" to "deep_thinking", "stage" to 4)),
        )
        val artifact = mapOf<String, Any?>("androidPath" to "/full.json", "previewKind" to "file")
        val displayed = AgentHistoryDisplayProjection.attachCompleteRecord(original, artifact)
        assertFalse(AgentHistoryDisplayProjection.isOffloadedMessage(original))
        assertTrue(AgentHistoryDisplayProjection.isOffloadedMessage(displayed))
        assertEquals(original["id"], displayed["id"])
        assertEquals(original["streamMeta"], displayed["streamMeta"])
        val content = displayed["content"] as Map<*, *>
        assertEquals(listOf(artifact), content["attachments"])
        assertEquals(true, (content["cardData"] as Map<*, *>)[AgentHistoryDisplayProjection.OFFLOADED_MARKER])
        assertEquals(4, (content["cardData"] as Map<*, *>)["stage"])
    }

    @Test fun `oversized nested identities fall back to the same canonical item and complete file`() = runBlocking {
        withDirectory { directory ->
            val source = entry().copy(entryType = "ui_card")
            val largeId = "中".repeat(8190)
            val raw = gson.toJson(mapOf("id" to largeId, "content" to mapOf("id" to largeId)))
            val projected = AgentHistoryDisplayProjection.offload(source, directory) { it.write(raw.toByteArray()) }
            val payload = gson.fromJson(projected.entry.payloadJson, Map::class.java)
            assertEquals(source.entryId, payload["id"])
            assertTrue(projected.entry.payloadJson.toByteArray().size < 1024)
            assertEquals(raw, projected.fullPayloadFile!!.readText())
            assertEquals("", source.payloadJson)
        }
    }

    @Test fun `offloaded ui card echo never reloads or replaces the canonical record`() = runBlocking {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        val repository = AgentConversationHistoryRepository(context)
        // Reaching DatabaseHelper here would require an initialized Android DB.
        // The display echo is rejected before any canonical lookup or JSON merge.
        repository.upsertUiCard(1, "agent", "thinking-1", mapOf(
            "type" to "deep_thinking", "thinkingContent" to "preview",
            AgentHistoryDisplayProjection.OFFLOADED_MARKER to true,
        ))
        org.mockito.Mockito.verifyNoInteractions(context)
    }

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("history-display-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
