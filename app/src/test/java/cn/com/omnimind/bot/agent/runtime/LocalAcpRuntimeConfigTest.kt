package cn.com.omnimind.bot.agent.runtime

import com.rk.terminal.runtime.TerminalDistribution
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.ConcurrentHashMap

class LocalAcpRuntimeConfigTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `saving launch edits preserves the connected process and its pending prompt`() =
        checkConfigEdit()

    @Test
    fun `a rejected launch edit leaves the existing prompt and connection usable`() =
        checkConfigEdit(rejectSave = true)

    @Test
    fun `clearing launch arguments and environment does not interrupt the current prompt`() =
        checkConfigEdit(clearOptions = true)

    @Test
    fun `accepted idle config survives a new runtime before any prompt and stays session scoped`() = runBlocking {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AcpAgentProfileStore(context)
        fun runtime() = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java), onMessage = {},
        )
        fun options(effort: String) = listOf<SessionConfigOption>(SessionConfigOption.Select(
            id = SessionConfigId("provider-thinking"), name = "Thinking",
            category = SessionConfigOptionCategory.THOUGHT_LEVEL,
            currentValue = SessionConfigValueId(effort),
            options = SessionConfigSelectOptions.Flat(listOf("off", "high").map {
                SessionConfigSelectOption(SessionConfigValueId(it), it)
            }),
        ))
        val writes = mutableListOf<String>()
        fun session(id: String): Pair<ClientSession, MutableStateFlow<List<SessionConfigOption>>> {
            val session = mock(ClientSession::class.java)
            val config = MutableStateFlow(options("high"))
            `when`(session.sessionId).thenReturn(SessionId(id))
            `when`(session.configOptionsSupported).thenReturn(true)
            `when`(session.configOptions).thenReturn(config)
            return session to config
        }
        suspend fun configure(session: ClientSession, state: MutableStateFlow<List<SessionConfigOption>>) {
            `when`(session.setConfigOption(SessionConfigId("provider-thinking"),
                SessionConfigOptionValue.StringValue("off"))).thenAnswer {
                writes += session.sessionId.value
                state.value = options("off")
                SetSessionConfigOptionResponse(state.value)
            }
        }
        try {
            val first = runtime()
            val (original, originalState) = session("selected-session")
            configure(original, originalState)
            first.registerForTest(original)
            first.handleMethod("session/set_config_option", mapOf(
                "sessionId" to "selected-session", "configId" to "provider-thinking", "value" to "off",
            ))
            // Simulate the Harness restoring its last executed (high) value.
            val reopened = runtime()
            val saved = store.sessionConfiguration("selected-session")
            val (rejected, _) = session("selected-session")
            `when`(rejected.setConfigOption(SessionConfigId("provider-thinking"),
                SessionConfigOptionValue.StringValue("off")))
                .thenThrow(IllegalStateException("configuration rejected"))
            try {
                reopened.registerForTest(rejected)
                fail("A rejected restore must not expose the stale session as ready")
            } catch (error: java.lang.reflect.InvocationTargetException) {
                assertEquals("configuration rejected", error.cause?.message)
            }
            assertFalse((reopened.field("sessions") as Map<*, *>).containsKey("selected-session"))
            assertEquals(saved, store.sessionConfiguration("selected-session"))
            val (restored, restoredState) = session("selected-session")
            configure(restored, restoredState)
            reopened.registerForTest(restored)
            assertEquals("off", (restoredState.value.single() as SessionConfigOption.Select).currentValue.value)
            val (other, otherState) = session("other-session")
            reopened.registerForTest(other)
            assertEquals("high", (otherState.value.single() as SessionConfigOption.Select).currentValue.value)
            assertEquals(listOf("selected-session", "selected-session"), writes)
            store.unbindSession("selected-session")
            assertTrue(store.sessionConfiguration("selected-session").isEmpty())
        } finally { scope.cancel() }
    }

    private suspend fun LocalAcpRuntime.registerForTest(session: ClientSession) =
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = javaClass.getDeclaredMethod("registerSession", ClientSession::class.java,
                String::class.java, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, session, "/workspace", continuation)
            if (result === COROUTINE_SUSPENDED) result else Unit
        }

    private fun checkConfigEdit(
        rejectSave: Boolean = false,
        clearOptions: Boolean = false,
    ) = runBlocking {
        val original = AcpAgentProfile(
            id = "user-agent",
            name = "My Agent",
            command = "original-acp",
            environment = mapOf("CUSTOM_OPTION" to "original"),
        )
        val edited = original.copy(
            command = if (rejectSave) "" else "replacement-acp",
            arguments = if (clearOptions) emptyList() else
                listOf("--config", "/workspace/my config.json"),
            environment = if (clearOptions) emptyMap() else mapOf("CUSTOM_OPTION" to "updated"),
        )
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        store.save(original)
        val persisted = if (rejectSave) original else edited
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val launches = mutableListOf<AcpAgentProfile>()
        val stopBeforeProcessLaunch = IllegalStateException("Test stops at the launch boundary")
        val runtime = LocalAcpRuntime(
            context = context,
            scope = scope,
            bindingRepository = mock(AgentSessionBindingRepository::class.java),
            profileStore = store,
            ownerAgentId = original.id,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { profile, _ ->
                launches.add(profile)
                throw stopBeforeProcessLaunch
            },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Saving must not emit ACP events") },
        )
        val connection = mock(AcpRuntimeConnection::class.java)
        `when`(connection.isRunning).thenReturn(true)
        // Seed an already-connected process without starting Android/PRoot.
        // Reflection stays in this test: no production lifecycle/test hook.
        runtime.setField("connection", connection)
        runtime.setField("client", mock(Client::class.java))
        runtime.setField("agentInfo", mock(AgentInfo::class.java))
        runtime.setField("activeProfile", original)
        runtime.setField("connectionDistributionId", "alpine")
        runtime.setField("activeLaunchEnvironment", original.environment)
        runtime.setField("workspaceManager", mock(AgentWorkspaceManager::class.java))
        val promptJob = Job()
        val execution = AcpPromptExecution(null).apply {
            attachPromptJob(promptJob)
            check(tryStartPrompt())
        }
        @Suppress("UNCHECKED_CAST")
        val executions = runtime.field("promptExecutions") as
            ConcurrentHashMap<String, AcpPromptExecution>
        executions["session/turn"] = execution
        try {
            assertTrue(runtime.isConnected)
            if (rejectSave) {
                try {
                    store.save(edited)
                    fail("The original save error must reach the caller")
                } catch (error: IllegalArgumentException) {
                    assertEquals("Agent name and command are required.", error.message)
                }
            } else {
                val response = store.save(edited)
                assertEquals(edited.command, response.command)
                assertEquals(edited.environment, response.environment)
            }
            val reopenedStore = AcpAgentProfileStore(acpProfileStoreTestContext(temporaryFolder.root))
            assertEquals(persisted, reopenedStore.requireProfile(original.id))

            assertFalse("Saving settings cancelled the pending prompt", promptJob.isCancelled)
            assertTrue(runtime.isConnected)
            assertSame(connection, runtime.field("connection"))
            assertSame(original, runtime.field("activeProfile"))
            assertEquals(original.environment, runtime.field("activeLaunchEnvironment"))

            // A normal request to reuse this Agent must not restart it merely
            // because its saved launch command changed.
            runtime.connect()
            assertSame(original, runtime.field("activeProfile"))
            assertFalse(promptJob.isCancelled)
            assertTrue(launches.isEmpty())

            // Explicit disconnect still cancels the old execution. The next
            // connect reads persisted launch settings, not activeProfile.
            runtime.disconnect()
            assertTrue(promptJob.isCancelled)
            assertFalse(runtime.isConnected)
            try {
                runtime.connect()
                fail("The test launch boundary must be reached")
            } catch (error: IllegalStateException) {
                assertEquals(listOf(persisted), launches)
                assertSame(stopBeforeProcessLaunch, error.cause)
            }
        } finally {
            promptJob.cancel()
            scope.cancel()
        }
    }

    private fun LocalAcpRuntime.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun LocalAcpRuntime.field(name: String): Any? =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)
}
