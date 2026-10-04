package cn.com.omnimind.bot.agent.projection

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Durable history the runtime coordinator writes; mirrors the Dart
 * `ConversationService` / `ConversationHistoryService` calls it made.
 */
interface ChatRuntimePersistence {
    /** Dart `ConversationService.updateConversation(preserveLatestMetadata: true)`. */
    suspend fun updateConversation(conversation: Map<String, Any?>): Boolean

    /** Dart `ConversationHistoryService.saveConversationMessages`. Throws on failure. */
    suspend fun saveConversationMessages(
        conversationId: Int,
        conversationMode: String,
        messages: List<ChatMessage>,
        allowHistoryRemoval: Boolean,
    )

    /** Dart `ConversationService.completeConversation`. */
    suspend fun completeConversation(conversationId: Int, conversationMode: String): Boolean

    /** Dart `ConversationService.generateConversationSummary`; null on failure. */
    suspend fun generateConversationSummary(conversationHistory: String): String?

    /** Dart `ConversationHistoryService.upsertConversationUiCard`; errors are logged. */
    suspend fun upsertConversationUiCard(
        conversationId: Int,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        createdAtMillis: Long,
    )
}

/**
 * The Dart history-service behavior around the native store, kept in one
 * place so production and tests share it:
 *
 * - writes for one `(mode, conversationId)` are serialized; a failed write
 *   does not block later ones;
 * - only rows whose JSON digest changed since the last acknowledged write
 *   are sent, unless history removal is allowed (then the full snapshot);
 * - a failed native write throws so the coordinator's persistence tail
 *   keeps its owner, and legacy snapshot keys are cleared after success;
 * - conversation metadata merges the latest stored row before writing so
 *   user-owned fields (title, archive/pin, token threshold, …) survive.
 */
abstract class ChatRuntimeHistoryStore : ChatRuntimePersistence {
    /** Native `replaceConversationMessages`; false when it failed. */
    protected abstract suspend fun replaceNativeConversationMessages(
        conversationId: Int,
        conversationMode: String,
        rows: List<Map<String, Any?>>,
        allowHistoryRemoval: Boolean,
    ): Boolean

    /** Removes pre-native history snapshots for the conversation. */
    protected abstract suspend fun clearLegacyConversationMessages(conversationId: Int, conversationMode: String)

    /** The stored conversation payload, or null when unknown. */
    protected abstract suspend fun readConversation(conversationId: Int): Map<String, Any?>?

    /** Native `updateConversation`; false when it failed. */
    protected abstract suspend fun writeConversation(conversation: Map<String, Any?>): Boolean

    private val writeLocks = HashMap<String, Mutex>()
    private val acknowledgedWrites = HashMap<String, Map<String, String>>()

    override suspend fun saveConversationMessages(
        conversationId: Int,
        conversationMode: String,
        messages: List<ChatMessage>,
        allowHistoryRemoval: Boolean,
    ) {
        val key = "$conversationMode:$conversationId"
        val snapshot = ArrayList(messages)
        val lock = synchronized(writeLocks) { writeLocks.getOrPut(key) { Mutex() } }
        lock.withLock { saveNow(key, conversationId, conversationMode, snapshot, allowHistoryRemoval) }
    }

    private suspend fun saveNow(
        key: String,
        conversationId: Int,
        conversationMode: String,
        messages: List<ChatMessage>,
        allowHistoryRemoval: Boolean,
    ) {
        val rows = messages.map { it.toJson() }
        val previous = acknowledgedWrites[key] ?: emptyMap()
        val next = LinkedHashMap<String, String>()
        val changed = ArrayList<Map<String, Any?>>()
        for (row in rows) {
            val id = dartToString(row["id"])
            if (id.isNullOrEmpty()) {
                changed.add(row)
                continue
            }
            val digest = sha256(DartJson.encode(row))
            next[id] = digest
            if (allowHistoryRemoval || previous[id] != digest) changed.add(row)
        }
        if (changed.isEmpty() && !allowHistoryRemoval) return
        val stored = replaceNativeConversationMessages(
            conversationId,
            conversationMode,
            if (allowHistoryRemoval) rows else changed,
            allowHistoryRemoval,
        )
        if (!stored) {
            // Legacy storage is an import source, not a fallback destination.
            throw IllegalStateException("Native conversation persistence failed")
        }
        acknowledgedWrites[key] = if (allowHistoryRemoval) next else previous + next
        runCatching { clearLegacyConversationMessages(conversationId, conversationMode) }
            .onFailure { Log.w(TAG, "清理旧版对话历史跳过：${it.message}") }
    }

