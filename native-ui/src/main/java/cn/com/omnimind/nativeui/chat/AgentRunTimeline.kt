package cn.com.omnimind.nativeui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Port of ui/lib/features/home/pages/chat/utils/agent_run_timeline.dart.
 * Branch order and fallbacks are kept identical to the Dart source.
 */

class AgentRunTimelineEntry private constructor(
    val message: ChatMessageUi?,
    val group: AgentRunTimelineGroup?,
) {
    val isMessage: Boolean get() = message != null

    val isUserMessage: Boolean get() = message?.user == 1

    val key: String get() = message?.id ?: "agent-run-${group!!.taskId}"

    companion object {
        fun message(message: ChatMessageUi): AgentRunTimelineEntry = AgentRunTimelineEntry(message, null)

        fun group(group: AgentRunTimelineGroup): AgentRunTimelineEntry = AgentRunTimelineEntry(null, group)
    }
}

/**
 * Whether an agent turn is still producing output.
 *
 * This is derived at render time from the set of in-flight task ids, never
 * from a persisted flag. A turn that is not in that set has ended — whether it
 * ended cleanly, was cancelled, or died with the process.
 */
enum class AgentRunStatus { running, finished, failed, cancelled }

/**
 * One chronological slice of a turn: either a message that stays in the
 * conversation, or a contiguous run of process cards that folds as a unit.
 *
 * A turn renders in the order the agent produced it. `runtime.messages` is
 * kept newest-first by every insert site, so list position is that order.
 * `streamMeta.seq` is not: a snapshot card rewrites its sequence on every
 * delta, so ordering by it puts a round's thinking card after the tools it
 * preceded.
 */
class AgentRunTimelineSegment private constructor(
    /** Oldest first. */
    val messages: List<ChatMessageUi>,
    /** Whether this slice folds away with the run header. */
    val isProcess: Boolean,
) {
    val message: ChatMessageUi get() = messages.first()

    companion object {
        fun visible(message: ChatMessageUi): AgentRunTimelineSegment =
            AgentRunTimelineSegment(listOf(message), false)

        fun process(messages: List<ChatMessageUi>): AgentRunTimelineSegment =
            AgentRunTimelineSegment(messages, true)
    }
}

class AgentRunTimelineGroup(
    val taskId: String,
    val status: AgentRunStatus,
    /**
     * Resolved once, when the group is built, so the live and restored render
     * paths cannot disagree about which agent produced the turn.
     */
    val agentId: String,
    /**
     * Run boundaries (epoch millis), carried on the group so the header does
     * not have to re-derive elapsed time by scanning message timestamps.
     */
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    /** The turn, in arrival order. */
    val segmentsOldestFirst: List<AgentRunTimelineSegment>,
) {
    /**
     * Canonical UI run identity. [taskId] remains as a source-compatible
     * compatibility name for expansion state and older callers.
     */
    val runId: String get() = taskId

    val isRunning: Boolean get() = status == AgentRunStatus.running

    val isEmpty: Boolean get() = segmentsOldestFirst.isEmpty()

    val hasProcessMessages: Boolean get() = segmentsOldestFirst.any { it.isProcess }

    val allMessagesOldestFirst: List<ChatMessageUi>
        get() = segmentsOldestFirst.flatMap { it.messages }

    val visibleMessagesOldestFirst: List<ChatMessageUi>
        get() = segmentsOldestFirst.filter { !it.isProcess }.flatMap { it.messages }

    val processMessagesOldestFirst: List<ChatMessageUi>
        get() = segmentsOldestFirst.filter { it.isProcess }.flatMap { it.messages }

    val visibleMessagesNewestFirst: List<ChatMessageUi>
        get() = visibleMessagesOldestFirst.reversed()

    val processMessagesNewestFirst: List<ChatMessageUi>
        get() = processMessagesOldestFirst.reversed()

    val thinkingCount: Int
        get() = processMessagesOldestFirst.count { cardType(it) == "deep_thinking" }

    val toolCount: Int
        get() = processMessagesOldestFirst.count { cardType(it) == "agent_tool_summary" }

    /**
     * Swaps in the newest instance of each message without re-deriving the
     * turn's shape, so a content-only stream update cannot reorder it.
     */
    fun withRefreshedMessages(latestById: Map<String, ChatMessageUi>): AgentRunTimelineGroup {
        fun refresh(source: List<ChatMessageUi>): List<ChatMessageUi> =
            source.map { message -> latestById[message.id] ?: message }

        return AgentRunTimelineGroup(
            taskId = taskId,
            status = status,
            agentId = agentId,
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
            segmentsOldestFirst = segmentsOldestFirst.map { segment ->
                if (segment.isProcess) {
                    AgentRunTimelineSegment.process(refresh(segment.messages))
                } else {
                    AgentRunTimelineSegment.visible(
                        projectAgentRequestMessage(latestById[segment.message.id] ?: segment.message),
                    )
                }
            },
        )
    }
}

