package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.AgentPermissionMode
import cn.com.omnimind.bot.agent.projection.CHAT_RUNTIME_MODE_AGENT
import cn.com.omnimind.bot.agent.projection.CHAT_RUNTIME_MODE_NORMAL
import cn.com.omnimind.bot.agent.projection.ConversationModes
import cn.com.omnimind.bot.agent.projection.conversationModeFromStorageValue
import cn.com.omnimind.nativeui.chat.ChatComposerPermission
import cn.com.omnimind.bot.agent.projection.DartJson

/**
 * Where a native composer submission goes (batch 5d-1b): the runtime it is
 * admitted on and the identity the turn carries. Ported from the Flutter
 * page's dispatch split (`_dispatchUserMessage`): pure chat sends with no
 * Harness on the normal runtime; an Agent conversation sends through its own
 * Harness on the Agent runtime.
 */
internal data class NativeChatComposerTarget(
    val runtimeMode: String,
    /** Durable conversation mode sent as `conversationMode`. */
    val conversationMode: String,
    /** ACP Harness; null for pure chat. */
    val agentId: String?,
    /** Agent turns carry the composer's permission choice and show its menu. */
    val showsPermission: Boolean,
) {
    companion object {
        const val XIAOWAN_AGENT_ID = "xiaowan-acp"
        private const val REMOTE_AGENT_ID = "codex-remote"

        /**
         * Null when the native composer cannot send here yet: OpenClaw,
         * scheduled Sub Agent runs and remote Codex sessions keep their
         * Flutter flows until 5e. A live runtime keeps the mode it already
         * runs on, so a turn never lands on a second runtime of the same
         * conversation.
         */
        fun resolve(storedMode: String?, agentId: String?, liveRuntimeMode: String?): NativeChatComposerTarget? {
            val mode = conversationModeFromStorageValue(storedMode)
            val harness = agentId?.trim()?.ifEmpty { null }
            return when {
                harness == REMOTE_AGENT_ID -> null
                mode == ConversationModes.CHAT_ONLY -> NativeChatComposerTarget(
                    runtimeMode = liveRuntimeMode ?: CHAT_RUNTIME_MODE_NORMAL,
                    conversationMode = ConversationModes.CHAT_ONLY,
                    agentId = null,
                    showsPermission = false,
                )
                mode == ConversationModes.AGENT -> NativeChatComposerTarget(
                    runtimeMode = liveRuntimeMode ?: CHAT_RUNTIME_MODE_AGENT,
                    conversationMode = ConversationModes.AGENT,
                    agentId = harness ?: XIAOWAN_AGENT_ID,
                    showsPermission = true,
                )
                else -> null
            }
        }
    }
}

/** Local Harnesses offer no auto review (Dart `chat_page_ui.dart` permission choices). */
internal val LOCAL_PERMISSION_CHOICES = listOf(
    ChatComposerPermission.ReadOnly,
    ChatComposerPermission.Default,
    ChatComposerPermission.FullAccess,
)

/** A stored auto-review choice falls back to workspace write on a local Harness, as on the Flutter page. */
internal fun AgentPermissionMode.forLocalHarness(): AgentPermissionMode =
    if (this == AgentPermissionMode.AutoReview) AgentPermissionMode.Default else this

internal fun AgentPermissionMode.toComposer(): ChatComposerPermission =
    ChatComposerPermission.fromPreferenceValue(preferenceValue) ?: ChatComposerPermission.FullAccess

internal fun ChatComposerPermission.toAgent(): AgentPermissionMode =
    AgentPermissionMode.fromPreference(preferenceValue) ?: AgentPermissionMode.FullAccess

/**
 * The pure-chat model and effort of one conversation: its override
 * (`conversation_model_overrides_v1` / `conversation_reasoning_efforts_v1`,
 * JSON maps keyed by conversation id), else the dispatch scene model and no
 * effort. Mirrors `_launchNormalTurn`'s selection.
 */
internal fun pureChatModelOverride(overridesJson: String?, conversationId: Long): String? {
    val entry = jsonEntry(overridesJson, conversationId) as? Map<*, *> ?: return null
    if (entry["providerProfileId"]?.toString()?.trim().isNullOrEmpty()) return null
    return entry["modelId"]?.toString()?.trim()?.ifEmpty { null }
}

internal fun pureChatReasoningEffort(effortsJson: String?, conversationId: Long): String? {
    val raw = jsonEntry(effortsJson, conversationId)?.toString()?.trim()?.lowercase() ?: return null
    // Dart `ConversationReasoningEffortService._normalizeEffort`.
    return when (raw) {
        "no", "off", "disabled" -> "none"
        "med" -> "medium"
        else -> raw
    }.takeIf { it in SUPPORTED_EFFORTS }
}

/** A broken cache reads as empty, like the Dart services. */
private fun jsonEntry(json: String?, conversationId: Long): Any? {
    if (json.isNullOrBlank()) return null
    val map = runCatching { DartJson.decode(json) }.getOrNull() as? Map<*, *> ?: return null
    return map[conversationId.toString()]
}

