package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.AgentConversationEntryHeader
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class AgentConversationHistoryRepository(
    @Suppress("UNUSED_PARAMETER")
    private val context: Context
) {
    data class ContextCompactionCandidate(
        val conversation: Conversation,
        val entriesToCompact: List<AgentConversationEntry>,
        val cutoffEntryDbId: Long
    )

    data class PromptSeed(
        val historyMessages: List<ChatCompletionMessage>
    )

    companion object {
        // Android's CursorWindow is bounded. Reading a whole conversation in
        // one Room query makes a few large ACP/tool payloads exhaust that
        // window and breaks the next prompt. Keep each native read bounded;
        // the repository still returns the same complete logical snapshot.
        private const val SAFE_HISTORY_PAGE_SIZE = 16

        const val ENTRY_TYPE_USER_MESSAGE = "user_message"
        const val ENTRY_TYPE_ASSISTANT_MESSAGE = "assistant_message"
        const val ENTRY_TYPE_TOOL_EVENT = "tool_event"
        const val ENTRY_TYPE_UI_CARD = "ui_card"
        /** Raw ACP notifications retained outside the user-facing projection. */
        const val ENTRY_TYPE_STREAM_EVENT = "stream_event"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_INTERRUPTED = "interrupted"

        internal fun preserveFullToolPayload(existing: Map<String, Any?>, incoming: Map<String, Any?>): Map<String, Any?> {
            if (incoming["payloadCompacted"] == true && existing.isNotEmpty()) return existing
            return incoming.toMutableMap().apply {
                listOf("toolCallId", "sessionId", "turnId", "modelToolCallId",
                    "modelAssistantMessageJson", "modelToolResultMessageJson").forEach { key ->
                    // Presentation snapshots may have empty placeholders before
                    // the canonical tool messages arrive. Only retain real values.
                    existing[key]?.takeIf { it.toString().isNotBlank() }
                        ?.let { put(key, it) }
                }
            }
        }

        internal fun resolveCompactionToolCutoff(
            entries: List<AgentConversationEntry>,
            compactedMessages: List<ChatCompletionMessage>,
            afterEntryId: Long
        ): Long? {
            if (compactedMessages.lastOrNull()?.role != "tool") return null
            val assistant = compactedMessages.lastOrNull { !it.toolCalls.isNullOrEmpty() }
                ?: return null
            val results = compactedMessages.takeLastWhile { it.role == "tool" }.associateBy { it.toolCallId }
            if (!assistant.toolCalls.orEmpty().all { it.id in results }) return null
            val required = assistant.toolCalls.orEmpty().map { it.id }.toSet()
            val codec = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            fun resultId(payload: Map<String, Any?>): String? =
                payload["toolCallId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: payload["modelToolCallId"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: payload["modelToolResultMessageJson"]?.toString()?.let {
                        runCatching { codec.decodeFromString(ChatCompletionMessage.serializer(), it).toolCallId }.getOrNull()
                    }
            val groups = entries.filter { it.entryType == ENTRY_TYPE_TOOL_EVENT && it.id > afterEntryId && it.status != STATUS_RUNNING }
                .map { entry -> entry to AgentConversationHistorySupport.readMap(entry.payloadJson) }
                .filter { (entry, payload) -> "restored_${entry.entryId}" in required || resultId(payload) in required }
                .groupBy { (_, payload) -> listOf(payload["sessionId"], payload["turnId"], payload["taskId"]) }
            return groups.values.mapNotNull { group ->
                val ids = group.map { (entry, payload) ->
                    "restored_${entry.entryId}".takeIf { it in required } ?: resultId(payload)
                }
                if (ids.size == required.size && ids.toSet() == required) group.maxOf { it.first.id } else null
            }.singleOrNull()

        }

        internal suspend fun awaitCompactionToolCutoff(
            entries: Flow<List<AgentConversationEntry>>,
            compactedMessages: List<ChatCompletionMessage>,
            afterEntryId: Long,
        ): Long = withTimeoutOrNull(30_000) {
            entries.map { resolveCompactionToolCutoff(it, compactedMessages, afterEntryId) }
                .filterNotNull().first()
        } ?: error("工具历史尚未完成保存，未提交不完整的压缩检查点。")

        /**
         * Applies pagination after the compatibility reader has merged the
         * canonical Agent bucket with legacy Xiaowan buckets. Paginating the
         * database query first loses legacy `normal` rows because they live in
         * a separate conversationMode partition.
         */
        internal fun pageConversationEntries(
            entries: List<AgentConversationEntry>,
            limit: Int,
            offset: Int
        ): Pair<List<AgentConversationEntry>, Boolean> {
            // Compatibility buckets are merged before this boundary and are
            // not guaranteed to arrive in the same order in tests, Room
            // implementations, or future storage adapters. Pagination must be
            // based on one deterministic newest-first timeline; otherwise the
            // first page can expose the oldest message and make later context
            // appear missing.
            val ordered = entries
                .distinctBy { it.entryId }
                .sortedWith(
                    compareByDescending<AgentConversationEntry> { it.createdAt }
                        .thenByDescending { it.id }
                )
            val safeOffset = offset.coerceAtLeast(0)
            val remaining = ordered.drop(safeOffset)
            val page = if (limit > 0) remaining.take(limit) else remaining
            return page to (safeOffset + page.size < ordered.size)
        }

        /**
         * Produces one chronological fork snapshot from canonical and legacy
         * storage buckets.  The caller supplies buckets in precedence order;
         * this keeps a migrated `agent` row authoritative over an equivalent
         * legacy `normal` row without changing either source bucket.
         */
        internal fun entriesForFork(entries: List<AgentConversationEntry>): List<AgentConversationEntry> {
            return entries
                .filter { it.entryType != ENTRY_TYPE_STREAM_EVENT }
                .distinctBy { it.entryId }
                .sortedWith(compareBy<AgentConversationEntry> { it.createdAt }.thenBy { it.id })
        }

    }

    private val gson = Gson()

    suspend fun upsertUserMessage(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>> = emptyList(),
        streamMeta: Map<String, Any?>? = null,
        turnUsage: Map<String, Any?>? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = AgentConversationHistorySupport.buildTextMessagePayload(
            messageId = entryId,
            user = 1,
            text = text,
            attachments = attachments,
            agentId = streamMeta?.get("agentId")?.toString(),
            agentName = streamMeta?.get("agentName")?.toString(),
            isError = false,
            streamMeta = streamMeta,
            turnUsage = turnUsage,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_USER_MESSAGE,
            payload = payload,
            summary = text,
            status = STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertAssistantMessage(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        reasoningContent: String? = null,
        isError: Boolean = false,
        interruptedTurn: Boolean = false,
        attachments: List<Map<String, Any?>> = emptyList(),
        streamMeta: Map<String, Any?>? = null,
        turnUsage: Map<String, Any?>? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = AgentConversationHistorySupport.buildTextMessagePayload(
            messageId = entryId,
            user = 2,
            text = text,
            attachments = attachments,
            reasoningContent = reasoningContent,
            agentId = streamMeta?.get("agentId")?.toString(),
            agentName = streamMeta?.get("agentName")?.toString(),
            isError = isError,
            interruptedTurn = interruptedTurn,
            streamMeta = streamMeta,
            turnUsage = turnUsage,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_ASSISTANT_MESSAGE,
            payload = payload,
            summary = text,
            status = if (isError) STATUS_ERROR else STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertUiCard(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        streamMeta: Map<String, Any?>? = null,
        createdAt: Long = System.currentTimeMillis()
    ) {
        val payload = AgentConversationHistorySupport.buildCardMessagePayload(
            messageId = entryId,
            cardData = cardData,
            isError = false,
            streamMeta = streamMeta,
            createdAt = createdAt
        )
        upsertMessageEntry(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            entryType = ENTRY_TYPE_UI_CARD,
            payload = payload,
            summary = cardData["summary"]?.toString().orEmpty(),
            status = STATUS_SUCCESS,
            createdAt = createdAt
        )
    }

    suspend fun upsertToolEvent(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        payload: Map<String, Any?>,
        fallbackStatus: String = STATUS_RUNNING,
        fallbackSummary: String = ""
    ) = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val normalizedEntryId = entryId.trim()
        val existing = loadThreadEntryByIdSafe(
            conversationId = conversationId,
            conversationMode = effectiveConversationMode,
            entryId = normalizedEntryId
        )
        // ACP tool/call ids are scoped to a single provider turn. Providers
        // are allowed to reuse ids such as `call_1` on the next prompt, while
        // our conversation table keys entries by conversation + entryId. If
        // we reuse the raw id here, the new turn updates the old row and
        // inherits its createdAt, which makes the UI show a wrong duration and
        // can make the old turn lose its tool card entirely.
        val incomingTaskId = payload["taskId"]?.toString()?.trim().orEmpty()
        val existingTaskId = existing
            ?.takeIf { it.entryType == ENTRY_TYPE_TOOL_EVENT }
            ?.let { AgentConversationHistorySupport.readMap(it.payloadJson)["taskId"] }
            ?.toString()
            ?.trim()
            .orEmpty()
        val storageEntryId = if (
            normalizedEntryId.isNotEmpty() &&
            incomingTaskId.isNotEmpty() &&
            existingTaskId.isNotEmpty() &&
            existingTaskId != incomingTaskId
        ) {
            "$incomingTaskId-$normalizedEntryId"
        } else {
            normalizedEntryId
        }
        val storageExisting = if (storageEntryId == normalizedEntryId) {
            existing
        } else {
            loadThreadEntryByIdSafe(
                conversationId = conversationId,
                conversationMode = effectiveConversationMode,
                entryId = storageEntryId
            )
        }
        val storagePayload = payload.toMutableMap().apply {
            put("cardId", storageEntryId)
            val streamMeta = (get("streamMeta") as? Map<*, *>)
                ?.entries
                ?.associate { (key, value) -> key.toString() to value }
                ?.toMutableMap()
            if (streamMeta != null) {
                streamMeta["entryId"] = storageEntryId
                put("streamMeta", streamMeta)
            }
        }
        val mergedPayload = mergeToolPayload(
            existing = storageExisting?.takeIf { it.entryType == ENTRY_TYPE_TOOL_EVENT }?.let {
                AgentConversationHistorySupport.readMap(it.payloadJson)
            }.orEmpty(),
            incoming = storagePayload,
            fallbackStatus = fallbackStatus,
            fallbackSummary = fallbackSummary
        )
        val normalizedStatus = mergedPayload["status"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: fallbackStatus
        val normalizedSummary = mergedPayload["summary"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: fallbackSummary

        upsertEntry(
            AgentConversationEntry(
                id = storageExisting?.id ?: 0,
                conversationId = conversationId,
                conversationMode = effectiveConversationMode,
                entryId = storageEntryId,
                entryType = ENTRY_TYPE_TOOL_EVENT,
                status = normalizedStatus,
                summary = normalizedSummary,
                payloadJson = gson.toJson(mergedPayload),
                createdAt = storageExisting?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshConversationMetadata(conversationId)
    }

    suspend fun persistHiddenStreamEvent(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        payload: Map<String, Any?>,
        createdAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        upsertEntry(
            AgentConversationEntry(
                conversationId = conversationId,
                conversationMode = effectiveConversationMode,
                entryId = entryId,
                entryType = ENTRY_TYPE_STREAM_EVENT,
                status = STATUS_SUCCESS,
                summary = "ACP stream event",
                payloadJson = gson.toJson(payload),
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun replaceThreadMessagesFromUiSnapshot(
        conversationId: Long,
        conversationMode: String,
        messages: List<Map<String, Any?>>,
        allowHistoryRemoval: Boolean = false
    ) = DatabaseHelper.withTransaction {
        // Keep entry identities and their checkpoint in one atomic snapshot.
        // Compaction must never observe the temporary delete/reinsert gap.
        val existingConversation = DatabaseHelper.getConversationById(conversationId)
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        if (messages.isEmpty() && !allowHistoryRemoval) return@withTransaction
        val existingEntries = if (allowHistoryRemoval) {
            loadThreadEntriesAscSafePaged(conversationId, effectiveConversationMode)
        } else {
            // Reconcile one row at a time below. Retaining all raw tool bodies
            // alongside their decoded maps defeats the bounded display projection.
            emptyList()
        }
        val existingToolPayloads = existingEntries
            .filter { it.entryType == ENTRY_TYPE_TOOL_EVENT }
            .associate { entry ->
                entry.entryId to AgentConversationHistorySupport.readMap(entry.payloadJson)
            }
        val preservedSummary = existingConversation?.contextSummary
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val cutoffEntryId = existingConversation?.contextSummaryCutoffEntryDbId?.let { cutoffDbId ->
            existingEntries.firstOrNull { it.id == cutoffDbId }?.entryId
        }
        val mergedMessages = AgentConversationHistorySupport.mergePendingExternalUserMessages(
            existingMessages = existingEntries.mapNotNull(::entryToMessagePayload),
            incomingMessages = messages
        )
        var remappedCutoffEntryDbId: Long? = null
        if (allowHistoryRemoval) conversationModeCandidates(effectiveConversationMode).forEach { storageMode ->
            DatabaseHelper.deleteAgentConversationThread(conversationId, storageMode)
        }
        ConversationSnapshotOrdering.prepareForStorage(mergedMessages).forEach { prepared ->
            val message = prepared.payload
            val restoredToolPayload =
                AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(message)
            val entryId = message["id"]?.toString()?.trim().orEmpty()
                .ifEmpty { restoredToolPayload?.get("cardId")?.toString()?.trim().orEmpty() }
                .ifEmpty {
                "entry_${System.currentTimeMillis()}"
            }
            val type = when {
                restoredToolPayload != null -> ENTRY_TYPE_TOOL_EVENT
                (message["type"] as? Number)?.toInt() == 2 -> ENTRY_TYPE_UI_CARD
                (message["user"] as? Number)?.toInt() == 1 -> ENTRY_TYPE_USER_MESSAGE
                else -> ENTRY_TYPE_ASSISTANT_MESSAGE
            }
            val existingEntry = existingEntries.firstOrNull { it.entryId == entryId }
                ?: if (!allowHistoryRemoval) loadThreadEntryByIdSafe(conversationId, effectiveConversationMode, entryId) else null
            val status = when {
                restoredToolPayload != null -> restoredToolPayload["status"]?.toString()?.trim()
                    ?.ifEmpty { null }
                    ?: if (message["isError"] == true) STATUS_ERROR else STATUS_SUCCESS
                message["isError"] == true -> STATUS_ERROR
                else -> STATUS_SUCCESS
            }
            val summary = when {
                restoredToolPayload != null -> restoredToolPayload["summary"]?.toString()?.trim()
                    .orEmpty()
                else -> extractSummaryFromMessagePayload(message)
            }
            val payloadJson = if (restoredToolPayload != null) {
                if (restoredToolPayload["payloadCompacted"] == true && existingEntry != null) {
                    existingEntry.payloadJson
                } else {
                    val existingToolPayload = existingToolPayloads[entryId]
                        ?: existingEntry?.let { AgentConversationHistorySupport.readMap(it.payloadJson) }.orEmpty()
                    gson.toJson(preserveFullToolPayload(existingToolPayload, restoredToolPayload))
                }
            } else {
                gson.toJson(message)
            }
            val insertedId = upsertEntry(
                AgentConversationEntry(
                    id = existingEntry?.id ?: 0,
                    conversationId = conversationId,
                    conversationMode = effectiveConversationMode,
                    entryId = entryId,
                    entryType = type,
                    status = status,
                    summary = summary,
                    payloadJson = payloadJson,
                    createdAt = prepared.createdAt,
                    updatedAt = prepared.createdAt
                )
            )
            if (entryId == cutoffEntryId) {
                remappedCutoffEntryDbId = insertedId
            }
        }
        if (allowHistoryRemoval && preservedSummary != null && remappedCutoffEntryDbId != null) {
            val refreshedConversation = DatabaseHelper.getConversationById(conversationId)
            if (refreshedConversation != null) {
                DatabaseHelper.commitConversationContextCheckpoint(
                    conversationId, preservedSummary, remappedCutoffEntryDbId,
                    refreshedConversation.contextSummaryUpdatedAt, System.currentTimeMillis()
                )
            }
        } else if (allowHistoryRemoval && preservedSummary != null) {
            resetContextSummary(conversationId)
        }
        refreshConversationMetadata(conversationId)
    }

    suspend fun listConversationMessages(
        conversationId: Long,
        conversationMode: String,
        finalizeInterruptedEntries: Boolean = true
    ): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val messagePayloads = mutableListOf<Map<String, Any?>>()
        var offset = 0
        while (true) {
            val page = DatabaseHelper.getLogicalAgentConversationPage(
                conversationId, conversationModeCandidates(effectiveConversationMode), SAFE_HISTORY_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            val displayEntries = if (finalizeInterruptedEntries) normalizeEntriesForDisplay(page) else page
            messagePayloads += displayEntries.mapNotNull(::entryToMessagePayload)
            offset += page.size
            if (page.size < SAFE_HISTORY_PAGE_SIZE) break
        }
        ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Pair<List<Map<String, Any?>>, Boolean> = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        // Deduplicate compatibility identities in SQL BEFORE paging. Hydrate only
        // the visible page plus one lookahead, never the entire large transcript.
        val pageSize = limit.coerceIn(1, Int.MAX_VALUE - 1)
        val page = DatabaseHelper.getLogicalAgentConversationPage(
            conversationId, conversationModeCandidates(effectiveConversationMode), pageSize + 1, offset.coerceAtLeast(0)
        )
        val entries = page.take(pageSize)
        val hasMore = page.size > pageSize
        // Every page is historical UI. A card from an older page must not
        // stay visually running merely because it was not in the first page
        // loaded after process restore.
        val normalized = normalizeEntriesForDisplay(entries)
        val messagePayloads = normalized.mapNotNull { entry -> entryToMessagePayload(entry) }
        val sorted = ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
        Pair(sorted, hasMore)
    }

    suspend fun clearConversationMessages(
        conversationId: Long,
        conversationMode: String
    ) = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        conversationModeCandidates(effectiveConversationMode).forEach { storageMode ->
            DatabaseHelper.deleteAgentConversationThread(conversationId, storageMode)
        }
        resetContextSummary(conversationId)
        refreshConversationMetadata(conversationId)
    }

    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        DatabaseHelper.deleteAgentConversationEntries(conversationId)
    }

    /**
     * Removes legacy ACP transport records created by the pre-ACP history
     * bridge. They are not conversation content and must not affect headers,
     * counts, pagination, or prompt reconstruction.
     */
    suspend fun purgeLegacyStreamEvents(): Int = withContext(Dispatchers.IO) {
        val affectedConversationIds = DatabaseHelper.getAgentConversationIdsWithStreamEvents()
        if (affectedConversationIds.isEmpty()) return@withContext 0
        val deleted = DatabaseHelper.deleteAgentConversationStreamEvents()
        for (conversationId in affectedConversationIds) {
            refreshConversationMetadata(conversationId)
        }
        deleted
    }

    suspend fun buildPromptSeed(
        conversationId: Long?,
        conversationMode: String
    ): PromptSeed = withContext(Dispatchers.IO) {
        if (conversationId == null || conversationId <= 0L) {
            return@withContext PromptSeed(emptyList())
        }
        val conversation = DatabaseHelper.getConversationById(conversationId)
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val normalizedEntries = AgentConversationHistorySupport.normalizeInterruptedEntries(
            loadThreadEntriesAscSafePaged(conversationId, effectiveConversationMode,
                if (conversation?.contextSummary.isNullOrBlank()) 0 else conversation?.contextSummaryCutoffEntryDbId ?: 0,
                promptProjection = newPromptProjection())
        )
        AgentConversationHistorySupport.buildPromptSeedFromEntries(
            entries = normalizedEntries,
            contextSummary = conversation?.contextSummary,
            cutoffEntryDbId = conversation?.contextSummaryCutoffEntryDbId
        )
    }

    suspend fun getContextCompactionCandidate(
        conversationId: Long,
        conversationMode: String
    ): ContextCompactionCandidate? = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext null
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val normalizedEntries = AgentConversationHistorySupport.normalizeInterruptedEntries(
            loadThreadEntriesAscSafePaged(conversationId, effectiveConversationMode, conversation.contextSummaryCutoffEntryDbId ?: 0,
                promptProjection = newPromptProjection())
        )
        val selection = AgentConversationHistorySupport.selectEntriesToCompact(
            entries = normalizedEntries,
            cutoffEntryDbId = conversation.contextSummaryCutoffEntryDbId
        ) ?: return@withContext null
        ContextCompactionCandidate(
            conversation = conversation,
            entriesToCompact = selection.entriesToCompact,
            cutoffEntryDbId = selection.cutoffEntryDbId
        )
    }

    /** Resolve Pi's retained-entry boundary against the canonical replay journal.
     * In-flight projection is allowed to lag: no match means no durable cutoff yet.
     */
    suspend fun findCompactionToolCutoff(
        conversationId: Long,
        conversationMode: String,
        compactedMessages: List<ChatCompletionMessage>
    ): Long? = withContext(Dispatchers.IO) {
        val conversation = getConversation(conversationId) ?: return@withContext null
        val afterEntryId = conversation.contextSummaryCutoffEntryDbId ?: 0
        val required = compactedMessages.lastOrNull { !it.toolCalls.isNullOrEmpty() }
            ?.toolCalls.orEmpty().map { it.id }.toSet()
        if (required.isEmpty() || compactedMessages.lastOrNull()?.role != "tool") return@withContext null
        // ACP projection commits asynchronously. Wait for the actual journal
        // identity instead of silently discarding a successful summary. Room
        // invalidations wake this wait; no user turn or network request is replayed.
        val entries = DatabaseHelper.observeAgentToolHeadersAfter(conversationId, afterEntryId).map { headers ->
                val projection = newPromptProjection()
                headers.filter { header ->
                    !header.entryId.startsWith("tool:") || required.any { callId ->
                        header.entryId == callId.removePrefix("restored_") ||
                            header.entryId.contains(":$callId:")
                    }
                }.distinctBy { it.entryId }.mapNotNull { header ->
                    loadThreadEntryByIdSafe(conversationId, header.conversationMode, header.entryId)
                        ?.let(projection::project)
                }
            }
        awaitCompactionToolCutoff(entries, compactedMessages, afterEntryId)
    }

    suspend fun updateContextSummary(
        conversationId: Long,
        summary: String,
        cutoffEntryDbId: Long,
        expectedRevision: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        check(DatabaseHelper.commitConversationContextCheckpoint(conversationId, summary.trim(), cutoffEntryDbId, expectedRevision, updatedAt)) {
            "压缩期间历史检查点已改变，未提交过期摘要。"
        }
    }

    suspend fun updatePromptTokenUsage(
        conversationId: Long,
        promptTokens: Int,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        DatabaseHelper.updateConversationPromptUsage(conversationId, promptTokens, updatedAt)
    }

    suspend fun getConversation(conversationId: Long): Conversation? = withContext(Dispatchers.IO) {
        DatabaseHelper.getConversationById(conversationId)
    }

    /**
     * Copies the durable, user-visible ACP history into a newly forked
     * conversation.  The ACP agent owns the remote session context, while
     * OmniBot owns the conversation projection; keeping this operation here
     * makes the fork seam work for every Harness and also reads legacy
     * Xiaowan buckets without rewriting or deleting them.
     *
     * Hidden raw stream events are deliberately not copied.  They are a
     * transport replay aid, not conversation content, and copying them would
     * make a fork look like it had received the same notifications twice.
     */
    suspend fun copyConversationHistory(
        sourceConversationId: Long,
        targetConversationId: Long,
        sourceConversationMode: String = "agent",
        targetConversationMode: String = "agent"
    ): Int = withContext(Dispatchers.IO) {
        if (sourceConversationId == targetConversationId) return@withContext 0
        val sourceEntries = entriesForFork(
            conversationModeCandidates(sourceConversationMode)
            .flatMap { storageMode ->
                DatabaseHelper.getAgentConversationEntriesAsc(
                    conversationId = sourceConversationId,
                    conversationMode = storageMode
                )
            }
        )
        if (sourceEntries.isEmpty()) return@withContext 0

        val targetMode = canonicalConversationMode(targetConversationMode)
        sourceEntries.forEach { entry ->
            DatabaseHelper.upsertAgentConversationEntry(
                entry.copy(
                    id = 0,
                    conversationId = targetConversationId,
                    conversationMode = targetMode,
                )
            )
        }
        refreshConversationMetadata(targetConversationId)
        sourceEntries.size
    }

    private suspend fun upsertMessageEntry(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        entryType: String,
        payload: Map<String, Any?>,
        summary: String,
        status: String,
        createdAt: Long
    ) = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val existing = loadThreadEntryByIdSafe(
            conversationId = conversationId,
            conversationMode = effectiveConversationMode,
            entryId = entryId
        )
        val resolvedPayload = if (
            entryType == ENTRY_TYPE_UI_CARD &&
            existing?.entryType == ENTRY_TYPE_UI_CARD
        ) {
            AgentConversationHistorySupport.mergeUiCardPayload(
                existingPayload = AgentConversationHistorySupport.readMap(
                    existing.payloadJson
                ),
                incomingPayload = payload
            )
        } else {
            payload
        }
        upsertEntry(
            AgentConversationEntry(
                id = existing?.id ?: 0,
                conversationId = conversationId,
                conversationMode = effectiveConversationMode,
                entryId = entryId,
                entryType = entryType,
                status = status,
                summary = summary.trim(),
                payloadJson = gson.toJson(resolvedPayload),
                createdAt = existing?.createdAt ?: createdAt,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshConversationMetadata(conversationId)
    }

    private suspend fun upsertEntry(entry: AgentConversationEntry): Long {
        return DatabaseHelper.upsertAgentConversationEntry(
            AgentConversationHistorySupport.prepareEntryForStorage(
                entry.copy(conversationMode = canonicalConversationMode(entry.conversationMode))
            )
        )
    }

    private fun canonicalConversationMode(mode: String): String {
        return when (mode.trim().lowercase()) {
            "", "normal", "agent", "codex", "acp", "coding" -> "agent"
            else -> mode.trim().lowercase().ifEmpty { "agent" }
        }
    }

    private suspend fun refreshConversationMetadata(conversationId: Long) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        val lastEntry = DatabaseHelper.getLatestAgentConversationEntryHeader(conversationId)
        val firstEntry = DatabaseHelper.getEarliestAgentConversationEntryHeader(conversationId)
        val lastUpdate = DatabaseHelper.getLatestAgentConversationUpdateHeader(conversationId)
        val messageCount = DatabaseHelper.countAgentConversationEntries(conversationId)
        val updatedConversation = conversation.copy(
            lastMessage = lastEntry?.let(::conversationLastMessageFromHeader)?.takeIf { it.isNotBlank() },
            messageCount = messageCount,
            createdAt = firstEntry?.createdAt ?: conversation.createdAt,
            updatedAt = lastUpdate?.updatedAt ?: conversation.updatedAt
        )
        DatabaseHelper.updateConversation(updatedConversation)
    }

    private suspend fun resetContextSummary(conversationId: Long) {
        DatabaseHelper.clearConversationContextCheckpoint(conversationId)
    }

    private suspend fun normalizeInterruptedToolEntries(
        entries: List<AgentConversationEntry>
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries
        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(entries)
        normalized.forEachIndexed { index, updated ->
            if (updated != entries[index]) {
                upsertEntry(updated.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        return normalized
    }

    private suspend fun normalizeEntriesForDisplay(
        entries: List<AgentConversationEntry>
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries
        val normalized = AgentConversationHistorySupport.normalizeInterruptedEntries(
            entries = entries,
            finalizeLatestThinkingEntries = true
        )
        normalized.forEachIndexed { index, updated ->
            if (updated != entries[index]) {
                upsertEntry(updated.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        return normalized
    }

    private fun entryToMessagePayload(entry: AgentConversationEntry): Map<String, Any?>? {
        return when (entry.entryType) {
            ENTRY_TYPE_TOOL_EVENT -> buildToolCardMessage(entry)
            ENTRY_TYPE_USER_MESSAGE,
            ENTRY_TYPE_ASSISTANT_MESSAGE -> AgentConversationHistorySupport.readMap(entry.payloadJson)
            ENTRY_TYPE_UI_CARD -> AgentConversationHistorySupport.buildDisplaySafeUiCardMessage(
                entry = entry,
                payload = AgentConversationHistorySupport.readMap(entry.payloadJson)
            )
            else -> null
        }
    }

    private fun buildToolCardMessage(entry: AgentConversationEntry): Map<String, Any?> {
        val payload = AgentConversationHistorySupport.readMap(entry.payloadJson)
        val messageId = entry.entryId
        val cardData = AgentConversationHistorySupport.buildDisplaySafeToolCardData(
            entry = entry,
            payload = payload
        ).toMutableMap()
        if (entry.payloadJson.length > 8192) {
            val workspace = AgentWorkspaceManager(context)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(entry.payloadJson.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val file = java.io.File(workspace.offloadsDirectory("history-${entry.conversationId}"), "${entry.id}-$digest.json")
            if (!file.exists()) file.writeText(entry.payloadJson)
            val artifact = workspace.buildArtifactForFile(file, "history", "完整工具记录").toPayload()
            cardData["artifacts"] = (cardData["artifacts"] as? List<*>).orEmpty() + artifact
        }
        return AgentConversationHistorySupport.buildCardMessagePayload(
            messageId = messageId,
            cardData = cardData,
            isError = entry.status == STATUS_ERROR,
            streamMeta = AgentConversationHistorySupport.compactDisplayStreamMeta(
                payload["streamMeta"]
            ),
            createdAt = entry.createdAt
        )
    }

    private fun mergeToolPayload(
        existing: Map<String, Any?>,
        incoming: Map<String, Any?>,
        fallbackStatus: String,
        fallbackSummary: String
    ): Map<String, Any?> {
        return AgentConversationHistorySupport.mergeToolPayload(
            existing = existing,
            incoming = incoming,
            fallbackStatus = fallbackStatus,
            fallbackSummary = fallbackSummary
        )
    }

    private fun conversationLastMessageFromHeader(entry: AgentConversationEntryHeader): String {
        return when (entry.entryType) {
            ENTRY_TYPE_TOOL_EVENT -> entry.summary.ifBlank { "执行了工具调用" }
            ENTRY_TYPE_UI_CARD -> entry.summary.ifBlank { "卡片消息" }
            else -> AgentTextSanitizer.sanitizeUtf16(entry.summary.trim())
        }
    }

    private fun extractSummaryFromMessagePayload(message: Map<String, Any?>): String {
        val content = toStringAnyMap(message["content"])
        val text = AgentTextSanitizer.sanitizeUtf16(
            content["text"]?.toString()?.trim().orEmpty()
        )
        if (text.isNotEmpty()) return text
        val cardData = toStringAnyMap(content["cardData"])
        return AgentTextSanitizer.sanitizeUtf16(
            cardData["summary"]?.toString()?.trim().orEmpty()
        )
    }

    private suspend fun loadThreadEntryByIdSafe(
        conversationId: Long,
        conversationMode: String,
        entryId: String
    ): AgentConversationEntry? {
        for (storageMode in conversationModeCandidates(conversationMode)) {
            val entry = DatabaseHelper.getAgentConversationEntryByThreadAndId(
                conversationId = conversationId,
                conversationMode = storageMode,
                entryId = entryId
            )
            if (entry != null) return entry
        }
        return null
    }

    private fun newPromptProjection() = AgentHistoryToolOutputProjection(offload = { entry ->
        val workspace = AgentWorkspaceManager(context)
        val file = java.io.File(workspace.offloadsDirectory("history-${entry.conversationId}"),
            "${entry.id}-${entry.updatedAt}-prompt.json")
        // Historical tools are immutable after completion, but write the actual
        // snapshot even when a running row changed without changing its timestamp.
        val temporary = java.io.File.createTempFile("history-", ".tmp", file.parentFile)
        try {
            temporary.writer().use { it.write(entry.payloadJson) }
            check(temporary.renameTo(file)) { "Could not save complete history record" }
        } finally {
            temporary.delete()
        }
        workspace.buildArtifactForFile(file, "history", "完整工具记录").workspacePath
    })

    private suspend fun loadThreadEntriesAscSafePaged(
        conversationId: Long,
        conversationMode: String,
        afterEntryId: Long = 0,
        promptProjection: AgentHistoryToolOutputProjection? = null
    ): List<AgentConversationEntry> {
        return loadThreadEntriesDescSafePaged(conversationId, conversationMode, afterEntryId, promptProjection).asReversed()
    }

    private suspend fun loadThreadEntriesDescSafePaged(
        conversationId: Long,
        conversationMode: String,
        afterEntryId: Long = 0,
        promptProjection: AgentHistoryToolOutputProjection? = null
    ): List<AgentConversationEntry> {
        val entries = conversationModeCandidates(conversationMode).flatMap { storageMode ->
            loadThreadEntriesDescSafePagedForMode(conversationId, storageMode, afterEntryId, promptProjection)
        }
        return entries
            // Canonical `agent` entries come first; an old `codex` row with
            // the same logical entry id must not be shown twice.
            .distinctBy { entry -> entry.entryId }
            .sortedWith(compareByDescending<AgentConversationEntry> { it.createdAt }
                .thenByDescending { it.id })
    }

    private suspend fun loadThreadEntriesDescSafePagedForMode(
        conversationId: Long,
        conversationMode: String,
        afterEntryId: Long = 0,
        promptProjection: AgentHistoryToolOutputProjection? = null
    ): List<AgentConversationEntry> {
        val entries = mutableListOf<AgentConversationEntry>()
        var offset = 0
        while (true) {
            val page = loadThreadEntriesDescPagedSafe(
                conversationId = conversationId,
                conversationMode = conversationMode,
                limit = SAFE_HISTORY_PAGE_SIZE,
                offset = offset,
                afterEntryId = afterEntryId
            )
            if (page.isEmpty()) break
            entries += if (promptProjection == null) page else page.map(promptProjection::project)
            offset += page.size
            if (page.size < SAFE_HISTORY_PAGE_SIZE) break
        }
        return entries
    }

    private fun conversationModeCandidates(conversationMode: String): List<String> {
        val normalized = conversationMode.trim().lowercase().ifEmpty { "agent" }
        return if (normalized in setOf("normal", "agent", "codex", "acp", "coding")) {
            // `normal` is the pre-ACP Xiaowan bucket. Keep it readable while
            // all new writes use canonical `agent`.
            listOf("agent", "codex", "normal")
        } else {
            listOf(normalized)
        }
    }

    /**
     * The Conversation row is the durable ownership boundary. UI callers may
     * still arrive through an old route and say `normal`/`codex`; allowing
     * that hint to select a different history bucket is what made context
     * disappear after a refresh or session/load. Use the requested mode only
     * for a not-yet-materialized conversation.
     */
    private suspend fun resolveConversationMode(
        conversationId: Long,
        requestedMode: String
    ): String {
        val persistedMode = DatabaseHelper.getConversationById(conversationId)
            ?.mode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return canonicalConversationMode(persistedMode ?: requestedMode)
    }

    private suspend fun loadThreadEntriesDescPagedSafe(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int,
        afterEntryId: Long = 0
    ): List<AgentConversationEntry> {
        return DatabaseHelper.getAgentConversationEntriesDescPaged(
            conversationId = conversationId,
            conversationMode = conversationMode,
            limit = limit,
            offset = offset,
            afterEntryId = afterEntryId
        )
    }

    private fun toStringAnyMap(value: Any?): Map<String, Any?> {
        if (value !is Map<*, *>) return emptyMap()
        return value.entries.associate { (key, rawValue) ->
            key.toString() to rawValue
        }
    }

    private fun toListOfStringAnyMap(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { item -> item?.let(::toStringAnyMap).takeIf { !it.isNullOrEmpty() } }
    }

}
