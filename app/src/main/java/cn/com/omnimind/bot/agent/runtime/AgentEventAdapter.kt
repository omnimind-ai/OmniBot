package cn.com.omnimind.bot.agent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AgentEventAdapter(
    private val json: Json
) {
    companion object {
    }

    fun mapOutputKind(result: ToolExecutionResult): AgentOutputKind {
        return when (result) {
            is ToolExecutionResult.ChatMessage -> AgentOutputKind.CHAT_MESSAGE
            is ToolExecutionResult.Clarify -> AgentOutputKind.CLARIFY
            is ToolExecutionResult.PermissionRequired -> AgentOutputKind.PERMISSION_REQUIRED
            is ToolExecutionResult.ScheduleResult,
            is ToolExecutionResult.McpResult,
            is ToolExecutionResult.MemoryResult,
            is ToolExecutionResult.TerminalResult,
            is ToolExecutionResult.Interrupted,
            is ToolExecutionResult.ContextResult -> AgentOutputKind.TOOL_RESULT
            is ToolExecutionResult.Error -> AgentOutputKind.NONE
        }
    }

    fun hasUserVisibleOutput(result: ToolExecutionResult): Boolean {
        return result is ToolExecutionResult.ChatMessage ||
            result is ToolExecutionResult.Clarify ||
            result is ToolExecutionResult.PermissionRequired ||
            result is ToolExecutionResult.ScheduleResult ||
            result is ToolExecutionResult.McpResult ||
            result is ToolExecutionResult.MemoryResult ||
            result is ToolExecutionResult.TerminalResult ||
            result is ToolExecutionResult.Interrupted ||
            result is ToolExecutionResult.ContextResult
    }

    fun isConversationStoppingResult(result: ToolExecutionResult): Boolean {
        return result is ToolExecutionResult.ChatMessage ||
            result is ToolExecutionResult.Clarify ||
            result is ToolExecutionResult.PermissionRequired
    }

    fun toolResultContent(
        descriptor: AgentToolRegistry.RuntimeToolDescriptor,
        result: ToolExecutionResult,
        extras: Map<String, Any?> = emptyMap()
    ): String {
        val payload: Map<String, Any?> = when (result) {
            is ToolExecutionResult.ChatMessage -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to true,
                "summary" to result.message,
                "message" to result.message
            )

            is ToolExecutionResult.Clarify -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to true,
                "summary" to result.question,
                "question" to result.question,
                "missingFields" to (result.missingFields ?: emptyList<String>())
            )

            is ToolExecutionResult.PermissionRequired -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to false,
                "summary" to "执行前缺少权限：${result.missing.joinToString("、")}",
                "missing" to result.missing
            )

            is ToolExecutionResult.ScheduleResult -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to result.success,
                "summary" to result.summaryText,
                "previewJson" to result.previewJson,
                "taskId" to result.taskId
            )

            is ToolExecutionResult.McpResult -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to result.success,
                "summary" to result.summaryText,
                "serverName" to result.serverName,
                "previewJson" to result.previewJson,
                "rawResultJson" to result.rawResultJson
            )

            is ToolExecutionResult.MemoryResult -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to result.success,
                "summary" to result.summaryText,
                "previewJson" to result.previewJson,
                "rawResultJson" to result.rawResultJson
            )

            is ToolExecutionResult.TerminalResult -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to result.success,
                "timedOut" to result.timedOut,
                "summary" to result.summaryText,
                "previewJson" to result.previewJson,
                "rawResultJson" to result.rawResultJson,
                "terminalOutput" to result.terminalOutput,
                "terminalSessionId" to result.terminalSessionId,
                "terminalStreamState" to result.terminalStreamState
            )

            is ToolExecutionResult.ContextResult -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to result.success,
                "summary" to result.summaryText,
                "previewJson" to result.previewJson,
                "rawResultJson" to result.rawResultJson
            )

            is ToolExecutionResult.Interrupted -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to false,
                "status" to "interrupted",
                "summary" to result.summaryText,
                "previewJson" to result.previewJson,
                "rawResultJson" to result.rawResultJson,
                "interruptedBy" to result.interruptedBy,
                "interruptionReason" to result.interruptionReason,
                "terminalOutput" to result.terminalOutput,
                "terminalSessionId" to result.terminalSessionId,
                "terminalStreamState" to result.terminalStreamState
            )

            is ToolExecutionResult.Error -> mapOf(
                "toolName" to descriptor.name,
                "displayName" to descriptor.displayName,
                "toolType" to descriptor.toolType,
                "success" to false,
                "summary" to result.message,
                "errorToolName" to result.toolName,
                "message" to result.message
            )
        }
        val enriched = payload.toMutableMap()
        if (result.artifacts.isNotEmpty()) {
            enriched["artifacts"] = result.artifacts.map { it.toPayload() }
        }
        result.workspaceId?.let { enriched["workspaceId"] = it }
        if (result.actions.isNotEmpty()) {
            enriched["actions"] = result.actions.map { it.toPayload() }
        }
        if (extras.isNotEmpty()) {
            enriched.putAll(extras)
        }
        return json.encodeToString(mapToJsonElement(enriched))
    }

    fun compactToolResultContent(
        rawContent: String,
        offloadArtifact: ArtifactRef?,
        maxChars: Int? = null,
    ): String {
        val limit = maxChars?.takeIf { it > 0 } ?: return rawContent
        if (rawContent.length <= limit) return rawContent
        val original = runCatching {
            json.parseToJsonElement(rawContent) as? JsonObject
        }.getOrNull()
        val preview = headTail(
            value = rawContent,
            maxChars = (limit / 2).coerceAtLeast(1),
            hasOffloadArtifact = offloadArtifact != null
        )
        val compact = linkedMapOf<String, Any?>()
        listOf(
            "toolName",
            "displayName",
            "toolType",
            "serverName",
            "success",
            "status",
            "summary",
            "workspaceId"
        ).forEach { key ->
            original?.get(key)?.let { compact[key] = it }
        }
        compact["outputTruncated"] = true
        compact["originalChars"] = rawContent.length
        compact["headTail"] = preview
        offloadArtifact?.let { artifact ->
            compact["fullOutputArtifact"] = artifact.toPayload()
        }
        original?.get("artifacts")?.let { compact["artifacts"] = it }
        original?.get("actions")?.let { compact["actions"] = it }
        val encoded = json.encodeToString(mapToJsonElement(compact))
        if (encoded.length <= limit) return encoded

        // A tool may put an unexpectedly large summary or artifact/action list
        // in its envelope. Keep the model-facing contract strictly bounded even
        // in that case; the complete envelope remains available in the offload.
        val minimal = linkedMapOf<String, Any?>(
            "toolName" to original?.get("toolName"),
            "success" to original?.get("success"),
            "outputTruncated" to true,
            "originalChars" to rawContent.length,
            "headTail" to preview
        )
        offloadArtifact?.let { artifact ->
            minimal["fullOutputArtifact"] = artifact.toPayload()
        }
        return json.encodeToString(mapToJsonElement(minimal)).take(limit)
    }

    private fun headTail(
        value: String,
        maxChars: Int,
        hasOffloadArtifact: Boolean
    ): String {
        if (value.length <= maxChars) return value
        val marker = if (hasOffloadArtifact) {
            "\n... [middle omitted; full output is in artifact] ...\n"
        } else {
            "\n... [middle omitted] ...\n"
        }
        val remaining = (maxChars - marker.length).coerceAtLeast(2)
        val head = remaining / 2
        val tail = remaining - head
        return value.take(head) + marker + value.takeLast(tail)
    }

    private fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
