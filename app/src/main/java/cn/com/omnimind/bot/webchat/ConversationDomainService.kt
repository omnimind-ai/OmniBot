package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentConversationHistorySupport
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore

private const val WEB_CONVERSATION_TITLE_LIMIT = 20

/**
 * Resolves the immutable Harness owner of one durable conversation.
 *
 * The conversation binding is authoritative. A session binding can recover
 * older ACP conversations that predate it, while the original storage mode is
 * the final migration signal. A canonical Agent row without either binding
 * must resolve deterministically to the default Harness; otherwise whichever
 * Harness happens to be selected in the UI can steal the conversation.
 */
internal fun resolveConversationHarnessOwner(
    storedMode: String?,
    sessionAgentId: String?,
    conversationAgentId: String?,
): String? {
    val conversationOwner = conversationAgentId?.trim()?.takeIf { it.isNotEmpty() }
    if (conversationOwner != null) return conversationOwner
    val sessionOwner = sessionAgentId?.trim()?.takeIf { it.isNotEmpty() }
    if (sessionOwner != null) return sessionOwner
    return when (storedMode?.trim()?.lowercase()) {
        "", "agent", "normal", "acp", "coding" ->
            AcpAgentProfileStore.DEFAULT_AGENT_ID
        "codex" -> AcpAgentProfileStore.CODEX_AGENT_ID
        else -> null
    }
}

internal fun deriveWebConversationTitle(firstUserMessage: String?): String? {
    val normalized = AgentTextSanitizer.sanitizeUtf16(firstUserMessage.orEmpty()).trim()
    if (normalized.isEmpty()) return null
    return if (normalized.length > WEB_CONVERSATION_TITLE_LIMIT) {
        "${normalized.substring(0, WEB_CONVERSATION_TITLE_LIMIT)}..."
    } else {
        normalized
    }
}

private fun isDefaultWebConversationTitle(title: String): Boolean {
    return title.trim().lowercase() in setOf("", "新对话", "new chat", "new conversation")
}

