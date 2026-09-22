package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LegacyConversationHistoryTest {
    private val gson = Gson()
    private val sources = mutableMapOf<String, String>()
    private val history = LegacyConversationHistory(sources::get) { retired ->
        retired.forEach { (key, value) -> if (sources[key] == value) sources.remove(key) }
    }
    private fun key(suffix: String) = "flutter.conversation_messages_$suffix"
    private fun message(id: String, text: String = id) = mapOf(
        "id" to id, "type" to 1, "user" to 1,
        "content" to mapOf("text" to text), "createAt" to 1000,
    )
    private fun put(suffix: String, vararg messages: Map<String, Any?>) {
        sources[key(suffix)] = gson.toJson(messages)
    }

    @Test fun `all legacy buckets merge with canonical precedence and preserve identities`() {
        put("agent_10", message("same", "canonical"))
        put("normal_10", message("same", "stale"), message("normal"))
        put("10", message("bare"))
        put("codex_10", message("codex"))
        put("chat_only_10", message("other-mode"))
        put("agent_11", message("other-conversation"))
        val snapshot = history.read(10, "agent")
        assertEquals(listOf("same", "normal", "bare", "codex"), snapshot.messages.map { it["id"] })
        assertEquals("canonical", (snapshot.messages.first()["content"] as Map<*, *>)["text"])
        assertEquals(4, snapshot.sources.size)
        assertEquals(6, sources.size) // Reading alone never consumes a source.
    }

    @Test fun `successful import retires only its own snapshots and is one way`() = runBlocking {
        put("normal_4", message("old"))
        put("chat_only_4", message("other"))
        var writes = 0
        repeat(2) {
            history.import(4, "agent") { messages ->
                assertEquals("old", messages.single()["id"])
                assertTrue(sources.containsKey(key("normal_4")))
                writes++
            }
        }
        assertEquals(1, writes)
        assertEquals(setOf(key("chat_only_4")), sources.keys)
    }

    @Test fun `failed import leaves every source available for retry`() = runBlocking {
        put("3", message("old"))
        val before = sources.toMap()
        try {
            history.import(3, "agent") { throw IllegalStateException("storage unavailable") }
            fail("storage failure must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(before, sources)
        history.import(3, "agent") { assertEquals(1, it.size) }
        assertTrue(sources.isEmpty())
    }

    @Test fun `malformed bucket does not suppress valid history or get deleted`() = runBlocking {
        sources[key("agent_8")] = "broken json"
        sources[key("normal_8")] = "[null,42,${gson.toJson(message("valid"))}]"
        history.import(8, "agent") { assertEquals("valid", it.single()["id"]) }
        assertEquals(mapOf(key("agent_8") to "broken json"), sources)
    }

    @Test fun `missing ids are stable across process restart and numeric fields survive`() {
        put("chat_only_5", mapOf("type" to "2.0", "user" to 3.0,
            "content" to mapOf("id" to "tool-id", "cardData" to mapOf("agentId" to "claude-code-acp")),
            "streamMeta" to mapOf("sessionId" to "session", "turnId" to "turn", "toolCallId" to "call")),
            mapOf("content" to mapOf("text" to "without id")))
        val first = history.read(5, "chat_only").messages
        val second = LegacyConversationHistory(sources::get) {}.read(5, "chat_only").messages
        assertEquals(first, second)
        assertEquals("tool-id", first[0]["id"])
        assertEquals(2, first[0]["type"])
        assertEquals("session", (first[0]["streamMeta"] as Map<*, *>)["sessionId"])
        assertTrue(first[1]["id"].toString().startsWith("legacy-"))
    }

    @Test fun `clear cannot race an import into resurrecting deleted messages`() = runBlocking {
        put("normal_7", message("old"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = mutableListOf<String>()
        val importing = launch {
            history.import(7, "agent") {
                entered.complete(Unit)
                release.await()
                operations += "import"
            }
        }
        entered.await()
        val clearing = launch { history.clear(7, listOf("agent")) { operations += "clear" } }
        yield()
        assertTrue(operations.isEmpty())
        release.complete(Unit)
        importing.join()
        clearing.join()
        history.import(7, "agent") { fail("cleared history must not reappear") }
        assertEquals(listOf("import", "clear"), operations)
        assertTrue(sources.isEmpty())
    }

    @Test fun `failed explicit clear preserves legacy history`() = runBlocking {
        put("subagent_1", message("keep"))
        try {
            history.clear(1, listOf("subagent")) { throw IllegalStateException("failed") }
            fail("clear failure must propagate")
        } catch (_: IllegalStateException) { }
        assertTrue(sources.containsKey(key("subagent_1")))
    }

    @Test fun `long legacy history is imported once before native paging`() = runBlocking {
        sources[key("normal_9")] = gson.toJson((0 until 10000).map { message("message-$it") })
        var imports = 0
        repeat(20) {
            history.import(9, "agent") { rows ->
                assertEquals(10000, rows.size)
                assertEquals(10000, rows.map { it["id"] }.toSet().size)
                imports++
            }
        }
        assertEquals(1, imports)
    }

    @Test fun `legacy assistant frames are normalized before persistence and empty placeholders are skipped`() {
        fun assistant(id: String, text: String) = message(id, text) + ("user" to 2)
        put("normal_6",
            assistant("answer", """{"choices":[{"delta":{"reasoning_content":"thinking"}}]}最终回答。"""),
            assistant("empty", """{"choices":[]}"""),
            assistant("error", "") + ("isError" to true),
            assistant("attachment", "") + ("content" to mapOf("attachments" to listOf(mapOf("name" to "photo")))),
        )
        val messages = history.read(6, "agent").messages
        assertEquals(listOf("answer", "error", "attachment"), messages.map { it["id"] })
        assertEquals("最终回答。", (messages.first()["content"] as Map<*, *>)["text"])
    }

    @Test fun `legacy card vocabulary is canonicalized without losing ACP identities`() {
        put("codex_12", mapOf("id" to "tool", "type" to 2, "user" to 3,
            "content" to mapOf("cardData" to mapOf("type" to "codex_request", "uiStyle" to "codex_tool",
                "toolName" to "codex.tool", "agentId" to "claude-code-acp", "toolCallId" to "call"))))
        val card = (history.read(12, "agent").messages.single()["content"] as Map<*, *>)["cardData"] as Map<*, *>
        assertEquals("agent_request", card["type"])
        assertEquals("agent_tool", card["uiStyle"])
        assertEquals("agent.tool", card["toolName"])
        assertEquals("claude-code-acp", card["agentId"])
        assertEquals("call", card["toolCallId"])
    }

    @Test fun `assistant text compatibility retains JSON code whitespace and escaped braces`() {
        for (text in listOf("""{"foo":1,"bar":{"baz":true}}""", "示例: {\"foo\":1}", "{broken", "plain text")) {
            assertEquals(text, LegacyAssistantText.sanitize(text))
        }
        val parts = listOf("Hello", ",", " ", "world", "!", " {\"x\":\"}\\\"\"}")
        val frames = parts.joinToString("") { gson.toJson(mapOf("choices" to listOf(mapOf("delta" to mapOf("content" to it))))) }
        assertEquals(parts.joinToString(""), LegacyAssistantText.sanitize(frames))
        assertEquals("answer", LegacyAssistantText.sanitize("""{"output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}"""))
    }
}
