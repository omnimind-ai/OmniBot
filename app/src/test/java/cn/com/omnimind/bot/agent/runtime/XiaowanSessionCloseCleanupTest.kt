package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.model.SessionId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class XiaowanSessionCloseCleanupTest {
    @Test
    fun cancellingCloseStillReleasesSessionResources() = checkClose(cancelOwner = true)

    @Test
    fun concurrentCloseWaitsForTheSameCleanup() = checkClose(cancelOwner = false)

    @Test
    fun failedMcpCleanupCanBeRetriedWithoutAcknowledgingAClosedSession() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val mcp = mock(XiaowanMcpSession::class.java)
        val failure = IllegalStateException("MCP cleanup failed")
        `when`(mcp.close()).thenAnswer { throw failure }.thenReturn(Unit)
        val closed = AtomicBoolean(false)
        var released = 0
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        set("mcpSession", mcp)
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "retry-close" else SessionId("retry-close"))
        set("onClosed", { _: String -> released++; Unit })
        try { session.close(null); fail("Cleanup failure must reach caller") }
        catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
        assertTrue("Partially closed session must reject new prompts", closed.get())
        assertEquals(0, released)
        session.close(null)
        verify(mcp, times(2)).close()
        assertEquals(1, released)
        session.close(null)
        verify(mcp, times(2)).close()
        assertEquals(1, released)
    }

    @Test
    fun mcpCleanupAttemptsAllConnectionsAndReportsFailure() = runBlocking {
        val session = mock(XiaowanMcpSession::class.java, CALLS_REAL_METHODS)
        val first = mock(XiaowanMcpServerConnection::class.java)
        val second = mock(XiaowanMcpServerConnection::class.java)
        val failure = IllegalStateException("first cleanup failed")
        `when`(first.close()).thenAnswer { throw failure }.thenReturn(Unit)
        XiaowanMcpSession::class.java.getDeclaredField("connections").apply {
            isAccessible = true; set(session, listOf(first, second))
        }
        try { session.close(); fail("MCP cleanup must not hide failure") }
        catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
        verify(first).close()
        verify(second).close()
        session.close()
        verify(first, times(2)).close()
        verify(second, times(2)).close()
    }

    private fun checkClose(cancelOwner: Boolean) = runBlocking {
        // Exercise the actual private session close/cancel methods without constructing
        // unrelated Android model/tool dependencies. No alternate lifecycle is modeled.
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val mcp = mock(XiaowanMcpSession::class.java)
        val closed = AtomicBoolean(false)
        var released = 0
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        set("mcpSession", mcp)
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "close-test" else SessionId("close-test"))
        set("onClosed", { _: String -> released++; Unit })
        val stopping = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val prompt = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { stopping.complete(Unit); finish.await() } }
        }
        set("activePromptJob", prompt)
        val closing = launch { session.close(null) }
        var duplicate: Job? = null
        try {
            withTimeout(5000) { stopping.await() }
            if (cancelOwner) {
                closing.cancel()
            } else {
                duplicate = launch(start = CoroutineStart.UNDISPATCHED) { session.close(null) }
                assertFalse("Duplicate close returned before resource cleanup", duplicate.isCompleted)
            }
            finish.complete(Unit)
            withTimeout(5000) { closing.join(); duplicate?.join(); prompt.join() }
            assertTrue(closed.get())
            verify(mcp, times(1)).close()
            assertEquals(1, released)
            session.close(null)
            verify(mcp, times(1)).close()
            assertEquals(1, released)
        } finally {
            finish.complete(Unit)
            closing.cancelAndJoin()
            duplicate?.cancelAndJoin()
            prompt.cancelAndJoin()
        }
    }
}
