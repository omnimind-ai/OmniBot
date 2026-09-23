package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Embedded
import java.io.OutputStream
import okio.Buffer

@Dao
interface AgentConversationEntryDao {
    @Query("""
        SELECT id, conversationId, conversationMode, entryId, entryType, status,
               '' AS summary, createdAt, updatedAt
        FROM agent_conversation_entries
        WHERE conversationId = :conversationId AND id > :afterEntryId
          AND entryType = 'tool_event'
        ORDER BY id ASC
    """)
    fun observeToolHeadersAfter(conversationId: Long, afterEntryId: Long):
        kotlinx.coroutines.flow.Flow<List<AgentConversationEntryHeader>>

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
          AND id > :afterEntryId
          AND (:afterEntryId = 0 OR NOT EXISTS (
            SELECT 1 FROM agent_conversation_entries AS preferred
            WHERE preferred.conversationId = :conversationId
              AND preferred.entryId = agent_conversation_entries.entryId
              AND preferred.entryType != 'stream_event'
              AND ((:conversationMode = 'codex' AND preferred.conversationMode = 'agent')
                OR (:conversationMode = 'normal' AND preferred.conversationMode IN ('agent', 'codex')))
          ))
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getThreadEntriesDescPagedSlices(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int,
        afterEntryId: Long = 0
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

    @Query("""
        SELECT $CHUNKED_ENTRY_PROJECTION FROM agent_conversation_entries
        WHERE conversationId = :conversationId AND conversationMode IN (:modes)
          AND entryType != 'stream_event'
          AND NOT EXISTS (
            SELECT 1 FROM agent_conversation_entries AS preferred
            WHERE preferred.conversationId = :conversationId
              AND preferred.entryId = agent_conversation_entries.entryId
              AND preferred.conversationMode IN (:modes)
              AND preferred.entryType != 'stream_event'
              AND (CASE preferred.conversationMode WHEN 'agent' THEN 0 WHEN 'codex' THEN 1 ELSE 2 END)
                < (CASE agent_conversation_entries.conversationMode WHEN 'agent' THEN 0 WHEN 'codex' THEN 1 ELSE 2 END)
          )
        ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset
    """)
    suspend fun getLogicalThreadPageSlices(conversationId: Long, modes: List<String>, limit: Int, offset: Int): List<AgentConversationEntrySlice>

    @Transaction
    suspend fun getLogicalThreadPage(conversationId: Long, modes: List<String>, limit: Int, offset: Int): List<AgentConversationEntry> =
        getLogicalThreadPageSlices(conversationId, modes, limit, offset).map { hydrate(it) }

    @Transaction
    suspend fun getThreadEntriesDescPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int,
        afterEntryId: Long = 0
    ): List<AgentConversationEntry> =
        getThreadEntriesDescPagedSlices(conversationId, conversationMode, limit, offset, afterEntryId).map { hydrate(it) }

    @Query("SELECT substr(CASE WHEN :summary THEN CAST(summary AS BLOB) ELSE CAST(payloadJson AS BLOB) END, :offset, 32768) FROM agent_conversation_entries WHERE id = :id")
    suspend fun readEntryChunk(id: Long, summary: Boolean, offset: Long): ByteArray?

    @Query("SELECT substr(summary, 1, 2048) FROM agent_conversation_entries WHERE id = :id")
    suspend fun readSummaryPreview(id: Long): String?

    // A display projection must never replace the complete stored payload.
    @Query("""UPDATE agent_conversation_entries SET status = :status,
        summary = CASE WHEN trim(summary) = '' THEN :summary ELSE summary END,
        updatedAt = :updatedAt WHERE id = :id AND entryType = 'tool_event' AND status = 'running'
        AND updatedAt = :expectedUpdatedAt""")
    suspend fun updateInterruptedToolHeader(
        id: Long, expectedUpdatedAt: Long, status: String, summary: String, updatedAt: Long,
    ): Int

    /** Caller owns the read transaction, so the size and all chunks share a snapshot. */
    suspend fun copyEntryTextTo(slice: AgentConversationEntrySlice, summary: Boolean, output: OutputStream) {
        val bytes = if (summary) slice.summaryBytes else slice.payloadBytes
        val inline = if (summary) slice.entry.summary else slice.entry.payloadJson
        copyConversationEntryText(bytes, inline, output) { offset ->
            readEntryChunk(slice.entry.id, summary, offset)
        }
    }

    suspend fun hydrate(slice: AgentConversationEntrySlice): AgentConversationEntry {
        suspend fun fullText(summary: Boolean, bytes: Long, inline: String): String {
            if (bytes <= 32768) return inline
            // Full reads still serve mutation/export callers. Segments avoid the
            // geometric, contiguous-array reallocations of ByteArrayOutputStream.
            return Buffer().use { buffer ->
                copyEntryTextTo(slice, summary, buffer.outputStream())
                buffer.readUtf8()
            }
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
