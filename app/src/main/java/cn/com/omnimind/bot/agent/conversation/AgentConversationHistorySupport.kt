package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import cn.com.omnimind.baselib.database.AgentConversationEntryRecord
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

internal object AgentConversationHistorySupport {
    private const val EXTERNAL_USER_MESSAGE_SOURCE = "external_user_message"
    private const val PENDING_SNAPSHOT_ACK = "pendingSnapshotAck"
    private data class ThinkingEntryRef(
        val index: Int,
        val entry: AgentConversationEntry,
        val taskId: String,
        val payload: Map<String, Any?>,
        val cardData: Map<String, Any?>,
        val startTime: Long,
        val sequenceRank: Int
    )

    data class CompactionSelection(
        val entriesToCompact: List<AgentConversationEntry>,
        val cutoffEntryDbId: Long
    )

    data class RuntimeCompactionWindow(
        val existingSummary: String?,
        val messagesToCompact: List<ChatCompletionMessage>
    )

    data class MaterializedEntry(
        val entry: AgentConversationEntry,
        val needsRepair: Boolean
    )

    private const val MAX_TOOL_SUMMARY_CHARS = 240
    private const val MAX_TOOL_PREVIEW_CHARS = 800
    private const val MAX_TOOL_TERMINAL_CHARS = 1200
    private const val MAX_DISPLAY_INLINE_CHARS = 600
    private const val MAX_DISPLAY_TOOL_JSON_CHARS = 2 * 1024
    private const val MAX_DISPLAY_TOOL_TERMINAL_CHARS = 8 * 1024
    private const val MAX_DISPLAY_THINKING_CHARS = 8 * 1024
    private const val MAX_DISPLAY_CARD_JSON_CHARS = 64 * 1024
    private const val MAX_DISPLAY_LIST_ITEMS = 8
    internal const val MAX_STORAGE_ENTRY_PAYLOAD_CHARS = 32 * 1024
    internal const val MAX_STORAGE_SUMMARY_CHARS = 2 * 1024
    private const val MAX_STORAGE_TOOL_JSON_CHARS = 4 * 1024
    private const val MAX_STORAGE_TOOL_TERMINAL_CHARS = 8 * 1024
    private const val MAX_STORAGE_MESSAGE_TEXT_CHARS = 24 * 1024
    private const val DISPLAY_TRUNCATION_NOTICE = "[Earlier content omitted]\n"
    private const val LEGACY_CONTEXT_SUMMARY_SYSTEM_PREFIX = """
以下是同一会话较早历史的压缩总结。它替代了压缩点之前的原始消息，请在后续对话中将其视为既有上下文。
如果总结与压缩点之后的新消息冲突，应以后续原始消息为准。

"""
    private const val CONTEXT_SUMMARY_USER_PREFIX =
        "<context-summary> The following is a summary of the earlier conversation that was compacted to save context space."

