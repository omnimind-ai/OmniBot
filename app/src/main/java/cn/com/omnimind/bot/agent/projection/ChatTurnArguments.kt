package cn.com.omnimind.bot.agent.projection

import cn.com.omnimind.bot.agent.AgentAttachmentPromptSupport

/*
 * Pure building blocks of one chat turn (batch 5d-0a), ported from the Dart
 * send path so the native turn launcher (5d-0b) assembles the same ACP
 * requests the Flutter page sends today:
 *
 * - `AgentRuntimeService.newSessionArguments` / `promptSessionArguments`
 *   (ui/lib/services/agent_runtime_service.dart)
 * - `_AgentPermissionModePayload` (adapters/agent_runtime_config_parser.dart)
 *   and the stored permission preference (`chat_page_agent.dart`)
 * - `agentModelSourceKey` / `selectAgentRequestModel`
 * - the user prompt text and attachment rules of `chat_dispatch_support.dart`
 *   and `chat_page_conversation_flow.dart`
 *
 * Terminal environment variables are not here: `OmnibotTerminalEnvironment.
 * loadUserVariables` already normalizes them with the same rules.
 */

// ---------------------------------------------------------------------------
// Permission mode
// ---------------------------------------------------------------------------

/** The composer's Agent permission choice and the ACP policy it maps to. */
enum class AgentPermissionMode(
    val approvalPolicy: String,
    val approvalsReviewer: String,
    /** Stored preference value (`chat_page_agent.dart` `_agentPermissionModePreferenceValue`). */
    val preferenceValue: String,
) {
    ReadOnly("on-request", "user", "read-only"),
    Default("on-request", "user", "workspace-write"),
    AutoReview("on-request", "auto_review", "auto-review"),
    FullAccess("never", "user", "full-access");

    /** Null leaves the Harness default sandbox in place. */
    val sandboxPolicy: Map<String, Any?>?
        get() = when (this) {
            ReadOnly -> mapOf("type" to "readOnly")
            FullAccess -> mapOf("type" to "dangerFullAccess")
            Default, AutoReview -> null
        }

    companion object {
        /** Dart `_parseAgentPermissionMode`, including the legacy spellings. */
        fun fromPreference(raw: String?): AgentPermissionMode? =
            when (raw?.trim()?.lowercase()?.replace('_', '-')) {
                "read-only", "readonly" -> ReadOnly
                "workspace-write", "workspacewrite", "agent", "default" -> Default
                "auto-review", "autoreview" -> AutoReview
                "full-access", "fullaccess", "agent-full-access" -> FullAccess
                else -> null
            }
    }
}

// ---------------------------------------------------------------------------
// Model selection
// ---------------------------------------------------------------------------

/**
 * Which model catalog a stored model id belongs to: one remote catalog, one
 * per local Agent. A model chosen for another source must not be sent.
 */
fun agentModelSourceKey(runtime: String?, remoteEnabled: Boolean, activeAgentId: String?): String =
    if (runtime == "remote" || remoteEnabled) "remote" else "local-${activeAgentId ?: "agent"}"

/** An explicit override wins; the active model only when it belongs to the current source. */
fun selectAgentRequestModel(
    overrideModel: String?,
    activeModel: String?,
    activeModelSourceMatches: Boolean,
): String? = (overrideModel ?: activeModel.takeIf { activeModelSourceMatches })?.trim()?.ifEmpty { null }

// ---------------------------------------------------------------------------
// ACP request arguments
// ---------------------------------------------------------------------------

/** Canonical `session/new` arguments; blank strings and empty lists are omitted. */
fun newSessionArguments(
    conversationId: Int? = null,
    cwd: String? = null,
    model: String? = null,
    effort: String? = null,
    collaborationMode: String? = null,
    conversationMode: String? = null,
    additionalDirectories: List<String> = emptyList(),
): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    conversationId?.let { put("conversationId", it) }
    putTrimmed("cwd", cwd)
    putTrimmed("model", model)
    putTrimmed("effort", effort)
    putTrimmed("collaborationMode", collaborationMode)
    putTrimmed("conversationMode", conversationMode)
    if (additionalDirectories.isNotEmpty()) put("additionalDirectories", additionalDirectories)
}

