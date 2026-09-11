package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpSessionDeleteTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun deleteUsesOfficialRequestAndNeverClosesAnAlreadyDeletedSession() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val repository = mock(AgentSessionBindingRepository::class.java)
        val runtime = LocalAcpRuntime(context, scope, repository,
            AcpAgentProfileStore(context), prepareLaunchEnvironment = { emptyMap() },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Listing must not emit lifecycle events") }, turnOwnership = ownership)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(xiaowanAgentCapabilities())
        runtime.javaClass.getDeclaredField("agentInfo").apply { isAccessible = true }.set(runtime, info)
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.javaClass.getDeclaredField("sessions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, ClientSession>
        }
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId("session-one"))
        sessions["session-one"] = session
        val client = mock(com.agentclientprotocol.client.Client::class.java)
        runtime.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(runtime, client)
        `when`(repository.getBindingByThreadId("session-one")).thenReturn(
            cn.com.omnimind.baselib.database.AgentSessionBinding(7, "session-one", "/workspace", 1, 1)
        )
        // Xiaowan's acknowledged delete callback may already detach this binding.
        `when`(repository.detachThread("session-one")).thenReturn(null)
        val failure = IllegalStateException("Agent rejected deletion")
        `when`(client.deleteSession(SessionId("session-one"))).thenThrow(failure)
            .thenReturn(com.agentclientprotocol.model.DeleteSessionResponse())
        try {
            ownership.reserve("session-one", "active-turn", "request")
            try {
                runtime.handleMethod("session/delete", mapOf("sessionId" to "session-one"))
                fail("An active turn must not be deleted")
            } catch (error: IllegalStateException) { assertTrue(error.message.orEmpty().contains("running")) }
            verify(client, never()).deleteSession(SessionId("session-one"))
            ownership.finish("session-one", "active-turn", "cancelled")
            try {
                runtime.handleMethod("session/delete", mapOf("sessionId" to "session-one"))
                fail("Delete failure must reach caller")
            } catch (error: IllegalStateException) { assertSame(failure, error) }
            assertSame(session, sessions["session-one"])
            verify(repository, never()).detachThread("session-one")
            val response = runtime.handleMethod("session/delete", mapOf("sessionId" to "session-one")) as Map<*, *>
            assertEquals(true, response["historyPreserved"])
            assertEquals(7L, response["conversationId"])
            assertNull(sessions["session-one"])
            verify(client, times(2)).deleteSession(SessionId("session-one"))
            verify(session, never()).close()
            verify(repository).detachThread("session-one")
            `when`(info.capabilities).thenReturn(AgentCapabilities())
            try {
                runtime.handleMethod("session/delete", mapOf("sessionId" to "unsupported"))
                fail("Unsupported deletion must not be sent")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("does not advertise"))
            }
            verify(client, times(2)).deleteSession(SessionId("session-one"))
            verify(client, never()).deleteSession(SessionId("unsupported"))
            Unit
        } finally { scope.cancel() }
    }
}
