package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.tool.AgentToolConcurrencyPolicy
import cn.com.omnimind.bot.agent.tool.ToolConcurrency
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolConcurrencyPolicyTest {

    private fun call(id: String, name: String): AssistantToolCall =
        AssistantToolCall(
            id = id,
            type = "function",
            function = AssistantToolCallFunction(name = name, arguments = "{}")
        )

    private val emptyArgs: JsonObject = JsonObject(emptyMap())

    @Test
    fun `classify whitelisted read tool returns PARALLEL_SAFE`() {
        assertEquals(
            ToolConcurrency.PARALLEL_SAFE,
            AgentToolConcurrencyPolicy.classify("file_read", emptyArgs)
        )
        assertEquals(
            ToolConcurrency.PARALLEL_SAFE,
            AgentToolConcurrencyPolicy.classify("memory_search", emptyArgs)
        )
        assertEquals(
            ToolConcurrency.PARALLEL_SAFE,
            AgentToolConcurrencyPolicy.classify("memory_load", emptyArgs)
        )
    }

    @Test
    fun `classify write tool returns SERIAL_BARRIER`() {
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("file_write", emptyArgs)
        )
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("terminal_execute", emptyArgs)
        )
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("subagent_dispatch", emptyArgs)
        )
    }

    @Test
    fun `unknown tool defaults to SERIAL_BARRIER`() {
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("totally_unknown_tool", emptyArgs)
        )
    }

    @Test
    fun `turn boundary is scoped to the current turn rather than all ACP sessions`() {
        assertTrue(AgentToolConcurrencyPolicy.isTurnBoundary("bash"))
        assertTrue(AgentToolConcurrencyPolicy.isTurnBoundary("android_privileged_action"))
        assertTrue(!AgentToolConcurrencyPolicy.isTurnBoundary("file_write"))
        assertTrue(!AgentToolConcurrencyPolicy.isTurnBoundary("file_read"))
    }

    @Test
    fun `browser_use parallel-safe only for read actions`() {
        val readAction = buildJsonObject { put("action", "get_text") }
        val writeAction = buildJsonObject { put("action", "click") }
        val noAction = buildJsonObject { }
        assertEquals(
            ToolConcurrency.PARALLEL_SAFE,
            AgentToolConcurrencyPolicy.classify("browser_use", readAction)
        )
        assertEquals(
            ToolConcurrency.PARALLEL_SAFE,
            AgentToolConcurrencyPolicy.classify(
                "browser_use",
                buildJsonObject { put("action", "screenshot") }
            )
        )
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("browser_use", writeAction)
        )
        assertEquals(
            ToolConcurrency.SERIAL_BARRIER,
            AgentToolConcurrencyPolicy.classify("browser_use", noAction)
        )
    }

    @Test
    fun `partition merges consecutive parallel-safe calls into one batch`() {
        val calls = listOf(
            call("c1", "file_read"),
            call("c2", "file_read"),
            call("c3", "memory_search")
        )
        val parsed = calls.associate { it.id to emptyArgs }
        val batches = AgentToolConcurrencyPolicy.partitionToolCalls(calls, parsed)
        assertEquals(1, batches.size)
        assertTrue(batches[0].parallel)
        assertEquals(listOf("c1", "c2", "c3"), batches[0].calls.map { it.id })
    }

    @Test
    fun `partition splits read then write into two batches`() {
        val calls = listOf(
            call("c1", "file_read"),
            call("c2", "file_write"),
            call("c3", "file_read")
        )
        val parsed = calls.associate { it.id to emptyArgs }
        val batches = AgentToolConcurrencyPolicy.partitionToolCalls(calls, parsed)
        assertEquals(3, batches.size)
        assertTrue(batches[0].parallel)
        assertTrue(!batches[1].parallel)
        assertTrue(batches[2].parallel)
        assertEquals("c1", batches[0].calls.single().id)
        assertEquals("c2", batches[1].calls.single().id)
        assertEquals("c3", batches[2].calls.single().id)
    }

    @Test
    fun `partition preserves original order`() {
        val calls = listOf(
            call("c1", "memory_search"),
            call("c2", "memory_search"),
            call("c3", "terminal_execute"),
            call("c4", "file_read"),
            call("c5", "file_list")
        )
        val parsed = calls.associate { it.id to emptyArgs }
        val batches = AgentToolConcurrencyPolicy.partitionToolCalls(calls, parsed)
        val flat = batches.flatMap { it.calls.map { c -> c.id } }
        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), flat)
        // c1+c2 parallel, c3 serial, c4+c5 parallel
        assertEquals(3, batches.size)
        assertTrue(batches[0].parallel && batches[0].calls.size == 2)
        assertTrue(!batches[1].parallel && batches[1].calls.size == 1)
        assertTrue(batches[2].parallel && batches[2].calls.size == 2)
    }

    @Test
    fun `partition empty list returns empty`() {
        val batches = AgentToolConcurrencyPolicy.partitionToolCalls(emptyList(), emptyMap())
        assertTrue(batches.isEmpty())
    }
}
