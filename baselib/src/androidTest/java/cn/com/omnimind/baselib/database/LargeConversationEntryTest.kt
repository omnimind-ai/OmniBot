package cn.com.omnimind.baselib.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeConversationEntryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oversizedLegacyRowLoadsLosslesslyAcrossEveryReadAndDatabaseReopen() = runBlocking {
        val name = "oob-large-entry-regression.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val summary = "甲😀\u0000".repeat(450000)
            val payload = "{\"text\":\"" + "中文😀\\n".repeat(450000) + "\"}"
            val entry = AgentConversationEntry(conversationId = 1, conversationMode = "agent",
                entryId = "large", entryType = "tool_event", status = "success",
                summary = summary, payloadJson = payload, createdAt = 1, updatedAt = 100)
            db.agentConversationEntryDao().upsert(entry)
            // Reproduce the old SELECT * failure on a real Android CursorWindow.
            val rawFailure = runCatching {
                db.openHelper.readableDatabase.query("SELECT * FROM agent_conversation_entries").use { cursor ->
                    cursor.moveToFirst()
                    cursor.getString(cursor.getColumnIndexOrThrow("payloadJson"))
                }
            }.exceptionOrNull()
            assertNotNull("Fixture must exceed the actual Android CursorWindow", rawFailure)
            assertTrue(rawFailure!!.message.orEmpty().contains("CursorWindow"))
            fun verify(value: AgentConversationEntry?) {
                assertNotNull(value)
                assertEquals(summary, value!!.summary)
                assertEquals(payload, value.payloadJson)
            }
            val dao = db.agentConversationEntryDao()
            verify(dao.getByThreadAndEntryId(1, "agent", "large"))
            verify(dao.getThreadEntriesAsc(1, "agent").single())
            verify(dao.getThreadEntriesDesc(1, "agent").single())
            verify(dao.getConversationEntriesAsc(1).single())
            verify(dao.getConversationEntriesDesc(1).single())
            verify(dao.getLatestConversationEntry(1))
            verify(dao.getEarliestConversationEntry(1))
            verify(dao.getLatestConversationUpdate(1))
            repeat(15) { index -> dao.upsert(entry.copy(id = 0, entryId = "small-$index", summary = "small",
                payloadJson = "{}", createdAt = index + 2L, updatedAt = index + 2L)) }
            val page = dao.getThreadEntriesDescPaged(1, "agent", 16, 0)
            assertEquals(16, page.size)
            verify(page.last())
            db.close()
            db = open()
            verify(db.agentConversationEntryDao().getByThreadAndEntryId(1, "agent", "large"))
            assertEquals(16, db.agentConversationEntryDao().countConversationEntries(1))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun inlineAndChunkBoundariesPreserveUtf8AndEmptyValues() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.agentConversationEntryDao()
            for (size in listOf(0, 32767, 32768, 32769, 65536)) {
                val text = "x".repeat(size) + "😀末尾"
                dao.upsert(AgentConversationEntry(conversationId = 1, conversationMode = "agent", entryId = "$size",
                    entryType = "assistant_message", status = "success", summary = "", payloadJson = text))
                val loaded = dao.getByThreadAndEntryId(1, "agent", "$size")!!
                assertEquals("", loaded.summary)
                assertEquals(text, loaded.payloadJson)
            }
            assertNull(dao.getByThreadAndEntryId(1, "agent", "missing"))
        } finally { db.close() }
    }
}
