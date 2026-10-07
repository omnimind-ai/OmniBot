package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/*
 * Slash commands of the native composer (batch 5d-1c), ported from
 * `utils/agent_slash_commands.dart` (submit intents), `chat_page_ui.dart`
 * (`_buildAgentRootCommandCards`, `_buildAgentModelCards`,
 * `_buildReasoningEffortCommandCard`) and `chat_page_openclaw.dart`
 * (`_tryHandleSlashCommand`). Pure: the panel and the submit path both read
 * one [ChatSlashContext].
 */

/** What the panel and the submit resolver know about the conversation. */
@Immutable
data class ChatSlashContext(
    /** Agent conversation (Agent commands) vs pure chat (`/effort`). */
    val agent: Boolean = false,
    /** Names the Agent advertised (`available_commands_update`), with or without a leading slash. */
    val advertisedCommands: List<ChatAdvertisedCommand> = emptyList(),
    val models: List<String> = emptyList(),
    val selectedModel: String? = null,
    /** The advertised `plan` value of `collaboration_mode`; null hides `/plan`. */
    val planMode: String? = null,
    val planActive: Boolean = false,
    val selectedEffort: String? = null,
    /**
     * True while any turn runs. Changing the shared model reconnects the ACP
     * runtime and an Agent only accepts configuration while idle.
     */
    val configLocked: Boolean = false,
)

@Immutable
data class ChatAdvertisedCommand(val name: String, val description: String = "") {
    val slashName: String get() = if (name.startsWith("/")) name else "/$name"
}

/** One row of the panel. Tapping it fills the draft or submits [submitText]. */
@Immutable
data class ChatSlashEntry(
    val id: String,
    val title: String,
    val kind: Kind,
    /** Advertised description, or the model id. */
    val detail: String = "",
    val selected: Boolean = false,
    /** Non-null: tapping replaces the draft with it (the user adds arguments). */
    val fillText: String? = null,
    /** Non-null: tapping submits it through the same path as typing it. */
    val submitText: String? = null,
) {
    enum class Kind { Model, Review, Init, Plan, AcpCommand, ModelOption, ModelsEmpty, Effort, EffortOption }
}

/** What a submitted draft does. */
sealed interface ChatSlashSubmit {
    /** An ordinary prompt; [display] is the user row when it differs from [text]. */
    data class Send(val text: String, val display: String? = null, val collaborationMode: String? = null) : ChatSlashSubmit
    data class FillText(val text: String) : ChatSlashSubmit
    data class SelectModel(val modelId: String) : ChatSlashSubmit
    data object TogglePlan : ChatSlashSubmit
    data class StartPlan(val prompt: String) : ChatSlashSubmit
    data class SetEffort(val effort: String) : ChatSlashSubmit
    data class Notice(val reason: Reason) : ChatSlashSubmit

    enum class Reason { Unsupported, ReviewUnavailable, PlanUnavailable, InvalidEffort, OpenInChat, Busy }
}

/** Dart `_kAgentReasoningEffortOptions`: the slider's stops. */
val CHAT_EFFORT_OPTIONS = listOf("no", "low", "high", "xhigh", "max")

private val SUPPORTED_EFFORTS = setOf("none", "low", "medium", "high", "xhigh", "max")
private val BUILT_IN_AGENT_COMMANDS = setOf("/model", "/review", "/init", "/plan")

/** Dart `ConversationReasoningEffortService.normalizeEffort`. */
fun normalizeChatEffort(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isEmpty()) return null
    val canonical = when (value) {
        "no", "off", "disabled" -> "none"
        "med" -> "medium"
        else -> value
    }
    return canonical.takeIf { it in SUPPORTED_EFFORTS }
}

/** Manual recording stays a Flutter flow (`ManualRecordingFlowController.isCommand`). */
private val FLUTTER_ONLY_COMMANDS = setOf(
    "/record", "/compact", "/openclaw", "手动录制", "开始手动录制", "人工录制", "录制轨迹",
    "开始录制轨迹", "轨迹录制", "manual recording", "manual record",
)

fun ChatSlashContext.advertised(text: String): ChatAdvertisedCommand? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/")) return null
    val name = trimmed.substring(1).split(Regex("\\s+")).first().lowercase()
    if (name.isEmpty()) return null
    return advertisedCommands.firstOrNull { it.name.removePrefix("/").lowercase() == name }
}

/**
 * Resolves a submitted draft. Agent: an advertised command is an ordinary
 * prompt; the built-ins follow `resolveAgentSlashSubmitIntent`; any other
 * slash text is refused. Pure chat: `/effort` is handled, the commands only
 * the Flutter page runs are refused, everything else is a prompt.
 */
