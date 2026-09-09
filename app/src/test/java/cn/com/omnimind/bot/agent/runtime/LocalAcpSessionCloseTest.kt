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

class LocalAcpSessionCloseTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedClosePreservesSessionUntilAConfirmedClose() = checkClose("session/close")
    @Test fun failedArchivePreservesSessionUntilAConfirmedClose() = checkClose("session/archive")

    private fun checkClose(method: String) = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val runtime = LocalAcpRuntime(context, scope, mock(AgentSessionBindingRepository::class.java),
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
        val failure = IllegalStateException("Agent rejected close")
        `when`(session.close()).thenThrow(failure).thenReturn(com.agentclientprotocol.model.CloseSessionResponse())
        try {
            try {
                runtime.handleMethod(method, mapOf("sessionId" to "session-one"))
                fail("Close failure must reach caller")
            } catch (error: IllegalStateException) { assertSame(failure, error) }
            assertSame("Failed close must retain the same session", session, sessions["session-one"])
            val response = runtime.handleMethod(method, mapOf("sessionId" to "session-one")) as Map<*, *>
            assertEquals(true, response[if (method == "session/close") "closed" else "ok"])
            assertNull(sessions["session-one"])
            verify(session, times(2)).close()
            Unit
        } finally { scope.cancel() }
    }
}
