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
