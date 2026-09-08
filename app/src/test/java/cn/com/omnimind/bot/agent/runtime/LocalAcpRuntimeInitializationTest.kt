package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation

class LocalAcpRuntimeInitializationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `command notification waits for session initialization without creating a turn`() = runBlocking {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delivered = mutableListOf<Map<String, Any?>>()
        val forwarded = CompletableDeferred<Unit>()
        val runtime = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), store,
            prepareLaunchEnvironment = { emptyMap() },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { delivered += it; forwarded.complete(Unit) },
        )
        val lock = runtime.javaClass.getDeclaredField("sessionMutex").run {
            isAccessible = true
            get(runtime) as kotlinx.coroutines.sync.Mutex
        }
        val factory = runtime.javaClass.getDeclaredMethod("operationsFactory").run {
            isAccessible = true
            invoke(runtime) as com.agentclientprotocol.client.ClientOperationsFactory
        }
        val operations = factory.createClientOperations(
            com.agentclientprotocol.model.SessionId("bound-session"),
            mock(com.agentclientprotocol.model.AcpCreatedSessionResponse::class.java),
        )
        try {
            lock.lock()
            val notifying = launch(start = CoroutineStart.UNDISPATCHED) {
                operations.notify(com.agentclientprotocol.model.SessionUpdate.AvailableCommandsUpdate(
                    listOf(com.agentclientprotocol.model.AvailableCommand("compact", "Compact context")),
                ), null)
            }
            assertTrue(delivered.isEmpty())
            assertTrue("The SDK receive loop must remain free to read session/new", notifying.isCompleted)
            lock.unlock()
            withTimeout(5_000) { forwarded.await() }
            assertEquals(1, delivered.size)
            assertEquals("session/update", delivered.single()["method"])
            assertEquals(null, delivered.single()["turnId"])
            assertEquals(false, delivered.single()["hostTurnId"])
        } finally {
            if (lock.isLocked) lock.unlock()
            scope.cancel()
        }
    }

    @Test
    fun `cancelling a real pending initialize releases its unadopted connection`() =
        checkCleanup(timeout = false)

    @Test
    fun `a caller timeout during initialize releases its unadopted connection`() =
        checkCleanup(timeout = true)

    private fun checkCleanup(timeout: Boolean) = runBlocking {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        store.select(AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val initializedRequest = CompletableDeferred<Unit>()
        var closes = 0
        var cleanupContextActive = false
        // Only replace the platform connection. The real runtime owner,
        // Protocol, Client.initialize, cancellation and catch path all run.
        mockConstruction(XiaowanAcpConnection::class.java) { connection, _ ->
            `when`(connection.exitSignal).thenReturn(CompletableDeferred())
            `when`(connection.diagnosticSummary()).thenReturn("")
            `when`(connection.createTransport(scope)).thenReturn(
                StdioTransport(
                    parentScope = scope,
                    ioDispatcher = Dispatchers.Unconfined,
                    input = flow { awaitCancellation() },
                    output = { line ->
                        check(line.contains("initialize"))
                        initializedRequest.complete(Unit)
                    },
                    name = "initialize-cancellation-regression",
                ),
            )
            runBlocking {
                doAnswer { call ->
                    closes++
                    cleanupContextActive = (call.rawArguments.last() as Continuation<*>).context.isActive
                    Unit
                }.`when`(connection).close()
            }
        }.use {
            val runtime = LocalAcpRuntime(
                context, scope, mock(AgentSessionBindingRepository::class.java), store,
                prepareLaunchEnvironment = { emptyMap() },
                buildHandoffContext = { _, _ -> null },
                scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
                onMessage = { error("An initialize cancellation must not synthesize turn events") },
            )
            runtime.javaClass.getDeclaredField("workspaceManager").apply {
                isAccessible = true
                set(runtime, mock(AgentWorkspaceManager::class.java))
            }
            try {
                val connecting = launch(start = CoroutineStart.UNDISPATCHED) {
                    if (timeout) withTimeout(1_000) { runtime.connect() } else runtime.connect()
                }
                withTimeout(5_000) { initializedRequest.await() }
                if (timeout) connecting.join() else connecting.cancelAndJoin()
                assertFalse(runtime.isConnected)
                assertEquals("Unadopted connection leaked", 1, closes)
                assertTrue("Suspending cleanup needs a live cleanup context", cleanupContextActive)
            } finally {
                scope.cancel()
            }
        }
    }
}
