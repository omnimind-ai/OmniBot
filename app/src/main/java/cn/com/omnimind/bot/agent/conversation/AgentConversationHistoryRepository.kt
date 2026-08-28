package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.AgentConversationEntryHeader
import cn.com.omnimind.baselib.database.AgentConversationEntryRecord
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        messages: List<Map<String, Any?>>
    ) = withContext(Dispatchers.IO) {
        val existingConversation = DatabaseHelper.getConversationById(conversationId)
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        val existingEntries = loadThreadEntriesAscSafePaged(
            conversationId,
            effectiveConversationMode
        )
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
        conversationModeCandidates(effectiveConversationMode).forEach { storageMode ->
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
                val existingToolPayload = existingToolPayloads[entryId].orEmpty()
                val replayPreservedPayload = restoredToolPayload.toMutableMap().apply {
                    listOf(
                        "modelToolCallId",
                        "modelAssistantMessageJson",
                        "modelToolResultMessageJson"
                    ).forEach { key ->
                        existingToolPayload[key]?.let { value -> put(key, value) }
                    }
                }
                gson.toJson(replayPreservedPayload)
            } else {
                gson.toJson(message)
            }
            val insertedId = upsertEntry(
                AgentConversationEntry(
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
        if (preservedSummary != null && remappedCutoffEntryDbId != null) {
            val refreshedConversation = DatabaseHelper.getConversationById(conversationId)
            if (refreshedConversation != null) {
                DatabaseHelper.updateConversation(
                    refreshedConversation.copy(
                        contextSummary = preservedSummary,
                        contextSummaryCutoffEntryDbId = remappedCutoffEntryDbId,
                        contextSummaryUpdatedAt = existingConversation.contextSummaryUpdatedAt
                            ?: refreshedConversation.contextSummaryUpdatedAt
                    )
                )
            }
        } else {
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
        val entries = loadThreadEntriesDescSafePaged(conversationId, effectiveConversationMode)
        val displayEntries = if (finalizeInterruptedEntries) {
            normalizeEntriesForDisplay(entries)
        } else {
            entries
        }
        val messagePayloads = displayEntries.mapNotNull { entry -> entryToMessagePayload(entry) }
        ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Pair<List<Map<String, Any?>>, Boolean> = withContext(Dispatchers.IO) {
        val effectiveConversationMode = resolveConversationMode(conversationId, conversationMode)
        // Read a bounded window from every compatibility bucket before
        // slicing the page. The old implementation queried only `agent`, so
        // conversations written as `normal` appeared empty after the default
        // switched to canonical Agent mode. Do not load the entire history on
        // every scroll request.
        val candidateModes = conversationModeCandidates(effectiveConversationMode)
        val windowSize = offset.coerceAtLeast(0) + limit.coerceAtLeast(1)
        val allEntries = loadThreadEntriesDescWindowSafe(
            conversationId = conversationId,
            conversationModes = candidateModes,
            maxEntriesPerMode = windowSize
        )
        val (entries, hasMore) = pageConversationEntries(
            entries = allEntries,
            limit = limit,
            offset = offset
        )
        val hasUnloadedEntries = candidateModes.any { storageMode ->
            DatabaseHelper.countAgentConversationThreadEntries(
                conversationId = conversationId,
                conversationMode = storageMode
            ) > windowSize
        }
        val normalized = if (offset == 0) {
            normalizeEntriesForDisplay(entries)
        } else {
            entries
        }
        val messagePayloads = normalized.mapNotNull { entry -> entryToMessagePayload(entry) }
        val sorted = ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
        Pair(sorted, hasMore || hasUnloadedEntries)
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
        val normalizedEntries = normalizeInterruptedToolEntries(
            loadThreadEntriesAscSafePaged(conversationId, effectiveConversationMode)
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
        val normalizedEntries = normalizeInterruptedToolEntries(
            loadThreadEntriesAscSafePaged(conversationId, effectiveConversationMode)
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

    suspend fun updateContextSummary(
        conversationId: Long,
        summary: String,
        cutoffEntryDbId: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext
        DatabaseHelper.updateConversation(
            conversation.copy(
                contextSummary = summary.trim(),
                contextSummaryCutoffEntryDbId = cutoffEntryDbId,
                contextSummaryUpdatedAt = updatedAt,
                updatedAt = maxOf(conversation.updatedAt, updatedAt)
            )
        )
    }

    suspend fun updatePromptTokenUsage(
        conversationId: Long,
        promptTokens: Int,
        threshold: Int,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return@withContext
        DatabaseHelper.updateConversation(
            conversation.copy(
                latestPromptTokens = promptTokens.coerceAtLeast(0),
                promptTokenThreshold = threshold.coerceAtLeast(1),
                latestPromptTokensUpdatedAt = updatedAt,
                updatedAt = maxOf(conversation.updatedAt, updatedAt)
            )
        )
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
            AgentConversationHistorySupport.preserveDeepThinkingContent(
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
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        DatabaseHelper.updateConversation(
            conversation.copy(
                contextSummary = null,
                contextSummaryCutoffEntryDbId = null,
                contextSummaryUpdatedAt = 0
            )
        )
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
        )
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
        var record: AgentConversationEntryRecord? = null
        for (storageMode in conversationModeCandidates(conversationMode)) {
            record = DatabaseHelper.getAgentConversationEntryByThreadAndIdSafe(
                conversationId = conversationId,
                conversationMode = storageMode,
                entryId = entryId,
                payloadLimit = AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS,
                summaryLimit = AgentConversationHistorySupport.MAX_STORAGE_SUMMARY_CHARS
            )
            if (record != null) break
        }
        record ?: return null
        return materializeEntries(listOf(record)).singleOrNull()
    }

    private suspend fun loadThreadEntriesAscSafePaged(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        return loadThreadEntriesDescSafePaged(conversationId, conversationMode).asReversed()
    }

    private suspend fun loadThreadEntriesDescSafePaged(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        val entries = conversationModeCandidates(conversationMode).flatMap { storageMode ->
            loadThreadEntriesDescSafePagedForMode(conversationId, storageMode)
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
        conversationMode: String
    ): List<AgentConversationEntry> {
        val entries = mutableListOf<AgentConversationEntry>()
        var offset = 0
        while (true) {
            val page = loadThreadEntriesDescPagedSafe(
                conversationId = conversationId,
                conversationMode = conversationMode,
                limit = SAFE_HISTORY_PAGE_SIZE,
                offset = offset
            )
            if (page.isEmpty()) break
            entries += page
            offset += page.size
            if (page.size < SAFE_HISTORY_PAGE_SIZE) break
        }
        return entries
    }

    private suspend fun loadThreadEntriesDescWindowSafe(
        conversationId: Long,
        conversationModes: List<String>,
        maxEntriesPerMode: Int
    ): List<AgentConversationEntry> {
        val boundedSize = maxEntriesPerMode.coerceAtLeast(1)
        return conversationModes
            .flatMap { storageMode ->
                loadThreadEntriesDescWindowSafeForMode(
                    conversationId = conversationId,
                    conversationMode = storageMode,
                    maxEntries = boundedSize
                )
            }
            .distinctBy { entry -> entry.entryId }
            .sortedWith(compareByDescending<AgentConversationEntry> { it.createdAt }
                .thenByDescending { it.id })
    }

    private suspend fun loadThreadEntriesDescWindowSafeForMode(
        conversationId: Long,
        conversationMode: String,
        maxEntries: Int
    ): List<AgentConversationEntry> {
        val entries = mutableListOf<AgentConversationEntry>()
        var offset = 0
        val boundedSize = maxEntries.coerceAtLeast(1)
        while (entries.size < boundedSize) {
            val page = loadThreadEntriesDescPagedSafe(
                conversationId = conversationId,
                conversationMode = conversationMode,
                limit = minOf(SAFE_HISTORY_PAGE_SIZE, boundedSize - entries.size),
                offset = offset
            )
            if (page.isEmpty()) break
            entries += page
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
        offset: Int
    ): List<AgentConversationEntry> {
        val records = DatabaseHelper.getAgentConversationEntriesDescPagedSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            limit = limit,
            offset = offset,
            payloadLimit = AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS,
            summaryLimit = AgentConversationHistorySupport.MAX_STORAGE_SUMMARY_CHARS
        )
        return materializeEntries(records)
    }

    private suspend fun materializeEntries(
        records: List<AgentConversationEntryRecord>
    ): List<AgentConversationEntry> {
        if (records.isEmpty()) return emptyList()
        val materialized = records.map(AgentConversationHistorySupport::materializeRecord)
        repairRecoveredEntries(materialized)
        return materialized.map { it.entry }
    }

    private suspend fun repairRecoveredEntries(
        entries: List<AgentConversationHistorySupport.MaterializedEntry>
    ) {
        entries
            .asSequence()
            .filter { it.needsRepair }
            .map { it.entry }
            .forEach { repaired ->
                upsertEntry(repaired)
            }
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
