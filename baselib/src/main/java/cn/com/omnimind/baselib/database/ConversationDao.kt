package cn.com.omnimind.baselib.database

import androidx.room.*

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Transaction
    suspend fun updatePreservingCheckpoint(conversation: Conversation) {
        val current = getById(conversation.id)
        val updated = if (current != null &&
            current.contextSummaryUpdatedAt > conversation.contextSummaryUpdatedAt) {
            conversation.copy(
                contextSummary = current.contextSummary,
                contextSummaryCutoffEntryDbId = current.contextSummaryCutoffEntryDbId,
                contextSummaryUpdatedAt = current.contextSummaryUpdatedAt,
            )
        } else conversation
        update(updated)
    }

    @Query("UPDATE conversations SET contextSummary = NULL, contextSummaryCutoffEntryDbId = NULL, contextSummaryUpdatedAt = 0 WHERE id = :id")
    suspend fun clearContextCheckpoint(id: Long)

    @Delete
    suspend fun delete(conversation: Conversation)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: Long): Conversation?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    suspend fun getUnarchived(): List<Conversation>

    @Query("SELECT * FROM conversations WHERE isArchived = 1 ORDER BY updatedAt DESC")
    suspend fun getArchived(): List<Conversation>

    @Query(
        "UPDATE conversations SET isArchived = 1 " +
            "WHERE isArchived = 0 AND updatedAt < :cutoff"
    )
    suspend fun archiveUpdatedBefore(cutoff: Long): Int

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun getConversationsByPage(offset: Int, limit: Int): List<Conversation>

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM conversations")
    suspend fun deleteAll(): Int

    @Query("UPDATE conversations SET messageCount = messageCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementMessageCount(id: Long, updatedAt: Long)

    @Query("SELECT * FROM conversations WHERE status = :status ORDER BY updatedAt DESC")
    suspend fun getByStatus(status: Int): List<Conversation>
}
