package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolCatalogViewTest {
    @Test
    fun `maps direct-agent tool aliases into the subagent whitelist`() {
        val parent = FakeCatalog(
            tools = listOf("read", "bash", "webfetch", "memory_search")
        )
        val view = SubagentToolCatalogView(
            parent = parent,
            allowed = setOf("file_read", "terminal_execute", "browser_use", "memory_search")
        )

        assertTrue(view.toolsForModel.map { it.function.name }.containsAll(
            setOf("read", "bash", "webfetch", "memory_search")
        ))
        view.runtimeDescriptor("read")
        view.runtimeDescriptor("bash")
        view.runtimeDescriptor("webfetch")
    }

    private class FakeCatalog(
        tools: List<String>
    ) : AgentToolCatalog {
        override val toolsForModel = tools.map { name ->
            ChatCompletionTool(
                function = ChatCompletionFunction(name = name)
            )
        }

        override fun runtimeDescriptor(
            toolName: String
        ): AgentToolRegistry.RuntimeToolDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = toolName,
            displayName = toolName,
            toolType = "builtin"
        )

        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }
}
