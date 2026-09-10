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

class LocalAcpSessionListStateTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun listReflectsExistingOwnershipBeforeAndAfterCancellation() = runBlocking {
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
        `when`(info.capabilities).thenReturn(AgentCapabilities())
        runtime.javaClass.getDeclaredField("agentInfo").apply { isAccessible = true }.set(runtime, info)
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.javaClass.getDeclaredField("sessions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, ClientSession>
        }
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId("session-one"))
        sessions["session-one"] = session
        suspend fun entry(): Map<*, *> {
            val response = runtime.handleMethod("session/list", emptyMap()) as Map<*, *>
            return (response["sessions"] as List<*>).single() as Map<*, *>
        }
        try {
            assertEquals(true, entry()["loaded"])
            assertEquals(false, entry()["active"])
            ownership.reserve("session-one", "turn-one", "request-one")
            assertEquals(true, entry()["active"])
            assertEquals("turn-one", entry()["activeTurnId"])
            assertEquals("turn-one", ownership.activeTurnId("session-one"))
            ownership.finish("session-one", "turn-one", "cancelled")
            assertEquals(false, entry()["active"])
            assertEquals(true, entry()["loaded"])
            assertNull(entry()["activeTurnId"])
            val archived = cn.com.omnimind.baselib.database.Conversation(id = 7, title = "Task", isArchived = true)
            `when`(repository.getConversationByThreadId("session-one")).thenReturn(archived)
            assertEquals(true, entry()["archived"])
            `when`(repository.getConversationByThreadId("session-one")).thenReturn(archived.copy(isArchived = false))
            assertEquals(false, entry()["archived"])
            verify(repository, never()).setArchived(anyString(), anyBoolean())
        } finally { scope.cancel() }
    }
}
