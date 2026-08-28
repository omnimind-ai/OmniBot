package cn.com.omnimind.bot.omniflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowPythonClientTest {
    @Test
    fun `tool calls have no default wall clock timeout`() {
        assertNull(
            OmniFlowPythonClient.defaultTimeoutMs(
                "tools/call",
                mapOf("name" to "update_function"),
            ),
        )
        assertNull(
            OmniFlowPythonClient.defaultTimeoutMs(
                "tools/call",
                mapOf("name" to "run_gui"),
            ),
        )
        assertEquals(30_000L, OmniFlowPythonClient.defaultTimeoutMs("tools/list"))
    }

    @Test
    fun `skill bridge command uses isolated source dependencies and transfer`() {
        val command = OmniFlowPythonClient.bridgeCommand(
            "/workspace/.omnibot/skills/OmniBotSkills/omniflow-gui-runtime/scripts/runtime/python",
            "/workspace/.omnibot/skills/OmniBotSkills/omniflow-gui-runtime/scripts/runtime/.runtime/site-packages",
            "/workspace/.omnibot/skills/OmniBotSkills/omniflow-gui-runtime/scripts/runtime/.runtime/omnitransfer",
            "/workspace/.omnibot/skills/OmniBotSkills/omniflow-gui-runtime/scripts/runtime/.runtime/omnitransfer/src/omnitransfer/checkpoints/matcher.npz",
        )

        assertTrue(command.contains("export PYTHONPATH="))
        assertTrue(command.contains("scripts/runtime/python"))
        assertTrue(command.contains("scripts/runtime/.runtime/site-packages"))
        assertTrue(command.contains("scripts/runtime/.runtime/omnitransfer/src"))
        assertTrue(command.contains("export OMNITRANSFER_ROOT="))
        assertTrue(command.contains("export OMNITRANSFER_MATCHER_CHECKPOINT="))
        assertTrue(command.contains("-m omniflow.bridge"))
        assertFalse(command.contains("--catalog"))
        assertFalse(command.contains("/workspace/.venv"))
    }

    @Test
    fun `developer override is first on Python path`() {
        val command = OmniFlowPythonClient.bridgeCommand(
            "/workspace/runtime/python",
            "/workspace/runtime/site-packages",
            "/workspace/runtime/omnitransfer",
            "/workspace/runtime/omnitransfer/checkpoint.npz",
            "/workspace/.omnibot/omniflow-developer/python",
        )

        assertTrue(
            command.contains(
                "export PYTHONPATH='/workspace/.omnibot/omniflow-developer/python:" +
                    "/workspace/runtime/python:/workspace/runtime/site-packages:" +
                    "/workspace/runtime/omnitransfer/src'",
            ),
        )
    }

    @Test
    fun `initialize uses MCP handshake and keeps one process`() = runBlocking {
        val process = FakeProcess(
            stdout = """{"jsonrpc":"2.0","id":"request-1","result":{"protocolVersion":"2025-11-25"}}""" + "\n",
        )
        var starts = 0
        val client = OmniFlowPythonClient(
            processStarter = { _, _ ->
                starts += 1
                process
            },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "request-1" },
        )

        val result = client.initialize()

        assertEquals("2025-11-25", result["protocolVersion"])
        assertEquals(1, starts)
        val written = process.writtenText().lines().filter(String::isNotBlank)
        assertEquals(2, written.size)
        assertTrue(written[0].contains("\"method\":\"initialize\""))
        assertTrue(written[0].contains("\"protocolVersion\":\"2025-11-25\""))
        assertTrue(written[1].contains("\"method\":\"notifications/initialized\""))
        client.close()
        assertTrue(process.destroyed)
    }

    @Test
    fun `tool call answers JSON RPC host requests on the same process`() = runBlocking {
        val process = FakeProcess(
            stdout = listOf(
                """{"jsonrpc":"2.0","id":"request-1:host:1","method":"omniflow/observe","params":{"xml":true}}""",
                """{"jsonrpc":"2.0","id":"request-1:host:2","method":"omniflow/act","params":{"tool":"wait"}}""",
                """{"jsonrpc":"2.0","id":"request-1","result":{"success":true,"actions_executed":1}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val methods = mutableListOf<String>()
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "request-1" },
        )

        val result = client.call(
            operation = "tools/call",
            payload = mapOf("name" to "run_gui"),
            hostCall = OmniFlowPythonHostCall { method, _ ->
                methods += method
                mapOf("success" to true)
            },
        )

        assertEquals(listOf("observe", "act"), methods)
        assertEquals(true, result["success"])
        val written = process.writtenText().lines().filter(String::isNotBlank)
        assertEquals(3, written.size)
        assertTrue(written[1].contains("\"id\":\"request-1:host:1\""))
        assertTrue(written[2].contains("\"id\":\"request-1:host:2\""))
        client.close()
    }

    @Test
    fun `integral bridge numbers stay integral in nested actions`() = runBlocking {
        val process = FakeProcess(
            stdout = """{"jsonrpc":"2.0","id":"request-1","result":{"function":{"steps":[{"step_index":0,"action":{"tool":"wait","args":{"duration_ms":500}}}]}}}""" + "\n",
        )
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "request-1" },
        )

        val result = client.call("tools/call", mapOf("name" to "get_function"))
        val function = result["function"] as Map<*, *>
        val step = (function["steps"] as List<*>).single() as Map<*, *>
        val action = step["action"] as Map<*, *>
        val args = action["args"] as Map<*, *>

        assertEquals(0L, step["step_index"])
        assertEquals(500L, args["duration_ms"])
        assertFalse(step["step_index"] is Double)
        assertFalse(args["duration_ms"] is Double)
        client.close()
    }

    private class FakeProcess(
        stdout: String = "",
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout.toByteArray()),
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stderrStream = ByteArrayInputStream(ByteArray(0))
        var destroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
        override fun isAlive(): Boolean = !destroyed

        fun writtenText(): String = stdin.toString(Charsets.UTF_8.name())
    }
}
