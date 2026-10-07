package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/*
 * Pure composer state (batch 5d-1b), ported from
 * `command_overlay/state/chat_composer_state_machine.dart` and the context
 * ring of `chat_input_context_usage.dart`. The Compose composer renders from
 * these; nothing here touches the runtime.
 */

/** One pending attachment (Dart `ChatInputAttachment`). */
@Immutable
data class ChatComposerAttachment(
    val id: String,
    val name: String,
    /** A readable path or `content://` uri; the runtime copies it into the workspace. */
    val path: String,
    val size: Long? = null,
    val mimeType: String? = null,
    val isImage: Boolean = false,
    val promptPath: String? = null,
    val sendToModel: Boolean = true,
) {
    /** Dart `ChatInputAttachment.toMap`: `sendToModel` is written only when false. */
    fun toMap(): Map<String, Any?> {
        // Not `linkedMapOf().apply {}`: inside it `size` is the map's own size.
        val map = linkedMapOf<String, Any?>("id" to id, "name" to name, "path" to path)
        size?.let { map["size"] = it }
        mimeType?.let { map["mimeType"] = it }
        map["isImage"] = isImage
        promptPath?.trim()?.takeIf { it.isNotEmpty() }?.let { map["promptPath"] = it }
        if (!sendToModel) map["sendToModel"] = false
        return map
    }
}

/** What the composer's primary button does. */
enum class ChatComposerPrimaryAction { Cancel, Send, Disabled }

/**
 * Dart `ChatComposerState.primaryAction` for the large composer, which has no
 * attachment fallback: a running turn always offers cancel, so a draft can be
 * typed (but not sent) while the reply streams.
 */
fun chatComposerPrimaryAction(
    isProcessing: Boolean,
    text: String,
    hasAttachments: Boolean,
): ChatComposerPrimaryAction = when {
    isProcessing -> ChatComposerPrimaryAction.Cancel
    text.isNotBlank() || hasAttachments -> ChatComposerPrimaryAction.Send
    else -> ChatComposerPrimaryAction.Disabled
}

/** Permission choices offered by the menu (Dart `AgentPermissionMode`), keyed by preference value. */
enum class ChatComposerPermission(val preferenceValue: String) {
    ReadOnly("read-only"),
    Default("workspace-write"),
    AutoReview("auto-review"),
    FullAccess("full-access");

    companion object {
        fun fromPreferenceValue(value: String?): ChatComposerPermission? =
            entries.firstOrNull { it.preferenceValue == value }
    }
}

/** Severity of the context-usage ring (Dart thresholds 0.85 and 1.0). */
enum class ContextUsageLevel { Normal, Warning, Full }

/** Ring progress and level for a usage ratio; null hides the ring. */
@Immutable
data class ContextUsageRing(val progress: Float, val level: ContextUsageLevel)

/**
 * Dart `ConversationModel.contextUsageRatio` and the ring colors: hidden
 * without a threshold or before the first usage report, clamped to a full
 * ring, warning from 85% and full at 100%.
 */
fun contextUsageRing(latestPromptTokens: Long, promptTokenThreshold: Long, updatedAt: Long): ContextUsageRing? {
    if (promptTokenThreshold <= 0) return null
    if (updatedAt <= 0 && latestPromptTokens <= 0) return null
    val ratio = latestPromptTokens.toDouble() / promptTokenThreshold
    val level = when {
        ratio >= 1.0 -> ContextUsageLevel.Full
        ratio >= 0.85 -> ContextUsageLevel.Warning
        else -> ContextUsageLevel.Normal
    }
    return ContextUsageRing(ratio.coerceIn(0.0, 1.0).toFloat(), level)
}

/** The composer's input state, fed by the page ViewModel. */
@Immutable
data class ChatComposerState(
    /** False on stored history the page cannot send to (no live target). */
    val available: Boolean = false,
    val isProcessing: Boolean = false,
    val attachments: List<ChatComposerAttachment> = emptyList(),
    /** Null hides the permission button (normal chat). */
    val permission: ChatComposerPermission? = null,
    val permissionChoices: List<ChatComposerPermission> = emptyList(),
    val contextUsage: ContextUsageRing? = null,
    /** "used / threshold tokens" shown when the ring is tapped. */
    val contextUsageLabel: String? = null,
    val cancelling: Boolean = false,
)

/** Intents the composer emits; the ViewModel owns every effect. */
class ChatComposerActions(
    val onSend: (text: String) -> Boolean = { false },
    val onCancel: () -> Unit = {},
    val onPickAttachment: () -> Unit = {},
    val onRemoveAttachment: (id: String) -> Unit = {},
    val onSelectPermission: (ChatComposerPermission) -> Unit = {},
)
