package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Embedded
import java.io.ByteArrayOutputStream

@Dao
interface AgentConversationEntryDao {
    companion object {
        const val CHUNKED_ENTRY_PROJECTION = """id, conversationId, conversationMode, entryId, entryType, status,
            CASE WHEN length(CAST(summary AS BLOB)) > 32768 THEN '' ELSE summary END AS summary,
            CASE WHEN length(CAST(payloadJson AS BLOB)) > 32768 THEN '' ELSE payloadJson END AS payloadJson,
            createdAt, updatedAt,
            length(CAST(summary AS BLOB)) AS summaryBytes,
            length(CAST(payloadJson AS BLOB)) AS payloadBytes"""
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: AgentConversationEntry): Long

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getThreadEntriesAscSlices(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntrySlice>

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getThreadEntriesDescSlices(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntrySlice>

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        """
    )
    suspend fun getConversationEntriesDescSlices(conversationId: Long): List<AgentConversationEntrySlice>

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        """
    )
    suspend fun getConversationEntriesAscSlices(conversationId: Long): List<AgentConversationEntrySlice>

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
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationEntrySlices(conversationId: Long): AgentConversationEntrySlice?

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """
    )
    suspend fun getEarliestConversationEntrySlices(conversationId: Long): AgentConversationEntrySlice?

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
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND entryType != 'stream_event'
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestConversationUpdateSlices(conversationId: Long): AgentConversationEntrySlice?

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
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryId = :entryId
          AND entryType != 'stream_event'
        LIMIT 1
        """
    )
    suspend fun getByThreadAndEntryIdSlices(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntrySlice?

    @Query(
        """
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId
          AND conversationMode = :conversationMode
          AND entryType != 'stream_event'
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getThreadEntriesDescPagedSlices(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): List<AgentConversationEntrySlice>

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

    // Split large values before they enter CursorWindow. Reading fewer rows alone
    // cannot help a single oversized ACP item. Transactions keep chunks consistent.
    @Transaction
    suspend fun getThreadEntriesAsc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> =
        getThreadEntriesAscSlices(conversationId, conversationMode).map { hydrate(it) }

    @Transaction
    suspend fun getThreadEntriesDesc(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> =
        getThreadEntriesDescSlices(conversationId, conversationMode).map { hydrate(it) }

    @Transaction
    suspend fun getConversationEntriesDesc(conversationId: Long): List<AgentConversationEntry> =
        getConversationEntriesDescSlices(conversationId).map { hydrate(it) }

    @Transaction
    suspend fun getConversationEntriesAsc(conversationId: Long): List<AgentConversationEntry> =
        getConversationEntriesAscSlices(conversationId).map { hydrate(it) }

    @Transaction
    suspend fun getLatestConversationEntry(conversationId: Long): AgentConversationEntry? =
        getLatestConversationEntrySlices(conversationId)?.let { hydrate(it) }

    @Transaction
    suspend fun getEarliestConversationEntry(conversationId: Long): AgentConversationEntry? =
        getEarliestConversationEntrySlices(conversationId)?.let { hydrate(it) }

    @Transaction
    suspend fun getLatestConversationUpdate(conversationId: Long): AgentConversationEntry? =
        getLatestConversationUpdateSlices(conversationId)?.let { hydrate(it) }

    @Transaction
    suspend fun getByThreadAndEntryId(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntry? =
        getByThreadAndEntryIdSlices(conversationId, conversationMode, entryId)?.let { hydrate(it) }

    @Transaction
    suspend fun getThreadEntriesDescPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): List<AgentConversationEntry> =
        getThreadEntriesDescPagedSlices(conversationId, conversationMode, limit, offset).map { hydrate(it) }

    @Query("SELECT substr(CASE WHEN :summary THEN CAST(summary AS BLOB) ELSE CAST(payloadJson AS BLOB) END, :offset, 32768) FROM agent_conversation_entries WHERE id = :id")
    suspend fun readEntryChunk(id: Long, summary: Boolean, offset: Long): ByteArray?

    suspend fun hydrate(slice: AgentConversationEntrySlice): AgentConversationEntry {
        suspend fun fullText(summary: Boolean, bytes: Long, inline: String): String {
            if (bytes <= 32768) return inline
            val output = ByteArrayOutputStream()
            var offset = 1L
            while (offset <= bytes) {
                val chunk = readEntryChunk(slice.entry.id, summary, offset)
                check(chunk != null && chunk.isNotEmpty()) { "Conversation entry disappeared while reading" }
                output.write(chunk)
                offset += chunk.size
            }
            check(output.size().toLong() == bytes) { "Conversation entry length changed while reading" }
            return output.toString("UTF-8")
        }
        return slice.entry.copy(
            summary = fullText(true, slice.summaryBytes, slice.entry.summary),
            payloadJson = fullText(false, slice.payloadBytes, slice.entry.payloadJson),
        )
    }
}

data class AgentConversationEntrySlice(
    @Embedded val entry: AgentConversationEntry,
    val summaryBytes: Long,
    val payloadBytes: Long,
)
