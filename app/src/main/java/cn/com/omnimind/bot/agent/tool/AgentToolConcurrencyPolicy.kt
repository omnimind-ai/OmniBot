package cn.com.omnimind.bot.agent.tool

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandlerConcurrencyHint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class ToolConcurrency {
    PARALLEL_SAFE,
    SERIAL_BARRIER
}

data class ToolBatch(
    val calls: List<AssistantToolCall>,
    val parallel: Boolean
)

/**
 * Decides which tool calls in one LLM round may run concurrently.
 *
 * Default = SERIAL_BARRIER. Only tools on the explicit whitelist below
 * (pure-read, no shared mutable side-effects) are declared PARALLEL_SAFE.
 * MCP remote tools, write/edit tools, privileged actions, terminal,
 * subagent_dispatch — all stay serial.
 *
 * Handlers can override per-tool by implementing [ToolHandlerConcurrencyHint].
 */
object AgentToolConcurrencyPolicy {

    /**
     * These tools are not globally exclusive. They are turn boundaries: once
     * one is selected from an assistant item, the current Agent turn must
     * return its result before the model chooses the next tool. Other ACP
     * sessions remain free to run their own turns.
     */
    private val TURN_BOUNDARY_TOOL_NAMES: Set<String> = setOf(
        "terminal_execute",
        "bash",
        "android_privileged_action",
        "android_privileged_session_start",
        "android_privileged_session_exec",
        "android_privileged_session_read",
        "android_privileged_session_stop",
    )

    private val PARALLEL_SAFE_TOOL_NAMES: Set<String> = setOf(
        "file_read",
        "read",
        "file_list",
        "glob",
        "file_search",
        "grep",
        "file_stat",
        "context_time_now",
        "context_apps_query",
        "memory_search",
        "memory_load",
        "skills_list",
        "skills_read"
    )

    private val BROWSER_USE_PARALLEL_SAFE_ACTIONS: Set<String> = setOf(
        "get_text",
        "screenshot"
    )

    fun classify(
        toolName: String,
        args: JsonObject,
        handler: ToolHandler? = null
    ): ToolConcurrency {
        if (handler is ToolHandlerConcurrencyHint) {
            handler.concurrencyFor(toolName, args)?.let { return it }
        }
        if (toolName == "browser_use" || toolName == "webfetch") {
            val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return if (action in BROWSER_USE_PARALLEL_SAFE_ACTIONS) {
                ToolConcurrency.PARALLEL_SAFE
            } else {
                ToolConcurrency.SERIAL_BARRIER
            }
        }
        return if (toolName in PARALLEL_SAFE_TOOL_NAMES) {
            ToolConcurrency.PARALLEL_SAFE
        } else {
            ToolConcurrency.SERIAL_BARRIER
        }
    }

    fun isTurnBoundary(toolName: String): Boolean =
        toolName in TURN_BOUNDARY_TOOL_NAMES

    /**
     * Greedy partition: consecutive PARALLEL_SAFE calls merge into one batch;
     * any SERIAL_BARRIER call becomes its own batch. Preserves original order.
     *
     * [classifier] lets callers swap in a handler-aware version. The
     * two-argument overload uses the static whitelist.
     *
     * Keep the production call site on a real two-argument JVM method. Kotlin's
     * default-argument bridge is normally harmless, but this method is called
     * from a large suspend state machine on Android. On the tested Android 16
     * ART build that bridge was the last frame before the native null receiver
     * crash, so use an overload instead of a default lambda.
     */
    fun partitionToolCalls(
        calls: List<AssistantToolCall>,
        parsedArgs: Map<String, JsonObject>,
    ): List<ToolBatch> = partitionToolCalls(
        calls = calls,
        parsedArgs = parsedArgs,
        classifier = { call, args -> classify(call.function.name, args) },
    )

    fun partitionToolCalls(
        calls: List<AssistantToolCall>,
        parsedArgs: Map<String, JsonObject>,
        classifier: (AssistantToolCall, JsonObject) -> ToolConcurrency,
    ): List<ToolBatch> {
        if (calls.isEmpty()) return emptyList()
        val batches = mutableListOf<ToolBatch>()
        val current = mutableListOf<AssistantToolCall>()
        var currentParallel = false
        for (call in calls) {
            val args = parsedArgs[call.id] ?: JsonObject(emptyMap())
            val safety = classifier(call, args)
            val isParallel = safety == ToolConcurrency.PARALLEL_SAFE
            if (current.isEmpty()) {
                current.add(call)
                currentParallel = isParallel
            } else if (isParallel && currentParallel) {
                current.add(call)
            } else {
                batches.add(ToolBatch(current.toList(), currentParallel))
                current.clear()
                current.add(call)
                currentParallel = isParallel
            }
        }
        if (current.isNotEmpty()) {
            batches.add(ToolBatch(current.toList(), currentParallel))
        }
        return batches
    }
}
