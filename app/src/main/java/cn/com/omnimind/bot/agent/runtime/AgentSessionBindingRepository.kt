package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import cn.com.omnimind.bot.webchat.RealtimeHub
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgentSessionBindingRepository(
    context: Context
) {
    private val appContext = context.applicationContext
    private val conversationDomainService by lazy { ConversationDomainService(appContext) }
    private val bindingMutex = Mutex()

    suspend fun getBindingByConversationId(conversationId: Long): AgentSessionBinding? {
        return DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)
    }

    suspend fun getBindingByThreadId(threadId: String): AgentSessionBinding? {
        return DatabaseHelper.getAgentSessionBindingByThreadId(threadId)
    }

    suspend fun ensureBinding(
        threadId: String,
        conversationId: Long? = null,
        cwd: String = AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD,
        title: String? = null,
        archived: Boolean? = null,
        conversationMode: String = AGENT_MODE_STORAGE_VALUE
    ): Long = bindingMutex.withLock {
        ensureBindingLocked(
            threadId = threadId,
            conversationId = conversationId,
            cwd = cwd,
            title = title,
            archived = archived,
            conversationMode = conversationMode,
        )
    }

    private suspend fun ensureBindingLocked(
        threadId: String,
        conversationId: Long?,
        cwd: String,
        title: String?,
        archived: Boolean?,
        conversationMode: String,
    ): Long {
        val normalizedThreadId = threadId.trim()
        require(normalizedThreadId.isNotEmpty()) { "threadId is required" }
        val now = System.currentTimeMillis()

        val existingBinding = DatabaseHelper.getAgentSessionBindingByThreadId(normalizedThreadId)
        if (existingBinding != null) {
            if (conversationId != null && conversationId != existingBinding.conversationId) {
                val oldConversation = DatabaseHelper.getConversationById(existingBinding.conversationId)
                check(
                    canSafelyRebindGeneratedEmptyConversation(
                        oldConversation,
                        expectedTitle = defaultConversationTitle(existingBinding.threadId),
                    )
                ) {
                    "ACP session $normalizedThreadId is already bound to conversation " +
                        "${existingBinding.conversationId}; refusing to move it to " +
                        "conversation $conversationId."
                }
                val reboundConversation = rebindExistingThread(
                    existingBinding = existingBinding,
                    conversationId = conversationId,
                    cwd = cwd,
                    title = title,
                    archived = archived,
                    conversationMode = conversationMode,
                    updatedAt = now
                )
                if (reboundConversation != null) {
                    return reboundConversation
                }
            }
            val conversation = DatabaseHelper.getConversationById(existingBinding.conversationId)
            val updatedConversation = conversation?.let {
                val titleForUpdate = if (conversationId != null && it.title.isNotBlank()) {
                    null
                } else {
                    title
                }
                buildUpdatedConversation(
                    conversation = it,
                    title = titleForUpdate,
                    archived = archived,
                    conversationMode = conversationMode,
                    updatedAt = now
                )
            }
            if (updatedConversation != null && updatedConversation != conversation) {
                DatabaseHelper.updateConversation(updatedConversation)
                publishConversationEvent("conversation_updated", updatedConversation)
            }
            DatabaseHelper.upsertAgentSessionBinding(
                existingBinding.copy(
                    cwd = cwd.ifBlank { existingBinding.cwd },
                    updatedAt = now
                )
            )
            return existingBinding.conversationId
        }

        val targetConversation = conversationId
            ?.let { DatabaseHelper.getConversationById(it) }
            ?.let {
                val updated = it.copy(
                    mode = normalizeConversationMode(conversationMode),
                    updatedAt = now
                )
                if (updated != it) {
                    DatabaseHelper.updateConversation(updated)
                    publishConversationEvent("conversation_updated", updated)
                }
                updated
            }
            ?: createConversation(
                title = title?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: defaultConversationTitle(normalizedThreadId),
                archived = archived == true,
                mode = normalizeConversationMode(conversationMode),
                now = now
            )

        val binding = AgentSessionBinding(
            conversationId = targetConversation.id,
            threadId = normalizedThreadId,
            cwd = cwd.ifBlank { AgentRuntimeDefaults.DEFAULT_WORKSPACE_CWD },
            createdAt = now,
            updatedAt = now
        )
        DatabaseHelper.upsertAgentSessionBinding(binding)
        return targetConversation.id
    }

    private suspend fun rebindExistingThread(
        existingBinding: AgentSessionBinding,
        conversationId: Long,
        cwd: String,
        title: String?,
        archived: Boolean?,
        conversationMode: String,
        updatedAt: Long
    ): Long? {
        val targetConversation = DatabaseHelper.getConversationById(conversationId) ?: return null
        val normalizedTitle = title?.trim().orEmpty()
        val updatedTarget = targetConversation.copy(
            mode = normalizeConversationMode(conversationMode),
            title = if (targetConversation.title.isBlank() && normalizedTitle.isNotEmpty()) {
                normalizedTitle
            } else {
                targetConversation.title
            },
            isArchived = archived ?: targetConversation.isArchived,
            updatedAt = updatedAt
        )
        if (updatedTarget != targetConversation) {
            DatabaseHelper.updateConversation(updatedTarget)
            publishConversationEvent("conversation_updated", updatedTarget)
        }

        DatabaseHelper.upsertAgentSessionBinding(
            existingBinding.copy(
                conversationId = conversationId,
                cwd = cwd.ifBlank { existingBinding.cwd },
                updatedAt = updatedAt
            )
        )
        cleanupGeneratedEmptyConversation(
            conversationId = existingBinding.conversationId,
            expectedTitle = defaultConversationTitle(existingBinding.threadId)
        )
        return conversationId
    }

    suspend fun updateTitle(threadId: String, title: String?) {
        val binding = getBindingByThreadId(threadId.trim()) ?: return
        val conversation = DatabaseHelper.getConversationById(binding.conversationId) ?: return
        val normalizedTitle = title?.trim().orEmpty()
        if (normalizedTitle.isEmpty() || normalizedTitle == conversation.title) {
            return
        }
        val updated = conversation.copy(
            title = normalizedTitle,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
    }

    suspend fun setArchived(threadId: String, archived: Boolean) {
        val binding = getBindingByThreadId(threadId.trim()) ?: return
        setConversationArchived(binding.conversationId, archived)
    }

    suspend fun setConversationArchived(conversationId: Long, archived: Boolean) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        if (conversation.isArchived == archived) {
            return
        }
        val updated = conversation.copy(
            isArchived = archived,
            updatedAt = System.currentTimeMillis()
        )
        DatabaseHelper.updateConversation(updated)
        publishConversationEvent("conversation_updated", updated)
    }

    /**
     * Detach an ACP session without deleting the conversation or its messages.
     * ACP `session/delete` is an agent-session operation; local history is
     * owned by OmniBot and must remain recoverable for the user.
     */
    suspend fun detachThread(threadId: String): Long? {
        val binding = getBindingByThreadId(threadId.trim()) ?: return null
        DatabaseHelper.deleteAgentSessionBindingByThreadId(threadId.trim())
        return binding.conversationId
    }

    private suspend fun createConversation(
        title: String,
        archived: Boolean,
        mode: String,
        now: Long
    ): Conversation {
        val conversation = Conversation(
            id = 0,
            title = title.ifBlank { "Agent" },
            mode = mode,
            isArchived = archived,
            status = 0,
            createdAt = now,
            updatedAt = now
        )
        val id = DatabaseHelper.insertConversation(conversation)
        val inserted = requireNotNull(DatabaseHelper.getConversationById(id)) {
            "Agent conversation was inserted but cannot be loaded back"
        }
        publishConversationEvent("conversation_created", inserted)
        return inserted
    }

    private fun buildUpdatedConversation(
        conversation: Conversation,
        title: String?,
        archived: Boolean?,
        conversationMode: String,
        updatedAt: Long
    ): Conversation {
        val normalizedTitle = title?.trim().orEmpty()
        return conversation.copy(
            mode = normalizeConversationMode(conversationMode),
            title = normalizedTitle.ifEmpty { conversation.title },
            isArchived = archived ?: conversation.isArchived,
            updatedAt = updatedAt
        )
    }

    private fun publishConversationEvent(eventName: String, conversation: Conversation) {
        val payload = conversationDomainService.conversationToPayload(conversation)
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

    private fun defaultConversationTitle(threadId: String): String {
        val suffix = threadId.takeLast(6).ifBlank { "thread" }
        return "Agent $suffix"
    }

    private fun normalizeConversationMode(value: String): String {
        return when (value.trim().lowercase()) {
            "", "normal", "codex", "acp", "coding" -> AGENT_MODE_STORAGE_VALUE
            else -> value.trim().lowercase()
        }
    }

    private suspend fun cleanupGeneratedEmptyConversation(
        conversationId: Long,
        expectedTitle: String
    ) {
        val conversation = DatabaseHelper.getConversationById(conversationId) ?: return
        if (conversation.mode != AGENT_MODE_STORAGE_VALUE) {
            return
        }
        if (conversation.title != expectedTitle) {
            return
        }
        if (conversation.messageCount != 0 || !conversation.lastMessage.isNullOrBlank()) {
            return
        }
        val entryCount = DatabaseHelper.countAgentConversationThreadEntries(
            conversationId = conversationId,
            conversationMode = AGENT_MODE_STORAGE_VALUE
        )
        if (entryCount != 0) {
            return
        }
        DatabaseHelper.deleteConversationById(conversationId)
        publishConversationEvent("conversation_deleted", conversation)
    }

    /**
     * A session may only be moved as part of cleaning up an empty placeholder
     * created by session discovery. Moving a populated conversation would not
     * delete its rows, but it would make its durable history impossible to
     * restore through the original ACP session and is therefore a data
     * ownership bug.
     */
    private suspend fun canSafelyRebindGeneratedEmptyConversation(
        conversation: Conversation?,
        expectedTitle: String,
    ): Boolean {
        if (conversation == null) return true
        if (conversation.title != expectedTitle) return false
        if (conversation.messageCount != 0 || !conversation.lastMessage.isNullOrBlank()) {
            return false
        }
        val entryCount = DatabaseHelper.countAgentConversationThreadEntries(
            conversationId = conversation.id,
            conversationMode = AGENT_MODE_STORAGE_VALUE,
        )
        return entryCount == 0
    }

    companion object {
        // `codex` remains a read-compatible legacy alias, but new bindings
        // must use the canonical Agent conversation mode.
        const val AGENT_MODE_STORAGE_VALUE = "agent"
    }
}

internal object AgentRuntimeDefaults {
    const val CODEX_HOME = "/root/.codex"
    const val DEFAULT_WORKSPACE_CWD = "/workspace"
    const val FALLBACK_CWD = "/root"
}

/**
 * A session id is reusable only when it is still bound to the conversation
 * addressed by the request. A session-only ACP request remains valid because
 * the session binding itself is the source of the conversation identity.
 */
internal fun explicitThreadMatchesConversation(
    explicitThreadId: String?,
    requestedConversationId: Long?,
    boundConversationId: Long?
): Boolean {
    if (explicitThreadId.isNullOrBlank() || requestedConversationId == null) {
        return true
    }
    return boundConversationId == requestedConversationId
}