fun buildAgentRunTimelineEntries(
    messages: List<ChatMessageUi>,
    activeTaskIds: Set<String> = emptySet(),
    conversationAgentId: String? = null,
): List<AgentRunTimelineEntry> {
    if (messages.isEmpty()) {
        return emptyList()
    }

    // ACP requests are transport interactions, not large forms. Keep the
    // original card in runtime state so the host can answer it, but project it
    // to the shared compact request card for the timeline.
    val renderMessages = messages.map(::projectAgentRequestMessage)

    val normalizedActiveTaskIds = activeTaskIds
        .map { it.dartTrim() }
        .filter { it.isNotEmpty() }
        .toSet()
    val emittedTaskIds = LinkedHashSet<String>()
    val entries = ArrayList<AgentRunTimelineEntry>()

    for (message in renderMessages) {
        // Artifact metadata is already carried by the corresponding tool card;
        // the standalone compatibility card would be a large duplicate.
        if (cardType(message) == "artifact_card") {
            continue
        }
        val taskId = agentRunId(message)
        if (taskId == null) {
            entries.add(AgentRunTimelineEntry.message(message))
            continue
        }
        if (emittedTaskIds.contains(taskId)) {
            if (!isAgentRunCandidateMessage(message)) {
                entries.add(AgentRunTimelineEntry.message(message))
            }
            continue
        }

        val group = buildTimelineGroup(
            renderMessages,
            taskId = taskId,
            isActive = normalizedActiveTaskIds.contains(taskId),
            conversationAgentId = conversationAgentId,
        )
        if (group == null) {
            entries.add(AgentRunTimelineEntry.message(message))
            continue
        }

        entries.add(AgentRunTimelineEntry.group(group))
        emittedTaskIds.add(taskId)
    }

    // A turn that has been dispatched but has not streamed anything yet owns no
    // messages. Surface exactly ONE header for that state.
    val hasRunningGroup = entries.any { entry -> entry.group?.isRunning ?: false }
    if (!hasRunningGroup) {
        val pendingTaskId = normalizedActiveTaskIds
            .filter { taskId -> !emittedTaskIds.contains(taskId) }
            .lastOrNull()
        if (pendingTaskId != null) {
            entries.add(
                0,
                AgentRunTimelineEntry.group(
                    AgentRunTimelineGroup(
                        taskId = pendingTaskId,
                        status = AgentRunStatus.running,
                        agentId = resolveAgentRunAgentId(
                            turnMessages = emptyList(),
                            conversationAgentId = conversationAgentId,
                        ),
                        startedAtMillis = pendingRunStartedAt(renderMessages, pendingTaskId),
                        segmentsOldestFirst = emptyList(),
                    ),
                ),
            )
        }
    }

    return stabilizeLegacyTurnEntriesNewestFirst(entries)
}

