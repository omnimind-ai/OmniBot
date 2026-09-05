package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentConversationEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AgentConversationEntry): Long

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getThreadEntriesAsc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry>

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getThreadEntriesDesc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry>

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getConversationEntriesDesc(conversationId: Long): List<AgentConversationEntry>

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getConversationEntriesAsc(conversationId: Long): List<AgentConversationEntry>

    @Query(
        """
        SELECT
            id,
            conversationId,
            conversationMode,
            entryId,
            entryType,
            status,
            CASE
                WHEN LENGTH(summary) > 2048 THEN substr(summary, 1, 2048)
                ELSE summary
            END AS summary,
            createdAt,
            updatedAt
        FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationEntryHeader(conversationId: Long): AgentConversationEntryHeader?

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationEntry(conversationId: Long): AgentConversationEntry?

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun getEarliestConversationEntry(conversationId: Long): AgentConversationEntry?

    @Query(
        """
        SELECT
            id,
            conversationId,
            conversationMode,
            entryId,
            entryType,
            status,
            CASE
                WHEN LENGTH(summary) > 2048 THEN substr(summary, 1, 2048)
                ELSE summary
            END AS summary,
            createdAt,
            updatedAt
        FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun getEarliestConversationEntryHeader(conversationId: Long): AgentConversationEntryHeader?

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationUpdate(conversationId: Long): AgentConversationEntry?

    @Query(
        """
        SELECT
            id,
            conversationId,
            conversationMode,
            entryId,
            entryType,
            status,
            CASE
                WHEN LENGTH(summary) > 2048 THEN substr(summary, 1, 2048)
                ELSE summary
            END AS summary,
            createdAt,
            updatedAt
        FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationUpdateHeader(conversationId: Long): AgentConversationEntryHeader?

    @Query(
        """
        SELECT COUNT(*) FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        """
    )
    suspend fun countConversationEntries(conversationId: Long): Int

    @Query(
        """
        SELECT DISTINCT conversationId FROM agent_conversation_entries
        WHERE entryType = 'stream_event'
        """
    )
    suspend fun getConversationIdsWithStreamEvents(): List<Long>

    @Query(
        """
        DELETE FROM agent_conversation_entries
        WHERE entryType = 'stream_event'
        """
    )
    suspend fun deleteStreamEvents(): Int

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryId = :entryId
          AND entryType != 'stream_event'
        LIMIT 1
        """
    )
    suspend fun getByThreadAndEntryId(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntry?

    @Query(
        """
        SELECT * FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getThreadEntriesDescPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): List<AgentConversationEntry>

    @Query(
        """
        SELECT COUNT(*) FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        """
    )
    suspend fun countThreadEntries(
        conversationId: Long,
        conversationMode: String
    ): Int

    @Query(
        """
        DELETE FROM agent_conversation_entries
        WHERE conversationId = :conversationId AND conversationMode = :conversationMode
        """
    )
    suspend fun deleteThreadEntries(
        conversationId: Long,
        conversationMode: String
    ): Int

    @Query(
        """
        DELETE FROM agent_conversation_entries
        WHERE conversationId = :conversationId
        """
    )
    suspend fun deleteConversationEntries(conversationId: Long): Int
}
