package cn.com.omnimind.baselib.database

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ConversationTitleOwnershipTest {
    @Test fun `migration preserves old titles and disables generation for existing rows`() {
        val context = RuntimeEnvironment.getApplication()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
                        db.execSQL("INSERT INTO conversations VALUES (1, 'Existing title')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            val db = helper.writableDatabase
            DatabaseHelper.ALL_MIGRATIONS.single { it.startVersion == 19 }.migrate(db)
            db.query("SELECT title, titleSource, titleGenerationAttempted FROM conversations WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("Existing title", it.getString(0))
                assertEquals("legacy", it.getString(1))
                assertEquals(1, it.getInt(2))
            }
        } finally { helper.close() }
    }

    @Test fun `one claim per conversation and manual rename wins over generated and agent names`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "temporary", messageCount = 2, updatedAt = 123))
            db.agentSessionBindingDao().upsert(AgentSessionBinding(conversationId = id, threadId = "session", cwd = "/", createdAt = 0, updatedAt = 0))
            val claims = (1..8).map { async { dao.claimTitleGeneration(id) } }.awaitAll()
            assertEquals(1, claims.sum())
            val oldSnapshot = dao.getById(id)!!
            assertEquals(1, dao.setOwnedTitle(id, "My title", "manual"))
            assertEquals(0, dao.commitGeneratedTitle(id, "session", "Generated"))
            assertEquals(0, dao.setOwnedTitle(id, "Late provider title", "agent"))
            dao.updatePreservingCheckpoint(oldSnapshot)
            val stored = dao.getById(id)!!
            assertEquals("My title", stored.title)
            assertEquals("manual", stored.titleSource)
            assertTrue(stored.titleGenerationAttempted)
            assertEquals(123L, stored.updatedAt)
            assertEquals(2, stored.messageCount)
        } finally { db.close() }
    }

    @Test fun `generated title commits only to its session and survives replay and snapshots`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            val id = dao.insert(Conversation(title = "question", updatedAt = 321))
            val other = dao.insert(Conversation(title = "other"))
            db.agentSessionBindingDao().upsert(AgentSessionBinding(conversationId = id, threadId = "session", cwd = "/", createdAt = 0, updatedAt = 0))
            val snapshot = dao.getById(id)!!
            assertEquals(1, dao.claimTitleGeneration(id))
            assertEquals(0, dao.commitGeneratedTitle(id, "wrong-session", "Wrong"))
            assertEquals(1, dao.commitGeneratedTitle(id, "session", "Correct"))
            assertEquals(0, dao.setOwnedTitle(id, "Replay", "agent"))
            dao.updatePreservingCheckpoint(snapshot, true)
            assertEquals("Correct", dao.getById(id)!!.title)
            assertEquals(321L, dao.getById(id)!!.updatedAt)
            assertEquals("other", dao.getById(other)!!.title)
            assertEquals(1, dao.setOwnedTitle(id, "Manual override", "manual"))
            dao.deleteById(id)
            assertEquals(0, dao.commitGeneratedTitle(id, "session", "Resurrected"))
        } finally { db.close() }
    }

    @Test fun `legacy conversations never acquire generation and new rows do`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val dao = db.conversationDao()
            // SQL defaults are the same defaults installed by migration 19 -> 20.
            val fresh = dao.insert(Conversation(title = "new"))
            val legacy = dao.insert(Conversation(title = "old", titleSource = "legacy", titleGenerationAttempted = true))
            assertEquals(0, dao.claimTitleGeneration(legacy))
            assertEquals(1, dao.claimTitleGeneration(fresh))
            assertEquals(0, dao.claimTitleGeneration(fresh))
        } finally { db.close() }
    }
}
