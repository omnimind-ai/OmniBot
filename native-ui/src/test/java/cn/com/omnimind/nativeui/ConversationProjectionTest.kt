package cn.com.omnimind.nativeui

import cn.com.omnimind.nativeui.home.DrawerRow
import cn.com.omnimind.nativeui.home.projectDrawerRows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ConversationProjectionTest {
    private fun conversation(id: Long, updated: Long = id, pinned: Boolean = false, parent: Long? = null, task: String? = null) =
        ConversationSummary(id, "Conversation $id", "Preview $id", "agent", updated, pinned, parent, scheduledTaskId = task)

    private fun threads(
        records: List<ConversationSummary>,
        tasks: List<ScheduledConversationTask> = emptyList(),
        expanded: Map<String, Boolean> = emptyMap(),
        query: String = "",
        archivedOnly: Boolean = false,
        recentOnly: Boolean = false,
    ) = projectDrawerRows(records, tasks, expanded, query, archivedOnly, recentOnly, ZoneId.of("UTC"))
        .filterIsInstance<DrawerRow.Thread>().map { it.conversation.id }

    @Test fun scheduledGroupsPrecedePinnedAndOrdinaryDatesWithoutDuplicateRows() {
        val records = listOf(conversation(1, pinned = true), conversation(2, parent = 1, task = "task"), conversation(3, pinned = true), conversation(4))
        assertEquals(listOf(1L, 2L, 3L, 4L), threads(records, listOf(ScheduledConversationTask("task", 1, "agent"))))
    }

    @Test fun collapsedParentHidesOnlyItsChildrenAndSearchStillFindsThem() {
        val records = listOf(conversation(1), conversation(2, parent = 1, task = "task"))
        val tasks = listOf(ScheduledConversationTask("task", 1, "agent"))
        val collapsed = mapOf("__home_drawer_scheduled_agent:1" to false)
        assertEquals(listOf(1L), threads(records, tasks, collapsed))
        assertEquals(listOf(2L), threads(records, tasks, collapsed, " PREVIEW 2 "))
    }

    @Test fun deletingScheduleReturnsParentAndRunToOrdinaryHistory() {
        val records = listOf(conversation(1), conversation(2, parent = 1, task = "removed"))
        assertEquals(listOf(2L, 1L), threads(records))
    }

    @Test fun archivedSearchAndArchivePageRespectRecentOnlyIndependently() {
        val records = listOf(conversation(1), conversation(2).copy(archived = true))
        assertEquals(listOf(1L), threads(records))
        assertEquals(listOf(2L), threads(records, query = "Preview 2"))
        assertEquals(emptyList<Long>(), threads(records, query = "Preview 2", recentOnly = true))
        assertEquals(listOf(2L), threads(records, archivedOnly = true, recentOnly = true))
    }

    @Test fun scheduledParentAlsoLinkedAsChildAppearsOnlyOnce() {
        val records = listOf(conversation(1), conversation(2, parent = 1, task = "a"))
        val tasks = listOf(ScheduledConversationTask("a", 1, "agent"), ScheduledConversationTask("b", 2, "agent"))
        val result = threads(records, tasks)
        assertEquals(setOf(1L, 2L), result.toSet())
        assertEquals(2, result.size)
    }
}
