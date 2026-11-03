@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.client.GlobalElicitationHandler
import com.agentclientprotocol.common.ClientSessionOperations
import com.agentclientprotocol.model.*
import com.agentclientprotocol.transport.StdioTransport
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpConnectionCallbackTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the initializing connection can complete an official global client request before ready`() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = Channel<String>(Channel.UNLIMITED)
        val initializeId = CompletableDeferred<String>()
        val elicitationEvent = CompletableDeferred<Map<String, Any?>>()
        val officialResponse = CompletableDeferred<Map<String, Any?>>()
        val runtime = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), AcpAgentProfileStore(context),
            ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { message ->
                if (message["method"] == "elicitation/create") elicitationEvent.complete(message)
                else error("Initialize must not emit a turn lifecycle event")
            },
        )
        runtime.setField("workspaceManager", mock(AgentWorkspaceManager::class.java))
        mockConstruction(XiaowanAcpConnection::class.java) { connection, _ ->
            `when`(connection.exitSignal).thenReturn(CompletableDeferred())
            `when`(connection.isRunning).thenReturn(true)
            `when`(connection.diagnosticSummary()).thenReturn("")
            `when`(connection.createTransport(scope)).thenReturn(StdioTransport(
                parentScope = scope, ioDispatcher = Dispatchers.Unconfined,
                input = input.receiveAsFlow(),
                output = { line ->
                    val message = Json.parseToJsonElement(line).jsonObject
                    if (message["method"]?.toString() == "\"initialize\"") {
                        initializeId.complete(message["id"].toString())
                        input.send("""{"jsonrpc":"2.0","id":"init-elicitation","method":"elicitation/create","params":{"requestId":${message["id"]},"mode":"form","requestedSchema":{"type":"object","properties":{}},"message":"Confirm initialization"}}""")
                    } else if (message["id"]?.toString() == "\"init-elicitation\"") {
                        officialResponse.complete(message)
                    }
                },
                name = "initialize-global-client-request",
            ))
        }.use { construction ->
            try {
                val connecting = async(start = CoroutineStart.UNDISPATCHED) { runtime.connect() }
                val event = withTimeout(5_000) { elicitationEvent.await() }
                assertFalse(runtime.isConnected)
                assertFalse(connecting.isCompleted)
                val connection = construction.constructed().single()
                assertSame(connection, runtime.field("connection"))
                runtime.handleMethod("respondToServerRequest", mapOf(
                    "requestId" to event["id"], "response" to mapOf("action" to "cancel"),
                ))
                val reply = withTimeout(5_000) { officialResponse.await() }
                assertNull(reply["error"])
                assertEquals("cancel", (reply["result"] as kotlinx.serialization.json.JsonObject)["action"]?.toString()?.trim('"'))
                input.send("""{"jsonrpc":"2.0","id":${initializeId.await()},"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[],"agentInfo":{"name":"fixture","version":"1"}}}""")
                withTimeout(5_000) { connecting.await() }
                assertTrue(runtime.isConnected)
                assertSame(connection, runtime.field("connection"))
                assertFalse(runtime.hasActiveTurns())
                runtime.disconnect()
            } finally {
                scope.cancel()
                input.close()
            }
        }
    }

    @Test
    fun `callbacks from a disconnected SDK connection cannot mutate a restored session`() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val store = AcpAgentProfileStore(context)
        val repository = mock(AgentSessionBindingRepository::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val messages = mutableListOf<Map<String, Any?>>()
        val registry = AcpServerRequestOwnerRegistry()
        val runtime = LocalAcpRuntime(
            context, scope, repository, store,
            ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            serverRequestOwners = registry,
            onMessage = { messages += it },
        )
        val sessionId = "restored-session"
        val oldConnection = mock(AcpRuntimeConnection::class.java)
        runtime.setField("connection", oldConnection)
        val oldOperations = runtime.operations(sessionId)
        val oldGlobalHandler = runtime.globalHandler(oldConnection)
        val lock = runtime.field("sessionMutex") as Mutex
        try {
            // The SDK callback is accepted while current, then queued behind
            // session/new. Disconnect must invalidate it before this queue runs.
            lock.lock()
            oldOperations.notify(SessionUpdate.AvailableCommandsUpdate(
                listOf(AvailableCommand("old-command", "Old commands")),
            ), null)
            assertTrue(messages.isEmpty())
            runtime.disconnect()
            val currentConnection = mock(AcpRuntimeConnection::class.java)
            runtime.setField("connection", currentConnection)
            val currentOperations = runtime.operations(sessionId)
            val currentSession = mock(ClientSession::class.java)
            `when`(currentSession.sessionId).thenReturn(SessionId(sessionId))
            @Suppress("UNCHECKED_CAST")
            (runtime.field("sessions") as ConcurrentHashMap<String, ClientSession>)[sessionId] = currentSession
            store.saveSessionConfiguration(sessionId, mapOf("acp.config:model" to "\"current-model\""))
            lock.unlock()
            yield()

            oldOperations.notify(SessionUpdate.ConfigOptionUpdate(listOf(modelOption("stale-model"))), null)
            oldOperations.notify(SessionUpdate.SessionInfoUpdate(title = "Stale title"), null)
            val tool = SessionUpdate.ToolCallUpdate(toolCallId = ToolCallId("old-tool"))
            val oldApproval = runCatching {
                oldOperations.requestPermissions(tool, emptyList(), null)
            }.exceptionOrNull()
            assertTrue(oldApproval is IllegalStateException)
            val inputRequest = CreateElicitationRequest(
                scope = ElicitationScope.Session(SessionId(sessionId)),
                mode = ElicitationMode.Form(ElicitationSchema()),
                message = "Confirm the current request",
            )
            val oldElicitation = runCatching {
                oldGlobalHandler.createElicitation(inputRequest)
            }.exceptionOrNull()
            assertTrue(oldElicitation is IllegalStateException)
            assertTrue(messages.isEmpty())
            assertEquals(mapOf("acp.config:model" to "\"current-model\""), store.sessionConfiguration(sessionId))
            assertFalse(mockingDetails(repository).invocations.any { it.method.name == "updateTitle" })
            assertTrue((runtime.field("pendingPermissions") as Map<*, *>).isEmpty())
            assertTrue((runtime.field("pendingElicitations") as Map<*, *>).isEmpty())
            assertFalse(runtime.hasActiveTurns())

            // The matching connection still owns the normal SDK paths.
            currentOperations.notify(SessionUpdate.ConfigOptionUpdate(listOf(modelOption("updated-model"))), null)
            currentOperations.notify(SessionUpdate.SessionInfoUpdate(title = "Current title"), null)
            currentOperations.notify(SessionUpdate.AvailableCommandsUpdate(
                listOf(AvailableCommand("current-command", "Current commands")),
            ), null)
            assertEquals("\"updated-model\"", store.sessionConfiguration(sessionId)["acp.config:model"])
            verify(repository).updateTitle(sessionId, "Current title")
            assertEquals(3, messages.count { it["method"] == "session/update" })

            val approval = async(start = CoroutineStart.UNDISPATCHED) {
                currentOperations.requestPermissions(tool, emptyList(), null)
            }
            val request = messages.single { it["method"] == "session/request_permission" }
            val requestId = request["id"]
            assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, registry.ownerFor(requestId)?.agentId)
            runtime.handleMethod("respondToServerRequest", mapOf(
                "requestId" to requestId,
                "response" to mapOf("decision" to "decline"),
            ))
            assertEquals(RequestPermissionOutcome.Cancelled, approval.await().outcome)
            assertNull(registry.ownerFor(requestId))

            val elicitation = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.globalHandler(currentConnection).createElicitation(inputRequest)
            }
            val input = messages.single { it["method"] == "elicitation/create" }
            runtime.handleMethod("respondToServerRequest", mapOf(
                "requestId" to input["id"],
                "response" to mapOf("action" to "cancel"),
            ))
            assertEquals(ElicitationAction.Cancel, elicitation.await().action)
            assertNull(registry.ownerFor(input["id"]))
            assertFalse(runtime.hasActiveTurns())
        } finally {
            if (lock.isLocked) lock.unlock()
            scope.cancel()
        }
    }

    private fun modelOption(value: String) = SessionConfigOption.Select(
        id = SessionConfigId("model"), name = "Model", category = SessionConfigOptionCategory.MODEL,
        currentValue = SessionConfigValueId(value),
        options = SessionConfigSelectOptions.Flat(listOf(
            SessionConfigSelectOption(SessionConfigValueId(value), value),
        )),
    )

    private suspend fun LocalAcpRuntime.operations(sessionId: String): ClientSessionOperations {
        val factory = javaClass.getDeclaredMethod("operationsFactory").run {
            isAccessible = true
            invoke(this@operations) as ClientOperationsFactory
        }
        return factory.createClientOperations(SessionId(sessionId), mock(AcpCreatedSessionResponse::class.java))
    }

    private fun LocalAcpRuntime.globalHandler(connection: AcpRuntimeConnection): GlobalElicitationHandler =
        javaClass.getDeclaredMethod("globalElicitationHandler", AcpRuntimeConnection::class.java).run {
            isAccessible = true
            invoke(this@globalHandler, connection) as GlobalElicitationHandler
        }

    private fun LocalAcpRuntime.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun LocalAcpRuntime.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
