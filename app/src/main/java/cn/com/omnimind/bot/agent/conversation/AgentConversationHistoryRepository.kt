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
        val existing = loadThreadEntryByIdSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId
        )
        val mergedPayload = mergeToolPayload(
            existing = existing?.takeIf { it.entryType == ENTRY_TYPE_TOOL_EVENT }?.let {
                AgentConversationHistorySupport.readMap(it.payloadJson)
            }.orEmpty(),
            incoming = payload,
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
                id = existing?.id ?: 0,
                conversationId = conversationId,
                conversationMode = conversationMode,
                entryId = entryId,
                entryType = ENTRY_TYPE_TOOL_EVENT,
                status = normalizedStatus,
                summary = normalizedSummary,
                payloadJson = gson.toJson(mergedPayload),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
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
        upsertEntry(
            AgentConversationEntry(
                conversationId = conversationId,
                conversationMode = conversationMode,
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
        val existingEntries = loadThreadEntriesAscSafe(conversationId, conversationMode)
        val existingStreamEvents = existingEntries.filter {
            it.entryType == ENTRY_TYPE_STREAM_EVENT
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
        DatabaseHelper.deleteAgentConversationThread(conversationId, conversationMode)
        existingStreamEvents.forEach { streamEvent ->
            upsertEntry(
                streamEvent.copy(
                    id = 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
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
                    conversationMode = conversationMode,
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
                        contextSummaryUpdatedAt = existingConversation?.contextSummaryUpdatedAt
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
        val entries = loadThreadEntriesDescSafe(conversationId, conversationMode)
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
        val totalCount = DatabaseHelper.countAgentConversationThreadEntries(
            conversationId, conversationMode
        )
        val entries = loadThreadEntriesDescPagedSafe(conversationId, conversationMode, limit, offset)
        val normalized = if (offset == 0) {
            normalizeEntriesForDisplay(entries)
        } else {
            entries
        }
        val messagePayloads = normalized.mapNotNull { entry -> entryToMessagePayload(entry) }
        val sorted = ConversationSnapshotOrdering.sortForDisplay(messagePayloads)
        val hasMore = offset + entries.size < totalCount
        Pair(sorted, hasMore)
    }

    suspend fun clearConversationMessages(
        conversationId: Long,
        conversationMode: String
    ) = withContext(Dispatchers.IO) {
        DatabaseHelper.deleteAgentConversationThread(conversationId, conversationMode)
        resetContextSummary(conversationId)
        refreshConversationMetadata(conversationId)
    }

    suspend fun deleteConversation(conversationId: Long) = withContext(Dispatchers.IO) {
        DatabaseHelper.deleteAgentConversationEntries(conversationId)
    }

    suspend fun buildPromptSeed(
        conversationId: Long?,
        conversationMode: String
    ): PromptSeed = withContext(Dispatchers.IO) {
        if (conversationId == null || conversationId <= 0L) {
            return@withContext PromptSeed(emptyList())
        }
        val conversation = DatabaseHelper.getConversationById(conversationId)
        val normalizedEntries = normalizeInterruptedToolEntries(
            loadThreadEntriesAscSafe(conversationId, conversationMode)
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
        val normalizedEntries = normalizeInterruptedToolEntries(
            loadThreadEntriesAscSafe(conversationId, conversationMode)
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
        val existing = loadThreadEntryByIdSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
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
                conversationMode = conversationMode,
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
            AgentConversationHistorySupport.prepareEntryForStorage(entry)
        )
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
        val record = DatabaseHelper.getAgentConversationEntryByThreadAndIdSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            payloadLimit = AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS,
            summaryLimit = AgentConversationHistorySupport.MAX_STORAGE_SUMMARY_CHARS
        ) ?: return null
        return materializeEntries(listOf(record)).singleOrNull()
    }

    private suspend fun loadThreadEntriesAscSafe(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        val records = DatabaseHelper.getAgentConversationEntriesAscSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            payloadLimit = AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS,
            summaryLimit = AgentConversationHistorySupport.MAX_STORAGE_SUMMARY_CHARS
        )
        return materializeEntries(records)
    }

    private suspend fun loadThreadEntriesDescSafe(
        conversationId: Long,
        conversationMode: String
    ): List<AgentConversationEntry> {
        val records = DatabaseHelper.getAgentConversationEntriesDescSafe(
            conversationId = conversationId,
            conversationMode = conversationMode,
            payloadLimit = AgentConversationHistorySupport.MAX_STORAGE_ENTRY_PAYLOAD_CHARS,
            summaryLimit = AgentConversationHistorySupport.MAX_STORAGE_SUMMARY_CHARS
        )
        return materializeEntries(records)
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