    private val gson = Gson()
    private val canonicalMessageJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildTextMessagePayload(
        messageId: String,
        user: Int,
        text: String,
        attachments: List<Map<String, Any?>> = emptyList(),
        reasoningContent: String? = null,
        agentId: String? = null,
        agentName: String? = null,
        isError: Boolean,
        interruptedTurn: Boolean = false,
        streamMeta: Map<String, Any?>?,
        turnUsage: Map<String, Any?>? = null,
        createdAt: Long
    ): Map<String, Any?> {
        val safeText = AgentTextSanitizer.sanitizeUtf16(text)
        val safeReasoning = AgentTextSanitizer.sanitizeUtf16(reasoningContent.orEmpty())
            .trim()
            .takeIf { it.isNotBlank() }
        val historyAttachments = AgentImageAttachmentSupport
            .prepareAttachments(attachments)
            .historyAttachments
        val content = linkedMapOf<String, Any?>(
            "text" to safeText,
            "id" to messageId
        )
        if (historyAttachments.isNotEmpty()) {
            content["attachments"] = historyAttachments
        }
        agentId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["agentId"] = it
        }
        agentName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["agentName"] = it
        }
        return linkedMapOf(
            "id" to messageId,
            "type" to 1,
            "user" to user,
            "content" to content,
            "isLoading" to false,
            "isFirst" to false,
            "isError" to isError,
            "interruptedTurn" to if (interruptedTurn) true else null,
            "isSummarizing" to false,
            "streamMeta" to streamMeta,
            "turnUsage" to turnUsage,
            "createAt" to Instant.ofEpochMilli(createdAt).toString()
        ).apply {
            if (user == 2 && safeReasoning != null) {
                put("reasoning_content", safeReasoning)
            }
        }.filterValues { it != null }
    }

    fun externalUserMessageStreamMeta(): Map<String, Any?> {
        return mapOf(
            "source" to EXTERNAL_USER_MESSAGE_SOURCE,
            PENDING_SNAPSHOT_ACK to true
        )
    }

    fun mergePendingExternalUserMessages(
        existingMessages: List<Map<String, Any?>>,
        incomingMessages: List<Map<String, Any?>>
    ): List<Map<String, Any?>> {
        val incomingIds = incomingMessages.mapNotNull(::messageId).toSet()
        val pendingById = linkedMapOf<String, Map<String, Any?>>()
        existingMessages.forEach { message ->
            val id = messageId(message) ?: return@forEach
            val streamMeta = toStringAnyMap(message["streamMeta"])
            if (
                (message["type"] as? Number)?.toInt() == 1 &&
                (message["user"] as? Number)?.toInt() == 1 &&
                streamMeta["source"]?.toString() == EXTERNAL_USER_MESSAGE_SOURCE &&
                parseBoolean(streamMeta[PENDING_SNAPSHOT_ACK], default = false) &&
                id !in incomingIds
            ) {
                pendingById[id] = message
            }
        }
        return pendingById.values + incomingMessages.map(::acknowledgeExternalUserMessage)
    }

    private fun acknowledgeExternalUserMessage(
        message: Map<String, Any?>
    ): Map<String, Any?> {
        val streamMeta = toStringAnyMap(message["streamMeta"])
        if (
            streamMeta["source"]?.toString() != EXTERNAL_USER_MESSAGE_SOURCE ||
            !parseBoolean(streamMeta[PENDING_SNAPSHOT_ACK], default = false)
        ) {
            return message
        }
        val acknowledgedMeta =
            streamMeta - PENDING_SNAPSHOT_ACK - "source"
        return LinkedHashMap(message).apply {
            if (acknowledgedMeta.isEmpty()) {
                remove("streamMeta")
            } else {
                put("streamMeta", acknowledgedMeta)
            }
        }
    }

    private fun messageId(message: Map<String, Any?>): String? {
        val content = toStringAnyMap(message["content"])
        return (message["id"] ?: content["id"])
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun buildCardMessagePayload(
        messageId: String,
        cardData: Map<String, Any?>,
        isError: Boolean,
        streamMeta: Map<String, Any?>?,
        createdAt: Long
    ): Map<String, Any?> {
        val content = linkedMapOf<String, Any?>(
            "cardData" to cardData,
            "id" to messageId
        )
        copyAgentIdentity(cardData, content)
        return linkedMapOf(
            "id" to messageId,
            "type" to 2,
            "user" to 3,
            "content" to content,
            "isLoading" to false,
            "isFirst" to false,
            "isError" to isError,
            "isSummarizing" to false,
            "streamMeta" to streamMeta,
            "createAt" to Instant.ofEpochMilli(createdAt).toString()
        ).filterValues { it != null }
    }

    fun preserveDeepThinkingContent(
        existingPayload: Map<String, Any?>,
        incomingPayload: Map<String, Any?>
    ): Map<String, Any?> {
        val existingContent = toStringAnyMap(existingPayload["content"])
        val existingCardData = toStringAnyMap(existingContent["cardData"])
        val incomingContent = toStringAnyMap(incomingPayload["content"])
        val incomingCardData = toStringAnyMap(incomingContent["cardData"])
        if (
            existingCardData["type"]?.toString() != "deep_thinking" ||
            incomingCardData["type"]?.toString() != "deep_thinking"
        ) {
            return incomingPayload
        }

        val existingThinking = AgentTextSanitizer.sanitizeUtf16(
            existingCardData["thinkingContent"]?.toString().orEmpty()
        )
        val incomingThinking = AgentTextSanitizer.sanitizeUtf16(
            incomingCardData["thinkingContent"]?.toString().orEmpty()
        )
        if (existingThinking.isBlank() || incomingThinking.isNotBlank()) {
            return incomingPayload
        }

        val mergedCardData = linkedMapOf<String, Any?>().apply {
            putAll(incomingCardData)
            put("thinkingContent", existingThinking)
            listOf(
                "thinkingContentTruncated",
                "thinkingOriginalLength",
                "thinkingTruncateMode"
            ).forEach { key ->
                existingCardData[key]?.let { put(key, it) }
            }
        }
        val mergedContent = linkedMapOf<String, Any?>().apply {
            putAll(incomingContent)
            put("cardData", mergedCardData)
        }
        return linkedMapOf<String, Any?>().apply {
            putAll(incomingPayload)
            put("content", mergedContent)
        }
    }

    private fun readReasoningContent(payload: Map<String, Any?>): String? {
        return AgentTextSanitizer.sanitizeUtf16(
            payload["reasoning_content"]?.toString()
                ?: payload["reasoningContent"]?.toString()
                ?: ""
        ).trim().takeIf { it.isNotBlank() }
    }

    fun materializeRecord(record: AgentConversationEntryRecord): MaterializedEntry {
        val normalizedSummary = normalizeStoredSummary(record.summary, record.entryType)
        val baseEntry = record.toEntry().copy(summary = normalizedSummary)
        if (!record.payloadTruncated && !record.summaryTruncated) {
            return MaterializedEntry(baseEntry, false)
        }
        if (!record.payloadTruncated) {
            return MaterializedEntry(
                prepareEntryForStorage(baseEntry),
                true
            )
        }
        val recoveredEntry = when (record.entryType) {
            AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT -> {
                buildStorageSafeGenericToolEntry(
                    entry = baseEntry.copy(payloadJson = ""),
                    originalPayloadLength = record.payloadOriginalLength
                )
            }

            AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD -> {
                buildRecoveredUiCardEntry(
                    entry = baseEntry.copy(payloadJson = ""),
                    originalPayloadLength = record.payloadOriginalLength
                )
            }

            AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE -> {
                buildRecoveredTextEntry(
                    entry = baseEntry.copy(payloadJson = ""),
                    originalPayloadLength = record.payloadOriginalLength
                )
            }

            else -> baseEntry.copy(payloadJson = "")
        }
        return MaterializedEntry(recoveredEntry, true)
    }

    fun prepareEntryForStorage(entry: AgentConversationEntry): AgentConversationEntry {
        val normalizedSummary = normalizeStoredSummary(entry.summary, entry.entryType)
        val normalizedEntry = entry.copy(summary = normalizedSummary)
        return when (entry.entryType) {
            AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT -> {
                if (entry.payloadJson.length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS &&
                    normalizedSummary.length <= MAX_STORAGE_SUMMARY_CHARS
                ) {
                    normalizedEntry
                } else {
                    buildStorageSafeToolEntry(normalizedEntry)
                }
            }

            AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD -> {
                if (entry.payloadJson.length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS &&
                    normalizedSummary.length <= MAX_STORAGE_SUMMARY_CHARS
                ) {
                    normalizedEntry
                } else {
                    buildStorageSafeUiCardEntry(normalizedEntry)
                }
            }

            AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE,
            AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE -> {
                if (entry.payloadJson.length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS &&
                    normalizedSummary.length <= MAX_STORAGE_SUMMARY_CHARS
                ) {
                    normalizedEntry
                } else {
                    buildStorageSafeTextEntry(normalizedEntry)
                }
            }

            else -> normalizedEntry
        }
    }

    fun buildPromptSeedFromEntries(
        entries: List<AgentConversationEntry>,
        contextSummary: String? = null,
        cutoffEntryDbId: Long? = null
    ): AgentConversationHistoryRepository.PromptSeed {
        val historyMessages = mutableListOf<ChatCompletionMessage>()
        contextSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { summary ->
            historyMessages += buildContextSummaryUserMessage(summary)
        }
        historyMessages += buildPromptRelevantMessages(
            entries = entries,
            cutoffEntryDbId = cutoffEntryDbId
        )
        return AgentConversationHistoryRepository.PromptSeed(historyMessages = historyMessages)
    }

    fun buildPromptRelevantMessages(
        entries: List<AgentConversationEntry>,
        cutoffEntryDbId: Long? = null
    ): List<ChatCompletionMessage> {
        val relevantEntries = entries
            .asSequence()
            .filter(::isPromptRelevantEntry)
            .filter { entry -> cutoffEntryDbId == null || entry.id > cutoffEntryDbId }
            .toList()

        val replayMessages = mutableListOf<ChatCompletionMessage>()
        val deferredAssistantEntries = mutableListOf<AgentConversationEntry>()
        val consumedCanonicalToolEntryIds = mutableSetOf<Long>()

        fun flushDeferredAssistantEntries() {
            if (deferredAssistantEntries.isEmpty()) return
            deferredAssistantEntries.forEach { assistantEntry ->
                replayMessages += buildAssistantPromptMessages(assistantEntry)
            }
            deferredAssistantEntries.clear()
        }

        relevantEntries.forEachIndexed { index, entry ->
            if (entry.id in consumedCanonicalToolEntryIds) {
                return@forEachIndexed
            }
            when (entry.entryType) {
                AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE -> {
                    flushDeferredAssistantEntries()
                    replayMessages += buildUserPromptMessages(entry)
                }

                AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE -> {
                    if (isInterruptedAssistantEntry(entry)) {
                        return@forEachIndexed
                    }
                    if (shouldReplayAssistantContentAfterTools(relevantEntries, index, entry)) {
                        deferredAssistantEntries += entry
                    } else {
                        flushDeferredAssistantEntries()
                        replayMessages += buildAssistantPromptMessages(entry)
                    }
                }

                AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT -> {
                    val canonicalAssistantJson = readMap(entry.payloadJson)
                        .get("modelAssistantMessageJson")
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                    if (canonicalAssistantJson == null) {
                        replayMessages += buildToolReplayMessages(entry)
                    } else {
                        val canonicalAssistant = decodeCanonicalMessage(canonicalAssistantJson)
                        if (canonicalAssistant == null) {
                            replayMessages += buildToolReplayMessages(entry)
                        } else {
                            val group = relevantEntries
                                .asSequence()
                                .drop(index)
                                .takeWhile {
                                    it.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT
                                }
                                .map { candidate -> candidate to readMap(candidate.payloadJson) }
                                .filter { (_, payload) ->
                                    payload["modelAssistantMessageJson"]?.toString() == canonicalAssistantJson
                                }
                                .toList()
                            val resultByCallId = group.mapNotNull { (candidate, payload) ->
                                val callId = payload["modelToolCallId"]?.toString()?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: return@mapNotNull null
                                val result = payload["modelToolResultMessageJson"]
                                    ?.toString()
                                    ?.let(::decodeCanonicalMessage)
                                    ?: return@mapNotNull null
                                consumedCanonicalToolEntryIds += candidate.id
                                callId to result
                            }.toMap()
                            replayMessages += canonicalAssistant
                            canonicalAssistant.toolCalls.orEmpty().forEach { toolCall ->
                                resultByCallId[toolCall.id]?.let(replayMessages::add)
                            }
                            resultByCallId
                                .filterKeys { callId ->
                                    canonicalAssistant.toolCalls.orEmpty().none { it.id == callId }
                                }
                                .values
                                .forEach(replayMessages::add)
                        }
                    }
                }

                else -> Unit
            }
        }

        flushDeferredAssistantEntries()
        return replayMessages
    }

    private fun decodeCanonicalMessage(raw: String): ChatCompletionMessage? {
        return runCatching {
            canonicalMessageJson.decodeFromString<ChatCompletionMessage>(raw)
        }.getOrNull()
    }

    private fun isInterruptedAssistantEntry(entry: AgentConversationEntry): Boolean {
        if (entry.entryType != AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE) {
            return false
        }
        val payload = runCatching { readMap(entry.payloadJson) }.getOrNull() ?: return false
        return parseBoolean(payload["interruptedTurn"], default = false)
    }

    fun buildContextSummaryUserMessage(summary: String): ChatCompletionMessage {
        val normalizedSummary = AgentTextSanitizer.sanitizeUtf16(summary).trim()
        val content = if (normalizedSummary.isEmpty()) {
            CONTEXT_SUMMARY_USER_PREFIX
        } else {
            "$CONTEXT_SUMMARY_USER_PREFIX\n\n$normalizedSummary"
        }
        return ChatCompletionMessage(
            role = "user",
            content = JsonPrimitive(content)
        )
    }

    fun buildContextSummarySystemMessage(summary: String): ChatCompletionMessage {
        return buildContextSummaryUserMessage(summary)
    }

    fun extractContextSummaryText(message: ChatCompletionMessage): String? {
        val content = message.content as? JsonPrimitive ?: return null
        return when {
            message.role == "user" &&
                content.content.startsWith(CONTEXT_SUMMARY_USER_PREFIX) -> {
                content.content.removePrefix(CONTEXT_SUMMARY_USER_PREFIX).trim()
            }

            message.role == "system" &&
                content.content.startsWith(LEGACY_CONTEXT_SUMMARY_SYSTEM_PREFIX) -> {
                content.content.removePrefix(LEGACY_CONTEXT_SUMMARY_SYSTEM_PREFIX).trim()
            }

            else -> null
        }
    }

    fun isContextSummaryMessage(message: ChatCompletionMessage): Boolean {
        val content = message.content as? JsonPrimitive ?: return false
        return (message.role == "user" &&
            content.content.startsWith(CONTEXT_SUMMARY_USER_PREFIX)) ||
            (message.role == "system" &&
                content.content.startsWith(LEGACY_CONTEXT_SUMMARY_SYSTEM_PREFIX))
    }

    fun isContextSummarySystemMessage(message: ChatCompletionMessage): Boolean {
        return isContextSummaryMessage(message)
    }

    fun isPromptRelevantEntry(entry: AgentConversationEntry): Boolean {
        return entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE ||
            entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE ||
            entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT
    }

    fun selectEntriesToCompact(
        entries: List<AgentConversationEntry>,
        cutoffEntryDbId: Long? = null
    ): CompactionSelection? {
        val relevantEntries = entries
            .asSequence()
            .filter(::isPromptRelevantEntry)
            .filter { entry -> cutoffEntryDbId == null || entry.id > cutoffEntryDbId }
            .toList()
        val lastUserIndex = relevantEntries.indexOfLast {
            it.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE
        }
        if (lastUserIndex <= 0) {
            return null
        }
        val entriesToCompact = relevantEntries.subList(0, lastUserIndex)
        val cutoff = entriesToCompact.lastOrNull()?.id ?: return null
        return CompactionSelection(
            entriesToCompact = entriesToCompact,
            cutoffEntryDbId = cutoff
        )
    }

    fun buildRuntimeCompactionWindow(
        messages: List<ChatCompletionMessage>
    ): RuntimeCompactionWindow? {
        if (messages.isEmpty()) return null
        val leadingSystemCount = messages.takeWhile { it.role == "system" }.size
        var summaryIndex = -1
        for (index in 0 until leadingSystemCount) {
            if (isContextSummaryMessage(messages[index])) {
                summaryIndex = index
            }
        }
        if (summaryIndex == -1 &&
            leadingSystemCount < messages.size &&
            isContextSummaryMessage(messages[leadingSystemCount])
        ) {
            summaryIndex = leadingSystemCount
        }
        val latestUserIndex = messages.indexOfLast { message ->
            message.role == "user" && !isContextSummaryMessage(message)
        }
        if (latestUserIndex == -1) return null
        val compactionStartIndex = if (summaryIndex >= 0) summaryIndex + 1 else leadingSystemCount
        if (latestUserIndex <= compactionStartIndex) {
            return null
        }
        val messagesToCompact = messages.subList(compactionStartIndex, latestUserIndex)
            .filter { it.role != "system" && !isContextSummaryMessage(it) }
        if (messagesToCompact.isEmpty()) {
            return null
        }
        val existingSummary = if (summaryIndex >= 0) {
            extractContextSummaryText(messages[summaryIndex])
        } else {
            null
        }
        return RuntimeCompactionWindow(
            existingSummary = existingSummary,
            messagesToCompact = messagesToCompact
        )
    }

    fun rebuildMessagesWithCompactedSummary(
        messages: List<ChatCompletionMessage>,
        summary: String
    ): List<ChatCompletionMessage> {
        val preservedSystemMessages = messages
            .takeWhile { it.role == "system" }
            .filterNot(::isContextSummaryMessage)
        val latestUserIndex = messages.indexOfLast { message ->
            message.role == "user" && !isContextSummaryMessage(message)
        }
        if (latestUserIndex == -1) {
            return preservedSystemMessages + buildContextSummaryUserMessage(summary)
        }
        val rebuilt = mutableListOf<ChatCompletionMessage>()
        rebuilt += preservedSystemMessages
        rebuilt += buildContextSummaryUserMessage(summary)
        rebuilt += messages.subList(latestUserIndex, messages.size)
        return rebuilt
    }

    fun normalizeInterruptedEntries(
        entries: List<AgentConversationEntry>,
        finalizeLatestThinkingEntries: Boolean = false
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries
        val normalized = entries.map { entry ->
            if (
                entry.entryType != AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT ||
                entry.status != AgentConversationHistoryRepository.STATUS_RUNNING
            ) {
                entry
            } else {
                val mergedPayload = mergeToolPayload(
                    existing = readMap(entry.payloadJson),
                    incoming = mapOf(
                        "status" to AgentConversationHistoryRepository.STATUS_INTERRUPTED,
                        "summary" to entry.summary.ifBlank { "工具调用已中断" }
                    ),
                    fallbackStatus = AgentConversationHistoryRepository.STATUS_INTERRUPTED,
                    fallbackSummary = entry.summary.ifBlank { "工具调用已中断" }
                )
                entry.copy(
                    status = AgentConversationHistoryRepository.STATUS_INTERRUPTED,
                    summary = mergedPayload["summary"]?.toString().orEmpty().ifBlank {
                        "工具调用已中断"
                    },
                    payloadJson = gson.toJson(mergedPayload),
                    updatedAt = entry.updatedAt
                )
            }
        }
        return normalizeStaleThinkingEntries(
            entries = normalized,
            finalizeLatestThinkingEntries = finalizeLatestThinkingEntries
        )
    }

    fun mergeToolPayload(
        existing: Map<String, Any?>,
        incoming: Map<String, Any?>,
        fallbackStatus: String,
        fallbackSummary: String
    ): Map<String, Any?> {
        fun text(source: Map<String, Any?>, key: String): String {
            return source[key]?.toString()?.trim().orEmpty()
        }

        fun rawText(source: Map<String, Any?>, key: String): String {
            return source[key]?.toString().orEmpty()
        }

        fun chooseText(key: String, fallback: String = ""): String {
            return text(incoming, key).ifEmpty {
                text(existing, key).ifEmpty { fallback }
            }
        }

        fun chooseAny(key: String): Any? {
            return incoming[key] ?: existing[key]
        }

        fun mergeMapList(key: String): List<Map<String, Any?>> {
            val merged = linkedMapOf<String, Map<String, Any?>>()
            fun addAll(items: List<Map<String, Any?>>) {
                for (item in items) {
                    val identity = item["id"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: listOf(
                            item["seq"],
                            item["kind"],
                            item["taskIndex"],
                            item["summary"],
                            item["toolName"]
                        ).joinToString("|") { it?.toString().orEmpty() }
                    merged[identity] = item
                }
            }
            addAll(toListOfStringAnyMap(existing[key]))
            addAll(toListOfStringAnyMap(incoming[key]))
            return merged.values.toList()
        }

        val toolType = chooseText("toolType", "builtin")
        val existingTerminalOutput = rawText(existing, "terminalOutput")
        val terminalOutputDelta = rawText(incoming, "terminalOutputDelta")
        val terminalOutput = if (toolType == "terminal") {
            rawText(incoming, "terminalOutput").ifEmpty {
                if (terminalOutputDelta.isNotEmpty()) {
                    existingTerminalOutput + terminalOutputDelta
                } else {
                    existingTerminalOutput
                }
            }
        } else {
            chooseText("terminalOutput")
        }
        val reasoningContent = readReasoningContent(incoming) ?: readReasoningContent(existing)

        return linkedMapOf<String, Any?>(
            "taskId" to chooseAny("taskId"),
            "streamMeta" to chooseAny("streamMeta"),
            "agentId" to chooseText("agentId"),
            "agentName" to chooseText("agentName"),
            "uiStyle" to chooseText("uiStyle", "agent_tool"),
            "cardId" to chooseText("cardId"),
            "toolName" to canonicalAgentToolName(chooseText("toolName")),
            "displayName" to chooseText("displayName"),
            "toolTitle" to chooseText("toolTitle"),
            "toolType" to toolType,
            "serverName" to chooseAny("serverName"),
            "status" to chooseText("status", fallbackStatus),
            "summary" to chooseText("summary", fallbackSummary),
            "reasoning_content" to reasoningContent,
            "progress" to chooseText("progress"),
            "subagentStatusText" to chooseText("subagentStatusText"),
            "subagentEvents" to mergeMapList("subagentEvents"),
            "args" to chooseText("args"),
            "argsJson" to chooseText("argsJson"),
            "modelToolCallId" to chooseText("modelToolCallId"),
            "modelAssistantMessageJson" to rawText(incoming, "modelAssistantMessageJson")
                .ifEmpty { rawText(existing, "modelAssistantMessageJson") },
            "modelToolResultMessageJson" to rawText(incoming, "modelToolResultMessageJson")
                .ifEmpty { rawText(existing, "modelToolResultMessageJson") },
            "resultPreviewJson" to chooseText("resultPreviewJson"),
            "rawResultJson" to chooseText("rawResultJson"),
            "terminalOutput" to terminalOutput,
            "terminalOutputDelta" to terminalOutputDelta,
            "terminalSessionId" to chooseAny("terminalSessionId"),
            "terminalStreamState" to chooseText("terminalStreamState"),
            "interruptedBy" to chooseText("interruptedBy"),
            "interruptionReason" to chooseText("interruptionReason"),
            "timedOut" to (incoming["timedOut"] ?: existing["timedOut"] ?: false),
            "workspaceId" to chooseAny("workspaceId"),
            "artifacts" to toListOfStringAnyMap(incoming["artifacts"]).ifEmpty {
                toListOfStringAnyMap(existing["artifacts"])
            },
            "actions" to toListOfStringAnyMap(incoming["actions"]).ifEmpty {
                toListOfStringAnyMap(existing["actions"])
            },
            "success" to (incoming["success"] ?: existing["success"] ?: (fallbackStatus == AgentConversationHistoryRepository.STATUS_SUCCESS))
        )
    }

    private fun buildUserPromptMessages(entry: AgentConversationEntry): List<ChatCompletionMessage> {
        val payload = readMap(entry.payloadJson)
        val content = buildPromptContentFromMessagePayload(payload) ?: return emptyList()
        if (content.isBlankJsonPrimitive()) return emptyList()
        return listOf(
            ChatCompletionMessage(
                role = "user",
                content = content
            )
        )
    }

    private fun buildAssistantPromptMessages(entry: AgentConversationEntry): List<ChatCompletionMessage> {
        val payload = readMap(entry.payloadJson)
        val content = buildPromptContentFromMessagePayload(payload) ?: return emptyList()
        if (content.isBlankJsonPrimitive()) return emptyList()
        return listOf(
            ChatCompletionMessage(
                role = "assistant",
                content = content,
                reasoningContent = readReasoningContent(payload)
            )
        )
    }

    private fun shouldReplayAssistantContentAfterTools(
        entries: List<AgentConversationEntry>,
        assistantIndex: Int,
        assistantEntry: AgentConversationEntry
    ): Boolean {
        val assistantTaskId = extractAssistantReplayTaskId(assistantEntry.entryId) ?: return false
        for (index in assistantIndex + 1 until entries.size) {
            val nextEntry = entries[index]
            if (nextEntry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE) {
                break
            }
            if (nextEntry.entryType != AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT) {
                continue
            }
            if (extractToolReplayTaskId(nextEntry.entryId) == assistantTaskId) {
                return true
            }
        }
        return false
    }

    private fun buildToolReplayMessages(entry: AgentConversationEntry): List<ChatCompletionMessage> {
        val payload = readMap(entry.payloadJson)
        if (parseBoolean(payload["historyOmitted"], default = false)) {
            return emptyList()
        }
        val toolName = payload["toolName"]?.toString()?.trim().orEmpty()
        if (toolName.isEmpty()) return emptyList()

        val toolCallId = "restored_${entry.entryId}"
        val argsJson = payload["argsJson"]?.toString()?.trim()?.ifEmpty { null } ?: "{}"
        val assistantMessage = ChatCompletionMessage(
            role = "assistant",
            reasoningContent = readReasoningContent(payload),
            toolCalls = listOf(
                AssistantToolCall(
                    id = toolCallId,
                    function = AssistantToolCallFunction(
                        name = toolName,
                        arguments = argsJson
                    )
                )
            )
        )
        val toolMessage = ChatCompletionMessage(
            role = "tool",
            toolCallId = toolCallId,
            content = JsonPrimitive(buildCompactToolReplayContent(entry, payload))
        )
        return listOf(assistantMessage, toolMessage)
    }

    private fun extractAssistantReplayTaskId(entryId: String): String? {
        val marker = "-assistant"
        val index = entryId.lastIndexOf(marker)
        if (index <= 0 || index + marker.length != entryId.length) {
            return null
        }
        return entryId.substring(0, index).takeIf { it.isNotBlank() }
    }

    private fun extractToolReplayTaskId(entryId: String): String? {
        val marker = "-tool-"
        val index = entryId.indexOf(marker)
        if (index <= 0) {
            return null
        }
        return entryId.substring(0, index).takeIf { it.isNotBlank() }
    }

    private fun buildCompactToolReplayContent(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>
    ): String {
        val status = payload["status"]?.toString()?.trim().orEmpty().ifEmpty { entry.status }
        val preview = parseJsonMap(payload["resultPreviewJson"]?.toString().orEmpty())
        val content = linkedMapOf<String, Any?>(
            "toolName" to payload["toolName"]?.toString().orEmpty(),
            "displayName" to payload["displayName"]?.toString().orEmpty(),
            "toolTitle" to payload["toolTitle"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "toolType" to payload["toolType"]?.toString().orEmpty().ifEmpty { "builtin" },
            "status" to status,
            "success" to parseBoolean(
                payload["success"],
                default = status == AgentConversationHistoryRepository.STATUS_SUCCESS
            ),
            "summary" to trimText(
                payload["summary"]?.toString()?.trim().orEmpty().ifEmpty { entry.summary.trim() },
                MAX_TOOL_SUMMARY_CHARS
            )
        )
        payload["progress"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["progress"] = trimText(it, MAX_TOOL_SUMMARY_CHARS)
        }
        payload["serverName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["serverName"] = it
        }
        payload["interruptedBy"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["interruptedBy"] = it
        }
        payload["interruptionReason"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["interruptionReason"] = trimText(it, MAX_TOOL_SUMMARY_CHARS)
        }
        listOf("message", "question", "taskId", "goal").forEach { key ->
            preview[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                content[key] = trimText(value, MAX_TOOL_PREVIEW_CHARS)
            }
        }
        listOf("missing", "missingFields").forEach { key ->
            val values = toStringList(preview[key])
            if (values.isNotEmpty()) {
                content[key] = values
            }
        }
        payload["resultPreviewJson"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            content["previewJson"] = trimText(raw, MAX_TOOL_PREVIEW_CHARS)
        }
        payload["terminalOutput"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            content["terminalOutput"] = trimText(raw, MAX_TOOL_TERMINAL_CHARS)
        }
        payload["terminalStreamState"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["terminalStreamState"] = it
        }
        payload["terminalSessionId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            content["terminalSessionId"] = it
        }
        if (payload["timedOut"] == true) {
            content["timedOut"] = true
        }
        return gson.toJson(content.filterValues { value -> value != null })
    }

    fun buildDisplaySafeToolCardData(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        val messageId = entry.entryId
        val storedStatus = entry.status.ifBlank {
            payload["status"]?.toString()?.trim().orEmpty()
                .ifBlank { AgentConversationHistoryRepository.STATUS_SUCCESS }
        }
        val previewPayload = parseJsonMap(payload["resultPreviewJson"]?.toString().orEmpty())
        val rawResultPayload = parseJsonMap(payload["rawResultJson"]?.toString().orEmpty())
        val clarificationQuestion = firstNonBlank(
            payload["question"],
            previewPayload["question"],
            rawResultPayload["question"]
        ).orEmpty()
        val status = storedStatus
        val toolType = payload["toolType"]?.toString()?.trim().orEmpty()
            .ifEmpty { "builtin" }
        val terminalOutput = payload["terminalOutput"]?.toString().orEmpty()
        val argsJson = payload["argsJson"]?.toString().orEmpty()
        val resultPreviewJson = payload["resultPreviewJson"]?.toString().orEmpty()
        val rawResultJson = payload["rawResultJson"]?.toString().orEmpty()
        val safeArgsJson = compactJsonText(argsJson, MAX_DISPLAY_TOOL_JSON_CHARS)
        val safeResultPreviewJson = compactJsonText(
            resultPreviewJson,
            MAX_DISPLAY_TOOL_JSON_CHARS
        )
        val safeRawResultJson = compactJsonText(rawResultJson, MAX_DISPLAY_TOOL_JSON_CHARS)
        val safeTerminalOutput = trimTailText(
            terminalOutput,
            MAX_DISPLAY_TOOL_TERMINAL_CHARS
        )
        val safeReasoningContent = trimText(
            readReasoningContent(payload).orEmpty(),
            MAX_STORAGE_MESSAGE_TEXT_CHARS
        ).trim().takeIf { it.isNotBlank() }
        val payloadCompacted =
            safeArgsJson.length < AgentTextSanitizer.sanitizeUtf16(argsJson).trim().length ||
                safeResultPreviewJson.length <
                AgentTextSanitizer.sanitizeUtf16(resultPreviewJson).trim().length ||
                safeRawResultJson.length <
                AgentTextSanitizer.sanitizeUtf16(rawResultJson).trim().length ||
                safeTerminalOutput.length <
                AgentTextSanitizer.sanitizeUtf16(terminalOutput).trim().length

        return linkedMapOf<String, Any?>(
            "type" to "agent_tool_summary",
            "uiStyle" to "agent_tool",
            "agentId" to payload["agentId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "agentName" to payload["agentName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "taskId" to payload["taskId"],
            "cardId" to payload["cardId"]?.toString().orEmpty().ifEmpty { messageId },
            "toolName" to trimText(
                canonicalAgentToolName(payload["toolName"]?.toString().orEmpty()),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "displayName" to trimText(
                payload["displayName"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "toolTitle" to trimText(
                payload["toolTitle"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "toolType" to toolType,
            "serverName" to compactDisplayScalar(payload["serverName"]),
            "status" to status,
            "summary" to trimText(
                payload["summary"]?.toString().orEmpty().ifEmpty { entry.summary },
                MAX_TOOL_SUMMARY_CHARS
            ),
            "question" to clarificationQuestion.takeIf { it.isNotBlank() },
            "missingFields" to (
                payload["missingFields"] ?:
                    previewPayload["missingFields"] ?:
                    rawResultPayload["missingFields"]
            ),
            "reasoning_content" to safeReasoningContent,
            "progress" to trimText(
                payload["progress"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "subagentStatusText" to trimText(
                payload["subagentStatusText"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "subagentEvents" to compactDisplayList(payload["subagentEvents"]),
            "argsJson" to safeArgsJson,
            "resultPreviewJson" to safeResultPreviewJson,
            "rawResultJson" to safeRawResultJson,
            "terminalOutput" to safeTerminalOutput,
            "terminalOutputDelta" to "",
            "terminalSessionId" to compactDisplayScalar(payload["terminalSessionId"]),
            "terminalStreamState" to trimText(
                payload["terminalStreamState"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "interruptedBy" to trimText(
                payload["interruptedBy"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "interruptionReason" to trimText(
                payload["interruptionReason"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "timedOut" to parseBoolean(payload["timedOut"], default = false),
            "workspaceId" to compactDisplayScalar(payload["workspaceId"]),
            "artifacts" to compactDisplayList(payload["artifacts"]),
            "actions" to compactDisplayList(payload["actions"]),
            "success" to (
                payload["success"]
                    ?: (status == AgentConversationHistoryRepository.STATUS_SUCCESS)
                ),
            "showScheduleAction" to (toolType == "schedule"),
            "showAlarmAction" to (toolType == "alarm"),
            "isHistorical" to true,
            "historyRenderMode" to "compact",
            "payloadCompacted" to payloadCompacted,
            "argsJsonOriginalLength" to originalLengthIfCompacted(argsJson, safeArgsJson),
            "resultPreviewJsonOriginalLength" to originalLengthIfCompacted(
                resultPreviewJson,
                safeResultPreviewJson
            ),
            "rawResultJsonOriginalLength" to originalLengthIfCompacted(
                rawResultJson,
                safeRawResultJson
            ),
            "terminalOutputOriginalLength" to originalLengthIfCompacted(
                terminalOutput,
                safeTerminalOutput
            )
        ).filterValues { value -> value != null }
    }

    fun buildDisplaySafeUiCardMessage(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        val messageId = payload["id"]?.toString()?.trim().orEmpty()
            .ifEmpty { entry.entryId }
        val content = toStringAnyMap(payload["content"])
        val contentId = content["id"]?.toString()?.trim().orEmpty()
            .ifEmpty { messageId }
        val cardData = toStringAnyMap(content["cardData"])
        val safeCardData = buildDisplaySafeUiCardData(
            entry = entry,
            cardData = cardData,
            originalPayloadLength = entry.payloadJson.length
        )
        val safeContent = linkedMapOf<String, Any?>(
            "cardData" to safeCardData,
            "id" to contentId
        )
        content["dbId"]?.let { safeContent["dbId"] = it }
        copyAgentIdentity(content, safeContent)
        copyAgentIdentity(safeCardData, safeContent)

        return linkedMapOf(
            "id" to messageId,
            "type" to (parseInt(payload["type"]) ?: 2),
            "user" to (parseInt(payload["user"]) ?: 3),
            "content" to safeContent,
            "isLoading" to false,
            "isFirst" to parseBoolean(payload["isFirst"], default = false),
            "isError" to parseBoolean(payload["isError"], default = false),
            "isSummarizing" to false,
            "streamMeta" to compactDisplayStreamMeta(payload["streamMeta"]),
            "createAt" to (payload["createAt"] ?: entry.createdAt)
        ).filterValues { value -> value != null }
    }

    fun compactDisplayStreamMeta(value: Any?): Map<String, Any?>? {
        val raw = toStringAnyMap(value)
        if (raw.isEmpty()) return null
        val safe = linkedMapOf<String, Any?>()
        listOf(
            "seq",
            "entrySeq",
            "roundIndex",
            "kind",
            "parentTaskId",
            "entryId",
            "isFinal",
            "source",
            "agentId",
            "agentName",
            PENDING_SNAPSHOT_ACK
        ).forEach { key ->
            raw[key]?.let { candidate ->
                safe[key] = compactDisplayScalar(candidate)
            }
        }
        return safe.takeIf { it.isNotEmpty() }
    }

    fun restoreToolPayloadFromUiMessage(
        message: Map<String, Any?>
    ): Map<String, Any?>? {
        val type = (message["type"] as? Number)?.toInt()
        if (type != 2) return null

        val content = toStringAnyMap(message["content"])
        val cardData = toStringAnyMap(content["cardData"])
        if (cardData["type"]?.toString()?.trim() != "agent_tool_summary") {
            return null
        }

        val toolName = canonicalAgentToolName(
            cardData["toolName"]?.toString()?.trim().orEmpty()
        )
        if (toolName.isEmpty()) return null
        val streamMeta = toStringAnyMap(message["streamMeta"])
        val agentId = firstNonBlank(
            cardData["agentId"],
            content["agentId"],
            streamMeta["agentId"]
        )
        val agentName = firstNonBlank(
            cardData["agentName"],
            content["agentName"],
            streamMeta["agentName"]
        )

        val rawPayload = linkedMapOf<String, Any?>(
            "taskId" to cardData["taskId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "streamMeta" to streamMeta.takeIf { it.isNotEmpty() },
            "agentId" to agentId,
            "agentName" to agentName,
            "uiStyle" to "agent_tool",
            "cardId" to cardData["cardId"]?.toString()?.trim().orEmpty(),
            "toolName" to toolName,
            "displayName" to cardData["displayName"]?.toString()?.trim().orEmpty(),
            "toolTitle" to cardData["toolTitle"]?.toString()?.trim().orEmpty(),
            "toolType" to cardData["toolType"]?.toString()?.trim().orEmpty().ifEmpty { "builtin" },
            "serverName" to cardData["serverName"],
            "status" to cardData["status"]?.toString()?.trim().orEmpty(),
            "summary" to cardData["summary"]?.toString()?.trim().orEmpty(),
            "reasoning_content" to cardData["reasoning_content"]?.toString().orEmpty(),
            "progress" to cardData["progress"]?.toString()?.trim().orEmpty(),
            "subagentStatusText" to cardData["subagentStatusText"]?.toString()?.trim().orEmpty(),
            "subagentEvents" to toListOfStringAnyMap(cardData["subagentEvents"]),
            "args" to cardData["argsJson"]?.toString().orEmpty(),
            "argsJson" to cardData["argsJson"]?.toString().orEmpty(),
            "resultPreviewJson" to cardData["resultPreviewJson"]?.toString().orEmpty(),
            "rawResultJson" to cardData["rawResultJson"]?.toString().orEmpty(),
            "terminalOutput" to cardData["terminalOutput"]?.toString().orEmpty(),
            "terminalOutputDelta" to cardData["terminalOutputDelta"]?.toString().orEmpty(),
            "terminalSessionId" to cardData["terminalSessionId"],
            "terminalStreamState" to cardData["terminalStreamState"]?.toString()?.trim().orEmpty(),
            "interruptedBy" to cardData["interruptedBy"]?.toString()?.trim().orEmpty(),
            "interruptionReason" to cardData["interruptionReason"]?.toString()?.trim().orEmpty(),
            "timedOut" to parseBoolean(cardData["timedOut"], default = false),
            "workspaceId" to cardData["workspaceId"],
            "artifacts" to toListOfStringAnyMap(cardData["artifacts"]),
            "actions" to toListOfStringAnyMap(cardData["actions"]),
            "success" to parseBoolean(
                cardData["success"],
                default = cardData["status"]?.toString()?.trim() ==
                    AgentConversationHistoryRepository.STATUS_SUCCESS
            )
        )
        val fallbackStatus = rawPayload["status"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: if (message["isError"] == true) {
                AgentConversationHistoryRepository.STATUS_ERROR
            } else {
                AgentConversationHistoryRepository.STATUS_SUCCESS
            }
        val fallbackSummary = rawPayload["summary"]?.toString()?.trim().orEmpty()
        return mergeToolPayload(
            existing = emptyMap(),
            incoming = rawPayload,
            fallbackStatus = fallbackStatus,
            fallbackSummary = fallbackSummary
        )
    }

    private fun buildPromptContentFromMessagePayload(
        payload: Map<String, Any?>
    ): JsonElement? {
        val content = toStringAnyMap(payload["content"])
        val attachments = toListOfStringAnyMap(content["attachments"])
        val text = AgentAttachmentPromptSupport.buildUserMessageText(
            text = content["text"]?.toString().orEmpty(),
            attachments = attachments
        )
        val imageBlocks = attachments.mapNotNull { attachment ->
            if (!shouldSendAttachmentToModel(attachment)) {
                return@mapNotNull null
            }
            if (!AgentImageAttachmentSupport.isImageAttachment(attachment)) {
                return@mapNotNull null
            }
            val imageUrl = resolveImageAttachmentUrl(attachment)
            if (imageUrl.isBlank()) {
                null
            } else {
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(
                            mapOf("url" to JsonPrimitive(imageUrl))
                        )
                    )
                )
            }
        }
        if (imageBlocks.isEmpty()) {
            return JsonPrimitive(text)
        }
        val blocks = mutableListOf<JsonElement>()
        if (text.isNotBlank()) {
            blocks += JsonObject(
                mapOf(
                    "type" to JsonPrimitive("text"),
                    "text" to JsonPrimitive(text)
                )
            )
        }
        blocks += imageBlocks
        return JsonArray(blocks)
    }

    private fun shouldSendAttachmentToModel(attachment: Map<String, Any?>): Boolean {
        return when (val raw = attachment["sendToModel"]) {
            is Boolean -> raw
            is String -> !raw.equals("false", ignoreCase = true)
            else -> true
        }
    }

    private fun resolveImageAttachmentUrl(attachment: Map<String, Any?>): String {
        return AgentImageAttachmentSupport.resolveImageAttachmentUrl(attachment)
    }

    fun readMap(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Any?>>(
                json,
                object : TypeToken<Map<String, Any?>>() {}.type
            )
        }.getOrElse { emptyMap() }
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

    private fun parseJsonMap(json: String): Map<String, Any?> {
        return readMap(json)
    }

    private fun toStringList(value: Any?): List<String> {
        if (value !is List<*>) return emptyList()
        return value.mapNotNull { item ->
            item?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun buildDisplaySafeUiCardData(
        entry: AgentConversationEntry,
        cardData: Map<String, Any?>,
        originalPayloadLength: Int
    ): Map<String, Any?> {
        val type = cardData["type"]?.toString()?.trim().orEmpty()
        if (type.isEmpty()) {
            return buildHistoryOmittedCardData(
                originalType = "",
                originalPayloadLength = originalPayloadLength,
                summary = entry.summary.ifBlank { "历史过程卡片已折叠" }
            )
        }

        if (type == "deep_thinking") {
            return buildDisplaySafeDeepThinkingCardData(
                entry = entry,
                cardData = cardData,
                originalPayloadLength = originalPayloadLength
            )
        }

        val compact = toStringAnyMap(compactDisplayValue(cardData, depth = 0))
        val withHistoryMeta = linkedMapOf<String, Any?>().apply {
            putAll(compact)
            put("type", type)
            put("isHistorical", true)
            put("historyRenderMode", "compact")
        }
        if (gson.toJson(withHistoryMeta).length <= MAX_DISPLAY_CARD_JSON_CHARS) {
            return withHistoryMeta
        }

        return buildHistoryOmittedCardData(
            originalType = type,
            originalPayloadLength = originalPayloadLength,
            summary = cardData["summary"]?.toString()?.trim().orEmpty()
                .ifEmpty { entry.summary.ifBlank { "历史过程卡片已折叠" } }
        )
    }

    private fun buildDisplaySafeDeepThinkingCardData(
        entry: AgentConversationEntry,
        cardData: Map<String, Any?>,
        originalPayloadLength: Int
    ): Map<String, Any?> {
        val thinking = cardData["thinkingContent"]?.toString().orEmpty()
        val safeThinking = trimTailText(thinking, MAX_DISPLAY_THINKING_CHARS)
        val rawStage = parseInt(cardData["stage"]) ?: 4
        val displayStage = if (rawStage == 5) 5 else 4
        val originalThinkingLength = AgentTextSanitizer.sanitizeUtf16(thinking).trim().length
        val safeThinkingLength = AgentTextSanitizer.sanitizeUtf16(safeThinking).trim().length

        return linkedMapOf<String, Any?>(
            "type" to "deep_thinking",
            "taskID" to (
                cardData["taskID"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: extractTaskIdFromEntryId(entry.entryId)
                ),
            "cardId" to cardData["cardId"]?.toString()?.trim().orEmpty()
                .ifEmpty { entry.entryId },
            "thinkingContent" to safeThinking,
            "thinkingContentTruncated" to (
                parseBoolean(cardData["thinkingContentTruncated"], default = false) ||
                    safeThinkingLength < originalThinkingLength
                ),
            "thinkingOriginalLength" to (
                parseInt(cardData["thinkingOriginalLength"]) ?: originalThinkingLength
                ),
            "thinkingTruncateMode" to if (safeThinkingLength < originalThinkingLength) {
                "head_omitted"
            } else {
                cardData["thinkingTruncateMode"]?.toString()?.trim().orEmpty()
                    .ifEmpty { "none" }
            },
            "stage" to displayStage,
            "isLoading" to false,
            "startTime" to parseLong(cardData["startTime"]),
            "endTime" to (
                parseLong(cardData["endTime"])
                    ?: maxOf(entry.createdAt, entry.updatedAt)
                ),
            "isExecutable" to parseBoolean(cardData["isExecutable"], default = false),
            "isCollapsible" to true,
            "isHistorical" to true,
            "historyRenderMode" to "compact",
            "payloadCompacted" to (
                safeThinkingLength < originalThinkingLength ||
                    originalPayloadLength > MAX_DISPLAY_CARD_JSON_CHARS
                ),
            "originalPayloadLength" to originalPayloadLength.takeIf {
                it > MAX_DISPLAY_CARD_JSON_CHARS
            }
        ).filterValues { value -> value != null }
    }

    private fun buildHistoryOmittedCardData(
        originalType: String,
        originalPayloadLength: Int,
        summary: String
    ): Map<String, Any?> {
        return linkedMapOf(
            "type" to "history_omitted_card",
            "originalType" to originalType.takeIf { it.isNotEmpty() },
            "summary" to trimText(
                summary.ifBlank { "历史过程卡片已折叠，核心对话内容仍然保留。" },
                MAX_TOOL_SUMMARY_CHARS
            ),
            "originalPayloadLength" to originalPayloadLength.takeIf { it > 0 },
            "isHistorical" to true,
            "historyRenderMode" to "omitted"
        ).filterValues { value -> value != null }
    }

    private fun buildStorageSafeToolEntry(entry: AgentConversationEntry): AgentConversationEntry {
        val payload = readMap(entry.payloadJson)
        if (payload.isEmpty()) {
            return buildStorageSafeGenericToolEntry(
                entry = entry,
                originalPayloadLength = entry.payloadJson.length
            )
        }
        val normalizedStatus = payload["status"]?.toString()?.trim()
            ?.ifEmpty { null }
            ?: entry.status
        val normalizedSummary = normalizeStoredSummary(
            payload["summary"]?.toString().orEmpty().ifBlank { entry.summary },
            entry.entryType
        )
        val safeReasoningContent = trimText(
            readReasoningContent(payload).orEmpty(),
            MAX_STORAGE_MESSAGE_TEXT_CHARS
        ).trim().takeIf { it.isNotBlank() }
        val safePayload = linkedMapOf<String, Any?>(
            "taskId" to compactDisplayScalar(payload["taskId"]),
            "streamMeta" to compactDisplayStreamMeta(payload["streamMeta"]),
            "agentId" to compactDisplayScalar(payload["agentId"]),
            "agentName" to compactDisplayScalar(payload["agentName"]),
            "uiStyle" to "agent_tool",
            "cardId" to payload["cardId"]?.toString()?.trim().orEmpty().ifEmpty { entry.entryId },
            "toolName" to trimText(
                canonicalAgentToolName(payload["toolName"]?.toString().orEmpty()),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "displayName" to trimText(
                payload["displayName"]?.toString().orEmpty().ifBlank { "工具调用" },
                MAX_DISPLAY_INLINE_CHARS
            ),
            "toolTitle" to trimText(
                payload["toolTitle"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "toolType" to payload["toolType"]?.toString()?.trim().orEmpty().ifEmpty { "builtin" },
            "serverName" to compactDisplayScalar(payload["serverName"]),
            "status" to normalizedStatus,
            "reasoning_content" to safeReasoningContent,
            "summary" to normalizedSummary.ifBlank { "工具调用结果已压缩保存" },
            "progress" to trimText(
                payload["progress"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "subagentStatusText" to trimText(
                payload["subagentStatusText"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "subagentEvents" to compactDisplayList(payload["subagentEvents"]),
            "args" to compactJsonText(payload["args"]?.toString().orEmpty(), MAX_STORAGE_TOOL_JSON_CHARS),
            "argsJson" to compactJsonText(
                payload["argsJson"]?.toString().orEmpty(),
                MAX_STORAGE_TOOL_JSON_CHARS
            ),
            "modelToolCallId" to payload["modelToolCallId"]?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() },
            "modelAssistantMessageJson" to payload["modelAssistantMessageJson"]
                ?.toString()
                ?.takeIf { it.isNotBlank() },
            "modelToolResultMessageJson" to payload["modelToolResultMessageJson"]
                ?.toString()
                ?.takeIf { it.isNotBlank() },
            "resultPreviewJson" to compactJsonText(
                payload["resultPreviewJson"]?.toString().orEmpty(),
                MAX_STORAGE_TOOL_JSON_CHARS
            ),
            "rawResultJson" to compactJsonText(
                payload["rawResultJson"]?.toString().orEmpty(),
                MAX_STORAGE_TOOL_JSON_CHARS
            ),
            "terminalOutput" to trimTailText(
                payload["terminalOutput"]?.toString().orEmpty(),
                MAX_STORAGE_TOOL_TERMINAL_CHARS
            ),
            "terminalOutputDelta" to "",
            "terminalSessionId" to compactDisplayScalar(payload["terminalSessionId"]),
            "terminalStreamState" to trimText(
                payload["terminalStreamState"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "interruptedBy" to trimText(
                payload["interruptedBy"]?.toString().orEmpty(),
                MAX_DISPLAY_INLINE_CHARS
            ),
            "interruptionReason" to trimText(
                payload["interruptionReason"]?.toString().orEmpty(),
                MAX_TOOL_SUMMARY_CHARS
            ),
            "timedOut" to parseBoolean(payload["timedOut"], default = false),
            "workspaceId" to compactDisplayScalar(payload["workspaceId"]),
            "artifacts" to compactDisplayList(payload["artifacts"]),
            "actions" to compactDisplayList(payload["actions"]),
            "success" to parseBoolean(
                payload["success"],
                default = normalizedStatus == AgentConversationHistoryRepository.STATUS_SUCCESS
            ),
            "payloadCompacted" to true,
            "originalPayloadLength" to entry.payloadJson.length.takeIf { it > 0 }
        ).filterValues { value -> value != null }
        val encoded = gson.toJson(safePayload)
        if (encoded.length > MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
            buildStorageSafeCanonicalToolEntry(
                entry = entry.copy(status = normalizedStatus, summary = normalizedSummary),
                payload = payload
            )?.let { return it }
            return buildStorageSafeGenericToolEntry(
                entry = entry.copy(status = normalizedStatus, summary = normalizedSummary),
                originalPayloadLength = entry.payloadJson.length
            )
        }
        return entry.copy(
            status = normalizedStatus,
            summary = normalizedSummary,
            payloadJson = encoded
        )
    }

    private fun buildStorageSafeCanonicalToolEntry(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>
    ): AgentConversationEntry? {
        val assistantJson = payload["modelAssistantMessageJson"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val resultJson = payload["modelToolResultMessageJson"]?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val canonicalPayload = linkedMapOf<String, Any?>(
            "taskId" to payload["taskId"],
            "streamMeta" to compactDisplayStreamMeta(payload["streamMeta"]),
            "cardId" to payload["cardId"]?.toString().orEmpty().ifEmpty { entry.entryId },
            "toolName" to canonicalAgentToolName(payload["toolName"]?.toString().orEmpty()),
            "displayName" to payload["displayName"]?.toString().orEmpty(),
            "toolType" to payload["toolType"]?.toString().orEmpty().ifEmpty { "builtin" },
            "status" to entry.status,
            "summary" to entry.summary,
            "success" to parseBoolean(
                payload["success"],
                default = entry.status == AgentConversationHistoryRepository.STATUS_SUCCESS
            ),
            "modelToolCallId" to payload["modelToolCallId"]?.toString().orEmpty(),
            "modelAssistantMessageJson" to assistantJson,
            "modelToolResultMessageJson" to resultJson,
            "payloadCompacted" to true,
            "canonicalReplayPreserved" to true
        ).filterValues { it != null }
        val encoded = gson.toJson(canonicalPayload)
        return encoded.takeIf { it.length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS }?.let {
            entry.copy(payloadJson = it)
        }
    }

    private fun buildStorageSafeGenericToolEntry(
        entry: AgentConversationEntry,
        originalPayloadLength: Int
    ): AgentConversationEntry {
        val originalPayload = readMap(entry.payloadJson)
        val streamMeta = toStringAnyMap(originalPayload["streamMeta"])
        val normalizedStatus = entry.status.trim()
            .ifEmpty { AgentConversationHistoryRepository.STATUS_SUCCESS }
        val normalizedSummary = normalizeStoredSummary(
            entry.summary.ifBlank { "工具调用历史已折叠" },
            entry.entryType
        )
        val fallbackPayload = linkedMapOf<String, Any?>(
            "streamMeta" to compactDisplayStreamMeta(streamMeta),
            "agentId" to firstNonBlank(originalPayload["agentId"], streamMeta["agentId"]),
            "agentName" to firstNonBlank(originalPayload["agentName"], streamMeta["agentName"]),
            "uiStyle" to "agent_tool",
            "cardId" to entry.entryId,
            "toolName" to canonicalAgentToolName(
                originalPayload["toolName"]?.toString().orEmpty()
            ).ifEmpty { "agent.tool" },
            "displayName" to "工具调用历史",
            "toolType" to "builtin",
            "status" to normalizedStatus,
            "summary" to normalizedSummary,
            "resultPreviewJson" to gson.toJson(
                mapOf(
                    "omitted" to true,
                    "originalLength" to originalPayloadLength
                )
            ),
            "rawResultJson" to "",
            "terminalOutput" to "",
            "success" to (normalizedStatus == AgentConversationHistoryRepository.STATUS_SUCCESS),
            "payloadCompacted" to true,
            "historyOmitted" to true,
            "originalPayloadLength" to originalPayloadLength.takeIf { it > 0 }
        ).filterValues { value -> value != null }
        return entry.copy(
            status = normalizedStatus,
            summary = normalizedSummary,
            payloadJson = gson.toJson(fallbackPayload)
        )
    }

    private fun buildStorageSafeUiCardEntry(entry: AgentConversationEntry): AgentConversationEntry {
        val payload = readMap(entry.payloadJson)
        if (payload.isEmpty()) {
            return buildRecoveredUiCardEntry(
                entry = entry,
                originalPayloadLength = entry.payloadJson.length
            )
        }
        val storageSafePayload = buildStorageSafeUiCardPayload(entry, payload)
        val encoded = gson.toJson(storageSafePayload)
        return if (encoded.length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
            entry.copy(payloadJson = encoded)
        } else {
            buildRecoveredUiCardEntry(
                entry = entry,
                originalPayloadLength = entry.payloadJson.length
            )
        }
    }

    private fun buildRecoveredUiCardEntry(
        entry: AgentConversationEntry,
        originalPayloadLength: Int
    ): AgentConversationEntry {
        val summary = normalizeStoredSummary(
            entry.summary.ifBlank { "历史过程卡片已折叠" },
            entry.entryType
        )
        val payload = buildCardMessagePayload(
            messageId = entry.entryId,
            cardData = buildHistoryOmittedCardData(
                originalType = "",
                originalPayloadLength = originalPayloadLength,
                summary = summary
            ),
            isError = entry.status == AgentConversationHistoryRepository.STATUS_ERROR,
            streamMeta = null,
            createdAt = entry.createdAt
        )
        return entry.copy(
            summary = summary,
            payloadJson = gson.toJson(payload)
        )
    }

    private fun buildStorageSafeUiCardPayload(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        val messageId = payload["id"]?.toString()?.trim().orEmpty()
            .ifEmpty { entry.entryId }
        val content = toStringAnyMap(payload["content"])
        val contentId = content["id"]?.toString()?.trim().orEmpty()
            .ifEmpty { messageId }
        val cardData = toStringAnyMap(content["cardData"])
        val safeCardData = buildStorageSafeUiCardData(
            entry = entry,
            cardData = cardData,
            originalPayloadLength = entry.payloadJson.length
        )
        val safeContent = linkedMapOf<String, Any?>(
            "cardData" to safeCardData,
            "id" to contentId
        )
        content["dbId"]?.let { safeContent["dbId"] = it }
        copyAgentIdentity(content, safeContent)
        copyAgentIdentity(safeCardData, safeContent)

        return linkedMapOf(
            "id" to messageId,
            "type" to (parseInt(payload["type"]) ?: 2),
            "user" to (parseInt(payload["user"]) ?: 3),
            "content" to safeContent,
            "isLoading" to parseBoolean(payload["isLoading"], default = false),
            "isFirst" to parseBoolean(payload["isFirst"], default = false),
            "isError" to parseBoolean(payload["isError"], default = false),
            "isSummarizing" to parseBoolean(payload["isSummarizing"], default = false),
            "streamMeta" to compactDisplayStreamMeta(payload["streamMeta"]),
            "createAt" to (payload["createAt"] ?: entry.createdAt)
        ).filterValues { value -> value != null }
    }

    private fun buildStorageSafeUiCardData(
        entry: AgentConversationEntry,
        cardData: Map<String, Any?>,
        originalPayloadLength: Int
    ): Map<String, Any?> {
        val type = cardData["type"]?.toString()?.trim().orEmpty()
        if (type.isEmpty()) {
            return buildHistoryOmittedCardData(
                originalType = "",
                originalPayloadLength = originalPayloadLength,
                summary = entry.summary.ifBlank { "历史过程卡片已折叠" }
            )
        }
        if (type == "deep_thinking") {
            return buildStorageSafeDeepThinkingCardData(
                entry = entry,
                cardData = cardData,
                originalPayloadLength = originalPayloadLength
            )
        }

        val compact = toStringAnyMap(compactDisplayValue(cardData, depth = 0))
        val normalized = linkedMapOf<String, Any?>().apply {
            putAll(compact)
            put("type", type)
        }
        if (gson.toJson(normalized).length <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
            return normalized
        }

        return buildHistoryOmittedCardData(
            originalType = type,
            originalPayloadLength = originalPayloadLength,
            summary = cardData["summary"]?.toString()?.trim().orEmpty()
                .ifEmpty { entry.summary.ifBlank { "历史过程卡片已折叠" } }
        )
    }

    private fun buildStorageSafeDeepThinkingCardData(
        entry: AgentConversationEntry,
        cardData: Map<String, Any?>,
        originalPayloadLength: Int
    ): Map<String, Any?> {
        val thinking = cardData["thinkingContent"]?.toString().orEmpty()
        val safeThinking = trimTailText(thinking, MAX_DISPLAY_THINKING_CHARS)
        val originalThinkingLength = AgentTextSanitizer.sanitizeUtf16(thinking).trim().length
        val safeThinkingLength = AgentTextSanitizer.sanitizeUtf16(safeThinking).trim().length

        return linkedMapOf<String, Any?>(
            "type" to "deep_thinking",
            "agentId" to cardData["agentId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "agentName" to cardData["agentName"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            "taskID" to (
                cardData["taskID"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: extractTaskIdFromEntryId(entry.entryId)
                ),
            "cardId" to cardData["cardId"]?.toString()?.trim().orEmpty()
                .ifEmpty { entry.entryId },
            "thinkingContent" to safeThinking,
            "thinkingContentTruncated" to (
                parseBoolean(cardData["thinkingContentTruncated"], default = false) ||
                    safeThinkingLength < originalThinkingLength
                ),
            "thinkingOriginalLength" to (
                parseInt(cardData["thinkingOriginalLength"]) ?: originalThinkingLength
                ),
            "thinkingTruncateMode" to if (safeThinkingLength < originalThinkingLength) {
                "head_omitted"
            } else {
                cardData["thinkingTruncateMode"]?.toString()?.trim().orEmpty()
                    .ifEmpty { "none" }
            },
            "stage" to (parseInt(cardData["stage"]) ?: 1),
            "isLoading" to parseBoolean(cardData["isLoading"], default = false),
            "startTime" to parseLong(cardData["startTime"]),
            "endTime" to parseLong(cardData["endTime"]),
            "isExecutable" to parseBoolean(cardData["isExecutable"], default = false),
            "isCollapsible" to parseBoolean(cardData["isCollapsible"], default = true),
            "payloadCompacted" to (
                safeThinkingLength < originalThinkingLength ||
                    originalPayloadLength > MAX_STORAGE_ENTRY_PAYLOAD_CHARS
                ),
            "originalPayloadLength" to originalPayloadLength.takeIf {
                it > MAX_STORAGE_ENTRY_PAYLOAD_CHARS
            }
        ).filterValues { value -> value != null }
    }

    private fun buildStorageSafeTextEntry(entry: AgentConversationEntry): AgentConversationEntry {
        val payload = readMap(entry.payloadJson)
        if (payload.isEmpty()) {
            return buildRecoveredTextEntry(
                entry = entry,
                originalPayloadLength = entry.payloadJson.length
            )
        }
        val messageId = payload["id"]?.toString()?.trim().orEmpty().ifEmpty { entry.entryId }
        val content = toStringAnyMap(payload["content"])
        val safeText = trimText(
            content["text"]?.toString().orEmpty().ifBlank { entry.summary },
            MAX_STORAGE_MESSAGE_TEXT_CHARS
        )
        val safeReasoningContent = if (
            entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE
        ) {
            trimText(
                payload["reasoning_content"]?.toString()
                    ?: payload["reasoningContent"]?.toString()
                    ?: "",
                MAX_STORAGE_MESSAGE_TEXT_CHARS
            ).trim().takeIf { it.isNotBlank() }
        } else {
            null
        }
        val safeStreamMeta = compactDisplayStreamMeta(payload["streamMeta"])
        val safeTurnUsage = toStringAnyMap(payload["turnUsage"])
            .mapValues { (_, value) -> parseLong(value) }
            .filterValues { value -> value != null }
            .mapValues { (_, value) -> value!! }
            .takeIf { it.isNotEmpty() }
        val safeAttachments = compactDisplayList(content["attachments"])
        val agentId = firstNonBlank(content["agentId"], safeStreamMeta?.get("agentId"))
        val agentName = firstNonBlank(content["agentName"], safeStreamMeta?.get("agentName"))
        fun buildPayload(attachments: List<Map<String, Any?>>): Map<String, Any?> {
            return buildTextMessagePayload(
                messageId = messageId,
                user = if (entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE) {
                    1
                } else {
                    2
                },
                text = safeText,
                attachments = attachments,
                reasoningContent = safeReasoningContent,
                agentId = agentId,
                agentName = agentName,
                isError = parseBoolean(
                    payload["isError"],
                    default = entry.status == AgentConversationHistoryRepository.STATUS_ERROR
                ),
                streamMeta = safeStreamMeta,
                turnUsage = safeTurnUsage,
                createdAt = entry.createdAt
            )
        }
        var storagePayload = buildPayload(safeAttachments)
        var encoded = gson.toJson(storagePayload)
        if (encoded.length > MAX_STORAGE_ENTRY_PAYLOAD_CHARS && safeAttachments.isNotEmpty()) {
            storagePayload = buildPayload(emptyList())
            encoded = gson.toJson(storagePayload)
        }
        if (encoded.length > MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
            return buildRecoveredTextEntry(
                entry = entry.copy(summary = safeText),
                originalPayloadLength = entry.payloadJson.length,
                reasoningContent = safeReasoningContent
            )
        }
        return entry.copy(
            summary = normalizeStoredSummary(entry.summary.ifBlank { safeText }, entry.entryType),
            payloadJson = encoded
        )
    }

    private fun canonicalAgentToolName(value: String): String {
        val normalized = value.trim()
        if (!normalized.startsWith("codex.")) {
            return if (normalized.startsWith("codex/")) {
                "agent/${normalized.removePrefix("codex/")}"
            } else {
                normalized
            }
        }
        return "agent.${normalized.removePrefix("codex.")}"
    }

    private fun firstNonBlank(vararg values: Any?): String? {
        return values.firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun copyAgentIdentity(
        source: Map<String, Any?>,
        target: MutableMap<String, Any?>
    ) {
        listOf("agentId", "agentName").forEach { key ->
            source[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                target[key] = it
            }
        }
    }

    private fun buildRecoveredTextEntry(
        entry: AgentConversationEntry,
        originalPayloadLength: Int,
        reasoningContent: String? = null
    ): AgentConversationEntry {
        val summary = normalizeStoredSummary(
            entry.summary.ifBlank { "历史消息内容过大，已压缩保存。" },
            entry.entryType
        )
        val safeText = trimText(
            if (summary.isBlank()) {
                "历史消息内容过大，已压缩保存。"
            } else {
                summary
            },
            MAX_STORAGE_MESSAGE_TEXT_CHARS
        )
        val safeReasoningContent = if (
            entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE
        ) {
            fitReasoningContentToRecoveredTextPayload(
                messageId = entry.entryId,
                text = safeText,
                isError = entry.status == AgentConversationHistoryRepository.STATUS_ERROR,
                createdAt = entry.createdAt,
                reasoningContent = trimText(
                    reasoningContent.orEmpty(),
                    MAX_STORAGE_MESSAGE_TEXT_CHARS
                ).trim().takeIf { it.isNotBlank() }
            )
        } else {
            null
        }
        val payload = buildTextMessagePayload(
            messageId = entry.entryId,
            user = if (entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_USER_MESSAGE) {
                1
            } else {
                2
            },
            text = safeText,
            attachments = emptyList(),
            reasoningContent = safeReasoningContent,
            isError = entry.status == AgentConversationHistoryRepository.STATUS_ERROR,
            streamMeta = mapOf(
                "payloadCompacted" to true,
                "originalPayloadLength" to originalPayloadLength
            ),
            createdAt = entry.createdAt
        )
        return entry.copy(
            summary = summary.ifBlank { safeText },
            payloadJson = gson.toJson(payload)
        )
    }

    private fun fitReasoningContentToRecoveredTextPayload(
        messageId: String,
        text: String,
        isError: Boolean,
        createdAt: Long,
        reasoningContent: String?
    ): String? {
        val normalizedReasoning = reasoningContent?.trim()?.takeIf { it.isNotBlank() } ?: return null

        fun encodedLength(candidate: String?): Int {
            return gson.toJson(
                buildTextMessagePayload(
                    messageId = messageId,
                    user = 2,
                    text = text,
                    attachments = emptyList(),
                    reasoningContent = candidate,
                    isError = isError,
                    streamMeta = mapOf(
                        "payloadCompacted" to true,
                        "originalPayloadLength" to MAX_STORAGE_ENTRY_PAYLOAD_CHARS + 1
                    ),
                    createdAt = createdAt
                )
            ).length
        }

        if (encodedLength(normalizedReasoning) <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
            return normalizedReasoning
        }

        var low = 0
        var high = normalizedReasoning.length
        var best: String? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = normalizedReasoning.take(mid).trimEnd().takeIf { it.isNotBlank() }
            if (encodedLength(candidate) <= MAX_STORAGE_ENTRY_PAYLOAD_CHARS) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun normalizeStoredSummary(summary: String, entryType: String): String {
        val fallback = when (entryType) {
            AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT -> "工具调用历史"
            AgentConversationHistoryRepository.ENTRY_TYPE_UI_CARD -> "过程卡片"
            AgentConversationHistoryRepository.ENTRY_TYPE_ASSISTANT_MESSAGE -> "助手消息"
            else -> ""
        }
        return trimText(summary.trim().ifEmpty { fallback }, MAX_STORAGE_SUMMARY_CHARS)
    }

    private fun compactJsonText(raw: String, maxChars: Int): String {
        val normalized = AgentTextSanitizer.sanitizeUtf16(raw).trim()
        if (normalized.isEmpty() || normalized.length <= maxChars) {
            return normalized
        }

        val compactJson = runCatching {
            val decoded = readMap(normalized)
            if (decoded.isEmpty() && normalized != "{}") {
                null
            } else {
                compactDisplayValue(decoded, depth = 0)
            }
        }.getOrNull()?.let { compactMap ->
            gson.toJson(compactMap)
        }

        if (!compactJson.isNullOrBlank() && compactJson.length <= maxChars) {
            return compactJson
        }

        val envelope = linkedMapOf<String, Any?>(
            "omitted" to true,
            "originalLength" to normalized.length,
            "preview" to trimText(normalized, (maxChars / 2).coerceAtLeast(160))
        )
        val encoded = gson.toJson(envelope)
        if (encoded.length <= maxChars) {
            return encoded
        }
        return gson.toJson(
            mapOf(
                "omitted" to true,
                "originalLength" to normalized.length
            )
        )
    }

    private fun compactDisplayValue(value: Any?, depth: Int): Any? {
        if (value == null) return null
        if (value is Boolean || value is Number) return value
        if (value is String) return trimText(value, MAX_DISPLAY_INLINE_CHARS)
        if (depth >= 4) return "[nested content omitted]"

        if (value is Map<*, *>) {
            val result = linkedMapOf<String, Any?>()
            val entries = value.entries.toList()
            entries.take(24).forEach { (key, rawValue) ->
                val normalizedKey = key?.toString()?.trim().orEmpty()
                if (normalizedKey.isEmpty()) return@forEach
                result[normalizedKey] = compactDisplayValue(rawValue, depth + 1)
            }
            if (entries.size > 24) {
                result["_omittedKeys"] = entries.size - 24
            }
            return result.filterValues { it != null }
        }

        if (value is List<*>) {
            val compactItems = value
                .take(MAX_DISPLAY_LIST_ITEMS)
                .mapNotNull { item -> compactDisplayValue(item, depth + 1) }
                .toMutableList()
            if (value.size > MAX_DISPLAY_LIST_ITEMS) {
                compactItems += mapOf("_omittedItems" to value.size - MAX_DISPLAY_LIST_ITEMS)
            }
            return compactItems
        }

        return trimText(value.toString(), MAX_DISPLAY_INLINE_CHARS)
    }

    private fun compactDisplayScalar(value: Any?): Any? {
        return when (value) {
            null -> null
            is Boolean -> value
            is Number -> value
            else -> trimText(value.toString(), MAX_DISPLAY_INLINE_CHARS)
        }
    }

    private fun compactDisplayList(value: Any?): List<Map<String, Any?>> {
        if (value !is List<*>) return emptyList()
        return value
            .take(MAX_DISPLAY_LIST_ITEMS)
            .mapNotNull { item ->
                toStringAnyMap(compactDisplayValue(item, depth = 0))
                    .takeIf { it.isNotEmpty() }
            }
    }

    private fun originalLengthIfCompacted(raw: String, compacted: String): Int? {
        val originalLength = AgentTextSanitizer.sanitizeUtf16(raw).trim().length
        val compactedLength = AgentTextSanitizer.sanitizeUtf16(compacted).trim().length
        return originalLength.takeIf { originalLength > compactedLength }
    }

    private fun trimText(value: String, maxChars: Int): String {
        val normalized = AgentTextSanitizer.sanitizeUtf16(value).trim()
        if (normalized.length <= maxChars) {
            return normalized
        }
        return AgentTextSanitizer.sanitizeUtf16(normalized.take(maxChars)).trimEnd() + "..."
    }

    private fun trimTailText(
        value: String,
        maxChars: Int,
        notice: String = DISPLAY_TRUNCATION_NOTICE
    ): String {
        val normalized = AgentTextSanitizer.sanitizeUtf16(value).trim()
        if (normalized.length <= maxChars) {
            return normalized
        }
        val bodyLimit = (maxChars - notice.length).coerceAtLeast(0)
        if (bodyLimit == 0) {
            return notice.take(maxChars)
        }
        val runes = normalized.codePoints().toArray()
        val tail = if (runes.size <= bodyLimit) {
            normalized
        } else {
            String(runes.copyOfRange(runes.size - bodyLimit, runes.size), 0, bodyLimit)
        }
        return notice + tail
    }

    private fun normalizeStaleThinkingEntries(
        entries: List<AgentConversationEntry>,
        finalizeLatestThinkingEntries: Boolean
    ): List<AgentConversationEntry> {
        if (entries.isEmpty()) return entries

        val terminalEntryTimeByTask = linkedMapOf<String, Long>()
        val thinkingEntriesByTask = linkedMapOf<String, MutableList<ThinkingEntryRef>>()

        entries.forEachIndexed { index, entry ->
            val payload = readMap(entry.payloadJson)
            val cardData = deepThinkingCardData(payload)
            if (cardData != null) {
                val taskId = deepThinkingTaskId(entry, cardData) ?: return@forEachIndexed
                val startTime = parseLong(cardData["startTime"]) ?: entry.createdAt
                thinkingEntriesByTask.getOrPut(taskId) { mutableListOf() }
                    .add(
                        ThinkingEntryRef(
                            index = index,
                            entry = entry,
                            taskId = taskId,
                            payload = payload,
                            cardData = cardData,
                            startTime = startTime,
                            sequenceRank = thinkingSequenceRank(entry.entryId)
                        )
                    )
                return@forEachIndexed
            }

            val taskId = terminalTaskId(entry) ?: return@forEachIndexed
            val current = terminalEntryTimeByTask[taskId] ?: 0L
            terminalEntryTimeByTask[taskId] = maxOf(current, entry.createdAt)
        }

        if (thinkingEntriesByTask.isEmpty()) {
            return entries
        }

        val updatedEntries = entries.toMutableList()
        thinkingEntriesByTask.values.forEach { candidates ->
            val ordered = candidates.sortedWith(
                compareBy<ThinkingEntryRef> { it.startTime }
                    .thenBy { it.sequenceRank }
                    .thenBy { it.entry.createdAt }
                    .thenBy { it.index }
            )
            val latest = ordered.lastOrNull()
            val terminalTime = latest?.taskId?.let { terminalEntryTimeByTask[it] }

            ordered.forEachIndexed { orderedIndex, thinkingEntry ->
                val nextThinkingStart = ordered.getOrNull(orderedIndex + 1)?.startTime
                val shouldFinalize = when {
                    latest == null -> false
                    thinkingEntry.index != latest.index -> true
                    terminalTime != null && terminalTime >= thinkingEntry.startTime -> true
                    finalizeLatestThinkingEntries -> true
                    else -> false
                }
                if (!shouldFinalize) {
                    return@forEachIndexed
                }
                val resolvedEndTime = when {
                    terminalTime != null -> terminalTime
                    nextThinkingStart != null -> nextThinkingStart
                    finalizeLatestThinkingEntries -> maxOf(
                        thinkingEntry.startTime,
                        thinkingEntry.entry.updatedAt,
                        thinkingEntry.entry.createdAt
                    )
                    else -> System.currentTimeMillis()
                }
                val normalized = finalizeThinkingEntry(
                    entry = thinkingEntry.entry,
                    payload = thinkingEntry.payload,
                    cardData = thinkingEntry.cardData,
                    endTime = maxOf(thinkingEntry.startTime, resolvedEndTime)
                )
                if (normalized != null) {
                    updatedEntries[thinkingEntry.index] = normalized
                }
            }
        }

        return updatedEntries
    }

    private fun finalizeThinkingEntry(
        entry: AgentConversationEntry,
        payload: Map<String, Any?>,
        cardData: Map<String, Any?>,
        endTime: Long
    ): AgentConversationEntry? {
        val currentStage = parseInt(cardData["stage"]) ?: 1
        val currentLoading = parseBoolean(cardData["isLoading"], currentStage != 4)
        if (!currentLoading && currentStage == 4) {
            return null
        }

        val content = linkedMapOf<String, Any?>().apply {
            putAll(toStringAnyMap(payload["content"]))
        }
        val nextCardData = linkedMapOf<String, Any?>().apply {
            putAll(cardData)
            put("isLoading", false)
            put("stage", 4)
            if (parseLong(cardData["endTime"]) == null) {
                put("endTime", endTime)
            }
        }
        content["cardData"] = nextCardData
        val nextPayload = linkedMapOf<String, Any?>().apply {
            putAll(payload)
            put("content", content)
        }
        return entry.copy(
            payloadJson = gson.toJson(nextPayload),
            updatedAt = entry.updatedAt
        )
    }

    private fun deepThinkingCardData(payload: Map<String, Any?>): Map<String, Any?>? {
        val content = toStringAnyMap(payload["content"])
        val cardData = toStringAnyMap(content["cardData"])
        return if (cardData["type"]?.toString() == "deep_thinking") {
            cardData
        } else {
            null
        }
    }

    private fun deepThinkingTaskId(
        entry: AgentConversationEntry,
        cardData: Map<String, Any?>
    ): String? {
        return cardData["taskID"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: extractTaskIdFromEntryId(entry.entryId)
    }

    private fun terminalTaskId(entry: AgentConversationEntry): String? {
        if (entry.entryType == AgentConversationHistoryRepository.ENTRY_TYPE_TOOL_EVENT) {
            return null
        }
        if (entry.entryId.endsWith("-assistant")) {
            return null
        }
        return extractTaskIdFromEntryId(entry.entryId)
            ?.takeIf { !entry.entryId.endsWith("-user") }
    }

    private fun extractTaskIdFromEntryId(entryId: String): String? {
        val normalized = entryId.trim()
        return when {
            normalized.endsWith("-assistant") ->
                normalized.removeSuffix("-assistant").takeIf { it.isNotBlank() }
            normalized.endsWith("-clarify") ->
                normalized.removeSuffix("-clarify").takeIf { it.isNotBlank() }
            normalized.endsWith("-permission") ->
                normalized.removeSuffix("-permission").takeIf { it.isNotBlank() }
            normalized.endsWith("-text") ->
                normalized.removeSuffix("-text").takeIf { it.isNotBlank() }
            normalized.contains("-text-") ->
                normalized.substringBefore("-text-").takeIf { it.isNotBlank() }
            normalized.endsWith("-thinking") ->
                normalized.removeSuffix("-thinking").takeIf { it.isNotBlank() }
            normalized.contains("-thinking-") ->
                normalized.substringBefore("-thinking-").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun thinkingSequenceRank(entryId: String): Int {
        val normalized = entryId.trim()
        return when {
            normalized.contains("-thinking-") ->
                normalized.substringAfterLast("-thinking-").toIntOrNull() ?: 1
            normalized.endsWith("-thinking") -> 1
            else -> 0
        }
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun parseBoolean(value: Any?, default: Boolean): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> default
        }
    }

    private fun JsonElement.isBlankJsonPrimitive(): Boolean {
        return this is JsonPrimitive && this.isString && this.content.isBlank()
    }
}
