package cn.com.omnimind.baselib.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseHelper {
    // 保留既有 OSS 数据库文件名，避免用户升级后丢失本地数据。
    private const val LOCAL_DATABASE_NAME = AppDatabase.DATABASE_NAME + "oss"
    private var database: AppDatabase? = null

    // Migration from version 1 to 2 - adding cache_suggestion table
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Create cache_suggestion table
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
    }

    // Migration from version 2 to 3 - updating execution_records table
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Since we're adding non-nullable columns, we need to recreate the table
            // First, rename the existing table
            database.execSQL("ALTER TABLE execution_records RENAME TO execution_records_old")
            
            // Create the new table with updated schema
            database.execSQL(
                """
                CREATE TABLE execution_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    appName TEXT NOT NULL,
                    packageName TEXT NOT NULL,
                    nodeId TEXT NOT NULL,
                    suggestionId TEXT NOT NULL,
                    iconUrl TEXT,
                    type TEXT NOT NULL DEFAULT 'unknown',
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            
            // Copy data from old table (filling default values for new columns)
            database.execSQL(
                """
                INSERT INTO execution_records (id, title, appName, packageName, nodeId, suggestionId, iconUrl, type, createdAt, updatedAt)
                SELECT id, title, appName, packageName, '' AS nodeId, '' AS suggestionId, NULL AS iconUrl, 'unknown' AS type, createdAt, updatedAt
                FROM execution_records_old
                """.trimIndent()
            )
            
            // Drop the old table
            database.execSQL("DROP TABLE execution_records_old")
        }
    }

    // Migration from version 3 to 4 - adding packageName column to favorite_records table
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Since we're adding a new column with a default value, we can simply alter the table
            database.execSQL("ALTER TABLE favorite_records ADD COLUMN packageName TEXT NOT NULL DEFAULT ''")
        }
    }

    // Migration from version 4 to 5 - adding content and status columns to execution_records table
    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE execution_records ADD COLUMN content TEXT")
            database.execSQL("ALTER TABLE execution_records ADD COLUMN status TEXT NOT NULL DEFAULT 'success'")
        }
    }

    // Migration from version 5 to 6 - adding conversations table
    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Keep the released v6 schema exact so later migrations can apply cleanly.
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `title` TEXT NOT NULL,
                    `summary` TEXT,
                    `status` INTEGER NOT NULL DEFAULT 0,
                    `lastMessage` TEXT,
                    `messageCount` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )

        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'normal'"
            )
        }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
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
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummary TEXT")
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummaryCutoffEntryDbId INTEGER")
            database.execSQL("ALTER TABLE conversations ADD COLUMN contextSummaryUpdatedAt INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE conversations ADD COLUMN latestPromptTokens INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE conversations ADD COLUMN promptTokenThreshold INTEGER NOT NULL DEFAULT 128000")
            database.execSQL("ALTER TABLE conversations ADD COLUMN latestPromptTokensUpdatedAt INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `token_usage_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
                    `model` TEXT NOT NULL DEFAULT '',
                    `promptTokens` INTEGER NOT NULL DEFAULT 0,
                    `completionTokens` INTEGER NOT NULL DEFAULT 0,
                    `reasoningTokens` INTEGER NOT NULL DEFAULT 0,
                    `textTokens` INTEGER NOT NULL DEFAULT 0,
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
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE token_usage_records ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
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
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
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
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS execution_records")
            database.execSQL("DROP TABLE IF EXISTS study_records")
            database.execSQL("DROP TABLE IF EXISTS favorite_records")
            database.execSQL("DROP TABLE IF EXISTS cache_suggestion")
        }
    }

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `token_usage_records_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `conversationId` INTEGER NOT NULL,
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
                INSERT INTO `token_usage_records_new` (
                    `id`,
                    `conversationId`,
                    `model`,
                    `promptTokens`,
                    `completionTokens`,
                    `reasoningTokens`,
                    `textTokens`,
                    `cachedTokens`,
                    `createdAt`
                )
                SELECT
                    `id`,
                    `conversationId`,
                    `model`,
                    `promptTokens`,
                    `completionTokens`,
                    `reasoningTokens`,
                    `textTokens`,
                    `cachedTokens`,
                    `createdAt`
                FROM `token_usage_records`
                WHERE `isLocal` = 0
                """.trimIndent()
            )
            database.execSQL("DROP TABLE `token_usage_records`")
            database.execSQL(
                "ALTER TABLE `token_usage_records_new` RENAME TO `token_usage_records`"
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
        }
    }

    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE token_usage_records ADD COLUMN cacheCreationTokens INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Older builds could delete a Conversation without deleting its
            // ACP binding. Bindings are only an execution index; remove
            // orphan rows during upgrade while leaving all conversation and
            // message tables untouched.
            database.execSQL(
                """
                DELETE FROM codex_thread_bindings
                WHERE conversationId NOT IN (
                    SELECT id FROM conversations
                )
                """.trimIndent()
            )
        }
    }

    internal val ALL_MIGRATIONS = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18
    )

    fun init(context: Context) {
        database = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, LOCAL_DATABASE_NAME
        ).addMigrations(*ALL_MIGRATIONS).build()

    }

    fun getDatabase(): AppDatabase {
        return database ?: throw IllegalStateException("Database not initialized")
    }

    // AppIcons相关方法
    suspend fun getAppIconByPackageName(packageName: String): AppIcons? {
        return getDatabase().appIconsDao().getByPackageName(packageName)
    }

    suspend fun getAppIconsByPackageNames(packageNames: List<String>): List<AppIcons> {
        return getDatabase().appIconsDao().getByPackageNames(packageNames)
    }

    // Message相关方法
    suspend fun insertMessage(message: Message): Long {
        return getDatabase().messageDao().insert(message)
    }

    suspend fun updateMessage(message: Message) {
        getDatabase().messageDao().update(message)
    }

    suspend fun getMessageById(id: Long): Message? {
        return getDatabase().messageDao().getById(id)
    }

    suspend fun getMessagesByPage(page: Int, pageSize: Int): PagedMessagesResult {
        val offset = page * pageSize
        val messages = getDatabase().messageDao().getMessagesByPage(offset, pageSize)
        val totalMessageCount = getDatabase().messageDao().getMessageCount()
        val hasMore = offset + messages.size < totalMessageCount
        return PagedMessagesResult(
            messageList = messages, hasMore = hasMore
        )
    }

    suspend fun deleteMessageById(id: Long): Int {
        return getDatabase().messageDao().deleteById(id)
    }

    suspend fun deleteAllMessages(): Int {
        return getDatabase().messageDao().deleteAll()
    }

    // 新增insert方法
    suspend fun insertAppIcon(
        appName: String,
        packageName: String,
        iconBase64: String,
        iconPath: String = ""
    ): Boolean {
        return getDatabase().appIconsDao().insert(
            AppIcons(
                id = 0,
                appName = appName,
                packageName = packageName,
                icon_base64 = iconBase64,
                icon_path = iconPath,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        ) > 0
    }


    // TokenUsageRecord相关方法
    suspend fun insertTokenUsageRecord(record: TokenUsageRecord): Long {
        return getDatabase().tokenUsageRecordDao().insert(record)
    }

    suspend fun getTokenUsageRecordsSince(since: Long): List<TokenUsageRecord> {
        return getDatabase().tokenUsageRecordDao().getRecordsSince(since)
    }

    // Conversation相关方法
    suspend fun insertConversation(conversation: Conversation): Long {
        return getDatabase().conversationDao().insert(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        getDatabase().conversationDao().update(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation) {
        getDatabase().conversationDao().delete(conversation)
    }

    suspend fun deleteConversationById(id: Long): Int {
        return getDatabase().conversationDao().deleteById(id)
    }

    suspend fun getConversationById(id: Long): Conversation? {
        return getDatabase().conversationDao().getById(id)
    }

    suspend fun getAllConversations(): List<Conversation> {
        return getDatabase().conversationDao().getAll()
    }

    suspend fun getUnarchivedConversations(): List<Conversation> {
        return getDatabase().conversationDao().getUnarchived()
    }

    suspend fun getArchivedConversations(): List<Conversation> {
        return getDatabase().conversationDao().getArchived()
    }

    suspend fun archiveConversationsUpdatedBefore(cutoff: Long): Int {
        return getDatabase().conversationDao().archiveUpdatedBefore(cutoff)
    }

    suspend fun getConversationsByPage(offset: Int, limit: Int): List<Conversation> {
        return getDatabase().conversationDao().getConversationsByPage(offset, limit)
    }

    suspend fun getConversationCount(): Int {
        return getDatabase().conversationDao().getConversationCount()
    }

    suspend fun upsertAgentConversationEntry(entry: AgentConversationEntry): Long {
        return getDatabase().agentConversationEntryDao().upsert(entry)
    }

    suspend fun getAgentConversationEntriesAsc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesAsc(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun getAgentConversationEntriesDesc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDesc(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun getAgentConversationEntryByThreadAndId(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getByThreadAndEntryId(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId
        )
    }

    suspend fun deleteAgentConversationThread(
        conversationId: Long,
        conversationMode: String
    ): Int {
        return getDatabase().agentConversationEntryDao().deleteThreadEntries(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun deleteAgentConversationEntries(conversationId: Long): Int {
        return getDatabase().agentConversationEntryDao().deleteConversationEntries(conversationId)
    }

    suspend fun getLatestAgentConversationEntry(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getLatestConversationEntry(conversationId)
    }

    suspend fun getLatestAgentConversationEntryHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getLatestConversationEntryHeader(
            conversationId
        )
    }

    suspend fun getLatestAgentConversationUpdate(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getLatestConversationUpdate(conversationId)
    }

    suspend fun getLatestAgentConversationUpdateHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getLatestConversationUpdateHeader(
            conversationId
        )
    }

    suspend fun getEarliestAgentConversationEntry(conversationId: Long): AgentConversationEntry? {
        return getDatabase().agentConversationEntryDao().getEarliestConversationEntry(conversationId)
    }

    suspend fun getEarliestAgentConversationEntryHeader(
        conversationId: Long
    ): AgentConversationEntryHeader? {
        return getDatabase().agentConversationEntryDao().getEarliestConversationEntryHeader(
            conversationId
        )
    }

    suspend fun countAgentConversationEntries(conversationId: Long): Int {
        return getDatabase().agentConversationEntryDao().countConversationEntries(conversationId)
    }

    suspend fun getAgentConversationIdsWithStreamEvents(): List<Long> {
        return getDatabase().agentConversationEntryDao().getConversationIdsWithStreamEvents()
    }

    suspend fun deleteAgentConversationStreamEvents(): Int {
        return getDatabase().agentConversationEntryDao().deleteStreamEvents()
    }

    suspend fun getAgentConversationEntriesDescPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): List<AgentConversationEntry> {
        return getDatabase().agentConversationEntryDao().getThreadEntriesDescPaged(
            conversationId = conversationId,
            conversationMode = conversationMode,
            limit = limit,
            offset = offset
        )
    }

    suspend fun countAgentConversationThreadEntries(
        conversationId: Long,
        conversationMode: String
    ): Int {
        return getDatabase().agentConversationEntryDao().countThreadEntries(
            conversationId = conversationId,
            conversationMode = conversationMode
        )
    }

    suspend fun incrementConversationMessageCount(id: Long) {
        getDatabase().conversationDao().incrementMessageCount(id, System.currentTimeMillis())
    }

    suspend fun upsertAgentSessionBinding(binding: AgentSessionBinding) {
        getDatabase().agentSessionBindingDao().upsert(binding)
    }

    suspend fun getAllAgentSessionBindings(): List<AgentSessionBinding> {
        return getDatabase().agentSessionBindingDao().getAll()
    }

    suspend fun getAgentSessionBindingsByConversationIds(
        conversationIds: List<Long>
    ): List<AgentSessionBinding> {
        if (conversationIds.isEmpty()) return emptyList()
        return getDatabase().agentSessionBindingDao()
            .getByConversationIds(conversationIds)
    }

    suspend fun getAgentSessionBindingByConversationId(conversationId: Long): AgentSessionBinding? {
        return getDatabase().agentSessionBindingDao().getByConversationId(conversationId)
    }

    suspend fun getAgentSessionBindingByThreadId(threadId: String): AgentSessionBinding? {
        return getDatabase().agentSessionBindingDao().getByThreadId(threadId)
    }

    suspend fun deleteAgentSessionBindingByConversationId(conversationId: Long): Int {
        return getDatabase().agentSessionBindingDao().deleteByConversationId(conversationId)
    }

    suspend fun deleteAgentSessionBindingByThreadId(threadId: String): Int {
        return getDatabase().agentSessionBindingDao().deleteByThreadId(threadId)
    }

}
