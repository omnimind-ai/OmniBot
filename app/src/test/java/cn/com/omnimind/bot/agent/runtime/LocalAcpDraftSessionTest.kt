package cn.com.omnimind.bot.agent.runtime

import com.rk.terminal.runtime.TerminalDistribution
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class LocalAcpDraftSessionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun configuringAnUnsentSessionDoesNotCreateHistoryAndTheFirstPromptKeepsItsConfiguration() = runBlocking {
        val f = fixture()
        val session = f.session("draft")
        val options = MutableStateFlow(listOf<SessionConfigOption>(modelOption("one")))
        `when`(session.configOptionsSupported).thenReturn(true)
        `when`(session.configOptions).thenReturn(options)
        `when`(session.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("two")))
            .thenAnswer { options.value = listOf(modelOption("two")); SetSessionConfigOptionResponse(options.value) }
        f.runtime.setField("catalogSessionId", "draft")
        try {
            val created = f.runtime.handleMethod("session/new", emptyMap()) as Map<*, *>
            assertEquals("draft", created["sessionId"])
            assertNull(created["conversationId"])
            f.runtime.handleMethod("session/set_config_option", mapOf(
                "sessionId" to "draft", "configId" to "model", "value" to "two",
            ))
            val loaded = f.runtime.handleMethod("session/load", mapOf("sessionId" to "draft")) as Map<*, *>
            assertNull(loaded["conversationId"])
            assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })

            `when`(f.repository.ensureBinding("draft", AcpAgentProfileStore.XIAOWAN_AGENT_ID, 42L, "/workspace", conversationMode = "agent"))
                .thenReturn(42L)
            val admitted = f.runtime.resolveForPrompt(mapOf("threadId" to "draft", "conversationId" to 42L))
            assertSame(session, admitted)
            assertEquals("two", (options.value.single() as SessionConfigOption.Select).currentValue.value)
            verify(f.repository).ensureBinding("draft", AcpAgentProfileStore.XIAOWAN_AGENT_ID, 42L, "/workspace", conversationMode = "agent")
            assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, f.store.agentIdForConversation(42L))
        } finally { f.scope.cancel() }
    }

    @Test fun conflictingOrUnknownExplicitSessionNeverCreatesAReplacement() = runBlocking {
        val f = fixture()
        val old = f.session("old")
        val fresh = f.session("fresh")
        f.runtime.setField("catalogSessionId", "fresh")
        f.store.bindSession("old", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        f.store.bindConversation(41L, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        `when`(f.repository.getBindingByThreadId("old"))
            .thenReturn(AgentSessionBinding(41L, "old", "/workspace", 1L, 1L))
        try {
            for (id in listOf("old", "unknown")) {
                for (method in listOf("session/prompt", "session/load", "session/resume")) {
                    val failure = runCatching { f.runtime.handleMethod(method, mapOf(
                        "sessionId" to id, "conversationId" to 42L, "text" to "hello",
                    )) }.exceptionOrNull()
                    assertTrue(failure is IllegalArgumentException)
                    assertTrue(failure!!.message.orEmpty().startsWith("agent_owner_conflict:"))
                }
            }
            assertSame(old, f.runtime.sessions()["old"])
            assertSame(fresh, f.runtime.sessions()["fresh"])
            assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, f.store.agentIdForConversation(41L))
            assertNull(f.store.agentIdForConversation(42L))
            assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })
        } finally { f.scope.cancel() }
    }

    @Test fun anotherAgentsPersistedBindingCannotFallThroughToNewSession() = runBlocking {
        val f = fixture()
        f.session("fresh")
        f.runtime.setField("catalogSessionId", "fresh")
        f.store.bindSession("foreign", AcpAgentProfileStore.CODEX_AGENT_ID)
        f.store.bindConversation(42L, AcpAgentProfileStore.CODEX_AGENT_ID)
        val binding = AgentSessionBinding(42L, "foreign", "/workspace", 1L, 1L)
        `when`(f.repository.getBindingByConversationId(42L)).thenReturn(binding)
        `when`(f.repository.getBindingByThreadId("foreign")).thenReturn(binding)
        try {
            val failure = runCatching { f.runtime.handleMethod("session/prompt", mapOf(
                "conversationId" to 42L, "text" to "hello",
            )) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().startsWith("agent_owner_conflict:"))
            assertEquals(AcpAgentProfileStore.CODEX_AGENT_ID, f.store.agentIdForConversation(42L))
            assertEquals(AcpAgentProfileStore.CODEX_AGENT_ID, f.store.agentIdForSession("foreign"))
            assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })
        } finally { f.scope.cancel() }
    }

    @Test fun failedOfficialRestorePreservesTheOriginalErrorOwnerAndHistoryWithoutNewSessionOrPrompt() = runBlocking {
        for (method in listOf("session/load", "session/resume", "session/prompt")) {
            val f = fixture()
            val original = IllegalStateException("unknown session: old")
            var restores = 0
            var newSessions = 0
            val client = mock(Client::class.java) { invocation ->
                // SessionId is an inline class: the SDK's JVM load/resume
                // method names carry a compiler-generated suffix.
                when (invocation.method.name.substringBefore('-')) {
                    "resumeSession", "loadSession" -> {
                        assertEquals("old", invocation.arguments.first())
                        restores++
                        throw original
                    }
                    "newSession" -> { newSessions++; error("Restore must not create a replacement session") }
                    else -> RETURNS_DEFAULTS.answer(invocation)
                }
            }
            val info = mock(AgentInfo::class.java)
            `when`(info.capabilities).thenReturn(AgentCapabilities(
                sessionCapabilities = SessionCapabilities(resume = SessionResumeCapabilities()),
            ))
            f.runtime.setField("client", client)
            f.runtime.setField("agentInfo", info)
            f.runtime.setSessionMcpEnabled(false)
            val binding = AgentSessionBinding(42L, "old", "/workspace", 1L, 1L)
            `when`(f.repository.getBindingByThreadId("old")).thenReturn(binding)
            `when`(f.repository.getBindingByConversationId(42L)).thenReturn(binding)
            f.store.bindSession("old", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
            f.store.bindConversation(42L, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
            f.store.saveSessionConfiguration("old", mapOf("model" to "original-model"))
            try {
                val failure = runCatching { f.runtime.handleMethod(method, mapOf(
                    "sessionId" to "old", "conversationId" to 42L, "text" to "hello",
                )) }.exceptionOrNull()
                assertSame(original, failure)
                assertEquals(1, restores)
                assertEquals(0, newSessions)
                assertTrue(f.runtime.sessions().isEmpty())
                assertFalse(f.runtime.hasActiveTurns())
                assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })
                assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, f.store.agentIdForSession("old"))
                assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, f.store.agentIdForConversation(42L))
                assertEquals(mapOf("model" to "original-model"), f.store.sessionConfiguration("old"))
            } finally { f.scope.cancel() }
        }
    }

    @Test fun explicitSessionWithoutConversationUsesOfficialLoadAndKeepsItsIdentity() = runBlocking {
        val f = fixture()
        val restored = f.session("unbound-restored")
        f.runtime.sessions().remove("unbound-restored")
        var loads = 0
        var newSessions = 0
        val client = mock(Client::class.java) { invocation ->
            when (invocation.method.name.substringBefore('-')) {
                "loadSession" -> {
                    assertEquals("unbound-restored", invocation.arguments.first())
                    loads++
                    restored
                }
                "newSession" -> { newSessions++; error("An explicit session must not be replaced") }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(AgentCapabilities(loadSession = true))
        f.runtime.setField("client", client)
        f.runtime.setField("agentInfo", info)
        f.runtime.setSessionMcpEnabled(false)
        try {
            val response = f.runtime.handleMethod("session/load", mapOf(
                "sessionId" to "unbound-restored", "agentId" to AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            )) as Map<*, *>
            assertEquals("unbound-restored", response["sessionId"])
            assertEquals(1, loads)
            assertEquals(0, newSessions)
            assertNull(response["conversationId"])
            assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })
            assertEquals(AcpAgentProfileStore.XIAOWAN_AGENT_ID, f.store.agentIdForSession("unbound-restored"))
        } finally { f.scope.cancel() }
    }

    @Test fun listingAfterRuntimeRecreationDoesNotImportUnsentSessionsButStillImportsExternalHistory() = runBlocking {
        val f = fixture()
        f.store.bindSession("draft", AcpAgentProfileStore.XIAOWAN_AGENT_ID)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(AgentCapabilities(sessionCapabilities = SessionCapabilities(list = SessionListCapabilities())))
        f.runtime.setField("agentInfo", info)
        val client = mock(Client::class.java)
        `when`(client.listSessions(null, emptyList())).thenReturn(flowOf(
            SessionInfo(SessionId("draft"), "/workspace", title = "Unsent"),
            SessionInfo(SessionId("external"), "/workspace", title = "Existing task"),
        ))
        f.runtime.setField("client", client)
        try {
            f.runtime.handleMethod("session/list", emptyMap())
            val createdFor = mockingDetails(f.repository).invocations
                .filter { it.method.name == "ensureBinding" }.map { it.arguments[0] }
            assertEquals(listOf("external"), createdFor)
        } finally { f.scope.cancel() }
    }

    @Test fun anotherAgentsSessionListCannotTakeAnExistingConversationOwner() = runBlocking {
        val f = fixture()
        val sessionId = "owned-by-codex"
        f.store.bindSession(sessionId, AcpAgentProfileStore.CODEX_AGENT_ID)
        f.store.bindConversation(42L, AcpAgentProfileStore.CODEX_AGENT_ID)
        f.store.saveSessionConfiguration(sessionId, mapOf("model" to "saved-model"))
        `when`(f.repository.getBindingByThreadId(sessionId))
            .thenReturn(AgentSessionBinding(42L, sessionId, "/workspace", 1L, 1L))
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(AgentCapabilities(
            sessionCapabilities = SessionCapabilities(list = SessionListCapabilities()),
        ))
        f.runtime.setField("agentInfo", info)
        val client = mock(Client::class.java)
        `when`(client.listSessions(null, emptyList())).thenReturn(flowOf(
            SessionInfo(SessionId(sessionId), "/workspace", title = "A colliding upstream id"),
        ))
        f.runtime.setField("client", client)
        try {
            val error = runCatching { f.runtime.handleMethod("session/list", emptyMap()) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().startsWith("agent_owner_conflict:"))
            assertEquals(AcpAgentProfileStore.CODEX_AGENT_ID, f.store.agentIdForSession(sessionId))
            assertEquals(AcpAgentProfileStore.CODEX_AGENT_ID, f.store.agentIdForConversation(42L))
            assertEquals(mapOf("model" to "saved-model"), f.store.sessionConfiguration(sessionId))
            assertFalse(mockingDetails(f.repository).invocations.any { it.method.name == "ensureBinding" })
        } finally { f.scope.cancel() }
    }

    private fun modelOption(value: String) = SessionConfigOption.Select(
        id = SessionConfigId("model"), name = "Model", category = SessionConfigOptionCategory.MODEL,
        currentValue = SessionConfigValueId(value),
        options = SessionConfigSelectOptions.Flat(listOf("one", "two").map {
            SessionConfigSelectOption(SessionConfigValueId(it), it)
        }),
    )

    private fun fixture(): Fixture {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = mock(AgentSessionBindingRepository::class.java)
        val store = AcpAgentProfileStore(context)
        val runtime = LocalAcpRuntime(context, scope, repository, store,
            ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java), onMessage = {})
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(AgentCapabilities())
        runtime.setField("agentInfo", info)
        runtime.setField("connection", mock(AcpRuntimeConnection::class.java))
        return Fixture(runtime, repository, store, scope)
    }

    private inner class Fixture(
        val runtime: LocalAcpRuntime,
        val repository: AgentSessionBindingRepository,
        val store: AcpAgentProfileStore,
        val scope: CoroutineScope,
    ) {
        fun session(id: String): ClientSession = mock(ClientSession::class.java).also {
            `when`(it.sessionId).thenReturn(SessionId(id))
            `when`(it.parameters).thenReturn(SessionCreationParameters("/workspace", emptyList()))
            runtime.sessions()[id] = it
            @Suppress("UNCHECKED_CAST")
            val cwds = runtime.javaClass.getDeclaredField("sessionCwds").run {
                isAccessible = true; get(runtime) as ConcurrentHashMap<String, String>
            }
            cwds[id] = "/workspace"
        }
    }

    private fun LocalAcpRuntime.setField(name: String, value: Any) =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)

    @Suppress("UNCHECKED_CAST")
    private fun LocalAcpRuntime.sessions() = javaClass.getDeclaredField("sessions").run {
        isAccessible = true; get(this@sessions) as ConcurrentHashMap<String, ClientSession>
    }

    private suspend fun LocalAcpRuntime.resolveForPrompt(args: Map<String, Any?>): ClientSession =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            javaClass.getDeclaredMethod("ensureSessionForTurn", Map::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, args, continuation).let {
                    if (it === COROUTINE_SUSPENDED) it else it as ClientSession
                }
        }
}