    override suspend fun updateConversation(conversation: Map<String, Any?>): Boolean {
        val payload = runCatching { mergeLatestConversationMetadata(conversation) }.getOrDefault(conversation)
        return runCatching { writeConversation(payload) }
            .onFailure { Log.w(TAG, "更新对话失败: ${it.message}") }
            .getOrDefault(false)
    }

    /** Dart `ConversationService._mergeLatestConversationMetadata`. */
    private suspend fun mergeLatestConversationMetadata(conversation: Map<String, Any?>): Map<String, Any?> {
        val incoming = ConversationPayload(conversation)
        val latestMap = readConversation(incoming.id) ?: return conversation
        val latest = ConversationPayload(latestMap)
        if (latest.mode != incoming.mode) return conversation
        fun latestOr(key: String) = latestMap[key]
        fun nonBlank(value: Any?) = dartToString(value)?.trim()?.takeIf { it.isNotEmpty() }
        return linkedMapOf(
            "id" to incoming.id,
            "mode" to incoming.mode,
            "agentCwd" to (nonBlank(latestOr("agentCwd")) ?: conversation["agentCwd"]),
            "agentId" to (nonBlank(latestOr("agentId")) ?: conversation["agentId"]),
            "isArchived" to (latestOr("isArchived") ?: false),
            "isPinned" to (latestOr("isPinned") ?: false),
            "parentConversationId" to latestOr("parentConversationId"),
            "parentConversationMode" to latestOr("parentConversationMode"),
            "scheduledTaskId" to latestOr("scheduledTaskId"),
            "title" to (if (latest.title.isNotEmpty()) latest.title else incoming.title),
            "summary" to (incoming.summary ?: latest.summary),
            "contextSummary" to latestOr("contextSummary"),
            "contextSummaryCutoffEntryDbId" to latestOr("contextSummaryCutoffEntryDbId"),
            "contextSummaryUpdatedAt" to (asLong(latestOr("contextSummaryUpdatedAt")) ?: 0L),
            "status" to (asInt(conversation["status"]) ?: 0),
            "lastMessage" to conversation["lastMessage"],
            "messageCount" to incoming.messageCount,
            "latestPromptTokens" to latest.latestPromptTokens,
            "promptTokenThreshold" to latest.promptTokenThreshold,
            "latestPromptTokensUpdatedAt" to latest.latestPromptTokensUpdatedAt,
            "createdAt" to (asLong(latestOr("createdAt")) ?: 0L),
            "updatedAt" to (asLong(conversation["updatedAt"]) ?: 0L),
        )
    }

    /** Drops the digest cache (Dart `resetWriteAcknowledgements`). */
    fun resetWriteAcknowledgements() = acknowledgedWrites.clear()

    private companion object {
        const val TAG = "ChatRuntimeHistoryStore"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Assistant-text voice side effect (Dart `VoicePlaybackCoordinator`
 * auto-play entry points). Voice is presentation, not an ACP event, so the
 * coordinator calls it after projection.
 */
interface ChatRuntimeVoice {
    fun onAssistantMessageUpdated(messageId: String, text: String, isFinal: Boolean)

    fun onAssistantMessageCompleted(messageId: String, text: String) =
        onAssistantMessageUpdated(messageId, text, isFinal = true)
}
