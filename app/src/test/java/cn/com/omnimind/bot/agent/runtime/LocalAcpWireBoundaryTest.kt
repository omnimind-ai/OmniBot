package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.protocol.Protocol
import com.agentclientprotocol.transport.StdioTransport
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction

class LocalAcpWireBoundaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `auth and extension requests preserve vendor params without host routing fields`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val input = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<JsonObject>()
        val transport = StdioTransport(
            parentScope = scope, ioDispatcher = Dispatchers.Unconfined,
            input = input.receiveAsFlow(),
            output = { line ->
                val request = Json.parseToJsonElement(line).jsonObject
                sent += request
                input.send(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", request.getValue("id"))
                    put("result", request["params"] ?: JsonNull)
                }.toString())
            },
            name = "local-acp-wire-boundary",
        )
        val protocol = Protocol(scope, transport)
        val runtime = runtime(scope)
        runtime.javaClass.getDeclaredField("protocol").apply { isAccessible = true }.set(runtime, protocol)
        protocol.start()
        val host = mapOf<String, Any?>(
            "agentId" to AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            "conversationId" to 42L,
            "threadId" to "legacy-host-session",
            "conversationMode" to "agent",
        )
        val vendorMetadata = mapOf("vendor" to "keep")
        try {
            for (method in listOf("authenticate", "auth/authenticate")) {
                withTimeout(5_000) {
                    runtime.handleMethod(method, host + mapOf("methodId" to "api-key", "_meta" to vendorMetadata))
                }
                assertEquals("authenticate", sent.last()["method"]?.jsonPrimitive?.content)
                assertEquals(Json.parseToJsonElement("""{"methodId":"api-key","_meta":{"vendor":"keep"}}"""),
                    sent.last()["params"])
            }
            for (method in listOf("logout", "auth/logout", "providers/list", "auth/providers/list", "providers/set", "providers/disable")) {
                withTimeout(5_000) {
                    runtime.handleMethod(method, host + mapOf("providerId" to "vendor-provider", "_meta" to vendorMetadata))
                }
                assertEquals(Json.parseToJsonElement("""{"providerId":"vendor-provider","_meta":{"vendor":"keep"}}"""),
                    sent.last()["params"])
            }

            withTimeout(5_000) {
                runtime.handleMethod("_vendor/flat", host + mapOf("value" to "keep", "_meta" to vendorMetadata))
            }
            assertEquals(Json.parseToJsonElement("""{"value":"keep","_meta":{"vendor":"keep"}}"""), sent.last()["params"])

            val values = listOf(
                listOf("first", 7, mapOf("agentId" to "vendor-value")) to
                    Json.parseToJsonElement("""["first",7,{"agentId":"vendor-value"}]"""),
                "scalar" to JsonPrimitive("scalar"),
                23 to JsonPrimitive(23),
                true to JsonPrimitive(true),
                null to JsonNull,
                mapOf("agentId" to "vendor-value", "params" to listOf("nested")) to
                    Json.parseToJsonElement("""{"agentId":"vendor-value","params":["nested"]}"""),
            )
            for ((payload, expected) in values) {
                withTimeout(5_000) {
                    runtime.handleMethod("_vendor/opaque", host + ("params" to payload))
                }
                assertEquals("_vendor/opaque", sent.last()["method"]?.jsonPrimitive?.content)
                assertTrue("Explicit null remains a params value", sent.last().containsKey("params"))
                assertEquals(expected, sent.last()["params"])
            }
        } finally {
            protocol.close()
            input.close()
            scope.cancel()
        }
    }

    @Test
    fun `dispatch preparation rejects unknown core methods without connecting or executing a known request`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var preparations = 0
        val runtime = runtime(scope) { preparations++ }
        try {
            mockConstruction(XiaowanAcpConnection::class.java).use { connections ->
                val args = mapOf("agentId" to AcpAgentProfileStore.XIAOWAN_AGENT_ID)
                runtime.prepareRequest("session/new", args)
                runtime.prepareRequest("_vendor/opaque", args + ("params" to listOf("value")))
                runtime.prepareRequest("notifyAcpExtension", args + mapOf("method" to "_vendor/event", "params" to null))
                for (method in listOf("session/reopen", "turn/replay", "agent/select", "unknown")) {
                    assertThrows(UnsupportedOperationException::class.java) {
                        runtime.prepareRequest(method, args)
                    }
                }
                for (method in listOf("session/prompt", "initialize", "providers/set", "")) {
                    assertThrows(IllegalArgumentException::class.java) {
                        runtime.prepareRequest("notifyAcpExtension", args + ("method" to method))
                    }
                }
                assertEquals(0, preparations)
                assertFalse(runtime.isConnected)
                assertTrue(connections.constructed().isEmpty())
                assertFalse(runtime.hasActiveTurns())
            }
        } finally {
            scope.cancel()
        }
    }

    private fun runtime(scope: CoroutineScope, onPrepare: () -> Unit = {}): LocalAcpRuntime {
        val context = acpProfileStoreTestContext(folder.root)
        return LocalAcpRuntime(
            context, scope, mock(AgentSessionBindingRepository::class.java), AcpAgentProfileStore(context),
            ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> onPrepare(); error("Wire tests must not prepare a process") },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Wire requests must not create an Agent lifecycle event") },
        )
    }
}
