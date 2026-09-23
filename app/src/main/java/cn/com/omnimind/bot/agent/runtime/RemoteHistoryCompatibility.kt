package cn.com.omnimind.bot.agent.runtime

import com.google.gson.GsonBuilder
import java.util.Base64

/**
 * One-way import of old bridge history at session/load. This does not consume live
 * session/update events or determine whether an ACP turn is active or complete.
 */
internal object RemoteHistoryCompatibility {
    private val json = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
    private val buckets = listOf("items", "outputItems", "output_items", "responseItems", "response_items",
        "rawItems", "raw_items", "messages", "events", "inputItems", "input_items")
    private val envelopes = listOf("message", "payload", "data", "event", "notification", "params", "result")
    private val idKeys = listOf("threadId", "thread_id", "turnId", "turn_id", "itemId", "item_id")
    private val aliases = mapOf("agent_message" to "agentMessage", "user_message" to "userMessage",
        "command_execution" to "commandExecution", "file_change" to "fileChange", "mcp_tool_call" to "mcpToolCall",
        "dynamic_tool_call" to "dynamicToolCall", "web_search" to "webSearch", "image_view" to "imageView",
        "image_generation" to "imageGeneration", "collab_agent_tool_call" to "collabAgentToolCall",
        "collab_tool_call" to "collabToolCall", "request_user_input" to "requestUserInput",
        "request_approval" to "requestApproval", "todo_list" to "plan")
    private val types = setOf("userMessage", "agentMessage", "reasoning", "requestUserInput", "requestApproval",
        "commandExecution", "local_shell_call", "commandExec", "processExecution", "fileChange", "tool", "mcpToolCall",
        "dynamicToolCall", "function_call", "custom_tool_call", "tool_search_call", "webSearch", "web_search_call",
        "imageView", "imageGeneration", "image_generation_call", "collabAgentToolCall", "collabToolCall", "plan",
        "function_call_output", "custom_tool_call_output", "tool_search_output")

