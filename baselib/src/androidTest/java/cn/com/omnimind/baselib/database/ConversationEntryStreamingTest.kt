package cn.com.omnimind.baselib.database

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationEntryStreamingTest {
    @Test fun largeToolPayloadStreamsFromRoomAndHeaderUpdatesPreserveFullHistory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "issue-538-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = db.agentConversationEntryDao()
            val payload = "{\"rawResultJson\":\"" + "中文🌊".repeat(800_000) +
                "\",\"toolCallId\":\"call-538\",\"sessionId\":\"session-538\",\"turnId\":\"turn-538\"}"
            val expected = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
            val id = dao.upsert(AgentConversationEntry(
                conversationId = 538, conversationMode = "agent", entryId = "tool-538",
                entryType = "tool_event", status = "running", summary = "summary".repeat(10_000),
                payloadJson = payload, createdAt = 1, updatedAt = 1,
            ))
            suspend fun digestStoredPayload(): ByteArray = db.withTransaction {
                val slice = dao.getLogicalThreadPageSlices(538, listOf("agent"), 16, 0).single()
                assertEquals("", slice.entry.payloadJson)
                assertTrue(slice.payloadBytes > 4 * 1024 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                val sink = DigestOutputStream(object : OutputStream() {
                    override fun write(b: Int) { }
                    override fun write(b: ByteArray, off: Int, len: Int) { }
                }, digest)
                dao.copyEntryTextTo(slice, summary = false, output = sink)
                digest.digest()
            }
            assertArrayEquals(expected, digestStoredPayload())
            assertEquals(1, dao.updateInterruptedToolHeader(id, 1, "interrupted", "tool interrupted", 2))
            assertEquals(0, dao.updateInterruptedToolHeader(id, 1, "interrupted", "stale", 3))
            assertEquals("interrupted", dao.getLogicalThreadPageSlices(538, listOf("agent"), 1, 0).single().entry.status)
            assertArrayEquals(expected, digestStoredPayload())
            assertEquals("summary".repeat(10_000).take(2048), dao.readSummaryPreview(id))
            assertEquals(70_000L, dao.getLogicalThreadPageSlices(538, listOf("agent"), 1, 0).single().summaryBytes)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
