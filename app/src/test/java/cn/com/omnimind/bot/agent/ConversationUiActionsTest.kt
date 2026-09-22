package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.ConversationDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ConversationUiActionsTest {
    private val data = mutableMapOf<String, Any?>()
    private val preferences = ChatConversationPreferences(data::get) { values -> values.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v } }
    private val rows = mutableMapOf(9L to mapOf<String, Any?>("isArchived" to false))
    private val calls = mutableListOf<String>()
    private var remoteFails = false
    private var localFails = false
    private val actions = ConversationUiActions(
        preferences,
        acp = { method, _ -> calls.add(method); if (remoteFails) error("offline") },
        get = { rows[it] },
        archive = { id, archived -> if (localFails) error("storage"); rows[id] = rows.getValue(id) + ("isArchived" to archived) },
        delete = { rows.remove(it) },
        rename = { id, title -> if (localFails) error("storage"); rows[id] = rows.getValue(id) + ("title" to title) },
    )

    @Test fun archiveAndRenameDurableHistoryEvenWhenNoAcpSessionExists() = runBlocking {
        remoteFails = true
        assertTrue(actions.execute("archive", 9, "agent"))
        assertEquals(true, rows[9]?.get("isArchived"))
        assertTrue(actions.execute("rename", 9, "agent", "New title"))
        assertEquals("New title", rows[9]?.get("title"))
        assertEquals(listOf("session/archive", "session/name/set"), calls)
    }

    @Test fun deleteAgentHidesWithoutErasingDurableHistoryAndClearsSelection() = runBlocking {
        remoteFails = true
        preferences.saveTarget(mapOf("conversationId" to 9), "agent")
        assertTrue(actions.execute("delete", 9, "agent"))
        assertEquals(true, rows[9]?.get("isArchived"))
        assertTrue(9L in preferences.hiddenIds())
        assertNull(preferences.currentTarget("agent"))
    }

    @Test fun bothFailuresLeaveVisibilityAndSelectionIntact() = runBlocking {
        remoteFails = true
        localFails = true
        preferences.saveTarget(mapOf("conversationId" to 9), "agent")
        assertFalse(actions.execute("delete", 9, "agent"))
        assertTrue(preferences.hiddenIds().isEmpty())
        assertEquals(9L, preferences.currentId("agent"))
    }

    @Test fun nonAgentDeleteDoesNotDispatchAnAcpOperation() = runBlocking {
        assertTrue(actions.execute("delete", 9, "openclaw"))
        assertFalse(rows.containsKey(9))
        assertTrue(calls.isEmpty())
    }

    @Test fun visibleOffsetIsAppliedAfterHiddenRowsWithoutLoadingAllRows() = runBlocking {
        var readCount = 0
        val rows = visibleConversationPage(20, 10, true, { it: Int -> it % 2 == 0 }) { offset, limit ->
            readCount += limit
            (offset until minOf(10000, offset + limit)).toList()
        }
        assertEquals((40..58 step 2).toList(), rows)
        assertEquals(64, readCount)
        assertTrue(visibleConversationPage<Int>(-1, 0, false, { true }) { _, _ -> error("no read") }.isEmpty())
    }

    @Test fun staleUiPersistencePreservesLatestUserMetadataInsideDaoTransaction() = runBlocking {
        val dao = Mockito.mock(ConversationDao::class.java)
        val current = Conversation(id = 9, title = "Renamed", isPinned = true, isArchived = true,
            contextSummary = "checkpoint", contextSummaryUpdatedAt = 20, promptTokenThreshold = 50000, createdAt = 1)
        val incoming = current.copy(title = "Old", isPinned = false, isArchived = false,
            contextSummary = null, contextSummaryUpdatedAt = 0, messageCount = 7, lastMessage = "new", createdAt = 99)
        Mockito.`when`(dao.getById(9)).thenReturn(current)
        Mockito.doCallRealMethod().`when`(dao).updatePreservingCheckpoint(incoming, true)
        dao.updatePreservingCheckpoint(incoming, true)
        Mockito.verify(dao).update(incoming.copy(title = "Renamed", isPinned = true, isArchived = true,
            contextSummary = "checkpoint", contextSummaryUpdatedAt = 20, createdAt = 1))
    }
}
