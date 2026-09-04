package cn.com.omnimind.bot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LiveAgentBrowserSessionStoreTest {
    private class FakeSession(
        override val workspaceId: String
    ) : AgentBrowserLiveSessionHandle {
        var closeCount: Int = 0
            private set
        var pageTitle: String = "Initial"

        override fun closeSession() {
            closeCount += 1
        }
    }

    @Test
    fun `reuses live session for same workspace`() {
        val store = LiveAgentBrowserSessionStore<FakeSession>()

        val first = store.acquire("conversation_1") {
            FakeSession(workspaceId = "conversation_1")
        }
        val second = store.acquire("conversation_1") {
            FakeSession(workspaceId = "conversation_1")
        }

        assertSame(first, second)
        assertEquals(0, first.closeCount)
    }

    @Test
    fun `keeps independent live sessions when workspace changes`() {
        val store = LiveAgentBrowserSessionStore<FakeSession>()

        val first = store.acquire("conversation_1") {
            FakeSession(workspaceId = "conversation_1")
        }
        val second = store.acquire("conversation_2") {
            FakeSession(workspaceId = "conversation_2")
        }

        assertNotSame(first, second)
        assertEquals(0, first.closeCount)
        assertEquals(0, second.closeCount)
    }

    @Test
    fun `reusing same live session preserves existing tab state`() {
        val store = LiveAgentBrowserSessionStore<FakeSession>()

        val first = store.acquire("conversation_1") {
            FakeSession(workspaceId = "conversation_1")
        }
        first.pageTitle = "Logged In"

        val reused = store.acquire("conversation_1") {
            FakeSession(workspaceId = "conversation_1")
        }

        assertSame(first, reused)
        assertEquals("Logged In", reused.pageTitle)
    }
}
