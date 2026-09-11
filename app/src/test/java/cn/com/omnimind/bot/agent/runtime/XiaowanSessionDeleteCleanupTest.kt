@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.agent.AgentSupport
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.SessionId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class XiaowanSessionDeleteCleanupTest {
    private class Fixture {
        @Suppress("UNCHECKED_CAST")
        val supportType = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSupport") as Class<AgentSupport>
        @Suppress("UNCHECKED_CAST")
        val sessionType = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val support = mock(supportType, CALLS_REAL_METHODS)
        val session = mock(sessionType)
        val sessions = ConcurrentHashMap<String, AgentSession>()
        val id = SessionId("delete-cleanup-test")
        var deletes = 0
        var deleteFailure = false
        init {
            sessions[id.value] = session
            fun set(name: String, value: Any) {
                supportType.getDeclaredField(name).apply { isAccessible = true }.set(support, value)
            }
            set("activeSessions", sessions)
            val owned: suspend (String) -> Boolean = { true }
            val delete: suspend (String) -> Unit = {
                deletes++
                if (deleteFailure) throw IllegalStateException("Session unlink failed")
            }
            set("isXiaowanSession", owned)
            set("deleteSessionCallback", delete)
        }
        suspend fun successfulClose() {
            `when`(session.close(JsonNull)).thenAnswer {
                sessions.remove(id.value, session) // Real session's onClosed callback.
                CloseSessionResponse(JsonNull)
            }
        }
    }

    @Test fun failedCloseRemainsOwnedAndExplicitDeleteRetriesCleanup() = runBlocking {
        val f = Fixture()
        `when`(f.session.close(JsonNull)).thenAnswer { throw IllegalStateException("MCP close failed") }
        val error = runCatching { f.support.deleteSession(f.id, JsonNull) }.exceptionOrNull()
        assertEquals("MCP close failed", error?.message)
        assertSame("Failed cleanup lost its session owner", f.session, f.sessions[f.id.value])
        assertEquals("Binding must remain while cleanup failed", 0, f.deletes)
        // Use doAnswer because evaluating a when() call would execute the prior throwing stub.
        doAnswer {
            f.sessions.remove(f.id.value, f.session)
            CloseSessionResponse(JsonNull)
        }.`when`(f.session).close(JsonNull)
        f.support.deleteSession(f.id, JsonNull)
        verify(f.session, times(2)).close(JsonNull)
        assertFalse(f.sessions.containsKey(f.id.value))
        assertEquals(1, f.deletes)
    }

    @Test fun unlinkFailureRetriesDeletionWithoutReopeningReleasedResources() = runBlocking {
        val f = Fixture(); f.successfulClose(); f.deleteFailure = true
        val error = runCatching { f.support.deleteSession(f.id, JsonNull) }.exceptionOrNull()
        assertEquals("Session unlink failed", error?.message)
        assertFalse(f.sessions.containsKey(f.id.value))
        f.deleteFailure = false
        f.support.deleteSession(f.id, JsonNull)
        verify(f.session, times(1)).close(JsonNull)
        assertEquals(2, f.deletes)
    }
}
