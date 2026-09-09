package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import cn.com.omnimind.baselib.database.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AgentSessionBindingMetadataTest {
    private val context = mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(it) }
    private val repository = AgentSessionBindingRepository(context)
    private val original = Conversation(id = 42, title = "Existing task", mode = "agent", updatedAt = 100,
        contextSummary = "Preserved context", messageCount = 5)

    @Test fun repeatedCatalogSyncPreservesActivityTimestamp() {
        var current = original
        repeat(3) { index ->
            current = repository.buildUpdatedConversation(current, " Existing task ", null, "agent", 200L + index)
            assertEquals(original, current)
        }
    }

    @Test fun changedMetadataUpdatesOnceAndPreservesHistory() {
        val changed = repository.buildUpdatedConversation(original, "New title", true, "agent", 200)
        assertEquals(original.copy(title = "New title", isArchived = true, updatedAt = 200), changed)
        assertEquals(changed, repository.buildUpdatedConversation(changed, "New title", true, "agent", 300))
    }

    @Test fun omittedMetadataDoesNotTouchActivity() {
        assertEquals(original, repository.buildUpdatedConversation(original, null, null, "agent", 200))
    }
}