/**
 * Canonical `session/prompt` arguments. [text] is always sent as given;
 * [permission] expands to the approval policy, reviewer and sandbox.
 */
fun promptSessionArguments(
    text: String,
    sessionId: String? = null,
    conversationId: Int? = null,
    requestId: String? = null,
    agentId: String? = null,
    attachments: List<Map<String, Any?>> = emptyList(),
    cwd: String? = null,
    permission: AgentPermissionMode? = null,
    model: String? = null,
    effort: String? = null,
    collaborationMode: String? = null,
    conversationMode: String? = null,
    terminalEnvironment: Map<String, String>? = null,
): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
    sessionId?.let { put("sessionId", it) }
    conversationId?.let { put("conversationId", it) }
    putTrimmed("requestId", requestId)
    putTrimmed("agentId", agentId)
    putTrimmed("cwd", cwd)
    if (permission != null) {
        put("approvalPolicy", permission.approvalPolicy)
        put("approvalsReviewer", permission.approvalsReviewer)
        permission.sandboxPolicy?.let { put("sandboxPolicy", it) }
    }
    putTrimmed("model", model)
    putTrimmed("effort", effort)
    putTrimmed("collaborationMode", collaborationMode)
    putTrimmed("conversationMode", conversationMode)
    if (!terminalEnvironment.isNullOrEmpty()) put("terminalEnvironment", terminalEnvironment)
    put("text", text)
    if (attachments.isNotEmpty()) put("attachments", attachments)
}

private fun MutableMap<String, Any?>.putTrimmed(key: String, value: String?) {
    val trimmed = value?.trim()
    if (!trimmed.isNullOrEmpty()) put(key, trimmed)
}

// ---------------------------------------------------------------------------
// Turn identity
// ---------------------------------------------------------------------------

/**
 * Message ids of one submission: the user row and the run (task) it starts.
 * The run id doubles as the ACP `requestId`, so a transport retry of the same
 * submission returns the same turn instead of running it twice.
 */
data class ChatTurnIds(val userMessageId: String, val taskId: String) {
    val requestId: String get() = taskId

    companion object {
        fun forSubmission(nowMillis: Long): ChatTurnIds = ChatTurnIds("$nowMillis-user", "$nowMillis-ai")

        /** A retry keeps its user row and starts a new run. */
        fun forRetry(userMessageId: String, nowMillis: Long): ChatTurnIds = ChatTurnIds(userMessageId, "$nowMillis-ai")
    }
}

// ---------------------------------------------------------------------------
// User prompt text and attachments
// ---------------------------------------------------------------------------

/**
 * The text a model receives for one submission: workspace paths of
 * attachments the model reads itself, otherwise the names of non-image
 * attachments. Delegates to the adapter's [AgentAttachmentPromptSupport],
 * which matches Dart `_buildMessageTextForModel` (and also sanitizes broken
 * UTF-16 and recognizes `data:image/` URLs).
 *
 * It is built from the submitted text and attachments. The Dart pure-chat
 * path re-read "the newest user message" after its awaits; the launcher
 * passes the submission instead (5d-0 fix).
 */
fun buildUserPromptText(text: String, attachments: List<Map<String, Any?>>): String =
    AgentAttachmentPromptSupport.buildUserMessageText(text, attachments)

/**
 * Attachments forwarded to the ACP prompt: a `sendToModel: false` file is
 * referenced by path in [buildUserPromptText] and must not also be sent as
 * content.
 *
 * Dart applied this filter only on the pure-chat path; the task-flow path
 * forwarded every attachment, and the ACP adapter turns any attachment with a
 * readable path into a resource link, so an excluded file still reached the
 * model. The launcher filters every path (5d-0 fix).
 */
fun modelAttachments(attachments: List<Map<String, Any?>>): List<Map<String, Any?>> =
    attachments.filter(AgentAttachmentPromptSupport::shouldSendAttachmentToModel)
