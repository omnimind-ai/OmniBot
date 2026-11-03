package cn.com.omnimind.bot.agent.runtime

import com.rk.terminal.runtime.TerminalDistribution
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.CloseSessionResponse
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.transport.StdioTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedConstruction
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.`when`
import java.util.concurrent.ConcurrentHashMap

class LocalAcpProviderAccessTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun preparedLaunchOwnsItsGrantOnceAndNeverPrintsCredentials() {
        val access = Access(mutableListOf())
        val environment = mutableMapOf("OPENAI_API_KEY" to "opaque-local-grant")
        val prepared = PreparedAcpLaunch(environment, access)
        environment["OPENAI_API_KEY"] = "changed"
        assertEquals("opaque-local-grant", prepared.environment["OPENAI_API_KEY"])
        assertFalse(prepared.toString().contains("opaque-local-grant"))
        prepared.close()
        prepared.close()
        assertEquals(1, access.closes)
        assertTrue(prepared.isClosed)
    }

    @Test fun configOnlyPreparationDoesNotOwnTheBorrowedProcessGrant() {
        val access = Access(mutableListOf())
        val active = PreparedAcpLaunch(emptyMap(), access)
        PreparedAcpLaunch(mapOf("OPENAI_API_KEY" to access.credentials.apiKey)).close()
        assertEquals(0, access.closes)
        active.close()
        assertEquals(1, access.closes)
    }

    @Test fun capabilityPreparationFailureRevokesTheUnadoptedGrant() = runBlocking {
        val problem = IllegalStateException("capability preparation failed")
        val fixture = fixture { _, access ->
            assertNotNull(access)
            throw problem
        }
        try {
            val failure = runCatching { fixture.runtime.connect(fixture.profile) }.exceptionOrNull()
            assertSame(problem, failure?.cause)
            assertEquals(1, fixture.access.closes)
            assertNull(fixture.runtime.activeProviderAccess())
        } finally { fixture.scope.cancel() }
    }

    @Test fun cancellationAfterCredentialPreparationRevokesTheUnadoptedGrant() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val fixture = fixture { _, _ -> entered.complete(Unit); awaitCancellation() }
        try {
            val connecting = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.runtime.connect(fixture.profile)
            }
            withTimeout(5000) { entered.await() }
            connecting.cancelAndJoin()
            assertEquals(1, fixture.access.closes)
            assertNull(fixture.runtime.activeProviderAccess())
        } finally { fixture.scope.cancel() }
    }

    @Test fun transportConstructionFailureRevokesBeforeClosingTheConnection() = runBlocking {
        val fixture = fixture()
        process(fixture, failTransport = true).use {
            try {
                assertTrue(runCatching { fixture.runtime.connect(fixture.profile) }.isFailure)
                assertEquals(listOf("grant", "process"), fixture.closedResources)
                assertNull(fixture.runtime.activeProviderAccess())
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun cancellingPendingInitializeRevokesBeforeClosingTheConnection() = runBlocking {
        val fixture = fixture()
        process(fixture, answerInitialize = false).use {
            try {
                val connecting = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.runtime.connect(fixture.profile)
                }
                withTimeout(5000) { fixture.initializeRequested.await() }
                connecting.cancelAndJoin()
                assertEquals(listOf("grant", "process"), fixture.closedResources)
                assertNull(fixture.runtime.activeProviderAccess())
                assertFalse(fixture.runtime.isConnected)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun initializedProcessReusesItsGrantUntilExplicitDisconnect() = runBlocking {
        val fixture = fixture()
        process(fixture).use {
            try {
                withTimeout(5000) { fixture.runtime.connect(fixture.profile) }
                assertSame(fixture.access, fixture.runtime.activeProviderAccess())
                assertEquals("alpine", fixture.runtime.connectionDistributionId)
                assertEquals(0, fixture.access.closes)
                fixture.runtime.connect(fixture.profile)
                assertEquals(1, fixture.preparations)
                assertEquals(listOf(AcpLaunchPreparation.PROCESS), fixture.preparationPurposes)
                assertEquals(0, fixture.access.closes)
                fixture.runtime.disconnect()
                fixture.runtime.disconnect()
                assertEquals(listOf("grant", "process"), fixture.closedResources)
                assertNull(fixture.runtime.activeProviderAccess())
                assertNull(fixture.runtime.connectionDistributionId)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun processExitRevokesItsGrantWithoutCreatingAnotherTurn() = runBlocking {
        val fixture = fixture()
        process(fixture).use {
            try {
                withTimeout(5000) { fixture.runtime.connect(fixture.profile) }
                fixture.exit.complete(0)
                withTimeout(5000) {
                    while (fixture.runtime.activeProviderAccess() != null || fixture.closedResources.size < 2) delay(10)
                }
                assertEquals(listOf("grant", "process"), fixture.closedResources)
                assertFalse(fixture.runtime.isConnected)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun closingOneSessionKeepsTheSharedProcessGrantAndOtherSession() = runBlocking {
        val fixture = fixture()
        process(fixture).use {
            try {
                withTimeout(5000) { fixture.runtime.connect(fixture.profile) }
                val first = mock(ClientSession::class.java)
                val second = mock(ClientSession::class.java)
                `when`(first.sessionId).thenReturn(SessionId("first"))
                `when`(second.sessionId).thenReturn(SessionId("second"))
                `when`(first.close()).thenReturn(CloseSessionResponse())
                @Suppress("UNCHECKED_CAST")
                val sessions = fixture.runtime.javaClass.getDeclaredField("sessions").run {
                    isAccessible = true
                    get(fixture.runtime) as ConcurrentHashMap<String, ClientSession>
                }
                sessions["first"] = first
                sessions["second"] = second
                fixture.runtime.handleMethod("session/close", mapOf("sessionId" to "first"))
                assertFalse(sessions.containsKey("first"))
                assertSame(second, sessions["second"])
                assertSame(fixture.access, fixture.runtime.activeProviderAccess())
                assertEquals(0, fixture.access.closes)
                fixture.runtime.disconnect()
                assertEquals(1, fixture.access.closes)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun configurationWriteKeepsItsGrantAliveUntilDisconnectCanAcquireTheLaunchLock() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        process(fixture).use {
            try {
                withTimeout(5000) { fixture.runtime.connect(fixture.profile) }
                val writing = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.runtime.withLaunchConfiguration { access ->
                        assertSame(fixture.access, access)
                        entered.complete(Unit)
                        finishWrite.await()
                        assertEquals(0, fixture.access.closes)
                        fixture.closedResources += "configuration-written"
                        "saved"
                    }
                }
                entered.await()
                val disconnecting = launch(start = CoroutineStart.UNDISPATCHED) { fixture.runtime.disconnect() }
                assertFalse(disconnecting.isCompleted)
                assertEquals(0, fixture.access.closes)
                finishWrite.complete(Unit)
                assertEquals("saved", writing.await())
                withTimeout(5000) { disconnecting.join() }
                assertEquals(listOf("configuration-written", "grant", "process"), fixture.closedResources)
            } finally {
                finishWrite.complete(Unit)
                fixture.scope.cancel()
            }
        }
    }

    @Test fun cancelledConfigurationWriteReleasesTheLockWithoutRevokingTheProcessGrant() = runBlocking {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        process(fixture).use {
            try {
                withTimeout(5000) { fixture.runtime.connect(fixture.profile) }
                val writing = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.runtime.withLaunchConfiguration {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                entered.await()
                writing.cancelAndJoin()
                assertSame(fixture.access, fixture.runtime.activeProviderAccess())
                assertEquals(0, fixture.access.closes)
                withTimeout(5000) { fixture.runtime.disconnect() }
                assertEquals(listOf("grant", "process"), fixture.closedResources)
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun offlineConfigurationIsAllowedWithoutCreatingAProcessGrant() = runBlocking {
        val fixture = fixture()
        try {
            assertEquals("saved", fixture.runtime.withLaunchConfiguration { access ->
                assertNull(access)
                "saved"
            })
            assertEquals(0, fixture.preparations)
            assertEquals(0, fixture.access.closes)
        } finally { fixture.scope.cancel() }
    }

    @Test fun testingOrPreparingAnOwnedRuntimeNeverChangesItsOwner() = runBlocking {
        for (method in listOf("agent/test", "agent/prepare")) {
            val custom = AcpAgentProfile("owned-test-agent", "Owned test agent", command = "fixture-acp")
            val fixture = fixture(profileOverride = custom)
            fixture.seedConnected(custom)
            try {
                val response = withTimeout(5000) {
                    fixture.runtime.handleMethod(method, mapOf("agentId" to custom.id)) as Map<*, *>
                }
                assertEquals(true, response["ok"])
                assertEquals(custom.id, fixture.runtime.activeAgentId())
                assertTrue(fixture.runtime.isConnected)
                assertSame(fixture.access, fixture.runtime.activeProviderAccess())
                assertEquals(0, fixture.preparations)
                assertEquals(0, fixture.access.closes)
            } finally {
                fixture.runtime.disconnect()
                fixture.scope.cancel()
            }
        }
    }

    @Test fun preparationWithoutSwitchingDeclaresProbePurposeAndKeepsTheActiveGrant() = runBlocking {
        val fixture = fixture(beforePrepare = { purpose ->
            assertEquals(AcpLaunchPreparation.PROBE, purpose)
            // Stop before the Android terminal command; the existing live process stays untouched.
            throw IllegalStateException("probe boundary")
        })
        val existing = fixture.profile
        fixture.seedConnected(existing)
        try {
            val result = fixture.runtime.handleMethod("agent/prepare", mapOf("agentId" to fixture.profile.id)) as Map<*, *>
            assertEquals(false, result["ok"])
            assertEquals(listOf(AcpLaunchPreparation.PROBE), fixture.preparationPurposes)
            assertSame(fixture.access, fixture.runtime.activeProviderAccess())
            assertEquals(existing.id, fixture.runtime.activeAgentId())
            assertEquals(0, fixture.access.closes)
        } finally {
            fixture.runtime.disconnect()
            fixture.scope.cancel()
        }
    }

    private fun fixture(
        profileOverride: AcpAgentProfile? = null,
        beforePrepare: ((AcpLaunchPreparation) -> Unit)? = null,
        capability: suspend (AcpAgentProfile, AgentProviderAccess?) -> Boolean = { _, _ -> true },
    ): Fixture {
        val context = acpProfileStoreTestContext(directory.root)
        val store = AcpAgentProfileStore(context)
        val profile = profileOverride?.let(store::save)
            ?: store.list().first { it.id == AcpAgentProfileStore.CODEX_AGENT_ID }
        store.saveHealth(profile.id, AcpAgentHealth(
            status = AcpAgentHealth.STATUS_ONLINE, installed = true,
            preparationRevision = AcpAgentProfileStore.officialRuntime(profile)?.preparationRevision,
        ))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fixture = Fixture(scope, profile, store)
        fixture.runtime = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), store,
            ownerAgentId = profile.id,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, purpose ->
                fixture.preparationPurposes += purpose
                fixture.preparations++
                beforePrepare?.invoke(purpose)
                PreparedAcpLaunch(mapOf("OPENAI_API_KEY" to fixture.access.credentials.apiKey), fixture.access)
            },
            resolveSessionMcpEnabled = capability,
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Provider resource cleanup must not emit an ACP lifecycle event") },
        ).also {
            it.javaClass.getDeclaredField("workspaceManager").apply {
                isAccessible = true
                set(it, mock(AgentWorkspaceManager::class.java))
            }
        }
        return fixture
    }

    private fun process(
        fixture: Fixture,
        answerInitialize: Boolean = true,
        failTransport: Boolean = false,
    ): MockedConstruction<AcpRuntimeConnection> {
        @Suppress("UNCHECKED_CAST")
        val processClass = Class.forName("cn.com.omnimind.bot.agent.runtime.AcpProcessConnection") as Class<AcpRuntimeConnection>
        return mockConstruction(processClass) { connection, _ ->
            var running = false
            `when`(connection.exitSignal).thenReturn(fixture.exit)
            `when`(connection.isRunning).thenAnswer { running }
            `when`(connection.diagnosticSummary()).thenReturn("")
            if (failTransport) {
                `when`(connection.createTransport(fixture.scope)).thenThrow(IllegalStateException("transport construction failed"))
            } else {
                val input = Channel<String>(Channel.UNLIMITED)
                `when`(connection.createTransport(fixture.scope)).thenReturn(StdioTransport(
                    parentScope = fixture.scope, ioDispatcher = Dispatchers.Unconfined,
                    input = input.receiveAsFlow(),
                    output = { line ->
                        val request = Json.parseToJsonElement(line).jsonObject
                        if (request["method"]?.toString() == "\"initialize\"") {
                            fixture.initializeRequested.complete(Unit)
                            if (answerInitialize) input.send(
                                """{"jsonrpc":"2.0","id":${request["id"]},"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[],"agentInfo":{"name":"fixture","version":"1"}}}"""
                            )
                        }
                    },
                    name = "provider-access-lifecycle-test",
                ))
            }
            runBlocking {
                doAnswer { running = true; Unit }.`when`(connection).start()
                doAnswer {
                    assertEquals("Grant must be revoked before process cleanup", 1, fixture.access.closes)
                    fixture.closedResources += "process"
                    running = false
                    Unit
                }.`when`(connection).close()
            }
        }
    }

    private fun Fixture.seedConnected(activeProfile: AcpAgentProfile) {
        val connection = mock(AcpRuntimeConnection::class.java)
        `when`(connection.isRunning).thenReturn(true)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(AgentCapabilities())
        for ((name, value) in mapOf(
            "connection" to connection, "client" to mock(Client::class.java), "agentInfo" to info,
            "activeProfile" to activeProfile, "connectionDistributionId" to "alpine",
            "activePreparedLaunch" to PreparedAcpLaunch(emptyMap(), access),
        )) {
            runtime.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
        }
    }

    private class Fixture(val scope: CoroutineScope, val profile: AcpAgentProfile, val store: AcpAgentProfileStore) {
        val closedResources = mutableListOf<String>()
        val access = Access(closedResources)
        val initializeRequested = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Int?>()
        val preparationPurposes = mutableListOf<AcpLaunchPreparation>()
        var preparations = 0
        lateinit var runtime: LocalAcpRuntime
    }

    private class Access(private val closedResources: MutableList<String>) : AgentProviderAccess {
        override val profile = ModelProviderProfile("oauth-fixture", "ChatGPT", sourceType = "chatgpt_oauth")
        override val credentials = AgentProviderCredentials(
            "http://127.0.0.1:12345/v1", "opaque-local-grant", wireApi = OpenAiWireApi.RESPONSES,
        )
        var closes = 0
        override fun close() { closes++; closedResources += "grant" }
    }
}
