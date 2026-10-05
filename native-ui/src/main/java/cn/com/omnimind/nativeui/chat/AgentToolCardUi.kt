package cn.com.omnimind.nativeui.chat

import androidx.compose.runtime.Immutable

/**
 * Presentation of one `agent_tool_summary` card, derived by the app module
 * from the runtime card data (the Flutter `AgentToolSummaryCard` helpers:
 * titles, labels, transcript, diff, actions). native-ui only lays it out.
 */
@Immutable
data class AgentToolCardUi(
    val cardId: String,
    /** Raw lifecycle status: running, pending, success, error, timeout, interrupted. */
    val status: String,
    val toolType: String,
    val style: AgentToolCardStyle,
    /** Live progress title (`resolveAgentToolProgressTitle`). */
    val title: String,
    /** Badge text: the type label while running, else the status label. */
    val badgeLabel: String,
    /** Running and not waiting for a legacy confirmation: the title shimmers. */
    val isActive: Boolean,
    /** Inline rows of agent tools open the detail sheet when they have no diff. */
    val opensDetail: Boolean,
    /** Inline file rows: the file name is highlighted inside [title]. */
    val fileName: String = "",
    val filePath: String = "",
    val diffStat: AgentDiffStatUi? = null,
    val diff: AgentDiffUi? = null,
    val detail: AgentToolDetailUi,
)

enum class AgentToolCardStyle {
    /** Rounded capsule with icon, title and status badge. */
    Capsule,

    /** Flat inline row (file edits and agent-native tools). */
    Inline,
}

@Immutable
data class AgentDiffStatUi(val label: String, val additions: Int, val deletions: Int)

/** Content of the tool detail sheet (Flutter `_AgentToolDetailContent`). */
@Immutable
data class AgentToolDetailUi(
    val title: String,
    /** Raw lifecycle status, for the status chip color. */
    val status: String,
    val typeLabel: String,
    val statusLabel: String,
    val promptLine: String,
    /** May contain ANSI SGR sequences; rendered with [ansiAnnotatedString]. */
    val outputText: String,
    val copyText: String,
    val isTerminal: Boolean,
    /** When non-null the sheet shows the diff instead of the transcript. */
    val diff: AgentDiffUi? = null,
    val actions: List<AgentToolActionUi> = emptyList(),
)

/** A follow-up button in the detail sheet; the host decides how to run it. */
@Immutable
data class AgentToolActionUi(
    val type: String,
    val label: String,
    val target: String = "",
    val payload: Map<String, Any?> = emptyMap(),
)

@Immutable
data class AgentDiffUi(
    val files: List<AgentDiffFileUi>,
    val additions: Int,
    val deletions: Int,
    /** `formatAgentDiffStat(additions, deletions)`, e.g. `+1.2k -3`. */
    val statLabel: String,
)

@Immutable
data class AgentDiffFileUi(
    val displayPath: String,
    val additions: Int,
    val deletions: Int,
    val isNewFile: Boolean,
    val isDeletedFile: Boolean,
    val lines: List<AgentDiffLineUi>,
    val statLabel: String,
)

enum class AgentDiffLineKind { Header, Addition, Deletion, Context, Meta }

@Immutable
data class AgentDiffLineUi(
    val kind: AgentDiffLineKind,
    val content: String,
    val prefix: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)
