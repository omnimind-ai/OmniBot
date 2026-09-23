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

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("history-display-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
