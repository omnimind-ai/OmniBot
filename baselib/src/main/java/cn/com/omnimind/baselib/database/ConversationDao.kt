package cn.com.omnimind.baselib.database

import androidx.room.*

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Update
    suspend fun update(conversation: Conversation)

    @Transaction
    suspend fun updatePreservingCheckpoint(conversation: Conversation, preserveLatestMetadata: Boolean = false) {
        val current = getById(conversation.id)
        val candidate = if (current != null && preserveLatestMetadata) {
            conversation.copy(
                title = current.title.ifEmpty { conversation.title },
                summary = conversation.summary ?: current.summary,
                isArchived = current.isArchived,
                isPinned = current.isPinned,
                parentConversationId = current.parentConversationId,
                parentConversationMode = current.parentConversationMode,
                scheduledTaskId = current.scheduledTaskId,
                createdAt = current.createdAt,
            )
        } else conversation
        val updated = if (current != null) {
            candidate.copy(
                contextSummary = current.contextSummary,
                contextSummaryCutoffEntryDbId = current.contextSummaryCutoffEntryDbId,
                contextSummaryUpdatedAt = current.contextSummaryUpdatedAt,
            )
        } else candidate
        // Generic history snapshots do not own the user setting or usage clock.
        update(if (current == null) updated else updated.copy(
            promptTokenThreshold = current.promptTokenThreshold,
            latestPromptTokens = if (current.latestPromptTokensUpdatedAt > updated.latestPromptTokensUpdatedAt)
                current.latestPromptTokens else updated.latestPromptTokens,
            latestPromptTokensUpdatedAt = maxOf(current.latestPromptTokensUpdatedAt, updated.latestPromptTokensUpdatedAt),
        ))
    }

    @Query("UPDATE conversations SET contextSummary = NULL, contextSummaryCutoffEntryDbId = NULL, contextSummaryUpdatedAt = contextSummaryUpdatedAt + 1 WHERE id = :id")
    suspend fun clearContextCheckpoint(id: Long)

    @Query("UPDATE conversations SET contextSummary = :summary, contextSummaryCutoffEntryDbId = :cutoff, contextSummaryUpdatedAt = MAX(contextSummaryUpdatedAt + 1, :at), updatedAt = MAX(updatedAt, :at) WHERE id = :id AND contextSummaryUpdatedAt = :expectedRevision")
    suspend fun commitContextCheckpoint(id: Long, summary: String, cutoff: Long, expectedRevision: Long, at: Long): Int

    @Query("UPDATE conversations SET promptTokenThreshold = :threshold, updatedAt = MAX(updatedAt, :at) WHERE id = :id")
    suspend fun updatePromptThreshold(id: Long, threshold: Int, at: Long)

    @Query("UPDATE conversations SET latestPromptTokens = :tokens, latestPromptTokensUpdatedAt = :at, updatedAt = MAX(updatedAt, :at) WHERE id = :id AND latestPromptTokensUpdatedAt <= :at")
    suspend fun updatePromptUsage(id: Long, tokens: Int, at: Long)

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

    @Query("""
        SELECT * FROM conversations
        WHERE (:archivedOnly = 0 OR isArchived = 1)
          AND (:archivedOnly = 1 OR :includeArchived = 1 OR isArchived = 0)
          AND (:mode IS NULL OR CASE WHEN mode IN ('normal','codex','acp','coding','') THEN 'agent' ELSE mode END = :mode)
        ORDER BY updatedAt DESC, CASE WHEN mode = 'subagent' THEN 1 ELSE 0 END,
                 createdAt DESC, id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getDisplayPage(offset: Int, limit: Int, includeArchived: Boolean, archivedOnly: Boolean, mode: String?): List<Conversation>

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
