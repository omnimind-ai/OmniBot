package cn.com.omnimind.bot.agent.projection

/**
 * Normalizes the native stream ordering metadata stored on projected
 * messages. Returns null when there is nothing to record.
 */
internal fun ensureAgentStreamMessageMeta(
    streamMeta: Map<String, Any?>?,
    seq: Int? = null,
    roundIndex: Int? = null,
    kind: String? = null,
    runId: String? = null,
    sessionId: String? = null,
    turnId: String? = null,
    itemId: String? = null,
    toolCallId: String? = null,
    cardId: String? = null,
    parentTaskId: String? = null,
    entryId: String? = null,
    isFinal: Boolean = false,
): JsonMap? {
    val normalized: JsonMap = LinkedHashMap(streamMeta ?: emptyMap())
    fun present(value: String?) = value?.trim()?.isNotEmpty() == true
    val hasInput = normalized.isNotEmpty() ||
        seq != null ||
        roundIndex != null ||
        present(kind) ||
        present(runId) ||
        present(sessionId) ||
        present(turnId) ||
        present(itemId) ||
        present(toolCallId) ||
        present(cardId) ||
        present(parentTaskId) ||
        present(entryId) ||
        isFinal
    if (!hasInput) return null

    if (seq != null) normalized["seq"] = seq
    if (roundIndex != null) normalized["roundIndex"] = roundIndex
    fun putString(key: String, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty()) normalized[key] = text
    }
    putString("kind", kind)
    putString("runId", runId)
    putString("sessionId", sessionId)
    putString("turnId", turnId)
    putString("itemId", itemId)
    putString("toolCallId", toolCallId)
    putString("cardId", cardId)
    putString("parentTaskId", parentTaskId)
    putString("entryId", entryId)
    normalized["isFinal"] = isFinal || normalized["isFinal"] == true
    return normalized
}
