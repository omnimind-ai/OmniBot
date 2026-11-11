package cn.com.omnimind.baselib.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @After
    fun cleanUp() {
        testContext.deleteDatabase(TEST_DB_NAME)
    }

    @Test
    fun migrate5To16_dropsLegacyAutomationTables_withoutInjectingSampleConversations() = runBlocking {
        createVersion5Database()

        val database = openMigratedDatabase()
        try {
            assertTableMissing(database, "execution_records")
            assertTableMissing(database, "study_records")
            assertTableMissing(database, "favorite_records")
            assertTableMissing(database, "cache_suggestion")
            assertTrue(database.conversationDao().getAll().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate8To9_preservesConversationRows_andBackfillsNewColumns() = runBlocking {
        createVersion8Database()

        val database = openMigratedDatabase()
        try {
            val conversation = database.conversationDao().getById(1L)
            assertNotNull(conversation)
            assertEquals("legacy conversation", conversation!!.title)
            assertEquals("legacy", conversation.titleSource)
            assertTrue(conversation.titleGenerationAttempted)
            assertEquals(0, database.conversationDao().claimTitleGeneration(conversation.id))
            assertEquals("agent", conversation.mode)
            assertEquals("legacy summary", conversation.summary)
            assertNull(conversation.contextSummary)
            assertNull(conversation.contextSummaryCutoffEntryDbId)
            assertEquals(0L, conversation.contextSummaryUpdatedAt)
            assertEquals(0, conversation.latestPromptTokens)
            assertEquals(128_000, conversation.promptTokenThreshold)
            assertEquals(0L, conversation.latestPromptTokensUpdatedAt)

            val entry = database.agentConversationEntryDao()
                .getByThreadAndEntryId(1L, "agent", "entry-1")
            assertNotNull(entry)
            assertEquals("queued", entry!!.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate12To13_addsAgentSessionBindingTable() = runBlocking {
        createVersion12Database()

        val database = openMigratedDatabase()
        try {
            val binding = AgentSessionBinding(
                conversationId = 1L,
                threadId = "thread-codex-1",
                cwd = "/workspace",
                createdAt = 1000L,
                updatedAt = 2000L
            )
            database.agentSessionBindingDao().upsert(binding)

            val byConversation = database.agentSessionBindingDao().getByConversationId(1L)
            val byThread = database.agentSessionBindingDao().getByThreadId("thread-codex-1")
            assertEquals(binding, byConversation)
            assertEquals(binding, byThread)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate13To14_addsConversationPinAndScheduleColumns() = runBlocking {
        createVersion13Database()

        val database = openMigratedDatabase()
        try {
            val conversation = database.conversationDao().getById(1L)
            assertNotNull(conversation)
            assertEquals("Scheduled seed", conversation!!.title)
            assertEquals(false, conversation.isPinned)
            assertNull(conversation.parentConversationId)
            assertNull(conversation.parentConversationMode)
            assertNull(conversation.scheduledTaskId)
        } finally {
            database.close()
        }
    }

    @Test
    fun migrate15To16_removesLocalUsageAndPreservesCloudUsage() = runBlocking {
        createVersion15Database()

        val database = openMigratedDatabase()
        try {
            assertColumnMissing(database, "token_usage_records", "isLocal")
            val records = database.tokenUsageRecordDao().getRecordsSince(0L)
            assertEquals(1, records.size)
            assertEquals("cloud-model", records.single().model)
            assertEquals(34, records.single().promptTokens)
            assertEquals(13, records.single().completionTokens)
        } finally {
            database.close()
        }
    }

    private fun openMigratedDatabase(): AppDatabase {
        return Room.databaseBuilder(testContext, AppDatabase::class.java, TEST_DB_NAME)
            .allowMainThreadQueries()
            .addMigrations(*DatabaseHelper.ALL_MIGRATIONS)
            .build()
            .also { it.openHelper.writableDatabase }
    }

    private fun assertTableMissing(database: AppDatabase, tableName: String) {
        database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor ->
            assertFalse("Expected $tableName to be removed", cursor.moveToFirst())
        }
    }

    private fun assertColumnMissing(
        database: AppDatabase,
        tableName: String,
        columnName: String
    ) {
        database.openHelper.readableDatabase.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                assertFalse(
                    "Expected $columnName to be removed from $tableName",
                    cursor.getString(nameIndex) == columnName
                )
            }
        }
    }

    private fun createVersion5Database() {
        val database = openLegacyDatabase(version = 5)
        try {
            createCommonPreConversationTables(database)
            database.execSQL(
                """
                INSERT INTO execution_records
                (id, title, appName, packageName, nodeId, suggestionId, iconUrl, type, content, status, createdAt, updatedAt)
                VALUES
                (1, 'legacy execution', 'Legacy App', 'cn.legacy.app', 'node-1', 'suggestion-1', NULL, 'summary', 'legacy-content', 'failed', 1000, 2000)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO favorite_records
                (id, title, `desc`, type, imagePath, packageName, status, createdAt, updatedAt)
                VALUES
                (2, 'legacy favorite', 'legacy desc', 'image', '/tmp/legacy.png', 'cn.legacy.favorite', 1, 3000, 4000)
                """.trimIndent()
            )
        } finally {
            database.close()
        }
    }

    private fun createVersion8Database() {
        val database = openLegacyDatabase(version = 8)
        try {
            createCommonPreConversationTables(database)
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mode` TEXT NOT NULL DEFAULT 'normal',
                    `summary` TEXT,
                    `status` INTEGER NOT NULL DEFAULT 0,
                    `lastMessage` TEXT,
                    `messageCount` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_conversation_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `conversationMode` TEXT NOT NULL,
                    `entryId` TEXT NOT NULL,
                    `entryType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_entryId`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `entryId`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_updatedAt`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `updatedAt`)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO conversations
                (id, title, mode, summary, status, lastMessage, messageCount, createdAt, updatedAt)
                VALUES
                (1, 'legacy conversation', 'agent', 'legacy summary', 0, 'last legacy message', 3, 5000, 6000)
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO agent_conversation_entries
                (id, conversationId, conversationMode, entryId, entryType, status, summary, payloadJson, createdAt, updatedAt)
                VALUES
                (1, 1, 'agent', 'entry-1', 'message', 'queued', 'legacy entry', '{"text":"hello"}', 7000, 8000)
                """.trimIndent()
            )
        } finally {
            database.close()
        }
    }

    private fun createVersion12Database() {
        val database = openLegacyDatabase(version = 12)
        try {
            createCommonPreConversationTables(database)
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `mode` TEXT NOT NULL DEFAULT 'normal',
                    `isArchived` INTEGER NOT NULL DEFAULT 0,
                    `summary` TEXT,
                    `contextSummary` TEXT,
                    `contextSummaryCutoffEntryDbId` INTEGER,
                    `contextSummaryUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                    `status` INTEGER NOT NULL DEFAULT 0,
                    `lastMessage` TEXT,
                    `messageCount` INTEGER NOT NULL DEFAULT 0,
                    `latestPromptTokens` INTEGER NOT NULL DEFAULT 0,
                    `promptTokenThreshold` INTEGER NOT NULL DEFAULT 128000,
                    `latestPromptTokensUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO conversations
                (id, title, mode, isArchived, summary, contextSummary, contextSummaryCutoffEntryDbId,
                 contextSummaryUpdatedAt, status, lastMessage, messageCount, latestPromptTokens,
                 promptTokenThreshold, latestPromptTokensUpdatedAt, createdAt, updatedAt)
                VALUES
                (1, 'Codex seed', 'codex', 0, NULL, NULL, NULL, 0, 0, NULL, 0, 0, 128000, 0, 1000, 1000)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_conversation_entries` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `conversationMode` TEXT NOT NULL,
                    `entryId` TEXT NOT NULL,
                    `entryType` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `summary` TEXT NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_entryId`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `entryId`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                `index_agent_conversation_entries_conversationId_conversationMode_updatedAt`
                ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `updatedAt`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `token_usage_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `isLocal` INTEGER NOT NULL DEFAULT 0,
                    `model` TEXT NOT NULL DEFAULT '',
                    `promptTokens` INTEGER NOT NULL DEFAULT 0,
                    `completionTokens` INTEGER NOT NULL DEFAULT 0,
                    `reasoningTokens` INTEGER NOT NULL DEFAULT 0,
                    `textTokens` INTEGER NOT NULL DEFAULT 0,
                    `cachedTokens` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_token_usage_records_createdAt`
                ON `token_usage_records` (`createdAt`)
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_token_usage_records_conversationId`
                ON `token_usage_records` (`conversationId`)
                """.trimIndent()
            )
        } finally {
            database.close()
        }
    }

    private fun createVersion13Database() {
        val database = openLegacyDatabase(version = 13)
        try {
            createCommonPreConversationTables(database)
            createVersion13ConversationTables(database)
            database.execSQL(
                """
                INSERT INTO conversations
                (id, title, mode, isArchived, summary, contextSummary, contextSummaryCutoffEntryDbId,
                 contextSummaryUpdatedAt, status, lastMessage, messageCount, latestPromptTokens,
                 promptTokenThreshold, latestPromptTokensUpdatedAt, createdAt, updatedAt)
                VALUES
                (1, 'Scheduled seed', 'normal', 0, NULL, NULL, NULL, 0, 0, NULL, 0, 0, 128000, 0, 1000, 1000)
                """.trimIndent()
            )
        } finally {
            database.close()
        }
    }

    private fun createVersion15Database() {
        val database = openLegacyDatabase(version = 15)
        try {
            createCommonPreConversationTables(database)
            createVersion13ConversationTables(database)
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN parentConversationId INTEGER"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN parentConversationMode TEXT"
            )
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN scheduledTaskId TEXT"
            )
            database.execSQL("DROP TABLE IF EXISTS execution_records")
            database.execSQL("DROP TABLE IF EXISTS study_records")
            database.execSQL("DROP TABLE IF EXISTS favorite_records")
            database.execSQL("DROP TABLE IF EXISTS cache_suggestion")
            database.execSQL(
                """
                INSERT INTO token_usage_records (
                    id,
                    conversationId,
                    isLocal,
                    model,
                    promptTokens,
                    completionTokens,
                    reasoningTokens,
                    textTokens,
                    cachedTokens,
                    createdAt
                ) VALUES
                    (1, 7, 1, 'legacy-local-model', 21, 8, 3, 5, 2, 1234),
                    (2, 8, 0, 'cloud-model', 34, 13, 5, 8, 3, 2345)
                """.trimIndent()
            )
        } finally {
            database.close()
        }
    }

    private fun createVersion13ConversationTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `mode` TEXT NOT NULL DEFAULT 'normal',
                `isArchived` INTEGER NOT NULL DEFAULT 0,
                `summary` TEXT,
                `contextSummary` TEXT,
                `contextSummaryCutoffEntryDbId` INTEGER,
                `contextSummaryUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                `status` INTEGER NOT NULL DEFAULT 0,
                `lastMessage` TEXT,
                `messageCount` INTEGER NOT NULL DEFAULT 0,
                `latestPromptTokens` INTEGER NOT NULL DEFAULT 0,
                `promptTokenThreshold` INTEGER NOT NULL DEFAULT 128000,
                `latestPromptTokensUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_conversation_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversationId` INTEGER NOT NULL,
                `conversationMode` TEXT NOT NULL,
                `entryId` TEXT NOT NULL,
                `entryType` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_agent_conversation_entries_conversationId_conversationMode_entryId`
            ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `entryId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_agent_conversation_entries_conversationId_conversationMode_updatedAt`
            ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `updatedAt`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `token_usage_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversationId` INTEGER NOT NULL,
                `isLocal` INTEGER NOT NULL DEFAULT 0,
                `model` TEXT NOT NULL DEFAULT '',
                `promptTokens` INTEGER NOT NULL DEFAULT 0,
                `completionTokens` INTEGER NOT NULL DEFAULT 0,
                `reasoningTokens` INTEGER NOT NULL DEFAULT 0,
                `textTokens` INTEGER NOT NULL DEFAULT 0,
                `cachedTokens` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_token_usage_records_createdAt`
            ON `token_usage_records` (`createdAt`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_token_usage_records_conversationId`
            ON `token_usage_records` (`conversationId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `codex_thread_bindings` (
                `conversationId` INTEGER NOT NULL,
                `threadId` TEXT NOT NULL,
                `cwd` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_codex_thread_bindings_threadId`
            ON `codex_thread_bindings` (`threadId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_codex_thread_bindings_updatedAt`
            ON `codex_thread_bindings` (`updatedAt`)
            """.trimIndent()
        )
    }

    private fun createCommonPreConversationTables(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_icons` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `appName` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `icon_base64` TEXT NOT NULL,
                `icon_path` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `study_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `suggestionId` TEXT NOT NULL,
                `appName` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `isFavorite` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorite_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `desc` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `imagePath` TEXT NOT NULL,
                `packageName` TEXT NOT NULL DEFAULT '',
                `status` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `execution_records` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `appName` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `nodeId` TEXT NOT NULL,
                `suggestionId` TEXT NOT NULL,
                `iconUrl` TEXT,
                `type` TEXT NOT NULL DEFAULT 'unknown',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `content` TEXT,
                `status` TEXT NOT NULL DEFAULT 'success'
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `messageId` TEXT NOT NULL,
                `type` INTEGER NOT NULL,
                `user` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cache_suggestion` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `suggestionId` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `indexNum` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun openLegacyDatabase(version: Int): SQLiteDatabase {
        testContext.deleteDatabase(TEST_DB_NAME)
        return testContext.openOrCreateDatabase(TEST_DB_NAME, Context.MODE_PRIVATE, null).apply {
            this.version = version
        }
    }

    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    companion object {
        private const val TEST_DB_NAME = "app-database-migration-test"
    }
}
