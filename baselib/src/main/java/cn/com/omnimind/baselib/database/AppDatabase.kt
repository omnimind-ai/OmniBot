package cn.com.omnimind.baselib.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [

        AppIcons::class,
        Message::class,
        Conversation::class,
        AgentConversationEntry::class,
        TokenUsageRecord::class,
        AgentSessionBinding::class
    ],
    version = 18,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appIconsDao(): AppIconsDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun agentConversationEntryDao(): AgentConversationEntryDao
    abstract fun tokenUsageRecordDao(): TokenUsageRecordDao
    abstract fun agentSessionBindingDao(): AgentSessionBindingDao

    companion object {
        const val DATABASE_NAME = "omnibot_cache_database"
    }
}