private fun projectAgentRequestMessage(message: ChatMessageUi): ChatMessageUi {
    if (!isAgentRequestMessage(message)) {
        return message
    }
    val card: Map<String, Any?> = message.cardData ?: emptyMap()
    val kind = (card["requestKind"] ?: "").toString().dartTrim()
    val rawParams = decodeRequestParams(card["rawParamsJson"])
    val schema = requestSchema(rawParams)
    val firstField = firstSchemaField(schema)
    val storedTitle = (card["title"] ?: "").toString().dartTrim()
    val title = if (looksGenericInputTitle(storedTitle) && firstField != null) {
        stringValue(firstField["title"]) ?: storedTitle
    } else {
        storedTitle
    }
    val storedDetail = (card["detail"] ?: "").toString().dartTrim()
    val detail = requestDisplayDetail(
        storedDetail = storedDetail,
        schemaField = firstField,
        title = title,
    )
    val text = if (detail.isEmpty() || detail == title) {
        title
    } else {
        if (title.isEmpty()) detail else "$title\n$detail"
    }
    val streamMeta = LinkedHashMap<String, Any?>().apply { message.streamMeta?.let { putAll(it) } }
    val taskId = (card["runId"] ?: card["taskId"] ?: card["taskID"])
        ?.toString()
        ?.dartTrim()
    if ((streamMeta["runId"]?.toString()?.dartTrim() ?: "").isEmpty() &&
        taskId != null &&
        taskId.isNotEmpty()
    ) {
        streamMeta["runId"] = taskId
    }
    val displayTitle = if (title.isEmpty()) {
        if (kind == "approval") "Permission requested" else "Agent question"
    } else {
        title
    }
    val displayDetail = if (detail.isEmpty() && text != displayTitle) text else detail
    val projectedCard = LinkedHashMap<String, Any?>(card).apply {
        put("type", kAgentRequestCardType)
        put("simplePresentation", true)
        put("title", displayTitle)
        put("detail", displayDetail)
    }
    val content = LinkedHashMap<String, Any?>().apply {
        put("cardData", projectedCard)
        put("id", message.contentId ?: message.id)
        if (card["agentId"] != null) put("agentId", card["agentId"])
        if (card["agentName"] != null) put("agentName", card["agentName"])
    }
    return message.copy(
        type = 2,
        user = 3,
        content = content,
        // Dart copyWith keeps the old value when passed null.
        streamMeta = if (streamMeta.isEmpty()) message.streamMeta else streamMeta,
    )
}

private fun decodeRequestParams(raw: Any?): Map<String, Any?>? {
    if (raw is Map<*, *>) {
        return stringKeyedCopy(raw)
    }
    val text = raw?.toString()?.dartTrim() ?: ""
    if (text.isEmpty()) return null
    try {
        val decoded = jsonDecode(text)
        if (decoded is Map<*, *>) {
            return stringKeyedCopy(decoded)
        }
    } catch (_: Exception) {
        // A malformed producer payload should still render its title, never the
        // raw exception or a second form.
    }
    return null
}

private fun requestSchema(params: Map<String, Any?>?): Map<String, Any?>? {
    if (params == null) return null
    for (key in listOf(
        "requestedSchema",
        "requested_schema",
        "schema",
        "inputSchema",
        "input_schema",
    )) {
        val value = decodeRequestParams(params[key])
        if (value != null) return value
    }
    for (key in listOf("request", "elicitation", "params")) {
        val nested = decodeRequestParams(params[key])
        val schema = requestSchema(nested)
        if (schema != null) return schema
    }
    return if (params["properties"] is Map<*, *>) params else null
}

private fun firstSchemaField(schema: Map<String, Any?>?): Map<String, Any?>? {
    val properties = schema?.get("properties")
    if (properties !is Map<*, *> || properties.isEmpty()) return null
    val value = properties.values.first()
    return if (value is Map<*, *>) stringKeyedCopy(value) else null
}

private fun stringValue(value: Any?): String? {
    val text = value?.toString()?.dartTrim() ?: ""
    return if (text.isEmpty()) null else text
}

