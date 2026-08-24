package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentSessionBindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: AgentSessionBinding)

    @Query("SELECT * FROM codex_thread_bindings")
    suspend fun getAll(): List<AgentSessionBinding>

    @Query("SELECT * FROM codex_thread_bindings WHERE conversationId IN (:conversationIds)")
    suspend fun getByConversationIds(conversationIds: List<Long>): List<AgentSessionBinding>

    @Query("SELECT * FROM codex_thread_bindings WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getByConversationId(conversationId: Long): AgentSessionBinding?

    @Query("SELECT * FROM codex_thread_bindings WHERE threadId = :threadId LIMIT 1")
    suspend fun getByThreadId(threadId: String): AgentSessionBinding?

    @Query("DELETE FROM codex_thread_bindings WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long): Int

    @Query("DELETE FROM codex_thread_bindings WHERE threadId = :threadId")
    suspend fun deleteByThreadId(threadId: String): Int
}
