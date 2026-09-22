package cn.com.omnimind.nativeui.home

import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.nativeui.ScheduledConversationTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class DrawerHeadingKind { Scheduled, Pinned, ChatOnly, Date }

internal sealed interface DrawerRow {
    val key: String

    data class Heading(
        override val key: String,
        val kind: DrawerHeadingKind,
        val expanded: Boolean,
        val count: Int,
        val depth: Int = 0,
        val date: LocalDate? = null,
    ) : DrawerRow

    data class Thread(
        val conversation: ConversationSummary,
        val depth: Int = 0,
        val expansionKey: String? = null,
        val expanded: Boolean = false,
        val childCount: Int = 0,
        val taskCount: Int = 0,
    ) : DrawerRow {
        override val key: String get() = conversation.key
    }
}

/** Pure list projection. A row appears once; grouping never creates or changes a conversation. */
internal fun projectDrawerRows(
    conversations: List<ConversationSummary>,
    tasks: List<ScheduledConversationTask>,
    expanded: Map<String, Boolean>,
    query: String,
    archivedOnly: Boolean,
    recentOnly: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DrawerRow> {
    val ordered = conversations.filter { archivedOnly || !recentOnly || !it.archived }.sortedWith(
        compareByDescending<ConversationSummary> { it.updatedAt }
            .thenBy { if (it.mode == "subagent") 1 else 0 }
            .thenByDescending { it.createdAt }.thenByDescending { it.id },
    )
    val search = query.trim()
    // Search matches the existing sidebar policy: archived results can be restored from search.
    if (search.isNotEmpty()) return ordered.filter {
        (!archivedOnly || it.archived) &&
            (it.title.contains(search, true) || it.preview.contains(search, true))
    }.map { DrawerRow.Thread(it) }
    val visible = ordered.filter { it.archived == archivedOnly }
    if (archivedOnly) return visible.map { DrawerRow.Thread(it) }
    val byKey = visible.associateBy { it.key }
    fun parentKey(id: Long?, mode: String?): String? {
        if (id == null) return null
        return if (mode != null) "$mode:$id".takeIf(byKey::containsKey)
        else visible.firstOrNull { it.id == id }?.key
    }
    val tasksByParent = tasks.mapNotNull { task ->
        parentKey(task.parentId, task.parentMode)?.let { it to task }
    }.groupBy({ it.first }, { it.second })
    val taskIds = tasks.map { it.id }.toSet()
    val children = visible.mapNotNull { conversation ->
        val parent = parentKey(conversation.parentId, conversation.parentMode) ?: return@mapNotNull null
        val belongs = conversation.scheduledTaskId?.takeIf(String::isNotBlank)?.let(taskIds::contains)
            ?: tasksByParent.containsKey(parent)
        if (belongs && parent != conversation.key) parent to conversation else null
    }.groupBy({ it.first }, { it.second })
    val parentKeys = (tasksByParent.keys + children.keys).sortedWith(
        compareByDescending<String> { key ->
            maxOf(byKey.getValue(key).updatedAt, children[key].orEmpty().maxOfOrNull { it.updatedAt } ?: 0)
        }.thenByDescending { byKey.getValue(it).createdAt },
    )
    val promotedKeys = parentKeys.toSet() + children.values.flatten().map { it.key }
    // A scheduled parent can itself be linked to another parent. Keep its own group once.
    val childRows = children.mapValues { (_, values) -> values.filterNot { it.key in parentKeys } }
    val remaining = visible.filterNot { it.key in promotedKeys }
    return buildList {
        fun heading(key: String, kind: DrawerHeadingKind, count: Int, depth: Int = 0, date: LocalDate? = null): Boolean {
            val isExpanded = expanded[key] ?: true
            add(DrawerRow.Heading(key, kind, isExpanded, count, depth, date))
            return isExpanded
        }
        if (parentKeys.isNotEmpty() && heading("__home_drawer_scheduled__", DrawerHeadingKind.Scheduled, parentKeys.size + childRows.values.sumOf { it.size })) {
            parentKeys.forEach { key ->
                val parent = byKey.getValue(key)
                val expansionKey = "__home_drawer_scheduled_$key"
                val isExpanded = expanded[expansionKey] ?: true
                val nested = childRows[key].orEmpty()
                add(DrawerRow.Thread(parent, 0, expansionKey, isExpanded, nested.size, tasksByParent[key].orEmpty().size))
                if (isExpanded) nested.forEach { add(DrawerRow.Thread(it, depth = 2)) }
            }
        }
        val pinned = remaining.filter { it.pinned }
        if (pinned.isNotEmpty() && heading("__home_drawer_pinned__", DrawerHeadingKind.Pinned, pinned.size)) {
            pinned.forEach { add(DrawerRow.Thread(it, depth = 1)) }
        }
        fun dateSections(entries: List<ConversationSummary>, namespace: String) {
            entries.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }.forEach { (date, group) ->
                if (heading("__home_drawer_date__${namespace}__$date", DrawerHeadingKind.Date, group.size, 1, date)) {
                    group.forEach { add(DrawerRow.Thread(it, depth = 1)) }
                }
            }
        }
        dateSections(remaining.filter { !it.pinned && it.mode != "chat_only" }, "omni_ai")
        val chatOnly = remaining.filter { !it.pinned && it.mode == "chat_only" }
        if (chatOnly.isNotEmpty() && heading("__home_drawer_chat_only__", DrawerHeadingKind.ChatOnly, chatOnly.size)) {
            dateSections(chatOnly, "chat_only")
        }
    }
}