private fun looksGenericInputTitle(value: String): Boolean {
    val normalized = value.lowercase()
    return value.isEmpty() ||
        (normalized.contains("agent") &&
            (normalized.contains("input") || normalized.contains("question"))) ||
        (value.contains("需要") && value.contains("输入"))
}

private fun requestDisplayDetail(
    storedDetail: String,
    schemaField: Map<String, Any?>?,
    title: String,
): String {
    val description = stringValue(schemaField?.get("description"))
    val choices = schemaChoices(schemaField)
    val looksLikeJson =
        storedDetail.startsWith("{") ||
            storedDetail.startsWith("[") ||
            storedDetail.length > 600
    if (looksLikeJson && description != null && description != title) {
        return appendSchemaChoices(description, choices)
    }
    if (looksLikeJson && description == null) {
        return appendSchemaChoices("", choices)
    }
    return appendSchemaChoices(
        if (storedDetail == title) "" else storedDetail,
        choices,
    )
}

private fun schemaChoices(field: Map<String, Any?>?): List<String> {
    val values = field?.get("oneOf") ?: field?.get("enum")
    if (values !is List<*>) return emptyList()
    return values.mapNotNull { value ->
        if (value is Map<*, *>) {
            stringValue(value["title"] ?: value["label"] ?: value["const"])
        } else {
            stringValue(value)
        }
    }
}

private fun appendSchemaChoices(detail: String, choices: List<String>): String {
    if (choices.isEmpty()) return detail
    val optionLine = "可选：${choices.joinToString("、")}"
    return if (detail.isEmpty()) optionLine else "$detail\n$optionLine"
}

private class LegacyTimelineEntry(
    val entry: AgentRunTimelineEntry,
    val anchor: Long,
    val order: Int,
)

/**
 * Keeps the top-level timeline newest-first even if an asynchronously restored
 * Xiaowan snapshot briefly arrives oldest-first.
 *
 * Built-in turns have a shared millisecond prefix (`<timestamp>-user` and
 * `<timestamp>-ai`). Only entries with that legacy identity participate, so
 * opaque ACP ids retain their reducer-defined arrival order.
 */
private fun stabilizeLegacyTurnEntriesNewestFirst(
    entries: List<AgentRunTimelineEntry>,
): List<AgentRunTimelineEntry> {
    val legacySlots = ArrayList<Int>()
    val legacyEntries = ArrayList<LegacyTimelineEntry>()
    for (index in entries.indices) {
        val entry = entries[index]
        val anchor = legacyTurnAnchor(entry) ?: continue
        legacySlots.add(index)
        legacyEntries.add(LegacyTimelineEntry(entry = entry, anchor = anchor, order = index))
    }
    if (legacyEntries.size < 2) {
        return entries
    }

    // Comparator is total (ties broken by original order), so sort stability
    // does not matter.
    legacyEntries.sortWith { left, right ->
        val anchorCompare = right.anchor.compareTo(left.anchor)
        if (anchorCompare != 0) {
            return@sortWith anchorCompare
        }
        // `ChatMessageList` reverses this newest-first projection for display, so
        // the run must precede its user prompt here to render prompt -> response.
        val turnRankCompare = legacyTurnNewestFirstRank(left.entry)
            .compareTo(legacyTurnNewestFirstRank(right.entry))
        if (turnRankCompare != 0) {
            return@sortWith turnRankCompare
        }
        left.order.compareTo(right.order)
    }

    val normalized = ArrayList(entries)
    for (index in legacySlots.indices) {
        normalized[legacySlots[index]] = legacyEntries[index].entry
    }
    return normalized
}

private fun legacyTurnAnchor(entry: AgentRunTimelineEntry): Long? {
    val groupTaskId = entry.group?.taskId
    if (groupTaskId != null) {
        return legacyTurnAnchorFromId(groupTaskId)
    }
    val message = entry.message ?: return null
    return legacyTurnAnchorFromId(message.id) ?: legacyTurnAnchorFromId(message.contentId)
}

