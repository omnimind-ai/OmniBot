package cn.com.omnimind.baselib.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationCheckpointTest {
    @Test fun lateSnapshotCannotResurrectExplicitlyClearedCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-clear-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "isolated clear race"))
            assertEquals(1, dao.commitContextCheckpoint(id, "old summary", 7, 0, 100))
            val delayedSnapshot = dao.getById(id)!!
            dao.clearContextCheckpoint(id)
            dao.updatePreservingCheckpoint(delayedSnapshot.copy(lastMessage = "late UI save"))
            assertNull(dao.getById(id)!!.contextSummary)
            assertNull(dao.getById(id)!!.contextSummaryCutoffEntryDbId)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun inFlightSummaryCannotCommitAfterClearOrNewerCheckpoint() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-cas-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "isolated summary race"))
            assertEquals(1, dao.commitContextCheckpoint(id, "first", 7, 0, 100))
            dao.clearContextCheckpoint(id)
            assertEquals(0, dao.commitContextCheckpoint(id, "stale", 8, 100, 500))
            assertNull(dao.getById(id)!!.contextSummary)
            val revision = dao.getById(id)!!.contextSummaryUpdatedAt
            assertEquals(1, dao.commitContextCheckpoint(id, "new", 9, revision, 600))
            assertEquals(0, dao.commitContextCheckpoint(id, "late", 8, revision, 700))
            assertEquals("new", dao.getById(id)!!.contextSummary)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun usageHistoryAndRestartPreserveUserBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "budget-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "isolated budget"))
            val stale = dao.getById(id)!!
            dao.updatePromptThreshold(id, 64000, 100)
            dao.updatePromptUsage(id, 31000, 200)
            dao.updatePromptUsage(id, 99000, 150) // delayed observation
            dao.updatePreservingCheckpoint(stale.copy(lastMessage = "completed"))
            assertEquals(64000, dao.getById(id)!!.promptTokenThreshold)
            assertEquals(31000, dao.getById(id)!!.latestPromptTokens)
            db.close()
            db = open()
            assertEquals(64000, db.conversationDao().getById(id)!!.promptTokenThreshold)
            assertEquals(31000, db.conversationDao().getById(id)!!.latestPromptTokens)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun staleCompletionCannotEraseCheckpointButExplicitClearCan() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "checkpoint-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "isolated test"))
            val stale = dao.getById(id)!!
            assertEquals(1, dao.commitContextCheckpoint(id, "CEDAR pending blue export", 7, 0, 100))
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