    internal fun map(value: Any?): Map<String, Any?>? = (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
    internal fun string(value: Any?): String? = value?.let(::displayString)?.trim()?.takeIf(String::isNotEmpty)
    private fun integer(value: Any?): Int? = (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull()
    private fun first(row: Map<String, Any?>, vararg keys: String): Any? = keys.firstNotNullOfOrNull { row[it] }
    private fun type(value: Any?): String = string(value).orEmpty().let { aliases[it] ?: it }

    // Match the historic Dart fallback spelling for opaque values, including raw tool summaries.
    internal fun displayString(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}: ${displayString(it.value)}" }
        is List<*> -> value.joinToString(", ", "[", "]", transform = ::displayString)
        else -> value.toString()
    }

    internal fun text(value: Any?, depth: Int = 0): String {
        if (value == null || depth > 32) return ""
        if (value is String) return value
        if (value is List<*>) return value.joinToString("") { text(it, depth + 1) }
        val row = map(value)
        if (row != null) for (key in listOf("text", "content", "message", "input", "value", "delta", "summary", "text_elements", "parts")) {
            text(row[key], depth + 1).takeIf(String::isNotEmpty)?.let { return it }
        }
        return displayString(value)
    }

    fun normalizeResponse(response: Map<String, Any?>): Map<String, Any?> {
        val thread = map(response["thread"]) ?: response
        val turns = (thread["turns"] ?: response["turns"]) as? List<*> ?: return response
        val normalized = turns.map { raw ->
            val turn = map(raw) ?: return@map raw
            // Only one item array crosses the Flutter boundary. Drop redundant transcript envelopes.
            turn.filterKeys { it !in buckets && it != "worklog" } + ("items" to items(turn))
        }
        return if (response["thread"] is Map<*, *>) {
            (response - "turns") + ("thread" to (thread + ("turns" to normalized)))
        } else response + ("turns" to normalized)
    }

    internal fun items(turn: Map<String, Any?>): List<Map<String, Any?>> {
        val rows = linkedMapOf<String, Map<String, Any?>>()
        fun add(value: Any?, depth: Int = 0) {
            if (depth > 32) return
            if (value is List<*>) { value.forEach { add(it, depth + 1) }; return }
            val item = fromValue(value)?.let(::normalizeItem) ?: return
            val id = string(first(item, "id", "itemId", "item_id", "callId", "call_id", "processId", "process_id", "processHandle", "process_handle"))
                ?: stableKey(item)
            val key = "${type(item["type"])}:$id"
            val existing = rows[key]
            rows[key] = if (existing == null) item else existing + item.filterValues { it != null && (it !is String || it.isNotBlank()) }
        }
        buckets.forEach { add(turn[it]) }
        add(map(turn["worklog"])?.get("messages"))
        return rows.values.map { item ->
            if (item["type"] != "userMessage") item else {
                val user = RemoteHistoryUserContent.extract(first(item, "content", "text", "message", "input", "text_elements", "parts"))
                val attachments = if (user.attachments.isNotEmpty()) user.attachments else
                    (item["attachments"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.takeIf { it.all { row -> row["isImage"] == true } }.orEmpty()
                item + mapOf("content" to user.text, "attachments" to attachments)
            }
        }
    }

    private fun stableKey(item: Map<String, Any?>): String {
        val stable = listOf("type", "name", "namespace", "arguments", "action", "execution", "query", "output", "status")
            .associateWith { item[it] }
        var hash = 0x811c9dc5L
        json.toJson(stable).forEach { hash = ((hash xor it.code.toLong()) * 0x01000193L) and 0xffffffffL }
        return "raw-${hash.toString(16).padStart(8, '0')}"
    }

    private fun mergeIds(envelope: Map<String, Any?>, item: Map<String, Any?>) = item +
        envelope.filterKeys { it in idKeys && it !in item }

    private fun fromValue(value: Any?, depth: Int = 0): Map<String, Any?>? {
        if (depth > 32) return null
        val row = map(value) ?: return null
        normalizeItem(row)?.let { return it }
        for (key in listOf("item", "rawItem", "raw_item", "responseItem", "response_item", "params")) {
            fromValue(row[key], depth + 1)?.let { return mergeIds(row, it) }
        }
        val params = map(row["params"]) ?: row
        fromProtocol(params)?.let { return mergeIds(row, it) }
        fromMethod(string(row["method"] ?: row["type"]), params)?.let { return mergeIds(row, it) }
        for (key in envelopes - "params") fromValue(row[key], depth + 1)?.let { return mergeIds(row, it) }
        return null
    }

    private fun normalizeItem(item: Map<String, Any?>): Map<String, Any?>? {
        val normalized = item.toMutableMap()
        var type = type(item["type"])
        val role = string(item["role"] ?: map(item["author"])?.get("role"))?.lowercase()
        if (type == "message" || type.isEmpty()) type = when (role) { "user" -> "userMessage"; "assistant" -> "agentMessage"; else -> type }
        if (type in setOf("output_diff", "pr") && (item["diff"] != null || item["output_diff"] != null)) {
            type = "fileChange"
            if (normalized["changes"] == null) normalized["changes"] = item["diff"] ?: item["output_diff"]
        }
        if (type.isEmpty()) type = when {
            item["command"] != null || item["cmd"] != null -> "commandExecution"
            item["name"] != null && item["arguments"] != null -> "function_call"
            (item["callId"] != null || item["call_id"] != null) && item["output"] != null -> "function_call_output"
            else -> type
        }
        if (type !in types) return null
        normalized["type"] = type
        return normalized
    }

    private fun fromMethod(raw: String?, params: Map<String, Any?>): Map<String, Any?>? {
        val method = raw.orEmpty().trim().replace('.', '/').replace("/command_execution/", "/commandExecution/")
            .replace("/file_change/", "/fileChange/").replace("/mcp_tool_call/", "/mcpToolCall/")
        val lower = method.lowercase()
        for ((camel, snake) in listOf("requestUserInput" to "request_user_input", "requestApproval" to "request_approval")) {
            if (method.endsWith(camel) || lower.endsWith(snake)) return normalizeItem(params + mapOf(
                "id" to (string(params["id"]) ?: string(params["requestId"]) ?: string(params["request_id"])), "type" to camel,
            ))
        }
        if (method.contains("commandExecution") || method in setOf("command/exec/outputDelta", "command/exec/completed", "process/outputDelta", "process/exited")) {
            return normalizeItem(params + mapOf(
                "id" to (string(first(params, "itemId", "item_id", "processId", "process_id", "processHandle", "process_handle")) ?: string(params["id"])),
                "type" to when { method.contains("process") -> "processExecution"; method.contains("command/exec") -> "commandExec"; else -> "commandExecution" },
                "aggregatedOutput" to first(params, "aggregatedOutput", "aggregated_output", "output", "delta", "text"),
                "status" to (params["status"] ?: "completed"),
            ))
        }
        val type = when {
            method.contains("fileChange") || method == "turn/diff/updated" -> "fileChange"
            method.contains("mcpToolCall") -> "mcpToolCall"
            else -> return null
        }
        return normalizeItem(params + mapOf("id" to (string(first(params, "itemId", "item_id")) ?: string(params["id"])),
            "type" to type, "status" to (params["status"] ?: "completed")))
    }

    private fun protocolMsg(root: Map<String, Any?>, depth: Int = 0): Map<String, Any?>? {
        if (depth > 6) return null
        map(root["msg"])?.let { return it }
        for (key in listOf("params", "message", "payload", "data", "event", "notification", "result")) {
            map(root[key])?.let { protocolMsg(it, depth + 1) }?.let { return it }
        }
        return null
    }

    private fun topIds(row: Map<String, Any?>) =
        map(row["_meta"]).orEmpty().filterKeys { it in setOf("threadId", "thread_id") } + row.filterKeys { it in idKeys }

    private fun fromProtocol(value: Map<String, Any?>): Map<String, Any?>? {
        val msg = protocolMsg(value) ?: return null
        val type = string(msg["type"]).orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val callId = string(first(msg, "callId", "call_id", "itemId", "item_id", "processId", "process_id") ?: string(value["id"]))
        fun withIds(item: Map<String, Any?>) = topIds(value) + topIds(msg) +
            (callId?.let { mapOf("id" to it) } ?: emptyMap()) + item
        val item = when (type) {
            "item_started", "item_completed", "raw_response_item" -> return map(msg["item"])?.let(::normalizeItem)
            "agent_message" -> text(msg["message"] ?: msg["text"]).takeIf(String::isNotBlank)
                ?.let { mapOf("type" to "agentMessage", "message" to it) } ?: return null
            "agent_reasoning", "agent_reasoning_raw_content", "reasoning_content_delta", "reasoning_raw_content_delta" ->
                text(msg["delta"] ?: msg["text"]).takeIf(String::isNotBlank)?.let { mapOf("type" to "reasoning", "summary" to it) } ?: return null
            "exec_command_begin", "exec_command_output_delta", "terminal_interaction", "exec_command_end" -> command(msg, type)
            "mcp_tool_call_begin", "mcp_tool_call_end" -> {
                val invocation = map(msg["invocation"]).orEmpty()
                val fields = mcpResult(msg["result"])
                msg + mapOf("type" to "mcpToolCall", "server" to (invocation["server"] ?: msg["server"]),
                    "tool" to (invocation["tool"] ?: msg["tool"]), "arguments" to (invocation["arguments"] ?: msg["arguments"]),
                    "status" to if (type.endsWith("_end")) fields["status"] ?: msg["status"] ?: "completed" else "in_progress") + fields
            }
            "web_search_begin", "web_search_end" -> msg + mapOf("type" to "webSearch",
                "query" to (msg["query"] ?: map(msg["action"])?.get("query")), "status" to if (type.endsWith("_end")) "completed" else "in_progress")
            "view_image_tool_call" -> msg + mapOf("type" to "imageView", "status" to "completed")
            "patch_apply_begin", "patch_apply_updated", "patch_apply_end" -> msg + mapOf("type" to "fileChange",
                "changes" to msg["changes"], "stdout" to msg["stdout"], "stderr" to msg["stderr"], "success" to msg["success"],
                "status" to (string(msg["status"]) ?: if (!type.endsWith("_end")) "in_progress" else if (msg["success"] == false) "failed" else "completed"))
            else -> return null
        }
        return normalizeItem(withIds(item))
    }

    private fun mcpResult(value: Any?): Map<String, Any?> {
        if (value == null) return emptyMap()
        val row = map(value)
        if (row != null) {
            if ("Ok" in row || "ok" in row) return mapOf("status" to "completed", "result" to (row["Ok"] ?: row["ok"]))
            if ("Err" in row || "err" in row) {
                val error = row["Err"] ?: row["err"]
                return mapOf("status" to "failed", "error" to if (error is Map<*, *>) error else mapOf("message" to error))
            }
        }
        return mapOf("status" to "completed", "result" to value)
    }

    private fun command(msg: Map<String, Any?>, type: String): Map<String, Any?> {
        val rawCommand = msg["command"]
        val command = if (rawCommand is List<*>) rawCommand.map { text(it).trim() }.filter(String::isNotEmpty).joinToString(" ")
            .takeIf(String::isNotEmpty) else text(rawCommand).trim().takeIf(String::isNotEmpty)
        val exit = integer(msg["exitCode"] ?: msg["exit_code"])
        val output = if (type == "exec_command_output_delta") outputDelta(msg) else
            text(first(msg, "aggregatedOutput", "aggregated_output", "output", "stdout", "formattedOutput", "formatted_output"))
        return msg + (command?.let { mapOf("command" to it) } ?: emptyMap()) + mapOf(
            "type" to "commandExecution", "cwd" to msg["cwd"], "processId" to (msg["processId"] ?: msg["process_id"]),
            "process_id" to (msg["process_id"] ?: msg["processId"]), "aggregatedOutput" to output, "aggregated_output" to output,
            "stdout" to msg["stdout"], "stderr" to msg["stderr"], "exitCode" to exit, "exit_code" to exit,
            "status" to (string(msg["status"]) ?: when { type == "exec_command_begin" -> "in_progress"; exit == null || exit == 0 -> "completed"; else -> "failed" }),
        )
    }

    private fun outputDelta(msg: Map<String, Any?>): String {
        fun base64(raw: Any?) = (raw as? String)?.trim()?.let { runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
        fun bytes(raw: Any?): String? {
            val values = raw as? List<*> ?: return null
            val bytes = values.map { integer(it)?.takeIf { byte -> byte in 0..255 }?.toByte() ?: return null }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
        val decoded = base64(msg["chunk"]) ?: bytes(msg["chunk"]) ?: base64(msg["deltaBase64"]) ?: base64(msg["delta_base64"])
            ?: text(first(msg, "delta", "output", "text"))
        val stream = string(msg["stream"])?.lowercase()
        return if (decoded.isEmpty() || stream == null || stream == "stdout") decoded
            else "\n[$stream]\n$decoded${if (decoded.endsWith('\n')) "" else "\n"}"
    }
}
