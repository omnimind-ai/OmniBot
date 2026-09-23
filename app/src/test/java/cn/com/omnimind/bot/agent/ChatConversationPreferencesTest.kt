package cn.com.omnimind.bot.agent

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ChatConversationPreferencesTest {
    private val data = mutableMapOf<String, Any?>()
    private val store = ChatConversationPreferences(data::get) { changes ->
        changes.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
    }
    private fun target(id: Long?, mode: String = "normal") = mapOf("conversationId" to id, "mode" to mode)

    @Test fun idsAndTargetsAreIsolatedByStorageMode() {
        store.saveId(11, "normal")
        store.saveId(22, "openclaw")
        assertEquals(11L, store.currentId("normal"))
        assertEquals(22L, store.currentId("openclaw"))
        assertNull(store.currentTarget("chat_only"))
        store.saveTarget(target(11), "normal")
        assertEquals("agent", store.currentTarget("normal")?.get("mode"))
        assertTrue(data.containsKey("current_conversation_target_normal"))
        store.saveTarget(null, "openclaw")
        assertNull(store.currentTarget("openclaw"))
        assertEquals(11L, store.currentTarget("normal")?.get("conversationId"))
    }

    @Test fun legacyIdIsOnlyAFallbackForNormalAndIsClearedTogether() {
        data["current_conversation_id"] = 61L
        assertEquals(61L, store.currentId("normal"))
        assertNull(store.currentId("agent"))
        store.saveId(null, "normal")
        assertNull(store.currentId("normal"))
    }

    @Test fun newTargetsAndMissingLastSelectionRetainMode() {
        store.saveTarget(target(null) + ("isNewConversation" to true), "normal")
        assertEquals(true, store.lastTarget()?.get("isNewConversation"))
        assertEquals("agent", store.lastTarget()?.get("mode"))
        assertNull(store.currentId("normal"))
        store.saveTarget(target(null, "chat_only") + ("isNewConversation" to true), "chat_only")
        assertEquals("chat_only", store.currentTarget("chat_only")?.get("mode"))
    }

    @Test fun sessionIdentitySurvivesButRouteRequestDoesNotReplay() {
        val target = target(42, "agent") + mapOf(
            "agentId" to "claude-code-acp", "agentSessionId" to "session-42", "agentRuntime" to "local",
            "agentSessionActive" to true, "fromNativeRoute" to true, "requestKey" to "send-once",
        )
        store.saveTarget(target, "agent")
        store.saveLast(target)
        val restored = store.lastTarget()!!
        assertEquals("claude-code-acp", restored["agentId"])
        assertEquals("session-42", restored["agentSessionId"])
        assertEquals("local", restored["agentRuntime"])
        assertEquals(true, restored["agentSessionActive"])
        assertEquals(false, restored["fromNativeRoute"])
        assertFalse(restored.containsKey("requestKey"))
        assertEquals(restored, store.currentTarget("agent"))
    }

    @Test fun clearOnlyTheSelectedThreadAndFallbackToAnotherMode() {
        store.saveTarget(target(1), "normal")
        store.saveTarget(target(2, "openclaw"), "openclaw")
        store.saveLast(target(2, "openclaw"))
        store.clearReferences(2, "openclaw")
        assertNull(store.currentTarget("openclaw"))
        assertEquals(1L, store.lastTarget()?.get("conversationId"))
        assertEquals("agent", store.lastTarget()?.get("mode"))
    }

    @Test fun corruptedLastSelectionDoesNotCreateAnUnrelatedRoute() {
        store.saveId(1, "normal")
        data["last_visible_conversation_target"] = "not json"
        assertNull(store.lastTarget())
        data["current_conversation_target_normal"] = "not json"
        assertEquals(1L, store.currentTarget("normal")?.get("conversationId"))
    }

    @Test fun hiddenIdsImportBothPluginEncodingsAndOldAlias() {
        val prefix = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(arrayListOf("12", "bad")) }
        data["hidden_codex_conversation_ids"] = prefix + Base64.getEncoder().encodeToString(bytes.toByteArray())
        data["hidden_agent_conversation_ids"] = "$prefix!" + Gson().toJson(listOf("13"))
        store.hide(14)
        assertEquals(setOf(12L, 13L, 14L), store.hiddenIds())
        assertTrue(data["hidden_agent_conversation_ids"].toString().startsWith("$prefix!"))
    }

    @Test fun twoEnginesReadCommittedSelectionWithoutReloadingACache() {
        val second = ChatConversationPreferences(data::get) { data.putAll(it) }
        store.saveTarget(target(71, "agent"), "agent")
        assertEquals(71L, second.currentId("agent"))
        second.saveTarget(target(72, "agent"), "agent")
        assertEquals(72L, store.currentTarget("agent")?.get("conversationId"))
    }

    @Test fun transcriptSkipsCardsAndPreservesChronologicalText() {
        val messages = listOf(
            mapOf("type" to 1, "user" to 2, "content" to mapOf("text" to "已经处理完成。")),
            mapOf("type" to 2, "user" to 3, "content" to mapOf("text" to "tool")),
            mapOf("type" to 1, "user" to 1, "content" to mapOf("text" to "帮我检查一下。")),
        )
        assertEquals("用户：\n帮我检查一下。\n\n助手：\n已经处理完成。", ConversationTranscript.clipboard(messages))
        val export = Gson().fromJson(ConversationTranscript.export(9, "agent", messages), Map::class.java)
        assertEquals("agent", export["mode"])
        assertEquals(3, (export["messages"] as List<*>).size)
    }
}
