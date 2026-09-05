package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
                toolType = "test"
            )
        }

        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }
}