private fun legacyTurnNewestFirstRank(entry: AgentRunTimelineEntry): Int {
    if (entry.group != null) {
        return 0
    }
    return if (entry.isUserMessage) 1 else 0
}

private fun legacyTurnAnchorFromId(raw: String?): Long? {
    val id = raw?.dartTrim() ?: ""
    if (id.isEmpty()) {
        return null
    }
    val match = legacyTurnId.find(id)
    return if (match == null) null else dartIntTryParse(match.groupValues[1])
}

// Dart (JS-semantics) `.` excludes only \n, \r,  ,  ; Dart `$`
// (non-multiline) is end-of-input, i.e. Java `\z`.
private const val DART_DOT = "[^\\n\\r\\u2028\\u2029]"

private val legacyTurnId = Regex(
    "^(\\d{13})-(?:user|ai(?:-$DART_DOT+)?|assistant(?:-$DART_DOT+)?|clarify(?:-$DART_DOT+)?" +
        "|permission(?:-$DART_DOT+)?|thinking(?:-$DART_DOT+)?|text(?:-$DART_DOT+)?|tool(?:-$DART_DOT+)?)\\z",
)

/**
 * When a dispatched-but-silent turn started.
 *
 * Prefers the user message minted alongside the dispatch id (`<x>-user` for a
 * `<x>-ai` task), then the newest user message.
 */
private fun pendingRunStartedAt(messages: List<ChatMessageUi>, taskId: String): Long {
    if (taskId.endsWith("-ai")) {
        val expectedUserId = "${taskId.substring(0, taskId.length - 3)}-user"
        for (message in messages) {
            if (message.id == expectedUserId) {
                return message.createAtMillis
            }
        }
    }
    for (message in messages) {
        if (message.user == 1) {
            return message.createAtMillis
        }
    }
    return System.currentTimeMillis()
}

private fun boundaryTimestamp(messages: List<ChatMessageUi>, earliest: Boolean): Long? {
    var boundary: Long? = null
    for (message in messages) {
        val createAt = message.createAtMillis
        if (createAt <= 0) {
            continue
        }
        if (boundary == null || (if (earliest) createAt < boundary else createAt > boundary)) {
            boundary = createAt
        }
    }
    return boundary
}

/**
 * A provider tool id can be reused on a later turn, so a stale tool timestamp
 * is not a reliable boundary. Reasoning/text entries are turn-owned anchors;
 * a tool-only run still falls back to its tool timestamp.
 */
private fun runBoundaryTimestamp(messages: List<ChatMessageUi>, earliest: Boolean): Long? {
    val contentMessages = messages.filter { cardType(it) != "agent_tool_summary" }
    return boundaryTimestamp(contentMessages, earliest = earliest)
        ?: boundaryTimestamp(messages, earliest = earliest)
}

fun agentRunId(message: ChatMessageUi): String? {
    val normalized = message.runId?.dartTrim() ?: ""
    if (normalized.isNotEmpty()) {
        return normalized
    }
    if (message.user == 1) {
        return null
    }
    return agentTaskIdFromEntryId(message.id) ?: agentTaskIdFromEntryId(message.contentId)
}

/** Compatibility alias for old callers and persisted-data adapters. */
fun agentRunParentTaskId(message: ChatMessageUi): String? = agentRunId(message)

fun agentRunKind(message: ChatMessageUi): String {
    return (message.streamMeta?.get("kind") ?: "").toString().dartTrim().lowercase()
}

