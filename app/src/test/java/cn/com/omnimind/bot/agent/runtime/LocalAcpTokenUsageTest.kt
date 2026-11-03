@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.rk.terminal.runtime.TerminalDistribution
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.baselib.database.TokenUsageRecord
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpTokenUsageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `canonical prompt records official usage once and deduplicates the same request`(): Unit = runBlocking {
        val fixture = fixture()
        try {
            val result = fixture.prompt()
            assertEquals("end_turn", result["status"])
            assertEquals(1, fixture.records.size)
            val repeated = fixture.prompt()
            assertEquals(true, repeated["deduplicated"])
            assertEquals(1, fixture.records.size)
            verify(fixture.session, times(1)).prompt(listOf(ContentBlock.Text("hello")), null)
        } finally { fixture.scope.cancel() }
    }

    @Test fun `native prompt does not duplicate its HTTP usage and absent official usage stays absent`() = runBlocking {
        for ((id, usage) in listOf(
            AcpAgentProfileStore.XIAOWAN_AGENT_ID to reportedUsage(),
            "external-acp" to null,
        )) {
            val fixture = fixture(agentId = id, usage = usage)
            try {
                assertEquals("end_turn", fixture.prompt()["status"])
                assertTrue(fixture.records.isEmpty())
            } finally { fixture.scope.cancel() }
        }
    }

    @Test fun `completion retains admission conversation and model after mutable metadata changes`() = runBlocking {
        val fixture = fixture()
        fixture.beforeResponse = {
            fixture.binding = fixture.binding.copy(conversationId = 99)
            fixture.options.value = modelOptions("later-model")
        }
        try {
            assertEquals("end_turn", fixture.prompt()["status"])
            val record = fixture.records.single()
            assertEquals(42L, record.conversationId)
            assertEquals("prompt-model", record.model)
            assertEquals(75, record.promptTokens)
            assertEquals(7, record.completionTokens)
        } finally { fixture.scope.cancel() }
    }

    @Test fun `a statistics write failure does not fail the official ACP prompt`() = runBlocking {
        val fixture = fixture(writeFailure = IllegalStateException("database unavailable"))
        try {
            val result = fixture.prompt()
            assertEquals("end_turn", result["status"])
            assertNull(result["error"])
        } finally { fixture.scope.cancel() }
    }

    @Test fun `accounting cancellation preserves the official response and usage presentation`() = runBlocking {
        val fixture = fixture(writeFailure = CancellationException("accounting write cancelled"))
        try {
            val result = fixture.prompt()
            assertEquals("end_turn", result["status"])
            assertNull(result["error"])
            val notification = fixture.messages.single { it["method"] == "session/update" }
            val update = (notification["params"] as Map<*, *>)["update"] as Map<*, *>
            assertEquals("agent_message_chunk", update["sessionUpdate"])
            assertNotNull(update["_meta"])
        } finally { fixture.scope.cancel() }
    }

    private suspend fun fixture(
        agentId: String = "external-acp",
        usage: Usage? = reportedUsage(),
        writeFailure: Exception? = null,
    ): Fixture {
        val context = acpProfileStoreTestContext(folder.newFolder())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val store = AcpAgentProfileStore(context)
        store.bindSession("session-usage", agentId)
        val records = mutableListOf<TokenUsageRecord>()
        val messages = mutableListOf<Map<String, Any?>>()
        val runtime = LocalAcpRuntime(
            context, scope, bindings, store,
            ownerAgentId = agentId,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            recordTokenUsage = { record ->
                if (writeFailure != null) throw writeFailure
                records += record
            },
            onMessage = { messages += it },
        )
        val session = mock(ClientSession::class.java)
        val options = MutableStateFlow(modelOptions("prompt-model"))
        `when`(session.sessionId).thenReturn(SessionId("session-usage"))
        `when`(session.configOptionsSupported).thenReturn(true)
        `when`(session.configOptions).thenReturn(options)
        val connection = mock(AcpRuntimeConnection::class.java)
        `when`(connection.exitSignal).thenReturn(CompletableDeferred())
        `when`(connection.materializesPromptAttachments).thenReturn(true)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(xiaowanAgentCapabilities())
        runtime.setField("agentInfo", info)
        runtime.setField("activeProfile", profile(agentId))
        runtime.setField("connection", connection)
        @Suppress("UNCHECKED_CAST")
        (runtime.field("sessions") as ConcurrentHashMap<String, ClientSession>)["session-usage"] = session
        val fixture = Fixture(runtime, scope, session, options, records, messages)
        `when`(bindings.getBindingByConversationId(42)).thenReturn(fixture.binding)
        `when`(bindings.getBindingByThreadId("session-usage")).thenAnswer { fixture.binding }
        `when`(session.prompt(listOf(ContentBlock.Text("hello")), null)).thenReturn(flow {
            fixture.beforeResponse()
            emit(Event.PromptResponseEvent(PromptResponse(stopReason = StopReason.END_TURN, usage = usage)))
            error("The prompt owner must stop at its first official response")
        })
        return fixture
    }

    private class Fixture(
        val runtime: LocalAcpRuntime,
        val scope: CoroutineScope,
        val session: ClientSession,
        val options: MutableStateFlow<List<SessionConfigOption>>,
        val records: List<TokenUsageRecord>,
        val messages: List<Map<String, Any?>>,
    ) {
        var binding = AgentSessionBinding(42, "session-usage", "/workspace", 1, 1)
        var beforeResponse: () -> Unit = {}
        suspend fun prompt() = runtime.handleMethod("session/prompt", mapOf(
            "conversationId" to 42L, "requestId" to "usage-request", "text" to "hello",
        )) as Map<*, *>
    }

    private fun profile(id: String) = AcpAgentProfile(id = id, name = id, command = "test")
    private fun reportedUsage() = Usage(inputTokens = 40, outputTokens = 7, totalTokens = 82,
        cachedReadTokens = 30, cachedWriteTokens = 5)
    private fun modelOptions(model: String): List<SessionConfigOption> = listOf(SessionConfigOption.Select(
        id = SessionConfigId("model"), name = "Model", category = SessionConfigOptionCategory.MODEL,
        currentValue = SessionConfigValueId(model),
        options = SessionConfigSelectOptions.Flat(listOf(SessionConfigSelectOption(SessionConfigValueId(model), model))),
    ))
    private fun LocalAcpRuntime.field(name: String): Any? = javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(this)
    private fun LocalAcpRuntime.setField(name: String, value: Any) = javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(this, value)
}
