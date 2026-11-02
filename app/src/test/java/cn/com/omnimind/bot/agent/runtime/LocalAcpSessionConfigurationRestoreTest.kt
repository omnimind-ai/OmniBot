package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.SessionConfigId
import com.agentclientprotocol.model.SessionConfigOption
import com.agentclientprotocol.model.SessionConfigOptionCategory
import com.agentclientprotocol.model.SessionConfigOptionValue
import com.agentclientprotocol.model.SessionConfigSelectOption
import com.agentclientprotocol.model.SessionConfigSelectOptions
import com.agentclientprotocol.model.SessionConfigValueId
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SetSessionConfigOptionResponse
import com.rk.terminal.runtime.TerminalDistribution
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class LocalAcpSessionConfigurationRestoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `removed thought preference keeps and persists the Agent's valid value for the same session`() = runBlocking {
        withRuntime { runtime, store ->
            store.saveSessionConfiguration("selected", mapOf(
                "acp.config:thinking" to "\"off\"", "other-setting" to "keep",
            ))
            store.saveSessionConfiguration("other", mapOf("acp.config:thinking" to "\"off\""))
            val (session, state) = session("selected", thought("high", listOf("low", "high")))

            runtime.registerForTest(session)

            assertSame(session, runtime.sessions()["selected"])
            assertEquals("high", (state.value.single() as SessionConfigOption.Select).currentValue.value)
            assertEquals(mapOf("acp.config:thinking" to "\"high\"", "other-setting" to "keep"),
                store.sessionConfiguration("selected"))
            assertEquals(mapOf("acp.config:thinking" to "\"off\""), store.sessionConfiguration("other"))
            // Reading the session's advertised state must not issue a mutation.
            org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce()).sessionId
            org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce()).configOptionsSupported
            org.mockito.Mockito.verify(session, org.mockito.Mockito.atLeastOnce()).configOptions
            verifyNoMoreInteractions(session)
        }
    }

    @Test
    fun `still supported thought preference is restored through ACP`() = runBlocking {
        withRuntime { runtime, store ->
            store.saveSessionConfiguration("selected", mapOf("acp.config:thinking" to "\"low\""))
            val (session, state) = session("selected", thought("high", listOf("low", "high")))
            var writes = 0
            `when`(session.setConfigOption(SessionConfigId("thinking"), SessionConfigOptionValue.StringValue("low")))
                .thenAnswer {
                    writes++
                    state.value = listOf(thought("low", listOf("low", "high")))
                    SetSessionConfigOptionResponse(state.value)
                }

            runtime.registerForTest(session)

            assertEquals(1, writes)
            assertEquals("\"low\"", store.sessionConfiguration("selected")["acp.config:thinking"])
        }
    }

    @Test
    fun `reasoning effort alias follows the advertised thought option`() = runBlocking {
        withRuntime { runtime, store ->
            store.saveSessionConfiguration("selected", mapOf("acp.config:reasoning_effort" to "\"off\""))
            val (session, _) = session("selected", thought("max", listOf("high", "max")))

            runtime.registerForTest(session)

            assertEquals(mapOf("acp.config:thinking" to "\"max\""), store.sessionConfiguration("selected"))
        }
    }

    @Test
    fun `thought restoration reads capabilities returned after a model restoration`() = runBlocking {
        withRuntime { runtime, store ->
            store.saveSessionConfiguration("selected", linkedMapOf(
                "acp.config:model" to "\"new-model\"", "acp.config:thinking" to "\"off\"",
            ))
            val (session, state) = session("selected",
                select("model", SessionConfigOptionCategory.MODEL, "old-model", listOf("old-model", "new-model")),
                thought("off", listOf("off", "high")),
            )
            `when`(session.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("new-model")))
                .thenAnswer {
                    state.value = listOf(
                        select("model", SessionConfigOptionCategory.MODEL, "new-model", listOf("old-model", "new-model")),
                        thought("high", listOf("high", "max")),
                    )
                    SetSessionConfigOptionResponse(state.value)
                }

            runtime.registerForTest(session)

            assertEquals("\"new-model\"", store.sessionConfiguration("selected")["acp.config:model"])
            assertEquals("\"high\"", store.sessionConfiguration("selected")["acp.config:thinking"])
        }
    }

    @Test
    fun `a thought-first saved map restores its model before checking the saved effort`() = runBlocking {
        withRuntime { runtime, store ->
            store.saveSessionConfiguration("selected", linkedMapOf(
                "acp.config:thinking" to "\"max\"", "acp.config:provider-model" to "\"new-model\"",
            ))
            val (session, state) = session("selected",
                thought("off", listOf("off", "low")),
                select("provider-model", SessionConfigOptionCategory.MODEL, "old-model", listOf("old-model", "new-model")),
            )
            val writes = mutableListOf<String>()
            `when`(session.setConfigOption(SessionConfigId("provider-model"), SessionConfigOptionValue.StringValue("new-model")))
                .thenAnswer {
                    writes += "model"
                    state.value = listOf(
                        thought("high", listOf("high", "max")),
                        select("provider-model", SessionConfigOptionCategory.MODEL, "new-model", listOf("old-model", "new-model")),
                    )
                    SetSessionConfigOptionResponse(state.value)
                }
            `when`(session.setConfigOption(SessionConfigId("thinking"), SessionConfigOptionValue.StringValue("max")))
                .thenAnswer {
                    writes += "thinking"
                    state.value = listOf(
                        thought("max", listOf("high", "max")),
                        select("provider-model", SessionConfigOptionCategory.MODEL, "new-model", listOf("old-model", "new-model")),
                    )
                    SetSessionConfigOptionResponse(state.value)
                }

            runtime.registerForTest(session)

            assertEquals(listOf("model", "thinking"), writes)
            assertEquals("\"new-model\"", store.sessionConfiguration("selected")["acp.config:provider-model"])
            assertEquals("\"max\"", store.sessionConfiguration("selected")["acp.config:thinking"])
        }
    }

    @Test
    fun `transport rejection of a valid thought preference still fails restoration`() = runBlocking {
        withRuntime { runtime, store ->
            val saved = mapOf("acp.config:thinking" to "\"low\"")
            store.saveSessionConfiguration("selected", saved)
            val (session, _) = session("selected", thought("high", listOf("low", "high")))
            val failure = IllegalStateException("transport disconnected")
            `when`(session.setConfigOption(SessionConfigId("thinking"), SessionConfigOptionValue.StringValue("low")))
                .thenThrow(failure)

            assertSame(failure, runtime.registrationFailure(session))
            assertFalse(runtime.sessions().containsKey("selected"))
            assertEquals(saved, store.sessionConfiguration("selected"))
        }
    }

    @Test
    fun `other invalid settings are not skipped after a stale thought preference`() = runBlocking {
        withRuntime { runtime, store ->
            val saved = linkedMapOf(
                "acp.config:thinking" to "\"off\"", "acp.config:temperature" to "\"invalid\"",
            )
            store.saveSessionConfiguration("selected", saved)
            val (session, _) = session("selected", thought("high", listOf("high", "max")),
                select("temperature", null, "normal", listOf("normal", "creative")),
            )

            assertTrue(runtime.registrationFailure(session) is IllegalArgumentException)
            assertFalse(runtime.sessions().containsKey("selected"))
            assertEquals(saved, store.sessionConfiguration("selected"))
        }
    }

    @Test
    fun `rejected model restoration never silently switches to the Agent model`() = runBlocking {
        withRuntime { runtime, store ->
            val saved = mapOf("acp.config:model" to "\"removed-model\"")
            store.saveSessionConfiguration("selected", saved)
            val (session, _) = session("selected",
                select("model", SessionConfigOptionCategory.MODEL, "current-model", listOf("current-model")),
            )
            val failure = IllegalArgumentException("removed model")
            `when`(session.setConfigOption(SessionConfigId("model"), SessionConfigOptionValue.StringValue("removed-model")))
                .thenThrow(failure)

            assertSame(failure, runtime.registrationFailure(session))
            assertFalse(runtime.sessions().containsKey("selected"))
            assertEquals(saved, store.sessionConfiguration("selected"))
        }
    }

    @Test
    fun `an invalid Agent current value does not become an invented valid fallback`() = runBlocking {
        withRuntime { runtime, store ->
            val saved = mapOf("acp.config:thinking" to "\"off\"")
            store.saveSessionConfiguration("selected", saved)
            val (session, _) = session("selected", thought("missing", listOf("high", "max")))

            assertTrue(runtime.registrationFailure(session) is IllegalArgumentException)
            assertFalse(runtime.sessions().containsKey("selected"))
            assertEquals(saved, store.sessionConfiguration("selected"))
        }
    }

    private suspend fun withRuntime(block: suspend (LocalAcpRuntime, AcpAgentProfileStore) -> Unit) {
        val context = acpProfileStoreTestContext(temporaryFolder.root)
        val store = AcpAgentProfileStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), store,
            ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = {},
        )
        try { block(runtime, store) } finally { scope.cancel() }
    }

    private fun session(id: String, vararg options: SessionConfigOption): Pair<ClientSession, MutableStateFlow<List<SessionConfigOption>>> {
        val session = mock(ClientSession::class.java)
        val state = MutableStateFlow(options.toList())
        `when`(session.sessionId).thenReturn(SessionId(id))
        `when`(session.configOptionsSupported).thenReturn(true)
        `when`(session.configOptions).thenReturn(state)
        return session to state
    }

    private fun thought(current: String, choices: List<String>) =
        select("thinking", SessionConfigOptionCategory.THOUGHT_LEVEL, current, choices)

    private fun select(id: String, category: SessionConfigOptionCategory?, current: String, choices: List<String>) =
        SessionConfigOption.Select(
            id = SessionConfigId(id), name = id, category = category,
            currentValue = SessionConfigValueId(current),
            options = SessionConfigSelectOptions.Flat(choices.map {
                SessionConfigSelectOption(SessionConfigValueId(it), it)
            }),
        )

    private fun LocalAcpRuntime.sessions(): Map<*, *> = javaClass.getDeclaredField("sessions").run {
        isAccessible = true
        get(this@sessions) as Map<*, *>
    }

    private suspend fun LocalAcpRuntime.registerForTest(session: ClientSession) =
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            val result = javaClass.getDeclaredMethod("registerSession", ClientSession::class.java,
                String::class.java, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, session, "/workspace", continuation)
            if (result === COROUTINE_SUSPENDED) result else Unit
        }

    private suspend fun LocalAcpRuntime.registrationFailure(session: ClientSession): Throwable {
        try {
            registerForTest(session)
            fail("The existing restoration failure must reach the caller")
        } catch (error: InvocationTargetException) {
            return checkNotNull(error.cause)
        }
        error("Registration unexpectedly succeeded")
    }
}
