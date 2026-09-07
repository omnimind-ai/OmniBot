package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class SubagentCapabilityInheritanceTest {

    @Test
    fun `child inherits every capability exposed by its parent harness`() {
        val parent = FakeCatalog(
            toolNames = listOf(
                "terminal_execute",
                "file_delete",
                "schedule_task_create",
                "plugin_custom_action",
                "subagent_dispatch"
            )
        )

        val child = inheritedSubagentCatalog(parent)

        assertSame(parent, child)
        assertEquals(
            parent.toolsForModel.map { it.function.name },
            child.toolsForModel.map { it.function.name }
        )
    }

    @Test
    fun `planner cannot invoke tools even if model invents a hidden call`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("file_read", "file_delete")), SubagentProfileRegistry.planner)
        assertTrue(child.toolsForModel.isEmpty())
        assertTrue(runCatching { child.validateArguments("file_delete", JsonObject(emptyMap())) }.isFailure)
        assertEquals("file_delete", child.runtimeDescriptor("file_delete").name)
    }

    @Test
    fun `explorer can read but cannot write or invoke browser script`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("file_read", "file_write", "browser_use")), SubagentProfileRegistry.explorer)
        assertEquals(listOf("file_read", "browser_use"), child.toolsForModel.map { it.function.name })
        child.validateArguments("file_read", JsonObject(emptyMap()))
        child.validateArguments("browser_use", JsonObject(mapOf("action" to JsonPrimitive("get_text"))))
        for (name in listOf("file_write", "terminal_execute", "plugin_custom_action")) {
            assertTrue(runCatching { child.validateArguments(name, JsonObject(emptyMap())) }.isFailure)
        }
        for (action in listOf("execute_js", "click", "type", "press_key")) {
            assertTrue(runCatching { child.validateArguments("browser_use", JsonObject(mapOf("action" to JsonPrimitive(action)))) }.isFailure)
        }
    }

    @Test
    fun `memory curator can write memory but cannot write workspace files`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("memory_upsert_longterm", "file_read", "file_write")), SubagentProfileRegistry.memoryCurator)
        assertEquals(listOf("memory_upsert_longterm", "file_read"), child.toolsForModel.map { it.function.name })
        child.validateArguments("memory_upsert_longterm", JsonObject(emptyMap()))
        assertTrue(runCatching { child.validateArguments("file_write", JsonObject(emptyMap())) }.isFailure)
    }

    @Test
    fun `specialist permissions never admit a plugin using a builtin name`() {
        val parent = object : AgentToolCatalog by FakeCatalog(listOf("file_read")) {
            override fun runtimeDescriptor(toolName: String) = AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, "plugin", "custom")
        }
        val child = inheritedSubagentCatalog(parent, SubagentProfileRegistry.explorer)
        assertTrue(child.toolsForModel.isEmpty())
        assertTrue(runCatching { child.validateArguments("file_read", JsonObject(emptyMap())) }.isFailure)
    }

    private class FakeCatalog(
        toolNames: List<String>
    ) : AgentToolCatalog {
        override val toolsForModel = toolNames.map { name ->
            ChatCompletionTool(function = ChatCompletionFunction(name = name))
        }

        override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor {
            return AgentToolRegistry.RuntimeToolDescriptor(
                name = toolName,
                displayName = toolName,
                toolType = when {
                    toolName.startsWith("file_") -> "workspace"
                    toolName.startsWith("memory_") -> "memory"
                    toolName == "browser_use" -> "browser"
                    else -> "builtin"
                }
            )
        }

        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }
}
