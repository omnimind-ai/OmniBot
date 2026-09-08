package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
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
        store.select(original.id)
        val persisted = if (rejectSave) original else edited
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val launches = mutableListOf<AcpAgentProfile>()
        val stopBeforeProcessLaunch = IllegalStateException("Test stops at the launch boundary")
        val runtime = LocalAcpRuntime(
            context = context,
            scope = scope,
            bindingRepository = mock(AgentSessionBindingRepository::class.java),
            profileStore = store,
            prepareLaunchEnvironment = {
                launches.add(it)
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
                    runtime.handleMethod("agent/save", mapOf("agent" to edited.toPayload()))
                    fail("The original save error must reach the caller")
                } catch (error: IllegalArgumentException) {
                    assertEquals("Agent name and command are required.", error.message)
                }
            } else {
                val response = runtime.handleMethod(
                    "agent/save", mapOf("agent" to edited.toPayload()),
                ) as Map<*, *>
                assertEquals(edited.command, (response["agent"] as Map<*, *>)["command"])
                assertEquals(edited.environment, (response["agent"] as Map<*, *>)["environment"])
            }
            val reopenedStore = AcpAgentProfileStore(acpProfileStoreTestContext(temporaryFolder.root))
            assertEquals(persisted, reopenedStore.selected())

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
