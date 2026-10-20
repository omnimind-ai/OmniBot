package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpDemoResetCancellationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun resetCancellationUsesOfficialSessionCancelAndWaitsForPromptOwner() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val runtime = LocalAcpRuntime(context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), prepareLaunchEnvironment = { emptyMap() },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Reset cancellation must not synthesize an Agent terminal") },
            turnOwnership = ownership)
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId("live-device-session"))
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.javaClass.getDeclaredField("sessions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, ClientSession>
        }
        @Suppress("UNCHECKED_CAST")
        val executions = runtime.javaClass.getDeclaredField("promptExecutions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, AcpPromptExecution>
        }
        sessions["live-device-session"] = session
        executions["live-device-session"] = AcpPromptExecution(null).also { assertTrue(it.tryStartPrompt()) }
        ownership.reserve("live-device-session", "live-turn", "live-request")
        try {
            runtime.cancelActivePromptsForDemoReset()
            verify(session).cancel()
            assertTrue(runtime.ownsSession("live-device-session"))
            assertEquals("live-turn", ownership.activeTurnId("live-device-session"))
            // Only the existing prompt response owner may finish that turn.
            ownership.finish("live-device-session", "live-turn", "cancelled")
            assertFalse(runtime.hasActiveTurns())
        } finally { scope.cancel() }
    }
}
