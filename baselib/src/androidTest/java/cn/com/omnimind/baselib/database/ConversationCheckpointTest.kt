package cn.com.omnimind.baselib.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationCheckpointTest {
    @Test fun staleCompletionCannotEraseCheckpointButExplicitClearCan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "isolated test"))
            val stale = dao.getById(id)!!
            dao.updatePreservingCheckpoint(stale.copy(contextSummary = "CEDAR pending blue export",
                contextSummaryCutoffEntryDbId = 7, contextSummaryUpdatedAt = 100))
            dao.updatePreservingCheckpoint(stale.copy(lastMessage = "completed", messageCount = 3))
            assertEquals("CEDAR pending blue export", dao.getById(id)!!.contextSummary)
            assertEquals(7L, dao.getById(id)!!.contextSummaryCutoffEntryDbId)
            assertEquals("completed", dao.getById(id)!!.lastMessage)
            db.close()
            db = open()
            assertEquals(100L, db.conversationDao().getById(id)!!.contextSummaryUpdatedAt)
            db.conversationDao().clearContextCheckpoint(id)
            assertNull(db.conversationDao().getById(id)!!.contextSummary)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
