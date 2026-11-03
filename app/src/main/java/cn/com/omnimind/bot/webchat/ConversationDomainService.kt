package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.bot.agent.ChatConversationPreferences
import cn.com.omnimind.bot.agent.ConversationUiActions
import cn.com.omnimind.bot.agent.visibleConversationPage
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.ConversationActivityRow
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentConversationHistorySupport
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.AgentModelOverride
import cn.com.omnimind.bot.agent.AgentTextSanitizer
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.NaConversationAccess
import cn.com.omnimind.bot.agent.runtime.NA_AGENT_ID
import cn.com.omnimind.bot.agent.runtime.REMOTE_CODEX_AGENT_ID

private const val WEB_CONVERSATION_TITLE_LIMIT = 20

internal data class VisibleConversationActivity(
    val createdAt: Long,
    val isNa: Boolean,
)

/**
 * Resolves the immutable Harness owner of one durable conversation.
 *
 * The conversation binding is authoritative. A session binding can recover
 * older ACP conversations that predate it. Explicit Xiaowan product modes
 * retain their identity; other unbound history stays unbound for display.
 * Request admission requires an owner rather than relabeling that history.
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
        "normal", "chat_only" -> AcpAgentProfileStore.XIAOWAN_AGENT_ID
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
    private val naAccess by lazy { NaConversationAccess(context) }

    internal suspend fun canReadConversation(conversationId: Long): Boolean =
        naAccess.canRead(conversationId)

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

    fun retiredAgentIds(): List<String> = acpAgentProfileStore.retiredAgentIds.toList()

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
        archivedOnly: Boolean = false,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        mode: String? = null,
        excludeHidden: Boolean = false,
    ): List<Map<String, Any?>> {
        val hidden = if (excludeHidden) ChatConversationPreferences(context).hiddenIds() else emptySet()
        val owner = naAccess.captureOwner()
        val agentBindingByConversationId = mutableMapOf<Long, cn.com.omnimind.baselib.database.AgentSessionBinding>()
        val conversations = visibleConversationPage(
            offset, limit, true,
            visible = { row: Conversation -> (!isAgentMode(row.mode) || row.id !in hidden) &&
                naAccess.canReadStored(row.id, agentBindingByConversationId[row.id]?.threadId, owner) },
            readPage = { start, count ->
                val rows = DatabaseHelper.getConversationDisplayPage(
                    start, count, includeArchived, archivedOnly, mode?.let(::normalizeConversationMode),
                )
                DatabaseHelper.getAgentSessionBindingsByConversationIds(rows.filter { hasAcpOwner(it.mode) }.map { it.id })
                    .forEach { agentBindingByConversationId[it.conversationId] = it }
                rows
            },
        )
        val payloads = conversations.map { conversation ->
            val agentBinding = agentBindingByConversationId[conversation.id]
            val agentId = if (hasAcpOwner(conversation.mode)) {
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
        // Room page reads suspend. A switch while a later page is loaded
        // must also fence the Na rows already projected earlier in this page.
        return if (owner == naAccess.captureOwner()) payloads else payloads.filter { payload ->
            val id = (payload["id"] as Number).toLong()
            payload["agentId"] != NA_AGENT_ID &&
                NaConversationAccess.sessionOwner(agentBindingByConversationId[id]?.threadId) == null &&
                acpAgentProfileStore.naOwnerForConversation(id) == null
        }
    }

    /** Same hidden/account policy as history, without constructing history payloads. */
    internal suspend fun listConversationActivity(
        since: Long,
        until: Long,
    ): List<VisibleConversationActivity> {
        val hidden = ChatConversationPreferences(context).hiddenIds()
        val owner = naAccess.captureOwner()
        val rows = DatabaseHelper.getConversationActivityRows(since, until)
        val visible = rows.mapNotNull { row ->
            val threadId = row.threadId.takeIf { isAgentMode(row.mode) }
            if ((isAgentMode(row.mode) && row.id in hidden) ||
                !naAccess.canReadStored(row.id, threadId, owner)) return@mapNotNull null
            VisibleConversationActivity(row.createdAt, isNaActivity(row, threadId))
        }
        // A suspended Room read must not publish a previous account's counts.
        return if (owner == naAccess.captureOwner()) visible else visible.filterNot { it.isNa }
    }

    private fun isNaActivity(row: ConversationActivityRow, threadId: String?): Boolean {
        val agentId = if (isAgentMode(row.mode)) resolveConversationHarnessOwner(
            storedMode = row.mode,
            sessionAgentId = threadId?.let(acpAgentProfileStore::agentIdForSession),
            conversationAgentId = acpAgentProfileStore.agentIdForConversation(row.id),
        ) else null
        return agentId == NA_AGENT_ID ||
            NaConversationAccess.sessionOwner(threadId) != null ||
            acpAgentProfileStore.naOwnerForConversation(row.id) != null
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
        val owner = naAccess.captureOwner()
        if (!naAccess.canRead(conversationId, owner)) return null
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return null
        val agentBinding = if (hasAcpOwner(conversation.mode)) {
            DatabaseHelper.getAgentSessionBindingByConversationId(conversation.id)
        } else {
            null
        }
        val agentId = if (hasAcpOwner(conversation.mode)) {
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
        if (!naAccess.canRead(conversationId, owner)) return null
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
        agentId: String? = null,
        archived: Boolean = false,
        expectedNaOwner: String? = null,
    ): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val normalizedMode = normalizeConversationMode(mode)
        val resolvedAgentId = validateRequestedAgentId(
            conversationMode = mode,
            requestedAgentId = agentId
        )
        val naOwner = if (resolvedAgentId == NA_AGENT_ID) {
            val owner = requireNotNull(naAccess.captureOwner()) { "请先登录账号再创建云端会话。" }
            require(expectedNaOwner == null || expectedNaOwner == owner) {
                "agent_owner_conflict: Cloud account changed before creating the conversation."
            }
            owner
        } else {
            require(expectedNaOwner == null) { "A cloud account owner is only valid for Na conversations." }
            null
        }
        val conversation = Conversation(
            id = 0,
            title = title.ifBlank { "新对话" },
            mode = normalizedMode,
            isArchived = archived,
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
        naOwner?.let { acpAgentProfileStore.bindNaConversationOwner(inserted.id, it) }
        check(naAccess.canRead(inserted.id, naOwner)) { "账号已切换，请重新创建会话。" }
        val payload = conversationToPayload(inserted, agentId = resolvedAgentId)
        publishConversationEvent("conversation_created", inserted, resolvedAgentId)
        return payload
    }

    suspend fun updateConversationFromPayload(
        conversationMap: Map<String, Any?>,
        preserveLatestMetadata: Boolean = false,
    ): Map<String, Any?> {
        val conversationId = conversationMap.readLong("id")
            ?: throw IllegalArgumentException("conversation.id is invalid")
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        DatabaseHelper.updateConversation(updated, preserveLatestMetadata)
        val committed = DatabaseHelper.getConversationById(conversationId) ?: updated
        publishConversationEvent("conversation_updated", committed)
        return conversationToPayload(committed)
    }

    suspend fun manageFromUi(action: String, conversationId: Long, mode: String?, title: String = ""): Boolean =
        ConversationUiActions(
            preferences = ChatConversationPreferences(context),
            acp = { method, args -> AgentRuntimeManager.getInstance(context).handleMethod(method, args) },
            get = ::getConversationPayload,
            archive = { id, archived -> setConversationArchived(id, archived) },
            delete = ::deleteConversation,
            rename = { id, name -> updateConversationTitle(id, name) },
        ).execute(action, conversationId, mode, title)

    suspend fun updateConversationTitle(
        conversationId: Long,
        newTitle: String
    ): Map<String, Any?> {
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
        DatabaseHelper.updateConversationPromptThreshold(conversationId, promptTokenThreshold)
        val updated = DatabaseHelper.getConversationById(conversationId)
            ?: throw IllegalArgumentException("Conversation not found")
        publishConversationEvent("conversation_updated", updated)
        return conversationToPayload(updated)
    }

    suspend fun setConversationArchived(
        conversationId: Long,
        archived: Boolean
    ): Map<String, Any?> {
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        return naAccess.read(conversationId) { historyRepository.listConversationMessages(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode),
            finalizeInterruptedEntries = finalizeInterruptedEntries
        ) }
    }

    suspend fun listConversationMessagesPaged(
        conversationId: Long,
        conversationMode: String,
        limit: Int,
        offset: Int
    ): Map<String, Any?> {
        val page = naAccess.read(conversationId) { historyRepository.listConversationMessagesPaged(
            conversationId = conversationId,
            conversationMode = normalizeConversationMode(conversationMode),
            limit = limit,
            offset = offset
        ) }
        return mapOf("messages" to page.messages, "hasMore" to page.hasMore, "nextOffset" to page.nextOffset)
    }

    suspend fun replaceConversationMessages(
        conversationId: Long,
        conversationMode: String,
        messages: List<Map<String, Any?>>,
        allowHistoryRemoval: Boolean = false,
        removedMessageIds: List<String>? = null,
    ) {
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
        val normalizedMode = normalizeConversationMode(conversationMode)
        historyRepository.replaceThreadMessagesFromUiSnapshot(
            conversationId = conversationId,
            conversationMode = normalizedMode,
            messages = messages,
            allowHistoryRemoval = allowHistoryRemoval,
            removedMessageIds = removedMessageIds,
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
        check(naAccess.canRead(conversationId)) { "请登录此云端会话所属的账号。" }
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
            ?: if (hasAcpOwner(normalizedMode)) {
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

    private fun hasAcpOwner(mode: String?): Boolean = isAgentMode(mode) ||
        AgentConversationModePolicy.isChatOnlyMode(mode) || AgentConversationModePolicy.isSubagentMode(mode)

    internal fun validateRequestedAgentId(
        conversationMode: String,
        requestedAgentId: String?
    ): String? {
        val normalizedMode = normalizeConversationMode(conversationMode)
        if (!hasAcpOwner(normalizedMode)) {
            require(requestedAgentId.isNullOrBlank()) { "agentId is only supported for ACP conversations." }
            return null
        }
        val resolution = AgentConversationModePolicy.resolveHarness(
            conversationMode = if (AgentConversationModePolicy.isNormalMode(conversationMode)) conversationMode else normalizedMode,
            requestedAgentId = requestedAgentId,
            conversationAgentId = null,
            sessionAgentId = null,
            xiaowanAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
        )
        require(!resolution.hasConflict) { "agent_owner_conflict: This product mode belongs to Xiaowan." }
        val normalizedAgentId = resolution.agentId
        // A remote ACP endpoint is an explicit owner, not the current bridge switch.
        if (normalizedAgentId == REMOTE_CODEX_AGENT_ID) return normalizedAgentId
        acpAgentProfileStore.requireSupportedAgent(normalizedAgentId)
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
        val owner = naAccess.captureOwner()
        RealtimeHub.publishSnapshot(
            "messages_replaced",
            isVisible = { naAccess.canRead(conversationId, owner) },
        ) {
            mapOf(
                "conversationId" to conversationId,
                "mode" to conversationMode,
                "messages" to historyRepository.listConversationMessages(
                    conversationId = conversationId,
                    conversationMode = normalizeConversationMode(conversationMode),
                )
            )
        }
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
