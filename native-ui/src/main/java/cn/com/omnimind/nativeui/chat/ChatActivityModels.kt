package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/*
 * Selection logic of the chat's floating surfaces, ported from:
 *
 * - `ui/lib/features/home/pages/chat/tool_activity_utils.dart`
 *   (`resolveAgentToolActivitySnapshot`, `shouldShowAgentToolActivitySnapshot`,
 *   `resolveActiveAgentToolCard`)
 * - `.../widgets/chat_message_anchor_bar.dart` (`buildChatMessageAnchors`)
 */

/** Dart `AgentToolActivitySnapshot`: the tool cards the activity strip shows. */
@Immutable
data class AgentToolActivitySnapshot(
    /** Newest first, like the runtime list. */
    val messages: List<ChatMessageUi>,
    val isActiveRun: Boolean,
    val taskId: String? = null,
)

/**
 * Tool cards of the active runs; otherwise of the run the user last
 * expanded; otherwise of the newest run when it used tools.
 */
fun resolveAgentToolActivitySnapshot(
    messages: List<ChatMessageUi>,
    activeTaskIds: Set<String> = emptySet(),
    preferredCompletedTaskId: String? = null,
    entries: List<AgentRunTimelineEntry>? = null,
): AgentToolActivitySnapshot {
    val active = activeTaskIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val activeMessages = if (active.isEmpty()) {
        emptyList()
    } else {
        messages.filter { it.toolCardType() && it.runId?.let(active::contains) == true }
    }
    if (activeMessages.isNotEmpty()) {
        return AgentToolActivitySnapshot(
            messages = activeMessages,
            isActiveRun = true,
            taskId = activeMessages.firstNotNullOfOrNull { it.runId } ?: active.singleOrNull(),
        )
    }
    if (active.isNotEmpty()) {
        return AgentToolActivitySnapshot(emptyList(), isActiveRun = true, taskId = active.singleOrNull())
    }
    val timeline by lazy(LazyThreadSafetyMode.NONE) { entries ?: buildAgentRunTimelineEntries(messages) }
    val preferred = preferredCompletedTaskId?.trim().orEmpty()
    if (preferred.isNotEmpty()) {
        val group = timeline.firstNotNullOfOrNull { entry -> entry.group?.takeIf { it.taskId == preferred } }
        return AgentToolActivitySnapshot(
            messages = group?.takeIf { it.toolCount > 0 }?.toolMessagesNewestFirst().orEmpty(),
            isActiveRun = false,
            taskId = preferred,
        )
    }
    val latest = timeline.firstOrNull()?.group?.takeIf { it.toolCount > 0 }
    return AgentToolActivitySnapshot(
        messages = latest?.toolMessagesNewestFirst().orEmpty(),
        isActiveRun = false,
        taskId = latest?.taskId,
    )
}

/** A finished run's tools show only while that run is expanded. */
fun shouldShowAgentToolActivitySnapshot(
    snapshot: AgentToolActivitySnapshot,
    expandedTaskIds: Set<String> = emptySet(),
): Boolean {
    if (snapshot.messages.isEmpty()) return false
    if (snapshot.isActiveRun) return true
    val taskId = snapshot.taskId?.trim().orEmpty()
    if (taskId.isEmpty()) return false
    return expandedTaskIds.any { it.trim() == taskId }
}

/** The first running or pending card, else the newest. */
fun resolveActiveAgentToolMessage(messages: List<ChatMessageUi>): ChatMessageUi? =
    messages.firstOrNull { message ->
        val status = message.cardData?.get("status")?.toString()?.trim()?.lowercase()
        status == "running" || status == "pending"
    } ?: messages.firstOrNull()

private fun ChatMessageUi.toolCardType(): Boolean =
    type == 2 && cardData?.get("type")?.toString() == "agent_tool_summary"

private fun AgentRunTimelineGroup.toolMessagesNewestFirst(): List<ChatMessageUi> =
    processMessagesNewestFirst.filter { it.toolCardType() }

// ---------------------------------------------------------------------------
// Message anchors
// ---------------------------------------------------------------------------

/** One jump target: a user message, an assistant message or an Agent run. */
@Immutable
data class ChatMessageAnchor(
    /** [AgentRunTimelineEntry.key] of the row to scroll to. */
    val entryKey: String,
    val isUser: Boolean,
    /** First line of the message, at most 50 characters; empty for a run still working. */
    val preview: String,
    /** ACP producer; null for a plain assistant reply. */
    val agentId: String? = null,
)

/** Oldest first; one anchor per text message and one per Agent run. */
fun buildChatMessageAnchors(entries: List<AgentRunTimelineEntry>): List<ChatMessageAnchor> {
    val anchors = ArrayList<ChatMessageAnchor>()
    for (entry in entries) {
        val message = entry.message
        if (message != null) {
            if (message.type != 1 || (message.user != 1 && message.user != 2)) continue
            val preview = firstPreviewLine(message.text)
            if (preview.isEmpty()) continue
            anchors.add(
                ChatMessageAnchor(
                    entryKey = entry.key,
                    isUser = message.user == 1,
                    preview = preview,
                    agentId = if (message.user == 2) message.agentId else null,
                ),
            )
            continue
        }
        // A dispatched turn that produced nothing yet has nothing to anchor to.
        val group = entry.group ?: continue
        if (group.isEmpty) continue
        val preview = group.visibleMessagesNewestFirst.firstNotNullOfOrNull { visible ->
            firstPreviewLine(visible.text).ifEmpty { null }
        }.orEmpty()
        anchors.add(ChatMessageAnchor(entry.key, isUser = false, preview = preview, agentId = group.agentId))
    }
    // Entries are newest first.
    return anchors.asReversed().toList()
}

private fun firstPreviewLine(text: String?): String {
    val normalized = text?.trim().orEmpty()
    if (normalized.isEmpty()) return ""
    for (line in normalized.split('\n')) {
        val candidate = line.trim()
        if (candidate.isEmpty()) continue
        return if (candidate.length > 50) candidate.substring(0, 50) else candidate
    }
    return ""
}
