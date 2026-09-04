package cn.com.omnimind.bot.agent

/**
 * Projects the official ACP ToolCallStatus into the local presentation
 * vocabulary used by the WebChat history writer.
 *
 * The outer ACP status is the only lifecycle source of truth. Tool output is
 * opaque data and must never be inspected to invent another lifecycle.
 */
internal fun resolveAgentToolPayloadStatus(
    raw: Map<String, Any?>,
    fallback: String
): String {
    val normalized = listOf(raw["status"], raw["state"])
        .firstNotNullOfOrNull { value ->
            value?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
    return when (normalized) {
        "pending" -> "pending"
        "in_progress" -> "running"
        "completed" -> "success"
        "failed" -> "error"
        // Compatibility for old non-ACP event producers. This branch does
        // not inspect raw tool output and can be removed with that protocol.
        "running", "progress", "inprogress", "executing", "started" -> "running"
        "success", "succeeded", "complete", "applied", "done" -> "success"
        "error", "failure", "rejected" -> "error"
        "cancelled", "canceled", "incomplete", "interrupted", "aborted" -> "interrupted"
        "timeout", "timedout" -> "timeout"
        else -> fallback
    }
}