private fun buildTimelineGroup(
    messages: List<ChatMessageUi>,
    taskId: String,
    isActive: Boolean,
    conversationAgentId: String?,
): AgentRunTimelineGroup? {
    val taskMessages = stabilizeTaskMessagesNewestFirst(
        messages
            .filter { message -> agentRunId(message) == taskId }
            .filter(::isAgentRunCandidateMessage),
    )
    if (taskMessages.isEmpty()) {
        return null
    }

    // Every agent turn is a group, however small.
    val segments = buildSegments(taskMessages)
    if (!segments.any { segment -> !segment.isProcess } &&
        !isActive &&
        !taskMessages.any(::isCancelledTurnItem)
    ) {
        return null
    }

    return AgentRunTimelineGroup(
        taskId = taskId,
        status = if (isActive) {
            AgentRunStatus.running
        } else if (taskMessages.any(::isAgentTurnFailureMessage)) {
            AgentRunStatus.failed
        } else if (taskMessages.any(::isCancelledTurnItem)) {
            AgentRunStatus.cancelled
        } else {
            AgentRunStatus.finished
        },
        agentId = resolveAgentRunAgentId(
            turnMessages = taskMessages,
            conversationAgentId = conversationAgentId,
        ),
        startedAtMillis = runBoundaryTimestamp(taskMessages, earliest = true) ?: System.currentTimeMillis(),
        finishedAtMillis = if (isActive) null else runBoundaryTimestamp(taskMessages, earliest = false),
        segmentsOldestFirst = segments,
    )
}

private class SequencedMessage(
    val message: ChatMessageUi,
    val sequence: Long,
    val order: Int,
)

/**
 * `entrySeq` is allocated once when a streamed entry is created, unlike `seq`.
 * If every entry in a run has a unique stable sequence, use it to recover
 * newest-first order after a snapshot replacement. Mixed/legacy ACP runs keep
 * their list order so prose interleaving remains untouched.
 */
private fun stabilizeTaskMessagesNewestFirst(messages: List<ChatMessageUi>): List<ChatMessageUi> {
    if (messages.size < 2) {
        return messages
    }
    val indexed = ArrayList<SequencedMessage>()
    val seenSequences = HashSet<Long>()
    var hasStableEntrySequences = true
    for (index in messages.indices) {
        val message = messages[index]
        val entrySeq = wholeIntFromDynamic(message.streamMeta?.get("entrySeq"))
        if (entrySeq == null || !seenSequences.add(entrySeq)) {
            hasStableEntrySequences = false
            break
        }
        indexed.add(SequencedMessage(message = message, sequence = entrySeq, order = index))
    }
    // Older persisted ACP snapshots have no entrySeq, but their terminal frames
    // still carry a unique seq. Restore those in newest-first seq order unless
    // some item is not a final frame.
    val finalSequences = ArrayList<Long>()
    var allMessagesAreFinal = true
    for (message in messages) {
        val sequence = wholeIntFromDynamic(message.streamMeta?.get("seq"))
        val isFinal = message.streamMeta?.get("isFinal") == true
        if (sequence == null || !isFinal) {
            allMessagesAreFinal = false
            break
        }
        finalSequences.add(sequence)
    }
    if (allMessagesAreFinal &&
        finalSequences.size == messages.size &&
        finalSequences.toSet().size == messages.size
    ) {
        val ordered = ArrayList<SequencedMessage>()
        for (index in messages.indices) {
            ordered.add(
                SequencedMessage(
                    message = messages[index],
                    sequence = finalSequences[index],
                    order = index,
                ),
            )
        }
        ordered.sortWith { left, right ->
            val sequenceCompare = right.sequence.compareTo(left.sequence)
            if (sequenceCompare != 0) {
                return@sortWith sequenceCompare
            }
            left.order.compareTo(right.order)
        }
        return ordered.map { it.message }
    }
    if (!hasStableEntrySequences) {
        return stabilizePartiallySequencedLegacyTaskNewestFirst(messages)
    }
    indexed.sortWith { left, right ->
        val sequenceCompare = right.sequence.compareTo(left.sequence)
        if (sequenceCompare != 0) {
            return@sortWith sequenceCompare
        }
        left.order.compareTo(right.order)
    }
    return indexed.map { it.message }
}

