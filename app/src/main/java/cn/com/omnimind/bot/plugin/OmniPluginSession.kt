package cn.com.omnimind.bot.plugin

import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class OmniPluginSession internal constructor(
    val toolDefinitions: List<OmniPluginToolDefinition>,
    val toolHandlers: List<ToolHandler>,
    val actionDefinitions: List<OmniPluginActionDefinition> = emptyList(),
    val actionHandlers: List<OmniPluginActionHandler> = emptyList(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    suspend fun closeSuspending() {
        if (!closed.compareAndSet(false, true)) return
        actionHandlers.asReversed().forEach { handler ->
            runCatching { handler.dispose() }
        }
        toolHandlers.asReversed().forEach { handler ->
            runCatching { handler.dispose() }
        }
    }

    override fun close() {
        runBlocking { closeSuspending() }
    }
}
