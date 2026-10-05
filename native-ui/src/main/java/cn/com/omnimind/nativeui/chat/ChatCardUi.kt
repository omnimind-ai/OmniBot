package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/**
 * Presentation of one `agent_request` card (Flutter `AgentRequestNotice`),
 * derived by the app module. Approvals are answered from the card; user
 * input is answered through the composer.
 */
@Immutable
data class AgentRequestCardUi(
    /** `approval`, `user_input` or another ACP request kind. */
    val kind: String,
    /** Empty when the request carries no usable title; the card picks a fallback. */
    val title: String,
    val detail: String,
    /** Normalized status: pending, accepted, declined, submitted, ignored, cancelled, failed, expired. */
    val status: String,
    /** No live JSON-RPC request id, or the session already ended. */
    val interactionUnavailable: Boolean,
    val sessionEnded: Boolean,
) {
    val isApproval: Boolean get() = kind == "approval"
    val isPending: Boolean get() = status == "pending"
}

/** Presentation of one `deep_thinking` card (Flutter `DeepThinkingCard`). */
@Immutable
data class DeepThinkingCardUi(
    /** Reasoning text, localized line by line. */
    val text: String,
    /** 1–3 thinking, 4 finished, 5 cancelled. */
    val stage: Int,
    val isLoading: Boolean,
    val startTimeMillis: Long?,
    val endTimeMillis: Long?,
    /** Default avatar rule: only the run's primary `<taskId>-thinking` card shows it. */
    val showAvatar: Boolean,
) {
    val isCompletedStage: Boolean get() = stage == 4 || stage == 5
    val isActivelyThinking: Boolean get() = isLoading && !isCompletedStage
    val hasContent: Boolean get() = text.isNotBlank()

    /** Elapsed seconds, shown only once a finished card has its end boundary. */
    val completedElapsedSeconds: Long?
        get() = if (stage == 4 && !isLoading && startTimeMillis != null && endTimeMillis != null) {
            (endTimeMillis - startTimeMillis) / 1000
        } else {
            null
        }
}

/** One entry of an ACP plan snapshot shown under its tool capsule. */
@Immutable
data class AgentPlanEntryUi(val text: String, val state: AgentPlanEntryState)

enum class AgentPlanEntryState { Pending, InProgress, Completed }
