package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.ConversationDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

/** Executes the production DAO merge policy; SQL persistence is covered by
 * ConversationCheckpointTest's in-memory Room tests. */
class ConversationMetadataOwnershipTest {
    @Test fun partialUiSnapshotPreservesCanonicalCountAndActivityTime() = runBlocking {
        val current = conversation()
        val incoming = current.copy(
            messageCount = 12,
            lastMessage = "truncated display preview",
            updatedAt = 9000,
            summary = "new summary",
        )
        val actual = update(current, incoming, preserveLatestMetadata = true)
        assertEquals(500, actual.messageCount)
        assertEquals("canonical latest message", actual.lastMessage)
        assertEquals(1000L, actual.updatedAt)
        assertEquals("new summary", actual.summary)
    }

    @Test fun nativeHistoryRefreshCanReduceCountAndWinsOverAnOlderUiPage() = runBlocking {
        val stalePage = conversation()
        val refreshed = update(
            stalePage,
            stalePage.copy(messageCount = 3, lastMessage = "remaining message", updatedAt = 700),
            preserveLatestMetadata = false,
        )
        assertEquals(3, refreshed.messageCount)
        assertEquals("remaining message", refreshed.lastMessage)
        assertEquals(700L, refreshed.updatedAt)
        val afterLatePage = update(refreshed, stalePage, preserveLatestMetadata = true)
        assertEquals(refreshed, afterLatePage)
    }

    private suspend fun update(
        current: Conversation,
        incoming: Conversation,
        preserveLatestMetadata: Boolean,
    ): Conversation {
        val dao = Mockito.mock(ConversationDao::class.java)
        Mockito.`when`(dao.getById(current.id)).thenReturn(current)
        Mockito.doCallRealMethod().`when`(dao).updatePreservingCheckpoint(incoming, preserveLatestMetadata)
        dao.updatePreservingCheckpoint(incoming, preserveLatestMetadata)
        return Mockito.mockingDetails(dao).invocations.single { it.method.name == "update" }
            .arguments[0] as Conversation
    }

    private fun conversation() = Conversation(
        id = 9,
        title = "canonical conversation",
        messageCount = 500,
        lastMessage = "canonical latest message",
        createdAt = 10,
        updatedAt = 1000,
    )
}