class ConversationDomainService(
    private val context: Context
) {
    private val historyRepository by lazy {
        AgentConversationHistoryRepository(context)
    }
    private val acpAgentProfileStore by lazy {
        AcpAgentProfileStore(context)
    }

    private companion object {
        const val AGENT_MODE_STORAGE_VALUE = "agent"
        // `normal` is the pre-ACP Xiaowan storage value. It is still an Agent
        // conversation for binding/session restoration purposes.
        val AGENT_MODE_STORAGE_ALIASES = setOf(
            "agent",
            "normal",
            "codex",
            "acp",
            "coding"
        )
    }

    fun listWebAgentProfiles(): List<Map<String, Any?>> {
        return acpAgentProfileStore.list().map { profile ->
            linkedMapOf(
                "id" to profile.id,
                "name" to profile.name,
                "description" to profile.description,
                "enabled" to profile.enabled,
                "builtIn" to profile.builtIn
            )
        }
    }

    suspend fun listConversationPayloads(
        includeArchived: Boolean = true,
        archivedOnly: Boolean = false
    ): List<Map<String, Any?>> {
        val conversations = when {
            archivedOnly -> DatabaseHelper.getArchivedConversations()
            includeArchived -> DatabaseHelper.getAllConversations()
            else -> DatabaseHelper.getUnarchivedConversations()
        }
        val agentConversationIds = conversations
            .filter { isAgentMode(it.mode) }
            .map { it.id }
        val agentBindings = DatabaseHelper.getAgentSessionBindingsByConversationIds(
            agentConversationIds
        )
        val agentBindingByConversationId =
            agentBindings.associateBy { binding -> binding.conversationId }
        return conversations.map { conversation ->
            val agentBinding = agentBindingByConversationId[conversation.id]
            val agentId = if (isAgentMode(conversation.mode)) {
                resolveConversationHarnessOwner(
                    storedMode = conversation.mode,
                    sessionAgentId = agentBinding?.let { binding ->
                        acpAgentProfileStore.agentIdForSession(binding.threadId)
                    },
                    conversationAgentId = acpAgentProfileStore
                        .agentIdForConversation(conversation.id),
                )
            } else {
                null
            }
            conversationToPayload(
                conversation,
                agentCwd = agentBinding?.cwd,
                agentId = agentId
            )
        }
    }

    suspend fun archiveConversationsUpdatedBefore(cutoff: Long): Int {
        if (cutoff <= 0L) return 0
        val archivedCount = DatabaseHelper.archiveConversationsUpdatedBefore(cutoff)
        if (archivedCount > 0) {
            FlutterChatSyncBridge.dispatchConversationListChanged(
                reason = "conversations_auto_archived"
            )
        }
        return archivedCount
    }

    suspend fun getConversationPayload(conversationId: Long): Map<String, Any?>? {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return null
        val agentBinding = if (isAgentMode(conversation.mode)) {
            DatabaseHelper.getAgentSessionBindingByConversationId(conversation.id)
        } else {
            null
        }
        val agentId = if (isAgentMode(conversation.mode)) {
            resolveConversationHarnessOwner(
                storedMode = conversation.mode,
                sessionAgentId = agentBinding?.let { binding ->
                    acpAgentProfileStore.agentIdForSession(binding.threadId)
                },
                conversationAgentId = acpAgentProfileStore
                    .agentIdForConversation(conversation.id),
            )
        } else {
            null
        }
        return conversationToPayload(
            conversation,
            agentCwd = agentBinding?.cwd,
            agentId = agentId
        )
    }

    suspend fun createConversation(
        title: String,
        mode: String,
        summary: String? = null,
        parentConversationId: Long? = null,
        parentConversationMode: String? = null,
        scheduledTaskId: String? = null,
        agentId: String? = null
    ): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val normalizedMode = normalizeConversationMode(mode)
        val resolvedAgentId = validateRequestedAgentId(
            conversationMode = normalizedMode,
            requestedAgentId = agentId
        )
        val conversation = Conversation(
            id = 0,
            title = title.ifBlank { "新对话" },
            mode = normalizedMode,
            parentConversationId = parentConversationId?.takeIf { it > 0L },
            parentConversationMode = parentConversationMode
                ?.let(::normalizeConversationMode)
                ?.takeIf { it.isNotEmpty() },
            scheduledTaskId = scheduledTaskId?.trim()?.takeIf { it.isNotEmpty() },
            summary = summary,
            status = 0,
            createdAt = now,
            updatedAt = now
        )
        val insertedId = DatabaseHelper.insertConversation(conversation)
        val inserted = requireNotNull(DatabaseHelper.getConversationById(insertedId)) {
            "Conversation was inserted but cannot be loaded back"
        }
        resolvedAgentId?.let {
            acpAgentProfileStore.bindConversation(inserted.id, it)
        }
        val payload = conversationToPayload(inserted, agentId = resolvedAgentId)
        publishConversationEvent("conversation_created", inserted, resolvedAgentId)
        return payload
    }

    suspend fun updateConversationFromPayload(
        conversationMap: Map<String, Any?>
    ): Map<String, Any?> {
        val conversationId = conversationMap.readLong("id")
            ?: throw IllegalArgumentException("conversation.id is invalid")
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val incomingContextSummary = conversationMap["contextSummary"]?.toString()?.trim()
        val updated = existing.copy(
            title = conversationMap["title"]?.toString()?.trim()?.ifEmpty {
                existing.title
            } ?: existing.title,
            mode = normalizeConversationMode(
                conversationMap["mode"]?.toString() ?: existing.mode
            ),
            isArchived = conversationMap.readBoolean("isArchived") ?: existing.isArchived,
            isPinned = conversationMap.readBoolean("isPinned") ?: existing.isPinned,
            parentConversationId = if (conversationMap.containsKey("parentConversationId")) {
                conversationMap.readLong("parentConversationId")?.takeIf { it > 0L }
            } else {
                existing.parentConversationId
            },
            parentConversationMode = if (conversationMap.containsKey("parentConversationMode")) {
                conversationMap["parentConversationMode"]
                    ?.toString()
                    ?.let(::normalizeConversationMode)
                    ?.takeIf { it.isNotEmpty() }
            } else {
                existing.parentConversationMode
            },
            scheduledTaskId = if (conversationMap.containsKey("scheduledTaskId")) {
                conversationMap["scheduledTaskId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                existing.scheduledTaskId
            },
            summary = if (conversationMap.containsKey("summary")) {
                conversationMap["summary"]?.toString()
            } else {
                existing.summary
            },
            contextSummary = incomingContextSummary
                ?.takeIf { it.isNotEmpty() }
                ?: existing.contextSummary,
            contextSummaryCutoffEntryDbId = conversationMap.readLong("contextSummaryCutoffEntryDbId")
                ?: existing.contextSummaryCutoffEntryDbId,
            contextSummaryUpdatedAt = conversationMap.readLong("contextSummaryUpdatedAt")
                ?.takeIf { it > 0L }
                ?: existing.contextSummaryUpdatedAt,
            status = conversationMap.readInt("status") ?: existing.status,
            lastMessage = conversationMap["lastMessage"]?.toString() ?: existing.lastMessage,
            messageCount = conversationMap.readInt("messageCount") ?: existing.messageCount,
            latestPromptTokens = conversationMap.readInt("latestPromptTokens")
                ?: existing.latestPromptTokens,
            promptTokenThreshold = conversationMap.readInt("promptTokenThreshold")
                ?.coerceAtLeast(1)
                ?: existing.promptTokenThreshold.coerceAtLeast(1),
            latestPromptTokensUpdatedAt = conversationMap.readLong("latestPromptTokensUpdatedAt")
                ?: existing.latestPromptTokensUpdatedAt,
            createdAt = conversationMap.readLong("createdAt") ?: existing.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun updateConversationTitle(
        conversationId: Long,
        newTitle: String
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            title = newTitle.ifBlank { existing.title },
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun applyFirstUserMessageTitle(
        conversationId: Long,
        firstUserMessage: String?
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val nextTitle = deriveWebConversationTitle(firstUserMessage)
        if (
            nextTitle == null ||
            existing.messageCount > 0 ||
            !isDefaultWebConversationTitle(existing.title)
        ) {
            return conversationToPayload(existing)
        }
        val updated = existing.copy(
            title = nextTitle,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun updateConversationPromptTokenThreshold(
        conversationId: Long,
        promptTokenThreshold: Int
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            promptTokenThreshold = promptTokenThreshold.coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun setConversationArchived(
        conversationId: Long,
        archived: Boolean
    ): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            isArchived = archived,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun completeConversation(conversationId: Long): Map<String, Any?> {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        val updated = existing.copy(
            status = 1,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun deleteConversation(conversationId: Long) {
        val existing = DatabaseHelper.getConversationById(conversationId)
            ?: return
        historyRepository.deleteConversation(conversationId)
        DatabaseHelper.deleteConversationById(conversationId)
        // The ACP binding is an execution index, not conversation content.
        // Remove it after the durable rows are deleted so a failed history
        // deletion does not strand an otherwise recoverable conversation.
        // A later session/list or stale session/load can then never resurrect
        // this deleted thread.
        DatabaseHelper.deleteAgentSessionBindingByConversationId(conversationId)
        acpAgentProfileStore.unbindConversation(conversationId)
        val payload = conversationToPayload(existing)
        RealtimeHub.publish(
            "conversation_deleted",
            mapOf(
                "conversation" to payload,
                "conversationId" to conversationId,
                "mode" to existing.mode
            )
        )
        FlutterChatSyncBridge.dispatchConversationListChanged(
            reason = "conversation_deleted",
            conversation = payload
        )
        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
            conversationId = conversationId,
            mode = existing.mode,
            reason = "conversation_deleted"
        )
    }

    suspend fun listConversationMessages(
        conversationId: Long,
        conversationMode: String,
        finalizeInterruptedEntries: Boolean = true
    ): List<Map<String, Any?>> {
        return historyRepository.listConversationMessages(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode),
            finalizeInterruptedEntries = finalizeInterruptedEntries
        )
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Map<String, Any?> {
        val (messages, hasMore) = historyRepository.listConversationMessagesPaged(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode),
            limit = limit,
            offset = offset
        )
        return mapOf("messages" to messages, "hasMore" to hasMore)
    }

    suspend fun replaceConversationMessages(
        conversationId: Long,
        conversationMode: String,
        messages: List<Map<String, Any?>>
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.replaceThreadMessagesFromUiSnapshot(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            messages = messages
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    suspend fun appendUserMessage(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>> = emptyList(),
        createdAt: Long = System.currentTimeMillis()
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.upsertUserMessage(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            entryId = entryId,
            text = text,
            attachments = attachments,
            streamMeta = AgentConversationHistorySupport.externalUserMessageStreamMeta(),
            createdAt = createdAt
        )
        // 来自 IM 等外部入口的用户消息：除了通过常规 messagesChanged 事件让聊天页重载，
        // 还直接把消息内容推给 Flutter，让 runtime 立刻插入气泡 ——
        // 避免 agent 流事件抢先到达打乱事件顺序导致用户消息消失。
        publishMessagesReplaced(conversationId, normalizedMode, "external_user_message")
        FlutterChatSyncBridge.dispatchExternalUserMessageAppended(
            conversationId = conversationId,
            mode = normalizedMode,
            entryId = entryId,
            text = text,
            attachments = attachments,
            createdAt = createdAt
        )
    }

    suspend fun upsertConversationUiCard(
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        createdAt: Long
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.upsertUiCard(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            entryId = entryId,
            cardData = cardData,
            createdAt = createdAt
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    suspend fun compactConversationContext(
        conversationId: Long,
        conversationMode: String,
        modelOverride: AgentModelOverride?,
        reasoningEffort: String? = null
    ): Map<String, Any?> {
        val normalizedMode = normalizeConversationMode(conversationMode)
        val compactor = AgentConversationContextCompactor(
            historyRepository = historyRepository,
            modelScene = AgentConversationContextCompactor.DEFAULT_AGENT_MODEL_SCENE,
            modelOverride = modelOverride,
            reasoningEffort = reasoningEffort
        )
        val outcome = compactor.compactConversationContext(
            conversationId = conversationId,
            conversationMode = normalizedMode
        )
        val updatedConversation = DatabaseHelper.getConversationById(conversationId)
        if (outcome.compacted && updatedConversation != null) {
            publishConversationEvent("conversation_updated", updatedConversation)
        }
        return linkedMapOf(
            "compacted" to outcome.compacted,
            "reason" to outcome.reason,
            "summary" to outcome.summary,
            "conversation" to updatedConversation?.let(::conversationToPayload)
        )
    }

    suspend fun clearConversationMessages(
        conversationId: Long,
        conversationMode: String
    ) {
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.clearConversationMessages(
            conversationId = conversationId,
            conversationMode = normalizedMode
        )
        publishMessagesReplaced(conversationId, normalizedMode)
    }

    fun conversationToPayload(
        conversation: Conversation,
        agentCwd: String? = null,
        agentId: String? = null
    ): Map<String, Any?> {
        val normalizedMode = normalizeConversationMode(conversation.mode)
        val resolvedAgentId = agentId
            ?: if (normalizedMode == AGENT_MODE_STORAGE_VALUE) {
                resolveConversationHarnessOwner(
                    storedMode = conversation.mode,
                    sessionAgentId = null,
                    conversationAgentId = acpAgentProfileStore
                        .agentIdForConversation(conversation.id),
                )
            } else {
                null
            }
        return linkedMapOf(
            "id" to conversation.id,
            "title" to conversation.title,
            "mode" to normalizedMode,
            "agentCwd" to agentCwd?.trim()?.takeIf { it.isNotEmpty() },
            "agentId" to resolvedAgentId?.trim()?.takeIf { it.isNotEmpty() },
            "isArchived" to conversation.isArchived,
            "isPinned" to conversation.isPinned,
            "parentConversationId" to conversation.parentConversationId,
            "parentConversationMode" to conversation.parentConversationMode
                ?.let(::normalizeConversationMode),
            "scheduledTaskId" to conversation.scheduledTaskId,
            "summary" to conversation.summary,
            "contextSummary" to conversation.contextSummary,
            "contextSummaryCutoffEntryDbId" to conversation.contextSummaryCutoffEntryDbId,
            "contextSummaryUpdatedAt" to conversation.contextSummaryUpdatedAt,
            "status" to conversation.status,
            "lastMessage" to conversation.lastMessage,
            "messageCount" to conversation.messageCount,
            "latestPromptTokens" to conversation.latestPromptTokens,
            "promptTokenThreshold" to conversation.promptTokenThreshold,
            "latestPromptTokensUpdatedAt" to conversation.latestPromptTokensUpdatedAt,
            "createdAt" to conversation.createdAt,
            "updatedAt" to conversation.updatedAt
        )
    }

    fun normalizeConversationMode(rawMode: String?): String {
        val normalized = rawMode?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            // Xiaowan used to be stored as `normal`. It is now the built-in
            // ACP Agent. Pure chat has its explicit `chat_only` mode.
            "", "normal" -> AGENT_MODE_STORAGE_VALUE
            "agent", "codex", "acp", "coding" -> AGENT_MODE_STORAGE_VALUE
            "chat", "chatonly", "chat-only" -> "chat_only"
            else -> normalized
        }
    }

    private fun isAgentMode(mode: String?): Boolean {
        return mode?.trim()?.lowercase() in AGENT_MODE_STORAGE_ALIASES
    }

    private fun validateRequestedAgentId(
        conversationMode: String,
        requestedAgentId: String?
    ): String? {
        val normalizedAgentId = requestedAgentId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        require(isAgentMode(conversationMode)) {
            "agentId is only supported for Agent conversations."
        }
        if (normalizeConversationMode(conversationMode) == "normal") {
            require(normalizedAgentId == AcpAgentProfileStore.XIAOWAN_AGENT_ID) {
                "Xiaowan conversations cannot switch Harness; create a new conversation."
            }
        }
        val profile = acpAgentProfileStore.list().firstOrNull { it.id == normalizedAgentId }
            ?: throw IllegalArgumentException("Unknown ACP agent: $normalizedAgentId")
        require(profile.enabled) { "ACP agent ${profile.name} is disabled." }
        return profile.id
    }

    private suspend fun publishMessagesReplaced(
        conversationId: Long,
        conversationMode: String,
        reason: String = "messages_replaced"
    ) {
        val messages = listConversationMessages(conversationId, conversationMode)
        RealtimeHub.publish(
            "messages_replaced",
            mapOf(
                "conversationId" to conversationId,
                "mode" to conversationMode,
                "messages" to messages
            )
        )
        FlutterChatSyncBridge.dispatchConversationMessagesChanged(
            conversationId = conversationId,
            mode = conversationMode,
            reason = reason
        )
    }

    private fun publishConversationEvent(
        eventName: String,
        conversation: Conversation,
        agentId: String? = null
    ) {
        val payload = conversationToPayload(conversation, agentId = agentId)
        RealtimeHub.publish(
            eventName,
            mapOf(
                "conversation" to payload,
                "conversationId" to conversation.id,
                "mode" to conversation.mode
            )
        )
        FlutterChatSyncBridge.dispatchConversationListChanged(
            reason = eventName,
            conversation = payload
        )
    }

    private fun Map<String, Any?>.readLong(key: String): Long? {
        return (this[key] as? Number)?.toLong()
    }

    private fun Map<String, Any?>.readInt(key: String): Int? {
        return (this[key] as? Number)?.toInt()
    }

    private fun Map<String, Any?>.readBoolean(key: String): Boolean? {
        val raw = this[key] ?: return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.trim().equals("true", ignoreCase = true)
            else -> null
        }
    }
}