/**
 * Older Xiaowan snapshots can contain a partially sequenced run. Timestamps are
 * allocated when each entry is first created, so for the built-in task-id shape
 * they remain a stable arrival-order fallback. Opaque ACP task ids keep their
 * reducer-owned list order.
 */
private fun stabilizePartiallySequencedLegacyTaskNewestFirst(
    messages: List<ChatMessageUi>,
): List<ChatMessageUi> {
    val taskIds = messages.mapNotNull(::agentRunId).toSet()
    if (taskIds.size != 1 || !legacyAgentTaskId.containsMatchIn(taskIds.single())) {
        return messages
    }
    val indexed = ArrayList<SequencedMessage>()
    for (index in messages.indices) {
        val message = messages[index]
        val createdAt = message.createAtMillis
        if (createdAt <= 0) {
            return messages
        }
        indexed.add(SequencedMessage(message = message, sequence = createdAt, order = index))
    }
    indexed.sortWith { left, right ->
        val createdAtCompare = right.sequence.compareTo(left.sequence)
        if (createdAtCompare != 0) {
            return@sortWith createdAtCompare
        }
        left.order.compareTo(right.order)
    }
    return indexed.map { it.message }
}

private val legacyAgentTaskId = Regex("^\\d{13}-ai\\z")

private fun wholeIntFromDynamic(value: Any?): Long? {
    when (value) {
        is Int -> return value.toLong()
        is Long -> return value
        is Short -> return value.toLong()
        is Byte -> return value.toLong()
    }
    if (value is Number) {
        val asDouble = value.toDouble()
        if (asDouble.isFinite() && asDouble == kotlin.math.truncate(asDouble)) {
            return value.toLong()
        }
    }
    if (value is String) {
        return dartIntTryParse(value.dartTrim())
    }
    return null
}

/**
 * Slices a turn into what stays in the conversation and what folds away.
 *
 * Each contiguous run of process cards becomes one segment, which keeps two
 * tool batches separated by prose from merging into a single card.
 */
private fun buildSegments(taskMessagesNewestFirst: List<ChatMessageUi>): List<AgentRunTimelineSegment> {
    val segments = ArrayList<AgentRunTimelineSegment>()
    var pendingProcess = ArrayList<ChatMessageUi>()

    fun flushProcess() {
        if (pendingProcess.isEmpty()) {
            return
        }
        segments.add(AgentRunTimelineSegment.process(pendingProcess))
        pendingProcess = ArrayList()
    }

    for (message in taskMessagesNewestFirst.asReversed()) {
        if (isProcessMessage(message)) {
            pendingProcess.add(message)
            continue
        }
        flushProcess()
        segments.add(AgentRunTimelineSegment.visible(message))
    }
    flushProcess()
    return segments.toList()
}

private fun isCancelledTurnItem(message: ChatMessageUi): Boolean =
    message.streamMeta?.get("stopReason") in setOf<Any?>("cancelled", "canceled")

/**
 * Only the owning runtime's terminal failure card denotes a failed turn.
 * An individual tool error may be recovered within the same prompt.
 */
fun isAgentTurnFailureMessage(message: ChatMessageUi): Boolean {
    val card = message.cardData
    return card?.get("type") == "agent_tool_summary" &&
        card["toolType"] == "status" &&
        card["status"] == "error" &&
        agentRunKind(message) == "error"
}

private fun isProcessMessage(message: ChatMessageUi): Boolean {
    val type = cardType(message)
    // A plan is a live ACP snapshot, not disposable tool activity.
    return type == "deep_thinking" ||
        (type == "agent_tool_summary" &&
            !isAgentPlanMessage(message) &&
            !isAgentTurnFailureMessage(message))
}

/**
 * The one rule for "which agent produced this turn".
 *
 * Per-message identity first, then the conversation's bound agent, then a
 * neutral icon.
 */