private val SUPPORTED_EFFORTS = setOf("none", "low", "medium", "high", "xhigh", "max")

/** Dart `persistConversationSnapshot`: the first user text, cut at 20 characters. */
internal fun newConversationTitle(firstText: String): String {
    val text = firstText.trim().ifEmpty { return "新对话" }
    return if (text.length > 20) "${text.substring(0, 20)}..." else text
}

/** What choosing a Harness on the chat page does (5e-3). */
internal enum class HarnessSwitchPlan { Ignore, Busy, ReplaceTarget, OpenNewConversation }

/**
 * A conversation keeps the Harness it was created with (Flutter
 * `buildHarnessSwitchTarget` always starts a new conversation), so a page that
 * already has one opens a new conversation; a new page only retargets its
 * first send. Any running turn refuses the switch.
 */
internal fun planHarnessSwitch(
    currentAgentId: String?,
    requestedAgentId: String,
    hasConversation: Boolean,
    anyTurnRunning: Boolean,
): HarnessSwitchPlan = when {
    requestedAgentId.isBlank() || requestedAgentId == currentAgentId -> HarnessSwitchPlan.Ignore
    anyTurnRunning -> HarnessSwitchPlan.Busy
    hasConversation -> HarnessSwitchPlan.OpenNewConversation
    else -> HarnessSwitchPlan.ReplaceTarget
}

/** What an untargeted chat entry opens (Dart `_resolveInitialThreadTarget`, 5e-4). */
internal sealed interface ChatStartupTarget {
    data object NewConversation : ChatStartupTarget
    data class Existing(val conversationId: Long, val mode: String, val title: String) : ChatStartupTarget
}

/**
 * The startup preference (`chat_startup_behavior`) then the last visible
 * thread target (`last_visible_conversation_target`, the Flutter page's JSON).
 * A target the native page cannot open (OpenClaw, remote session, a deleted
 * conversation) falls back to a new conversation; [exists] answers whether
 * the id is still a stored conversation and gives its title.
 */
internal suspend fun resolveChatStartupTarget(
    startupBehavior: String?,
    lastVisibleTargetJson: String?,
    exists: suspend (Long) -> String?,
): ChatStartupTarget {
    if (startupBehavior == "new_conversation") return ChatStartupTarget.NewConversation
    val target = runCatching { DartJson.decode(lastVisibleTargetJson ?: return ChatStartupTarget.NewConversation) }
        .getOrNull() as? Map<*, *> ?: return ChatStartupTarget.NewConversation
    if (target["isNewConversation"] == true) return ChatStartupTarget.NewConversation
    val mode = conversationModeFromStorageValue(target["mode"]?.toString())
    if (mode == ConversationModes.OPENCLAW) return ChatStartupTarget.NewConversation
    if ((target["agentRuntime"] ?: target["codexRuntime"])?.toString() == "remote") return ChatStartupTarget.NewConversation
    val id = (target["conversationId"] as? Number)?.toLong()
        ?: target["conversationId"]?.toString()?.toLongOrNull()
        ?: return ChatStartupTarget.NewConversation
    val title = exists(id) ?: return ChatStartupTarget.NewConversation
    return ChatStartupTarget.Existing(id, mode, title)
}

/** A request to continue a conversation in the Flutter chat (5e-6). */
internal data class ChatHandoff(val conversationId: Long?, val mode: String, val agentId: String?, val draft: String)

/** Dart `_executeManualContextCompactionCommand`'s marker status for a compaction result. */
internal fun compactionStatus(result: Map<String, Any?>?, failed: Boolean): String {
    if (failed || result == null) return "failed"
    if (result["compacted"] == true) return "completed"
    return when (result["reason"]?.toString()?.trim()) {
        "no_candidate", "no_prompt_messages" -> "noop"
        else -> "failed"
    }
}

/**
 * A shared draft's files as composer attachments (Dart
 * `_applyStagedSharedDraftIfNeeded`): id and name fall back to the path, and
 * a file the user kept out of the model stays `sendToModel: false`.
 */
internal fun sharedDraftAttachments(pending: Map<String, Any?>): List<cn.com.omnimind.nativeui.chat.ChatComposerAttachment> =
    (pending["attachments"] as? List<*>).orEmpty().mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val path = map["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        cn.com.omnimind.nativeui.chat.ChatComposerAttachment(
            id = map["id"]?.toString()?.ifBlank { null } ?: path,
            name = map["name"]?.toString()?.ifBlank { null } ?: path.substringAfterLast('/'),
            path = path,
            size = (map["size"] as? Number)?.toLong(),
            mimeType = map["mimeType"]?.toString()?.ifBlank { null },
            isImage = map["isImage"] == true,
            promptPath = map["promptPath"]?.toString()?.ifBlank { null },
            sendToModel = map["sendToModel"] != false,
        )
    }
