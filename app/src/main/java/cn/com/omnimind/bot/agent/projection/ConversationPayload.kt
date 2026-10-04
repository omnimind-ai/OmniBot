package cn.com.omnimind.bot.agent.projection

/**
 * Conversation metadata held by a runtime, in the exact shape of Dart
 * `ConversationModel.toJson()` (and the native `conversationToPayload`).
 *
 * The runtime keeps the map form so it crosses to Flutter unchanged and
 * feeds `ConversationDomainService.updateConversationFromPayload` directly.
 * Reads go through these accessors so defaults match `ConversationModel`.
 */
@JvmInline
value class ConversationPayload(val map: Map<String, Any?>) {
    val id: Int get() = asInt(map["id"]) ?: 0
    val mode: String get() = conversationModeFromStorageValue(dartToString(map["mode"]))
    val title: String get() = dartToString(map["title"] ?: "") ?: ""
    val summary: String? get() = map["summary"] as? String
    val messageCount: Int get() = asInt(map["messageCount"]) ?: 0
    val latestPromptTokens: Int get() = asInt(map["latestPromptTokens"]) ?: 0
    val promptTokenThreshold: Int get() = asInt(map["promptTokenThreshold"]) ?: 128000
    val latestPromptTokensUpdatedAt: Long get() = asLong(map["latestPromptTokensUpdatedAt"]) ?: 0L

    /** Dart `copyWith`: only non-null arguments replace values. */
    fun copyWith(vararg changes: Pair<String, Any?>): JsonMap {
        val next: JsonMap = LinkedHashMap(map)
        for ((key, value) in changes) {
            if (value != null) next[key] = value
        }
        return next
    }

    companion object {
        /** Dart `ConversationModel(...)` defaults for a new conversation. */
        fun create(
            id: Int,
            mode: String,
            title: String,
            summary: String? = null,
            status: Int = 0,
            lastMessage: String? = null,
            messageCount: Int,
            createdAt: Long,
            updatedAt: Long,
        ): JsonMap = linkedMapOf(
            "id" to id,
            "mode" to mode,
            "agentCwd" to null,
            "agentId" to null,
            "isArchived" to false,
            "isPinned" to false,
            "parentConversationId" to null,
            "parentConversationMode" to null,
            "scheduledTaskId" to null,
            "title" to title,
            "summary" to summary,
            "contextSummary" to null,
            "contextSummaryCutoffEntryDbId" to null,
            "contextSummaryUpdatedAt" to 0,
            "status" to status,
            "lastMessage" to lastMessage,
            "messageCount" to messageCount,
            "latestPromptTokens" to 0,
            "promptTokenThreshold" to 128000,
            "latestPromptTokensUpdatedAt" to 0,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
        )
    }
}

/** Dart `ConversationMode` storage values. */
object ConversationModes {
    const val NORMAL = "normal"
    const val CHAT_ONLY = "chat_only"
    const val OPENCLAW = "openclaw"
    const val SUBAGENT = "subagent"
    const val AGENT = "agent"
}

/** Dart `ConversationMode.fromStorageValue(...).storageValue`. */
fun conversationModeFromStorageValue(value: String?): String {
    return when (val normalized = value?.trim()?.lowercase().orEmpty()) {
        // `codex` and `normal` are legacy aliases of the generic Agent mode.
        "agent", "codex", "acp", "coding", "normal" -> ConversationModes.AGENT
        "chat", "chatonly", "chat-only" -> ConversationModes.CHAT_ONLY
        ConversationModes.CHAT_ONLY, ConversationModes.OPENCLAW, ConversationModes.SUBAGENT -> normalized
        else -> ConversationModes.AGENT
    }
}
