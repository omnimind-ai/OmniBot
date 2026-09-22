package cn.com.omnimind.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationProjectionTest {
    private fun conversation(id: Long, updated: Long = id, pinned: Boolean = false, parent: Long? = null) =
        ConversationSummary(id, "Conversation $id", "Preview $id", "agent", updated, pinned, parent)

    @Test fun pinnedConversationsPrecedeRecentWithoutLosingIdentity() {
        val records = listOf(conversation(1, pinned = true), conversation(3), conversation(2))
        assertEquals(listOf(1L, 3L, 2L), visibleConversations(records, "").map { it.id })
        assertEquals(listOf(1L, 3L, 2L), records.map { it.id })
    }

    @Test fun scheduledChildrenRemainSearchableButDoNotBecomeDuplicateRootThreads() {
        val records = listOf(conversation(1), conversation(2, parent = 1))
        assertEquals(listOf(1L), visibleConversations(records, "").map { it.id })
        assertEquals(listOf(2L), visibleConversations(records, "  PREVIEW 2  ").map { it.id })
    }

    @Test fun identicalTimestampsHaveDeterministicOrdering() {
        val records = listOf(conversation(2, updated = 100), conversation(3, updated = 100))
        assertEquals(listOf(3L, 2L), visibleConversations(records, "").map { it.id })
    }
}