fun ChatSlashContext.resolveSubmit(draft: String, initPrompt: String): ChatSlashSubmit {
    val text = draft.trim()
    val lower = text.lowercase()
    if (!agent) {
        if (lower in FLUTTER_ONLY_COMMANDS || lower.startsWith("/compact ") || lower.startsWith("/openclaw ")) {
            return ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.OpenInChat)
        }
        if (lower == "/effort") return ChatSlashSubmit.FillText("/effort ")
        if (lower.startsWith("/effort ")) {
            val effort = normalizeChatEffort(text.substring("/effort".length))
                ?: return ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.InvalidEffort)
            return ChatSlashSubmit.SetEffort(effort)
        }
        return ChatSlashSubmit.Send(text)
    }
    if (!text.startsWith("/")) return ChatSlashSubmit.Send(text)
    if (advertised(text) != null) return ChatSlashSubmit.Send(text)
    val locked = ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.Busy)
    return when {
        lower == "/model" -> ChatSlashSubmit.FillText("/model ")
        lower.startsWith("/model ") -> {
            val modelId = text.substring("/model".length).trim()
            when {
                modelId.isEmpty() -> ChatSlashSubmit.FillText("/model ")
                configLocked -> locked
                else -> ChatSlashSubmit.SelectModel(modelId)
            }
        }
        // An advertised /review was handled above.
        lower == "/review" -> ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.ReviewUnavailable)
        lower == "/init" -> ChatSlashSubmit.Send(initPrompt, display = "/init")
        lower == "/plan" -> when {
            planMode == null -> ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.PlanUnavailable)
            configLocked -> locked
            else -> ChatSlashSubmit.TogglePlan
        }
        lower.startsWith("/plan ") -> {
            val prompt = text.substring("/plan".length).trim()
            when {
                planMode == null -> ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.PlanUnavailable)
                configLocked -> locked
                prompt.isEmpty() -> ChatSlashSubmit.TogglePlan
                else -> ChatSlashSubmit.StartPlan(prompt)
            }
        }
        else -> ChatSlashSubmit.Notice(ChatSlashSubmit.Reason.Unsupported)
    }
}

/** The panel rows for a draft; empty unless the draft starts with a slash. */
fun ChatSlashContext.entries(draft: String): List<ChatSlashEntry> {
    val trimmed = draft.trimStart()
    if (!trimmed.startsWith("/")) return emptyList()
    val lower = trimmed.lowercase()
    if (!agent) {
        if (lower == "/effort" || lower.startsWith("/effort ")) {
            return CHAT_EFFORT_OPTIONS.map { effort ->
                ChatSlashEntry(
                    id = "effort-$effort",
                    title = effort,
                    kind = ChatSlashEntry.Kind.EffortOption,
                    selected = normalizeChatEffort(effort) == selectedEffort,
                    submitText = "/effort $effort",
                )
            }
        }
        return listOf(
            ChatSlashEntry("effort", "/effort", ChatSlashEntry.Kind.Effort, detail = selectedEffort.orEmpty(), fillText = "/effort "),
        ).filter { it.title.startsWith(lower) }
    }
    if (lower == "/model" || lower.startsWith("/model ")) return modelEntries(trimmed.drop(6).trim().lowercase())
    val rows = buildList {
        add(ChatSlashEntry("model", "/model", ChatSlashEntry.Kind.Model, detail = selectedModel.orEmpty(), fillText = "/model "))
        if (advertised("/review") != null) add(ChatSlashEntry("review", "/review", ChatSlashEntry.Kind.Review, submitText = "/review"))
        add(ChatSlashEntry("init", "/init", ChatSlashEntry.Kind.Init, submitText = "/init"))
        if (planMode != null) add(ChatSlashEntry("plan", "/plan", ChatSlashEntry.Kind.Plan, selected = planActive, submitText = "/plan"))
        val seen = BUILT_IN_AGENT_COMMANDS.toMutableSet()
        for (command in advertisedCommands) {
            val name = command.slashName
            if (!seen.add(name.lowercase())) continue
            add(ChatSlashEntry("acp-$name", name, ChatSlashEntry.Kind.AcpCommand, detail = command.description, fillText = "$name "))
        }
    }
    return rows.filter { it.title.lowercase().startsWith(lower) }
}

private fun ChatSlashContext.modelEntries(query: String): List<ChatSlashEntry> {
    val matching = models.filter { query.isEmpty() || it.lowercase().contains(query) }
    if (matching.isEmpty()) return listOf(ChatSlashEntry("models-empty", "/model", ChatSlashEntry.Kind.ModelsEmpty))
    // The selected model leads, as in `_buildAgentModelCards`.
    val ordered = matching.filter { it == selectedModel } + matching.filter { it != selectedModel }
    return ordered.map { model ->
        ChatSlashEntry(
            id = "model-$model",
            title = model,
            kind = ChatSlashEntry.Kind.ModelOption,
            detail = model,
            selected = model == selectedModel,
            submitText = "/model $model",
        )
    }
}
