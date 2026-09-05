package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentConversationHistoryRepositoryTest {
    @Test
    fun `fork snapshot prefers canonical rows and keeps chronological visible cards`() {
        val snapshot = AgentConversationHistoryRepository.entriesForFork(
            listOf(
                entry("normal-user", "normal", "user_message", 100, 1),
                entry("assistant", "agent", "assistant_message", 200, 2),
                // This is the stale pre-ACP copy of the canonical assistant.
                entry("assistant", "normal", "assistant_message", 200, 3),
                entry("hidden", "agent", "stream_event", 300, 4),
                entry("tool", "agent", "tool_event", 400, 5),
            )
        )

        assertEquals(listOf("normal-user", "assistant", "tool"), snapshot.map { it.entryId })
        assertEquals("agent", snapshot[1].conversationMode)
        assertEquals(3, snapshot.size)
    }

    @Test
    fun `paged merged history advances beyond duplicate compatibility rows`() {
        val canonical = (1..100).map { index ->
            entry(
                entryId = "shared-$index",
                mode = "agent",
                type = "assistant_message",
                createdAt = 1_000L - index,
                id = index.toLong(),
            )
        }
        val legacyCopies = canonical.map { entry ->
            entry.copy(conversationMode = "normal", id = entry.id + 1_000L)
        }
        val legacyOnly = entry(
            entryId = "legacy-only",
            mode = "normal",
            type = "user_message",
            createdAt = 1L,
            id = 2_000L,
        )

        val (page, hasMore) = AgentConversationHistoryRepository.pageConversationEntries(
            entries = canonical + legacyCopies + legacyOnly,
            limit = 50,
            offset = 100,
        )

        assertEquals(listOf("legacy-only"), page.map { it.entryId })
        assertFalse(hasMore)
    }

    private fun entry(
        entryId: String,
        mode: String,
        type: String,
        createdAt: Long,
        id: Long,
    ) = AgentConversationEntry(
        id = id,
        conversationId = 1,
        conversationMode = mode,
        entryId = entryId,
        entryType = type,
        status = AgentConversationHistoryRepository.STATUS_SUCCESS,
        summary = entryId,
        payloadJson = "{}",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