fun resolveAgentRunAgentId(
    turnMessages: Iterable<ChatMessageUi>,
    conversationAgentId: String? = null,
): String {
    for (message in turnMessages) {
        val agentId = message.agentId?.dartTrim() ?: ""
        if (agentId.isNotEmpty()) {
            return agentId
        }
    }
    val fallback = conversationAgentId?.dartTrim() ?: ""
    return if (fallback.isNotEmpty()) fallback else kGenericAgentId
}

const val kGenericAgentId = "generic-agent"

private fun isAgentRunCandidateMessage(message: ChatMessageUi): Boolean {
    if (message.user == 1) {
        return false
    }
    if (message.type == 1) {
        return message.user == 2
    }
    if (message.type != 2) {
        return false
    }
    val type = cardType(message)
    return type == "deep_thinking" ||
        type == "agent_tool_summary" ||
        type == "permission_section" ||
        isAgentRequestCardType(type)
}

private fun cardType(message: ChatMessageUi): String {
    return (message.cardData?.get("type") ?: "").toString().dartTrim()
}

private fun agentTaskIdFromEntryId(raw: String?): String? {
    val id = raw?.dartTrim() ?: ""
    if (id.isEmpty()) {
        return null
    }
    // Legacy Xiaowan final replies used the run id itself as the message id.
    if (legacyAgentTaskId.containsMatchIn(id)) {
        return id
    }
    val suffixes = listOf(
        "-assistant",
        "-clarify",
        "-permission",
        "-error",
        "-thinking",
        "-text",
    )
    for (suffix in suffixes) {
        if (id.endsWith(suffix)) {
            return id.substring(0, id.length - suffix.length)
        }
    }
    val markers = listOf("-thinking-", "-text-", "-tool-", "-permission-")
    for (marker in markers) {
        val index = id.indexOf(marker)
        if (index > 0) {
            return id.substring(0, index)
        }
    }
    return null
}

// ---------------------------------------------------------------------------
// Dart runtime-semantics helpers.

/** Dart `String.trim()` whitespace set (differs from Kotlin's `trim()`). */
private fun String.dartTrim(): String = trim { c ->
    when (c) {
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', ' ', '\u0085', ' ',
        ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        in ' '..' ' -> true
        else -> false
    }
}

/** Dart `int.tryParse`: optional sign, decimal or `0x` hex, 64-bit. */
private fun dartIntTryParse(source: String): Long? {
    var text = source.dartTrim()
    var negative = false
    if (text.startsWith("+") || text.startsWith("-")) {
        negative = text[0] == '-'
        text = text.substring(1)
    }
    if (text.isEmpty() || text.startsWith("+") || text.startsWith("-")) return null
    val radix = if (text.startsWith("0x") || text.startsWith("0X")) {
        text = text.substring(2)
        16
    } else {
        10
    }
    if (text.isEmpty()) return null
    return (if (negative) "-$text" else text).toLongOrNull(radix)
}

@Suppress("UNCHECKED_CAST")
private fun stringKeyedCopy(map: Map<*, *>): Map<String, Any?> =
    LinkedHashMap<String, Any?>().apply { map.forEach { (k, v) -> put(k.toString(), v) } }

/** Minimal Dart `jsonDecode`: objects -> Map, arrays -> List, numbers -> Int/Long/Double. */
private fun jsonDecode(text: String): Any? = jsonElementToValue(Json.parseToJsonElement(text))

private val jsonNumberPattern = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\z")

private fun jsonElementToValue(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().apply {
        element.forEach { (key, value) -> put(key, jsonElementToValue(value)) }
    }
    is JsonArray -> element.map(::jsonElementToValue)
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.content == "true" -> true
        element.content == "false" -> false
        else -> {
            val literal = element.content
            require(jsonNumberPattern.containsMatchIn(literal)) { "Invalid JSON literal: $literal" }
            if (literal.any { it == '.' || it == 'e' || it == 'E' }) {
                literal.toDouble()
            } else {
                literal.toIntOrNull() ?: literal.toLongOrNull() ?: literal.toDouble()
            }
        }
    }
}
