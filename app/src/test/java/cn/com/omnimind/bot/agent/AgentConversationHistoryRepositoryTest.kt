package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import org.junit.Assert.assertEquals
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
