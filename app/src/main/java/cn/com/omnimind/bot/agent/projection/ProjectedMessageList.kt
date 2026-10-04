package cn.com.omnimind.bot.agent.projection

/** Kind of the last mutation, carried in snapshots for row-level UI refresh. */
enum class MessageListMutationKind { none, content, structure }

/**
 * Newest-first message list owned by one runtime; the port of Dart
 * `ObservableChatMessageList` without listener plumbing.
 *
 * Inserting an id that already exists replaces it in place. Each mutation
 * records whether it changed timeline structure (grouping) or only content,
 * and whether it touched a tool-summary card (page chrome). Snapshots carry
 * these revisions so the UI adapter keeps its row-level refresh.
 */
class ProjectedMessageList : AbstractMutableList<ChatMessage>() {
    private val items = ArrayList<ChatMessage>()

    var structureRevision = 0
        private set
    var lastMutationRevision = 0
        private set
    var lastMutationAffectsPageChrome = false
        private set
    var lastMutationKind = MessageListMutationKind.none
        private set

    override val size: Int get() = items.size

    override fun get(index: Int): ChatMessage = items[index]

    fun replaceAllMessages(messages: Iterable<ChatMessage>) {
        val next = canonicalizeChatMessagesById(messages)
        val affectsChrome = batchAffectsPageChrome(items) || batchAffectsPageChrome(next)
        items.clear()
        items.addAll(next)
        recordStructureMutation(affectsChrome)
    }

    override fun set(index: Int, element: ChatMessage): ChatMessage {
        val previous = items[index]
        items[index] = element
        val affectsChrome = messageAffectsPageChrome(previous) || messageAffectsPageChrome(element)
        if (timelineStructureChanged(previous, element)) {
            recordStructureMutation(affectsChrome)
        } else {
            recordContentMutation(affectsChrome)
        }
        return previous
    }

    override fun add(index: Int, element: ChatMessage) {
        val existingIndex = items.indexOfFirst { it.id == element.id && element.id.trim().isNotEmpty() }
        if (existingIndex >= 0) {
            set(existingIndex, element)
            return
        }
        items.add(index, element)
        recordStructureMutation(messageAffectsPageChrome(element))
    }

    override fun addAll(index: Int, elements: Collection<ChatMessage>): Boolean {
        val next = canonicalizeChatMessagesById(elements)
        if (next.isEmpty()) return false
        var insertionIndex = index
        var structureChanged = false
        var affectsChrome = false
        for (message in next) {
            val existingIndex = items.indexOfFirst { it.id == message.id && message.id.trim().isNotEmpty() }
            if (existingIndex >= 0) {
                val previous = items[existingIndex]
                items[existingIndex] = message
                structureChanged = structureChanged || timelineStructureChanged(previous, message)
                affectsChrome = affectsChrome || messageAffectsPageChrome(previous) ||
                    messageAffectsPageChrome(message)
                continue
            }
            items.add(insertionIndex, message)
            insertionIndex += 1
            structureChanged = true
            affectsChrome = affectsChrome || messageAffectsPageChrome(message)
        }
        if (structureChanged) recordStructureMutation(affectsChrome) else recordContentMutation(affectsChrome)
        return true
    }

    override fun addAll(elements: Collection<ChatMessage>): Boolean = addAll(size, elements)

    override fun removeAt(index: Int): ChatMessage {
        val removed = items.removeAt(index)
        recordStructureMutation(messageAffectsPageChrome(removed))
        return removed
    }

    override fun clear() {
        if (items.isEmpty()) return
        val removed = ArrayList(items)
        items.clear()
        recordStructureMutation(batchAffectsPageChrome(removed))
    }

    /** Dart `removeRange(start, end)`: one mutation for the whole range. */
    public override fun removeRange(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val removed = ArrayList(items.subList(fromIndex, toIndex))
        items.subList(fromIndex, toIndex).clear()
        recordStructureMutation(batchAffectsPageChrome(removed))
    }

    /** Dart `removeWhere`: one mutation for the whole batch. */
    override fun removeIf(filter: java.util.function.Predicate<in ChatMessage>): Boolean =
        removeWhere { filter.test(it) }

    fun removeWhere(test: (ChatMessage) -> Boolean): Boolean {
        val removed = ArrayList<ChatMessage>()
        for (index in items.indices.reversed()) {
            if (!test(items[index])) continue
            removed.add(items.removeAt(index))
        }
        if (removed.isEmpty()) return false
        recordStructureMutation(batchAffectsPageChrome(removed))
        return true
    }

    /** Immutable copy for snapshots. */
    fun toList(): List<ChatMessage> = ArrayList(items)

    private fun recordContentMutation(affectsChrome: Boolean) {
        lastMutationRevision += 1
        lastMutationAffectsPageChrome = affectsChrome
        lastMutationKind = MessageListMutationKind.content
    }

    private fun recordStructureMutation(affectsChrome: Boolean) {
        structureRevision += 1
        lastMutationRevision += 1
        lastMutationAffectsPageChrome = affectsChrome
        lastMutationKind = MessageListMutationKind.structure
    }

    private companion object {
        fun batchAffectsPageChrome(messages: Iterable<ChatMessage>): Boolean =
            messages.any { messageAffectsPageChrome(it) }

        fun messageAffectsPageChrome(message: ChatMessage): Boolean =
            message.type == 2 &&
                dartToString(message.cardData?.get("type") ?: "") == AGENT_TOOL_SUMMARY_CARD_TYPE

        /**
         * Whether an in-place replacement changes timeline grouping. Streaming
         * text appends keep all of these fields and stay content mutations.
         */
        fun timelineStructureChanged(previous: ChatMessage, next: ChatMessage): Boolean {
            if (previous.id != next.id || previous.user != next.user || previous.type != next.type ||
                previous.isError != next.isError || previous.createAtMillis != next.createAtMillis
            ) {
                return true
            }
            val previousTaskId = agentRunParentTaskId(previous)
            if (previousTaskId != agentRunParentTaskId(next)) return true
            val previousMeta = previous.streamMeta
            val nextMeta = next.streamMeta
            if ((previousMeta?.containsKey("isFinal") ?: false) != (nextMeta?.containsKey("isFinal") ?: false) ||
                previousMeta?.get("isFinal") != nextMeta?.get("isFinal")
            ) {
                return true
            }
            // seq/roundIndex change on every chunk without regrouping.
            if (agentRunKind(previous) != agentRunKind(next)) return true
            if (timelineCardType(previous) != timelineCardType(next)) return true
            if (previousTaskId != null && isCancelledTaskText(previous) != isCancelledTaskText(next)) {
                return true
            }
            return false
        }

        fun timelineCardType(message: ChatMessage): String =
            dartToString(message.cardData?.get("type") ?: "")!!.trim()
    }
}
