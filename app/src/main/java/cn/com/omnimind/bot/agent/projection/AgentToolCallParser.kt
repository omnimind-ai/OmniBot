package cn.com.omnimind.bot.agent.projection

/**
 * Kotlin port of `ui/lib/services/agent_tool_call_parser.dart`.
 *
 * Pure functions that normalize ACP / Codex tool-call payloads into the shared
 * tool card shape. Keep this file mechanically comparable with the Dart
 * source: same function names, same branch order, same fallbacks.
 *
 * Note: this file's own `_asStringMap` decodes JSON strings and always copies,
 * and its `_firstString` only accepts String/num/bool, so both are private
 * helpers here ([asToolStringMap], [firstToolString]) instead of the generic
 * ProjectionValues helpers.
 */
internal data class AgentToolCallInfo(
    val itemType: String,
    val toolType: String,
    val toolName: String,
    val displayName: String,
    val toolTitle: String,
    val status: String,
    val arguments: JsonMap,
    val argsJson: String,
    val resultPreviewJson: String,
    val rawResultJson: String,
    val terminalOutput: String,
    val summary: String,
    val progress: String,
    val serverName: String? = null,
)

internal fun normalizeAgentToolCall(
    raw: Map<String, Any?>,
    itemType: String? = null,
    fallbackToolType: String? = null,
    fallbackTitle: String? = null,
    fallbackStatus: String = "running",
): AgentToolCallInfo {
    val type = canonicalAgentItemType(firstToolString(listOf(itemType, raw["type"])))
    val arguments = normalizedArguments(raw)
    val rawToolName = canonicalAgentToolName(resolveToolName(raw, itemType = type))
    val toolType = inferToolType(
        itemType = type,
        explicitToolType = visualToolType(
            firstToolString(listOf(raw["toolType"], raw["tool_type"])),
        ),
        fallbackToolType = fallbackToolType,
        toolName = rawToolName,
        arguments = arguments,
    )
    val status = normalizeAgentToolStatus(raw, fallbackStatus = fallbackStatus)
    val title = resolveToolTitle(
        raw,
        itemType = type,
        toolType = toolType,
        toolName = rawToolName,
        arguments = arguments,
        fallbackTitle = fallbackTitle,
    )
    val toolName = rawToolName ?: defaultToolName(type, toolType)
    val displayName =
        firstToolString(listOf(raw["displayName"], raw["display_name"], raw["name"])) ?: title
    val serverName = firstToolString(listOf(raw["serverName"], raw["server"]))
    // Live ACP and restored tool events share this parser. Read result details
    // without promoting nested status/identity into the owning lifecycle.
    val storedResult = if (toolType == "terminal") asToolStringMap(raw["rawResultJson"]) else null
    val commandResult = if (toolType == "terminal") {
        asToolStringMap(raw["rawOutput"] ?: storedResult?.get("rawOutput"))
    } else {
        null
    }
    val exitCode = asToolInt(
        raw["exitCode"] ?: raw["exit_code"]
            ?: commandResult?.get("exitCode") ?: commandResult?.get("exit_code")
            ?: storedResult?.get("exitCode") ?: storedResult?.get("exit_code"),
    )
    val terminalOutput = firstOutputString(
        listOf(
            raw["terminalOutput"],
            raw["aggregatedOutput"],
            raw["aggregated_output"],
            raw["output"],
            raw["stdout"],
            asToolStringMap(raw["result"])?.get("stdout"),
            asToolStringMap(raw["result"])?.get("output"),
            commandResult?.get("terminalOutput"),
            commandResult?.get("formatted_output"),
            storedResult?.get("terminalOutput"),
        ),
    )
    val summaryCandidates = mutableListOf(raw["summary"], raw["message"], raw["description"])
    if (type != "commandExecution" && !(toolType == "terminal" && exitCode != null)) {
        summaryCandidates.add(raw["status"])
    }
    val summary = firstToolString(summaryCandidates)
        ?: if (toolType == "terminal" &&
            setOf("success", "error").contains(status) &&
            exitCode != null
        ) {
            "Command exited with code $exitCode"
        } else {
            ""
        }
    val progress =
        firstToolString(listOf(raw["progress"], raw["message"], raw["delta"])) ?: ""

    val rawInput = raw["rawInput"]
    return AgentToolCallInfo(
        itemType = type,
        toolType = toolType,
        toolName = toolName,
        displayName = displayName,
        toolTitle = title,
        status = status,
        arguments = arguments,
        // Official ACP rawInput can be an unfinished JSON string while streaming.
        // Display it losslessly; parsing is not a prerequisite for a progress update.
        argsJson = if (rawInput is String) {
            rawInput
        } else if (arguments.isEmpty()) {
            ""
        } else {
            safeJson(arguments)
        },
        resultPreviewJson = resultPreviewJson(raw),
        rawResultJson = safeJson(raw),
        terminalOutput = terminalOutput ?: "",
        summary = summary,
        progress = progress,
        serverName = serverName,
    )
}

private val canonicalItemTypes: Map<String, String> = mapOf(
    "agent_message" to "agentMessage",
    "user_message" to "userMessage",
    "command_execution" to "commandExecution",
    "file_change" to "fileChange",
    "mcp_tool_call" to "mcpToolCall",
    "dynamic_tool_call" to "dynamicToolCall",
    "web_search" to "webSearch",
    "image_view" to "imageView",
    "image_generation" to "imageGeneration",
    "collab_agent_tool_call" to "collabAgentToolCall",
    "collab_tool_call" to "collabToolCall",
    "request_user_input" to "requestUserInput",
    "request_approval" to "requestApproval",
    "todo_list" to "plan",
)

internal fun canonicalAgentItemType(itemType: String?): String {
    val normalized = itemType?.trim() ?: ""
    if (normalized.isEmpty()) {
        return ""
    }
    return canonicalItemTypes[normalized] ?: normalized
}

internal fun canonicalAgentToolName(toolName: String?): String? {
    val normalized = toolName?.trim()
    if (normalized == null || normalized.isEmpty()) {
        return null
    }
    if (normalized.startsWith("codex.")) {
        return "agent.${normalized.substring("codex.".length)}"
    }
    if (normalized.startsWith("codex/")) {
        return "agent/${normalized.substring("codex/".length)}"
    }
    return normalized
}

/**
 * ACP ToolCallStatus is the only lifecycle source of truth. rawOutput and
 * rawResult are opaque tool data and are never read for status.
 */
internal fun normalizeAgentToolStatus(
    raw: Map<String, Any?>,
    fallbackStatus: String = "running",
): String {
    val explicit = firstToolString(listOf(raw["status"], raw["state"]))
    val normalized = explicit?.trim()?.lowercase()
    if (normalized != null && normalized.isNotEmpty()) {
        if (normalized == "pending") {
            return "pending"
        }
        if (normalized == "in_progress" ||
            normalized == "running" ||
            normalized == "progress" ||
            normalized == "inprogress" ||
            normalized == "executing" ||
            normalized == "started"
        ) {
            return "running"
        }
        if (normalized == "completed" ||
            normalized == "success" ||
            normalized == "succeeded" ||
            normalized == "complete" ||
            normalized == "applied" ||
            normalized == "done"
        ) {
            return "success"
        }
        if (normalized == "failed" ||
            normalized == "error" ||
            normalized == "failure" ||
            normalized == "rejected"
        ) {
            return "error"
        }
        if (normalized == "cancelled" ||
            normalized == "canceled" ||
            normalized == "incomplete" ||
            normalized == "interrupted" ||
            normalized == "aborted"
        ) {
            return "interrupted"
        }
        if (normalized == "timeout" || normalized == "timedout") {
            return "timeout"
        }
    }
    // Non-ACP item snapshots do not always carry a status. Keep their
    // compatibility projection based on fields at the item boundary only;
    // never inspect rawOutput/rawResult for a lifecycle decision.
    if (raw["error"] != null || raw["success"] == false) {
        return "error"
    }
    val exitCode = asToolInt(raw["exitCode"] ?: raw["exit_code"])
    if (exitCode != null && exitCode != 0L) {
        return "error"
    }
    if (raw["success"] == true) {
        return "success"
    }
    return fallbackStatus
}

internal fun agentToolStatusIsExplicit(raw: Map<String, Any?>): Boolean {
    return firstToolString(listOf(raw["status"], raw["state"])) != null ||
        raw.containsKey("success") ||
        raw.containsKey("error") ||
        raw.containsKey("exitCode") ||
        raw.containsKey("exit_code")
}

internal fun agentToolCardSuffix(toolType: String, itemType: String? = null): String {
    val canonicalItemType = canonicalAgentItemType(itemType)
    if (canonicalItemType == "fileChange" || toolType == "file") {
        return "file"
    }
    if (canonicalItemType == "plan" || toolType == "plan") {
        return "plan"
    }
    if (toolType == "search") {
        return "search"
    }
    if (toolType == "workspace") {
        return "workspace"
    }
    if (toolType == "browser") {
        return "browser"
    }
    if (toolType == "image") {
        return "image"
    }
    if (isCommandLikeItemType(canonicalItemType) || toolType == "terminal") {
        return "command"
    }
    return "tool"
}

private val agentToolItemTypes: Set<String> = setOf(
    "commandExecution",
    "local_shell_call",
    "commandExec",
    "processExecution",
    "fileChange",
    "tool",
    "mcpToolCall",
    "dynamicToolCall",
    "function_call",
    "function_call_output",
    "custom_tool_call",
    "custom_tool_call_output",
    "tool_search_call",
    "tool_search_output",
    "webSearch",
    "web_search_call",
    "imageView",
    "imageGeneration",
    "image_generation_call",
    "collabAgentToolCall",
    "collabToolCall",
    "plan",
)

internal fun isAgentToolItemType(itemType: String): Boolean {
    val canonicalItemType = canonicalAgentItemType(itemType)
    return agentToolItemTypes.contains(canonicalItemType)
}

private val agentToolOutputItemTypes: Set<String> = setOf(
    "function_call_output",
    "custom_tool_call_output",
    "tool_search_output",
)

internal fun isAgentToolOutputItemType(itemType: String): Boolean {
    return agentToolOutputItemTypes.contains(itemType)
}

private fun isCommandLikeItemType(itemType: String?): Boolean {
    val canonicalItemType = canonicalAgentItemType(itemType)
    return canonicalItemType == "commandExecution" ||
        itemType == "local_shell_call" ||
        canonicalItemType == "commandExec" ||
        canonicalItemType == "processExecution"
}

private val normalizedArgumentKeys: List<String> = listOf(
    "command",
    "cmd",
    "cwd",
    "workingDirectory",
    "working_directory",
    "query",
    "q",
    "url",
    "uri",
    "path",
    "file",
    "target",
    "filePath",
    "file_path",
    "filename",
    "fileName",
    "pattern",
    "regex",
    "glob",
    "include",
    "queryText",
    "query_text",
    "action",
    "tool",
    "server",
    "namespace",
    "prompt",
    "execution",
    "items",
)

private fun normalizedArguments(raw: Map<String, Any?>): JsonMap {
    val args: JsonMap = LinkedHashMap()
    val parsed = toolArguments(raw)
    args.putAll(parsed)
    for (key in listOf("command", "cmd")) {
        val normalizedCommand = commandFromValue(args[key])
        if (normalizedCommand != null) {
            args[key] = normalizedCommand
        }
    }
    val action = asToolStringMap(raw["action"])

    fun add(key: String, value: Any?) {
        if (args.containsKey(key) || value == null) {
            return
        }
        val text = if (value is String) value.trim() else null
        if (text != null && text.isEmpty()) {
            return
        }
        args[key] = value
    }

    for (key in normalizedArgumentKeys) {
        val value = if (key == "command" || key == "cmd") {
            commandFromValue(raw[key])
        } else {
            raw[key]
        }
        add(key, value)
    }
    add("command", commandFromValue(action?.get("command")))
    add("cmd", commandFromValue(raw["command"]))
    add("workingDirectory", action?.get("working_directory"))
    add("workingDirectory", action?.get("workingDirectory"))
    add("cwd", action?.get("cwd"))
    if (raw["changes"] != null) {
        add("changes", raw["changes"])
    }
    if (raw["files"] != null) {
        add("files", raw["files"])
    }
    val parsedCommands = ArrayList<JsonMap>()
    parsedCommands.addAll(agentParsedCommands(raw))
    parsedCommands.addAll(agentParsedCommands(parsed))
    parsedCommands.addAll(agentParsedCommands(action))
    if (parsedCommands.isNotEmpty()) {
        args["parsedCommands"] = parsedCommands
    }
    return args
}

private val parsedCommandKeys: List<String> = listOf(
    "parsedCommands",
    "parsed_commands",
    "parsedCmd",
    "parsed_cmd",
    "commandActions",
    "command_actions",
)

private fun agentParsedCommands(value: Any?): List<JsonMap> {
    if (value is List<*>) {
        return value.filterIsInstance<Map<*, *>>().map { copyStringMap(it)!! }
    }
    if (value is Map<*, *>) {
        for (key in parsedCommandKeys) {
            val raw = value[key]
            if (raw is List<*>) {
                return agentParsedCommands(raw)
            }
        }
    }
    return emptyList()
}

private val nonAlphanumericRun = Regex("[^a-z0-9]+")

private fun firstAgentCommandAction(arguments: Map<String, Any?>): AgentParsedCommandAction? {
    val list = agentParsedCommands(arguments)
    if (list.isEmpty()) {
        return null
    }
    var fallback: AgentParsedCommandAction? = null
    for (entry in list) {
        val typeRaw = firstToolString(listOf(entry["type"])) ?: continue
        val normalized = typeRaw.trim().lowercase().replace(nonAlphanumericRun, "_")
        val mappedType = when (normalized) {
            "read" -> "read"
            "list_files", "listfiles", "list" -> "listFiles"
            "search", "grep", "find" -> "search"
            else -> "unknown"
        }
        val action = AgentParsedCommandAction(
            type = mappedType,
            command = commandFromValue(entry["command"]) ?: commandFromValue(entry["cmd"]),
            name = firstToolString(listOf(entry["name"])),
            path = firstToolString(listOf(entry["path"])),
            query = firstToolString(listOf(entry["query"])),
        )
        if (mappedType != "unknown") {
            return action
        }
        if (fallback == null) fallback = action
    }
    return fallback
}

private fun titleFromParsedCommandAction(action: AgentParsedCommandAction): String? {
    when (action.type) {
        "read" -> {
            val target = action.name
                ?: (if (action.path == null) null else lastPathSegment(action.path))
                ?: action.path
            if (target != null && target.isNotEmpty()) {
                return "Read $target"
            }
            return null
        }
        "listFiles" -> {
            val target = action.path
            if (target == null || target.isEmpty()) {
                return "List files"
            }
            return "List ${lastPathSegment(target) ?: target}"
        }
        "search" -> {
            if (action.query != null && action.query.isNotEmpty()) {
                return "Search: ${action.query}"
            }
            if (action.path != null && action.path.isNotEmpty()) {
                return "Search ${lastPathSegment(action.path) ?: action.path}"
            }
            return null
        }
    }
    return null
}

internal data class AgentParsedCommandAction(
    val type: String,
    val command: String? = null,
    val name: String? = null,
    val path: String? = null,
    val query: String? = null,
)

private fun toolArguments(raw: Map<String, Any?>): JsonMap {
    for (key in listOf("arguments", "args", "input", "rawInput", "raw_input")) {
        val map = asToolStringMap(raw[key])
        if (map != null) {
            return map
        }
        val text = toolString(raw[key])
        if (text == null || text.trim().isEmpty()) {
            continue
        }
        try {
            val decoded = DartJson.decode(text)
            val decodedMap = asToolStringMap(decoded)
            if (decodedMap != null) {
                return decodedMap
            }
        } catch (_: Exception) {
            continue
        }
    }
    return LinkedHashMap()
}

private fun resolveToolName(raw: Map<String, Any?>, itemType: String): String? {
    val toolValue = raw["tool"]
    val toolString = if (toolValue is String) toolValue else null
    val actionType = firstToolString(listOf(asToolStringMap(raw["action"])?.get("type")))
    if (itemType == "local_shell_call" && actionType != null) {
        return "local_shell.$actionType"
    }
    return firstToolString(
        listOf(
            raw["toolName"],
            raw["tool_name"],
            raw["name"],
            raw["functionName"],
            raw["function_name"],
            asToolStringMap(raw["function"])?.get("name"),
            asToolStringMap(raw["tool"])?.get("name"),
            raw["execution"],
            toolString,
        ),
    )
}

/**
 * Some adapters use `context` as a result-envelope name rather than a UI
 * capability; infer the actual card route from the tool's facts instead.
 */
private fun visualToolType(value: String?): String? {
    val normalized = value?.trim()
    if (normalized == null || normalized.isEmpty()) {
        return null
    }
    return if (normalized.lowercase() == "context") null else normalized
}

private fun inferToolType(
    itemType: String,
    explicitToolType: String?,
    fallbackToolType: String?,
    toolName: String?,
    arguments: Map<String, Any?>,
): String {
    val canonicalItemType = canonicalAgentItemType(itemType)
    val explicit = explicitToolType?.trim()
    if (explicit != null && explicit.isNotEmpty()) {
        return explicit
    }
    when (canonicalItemType) {
        "commandExecution", "local_shell_call", "commandExec", "processExecution" -> {
            val action = firstAgentCommandAction(arguments)
            if (action != null) {
                when (action.type) {
                    "read", "listFiles" -> return "workspace"
                    "search" -> return "search"
                }
            }
            return inferToolTypeFromCommand(arguments) ?: "terminal"
        }
        "fileChange" -> return "file"
        "webSearch", "web_search_call", "tool_search_call", "tool_search_output" -> return "search"
        "imageView", "imageGeneration", "image_generation_call" -> return "image"
        "collabAgentToolCall", "collabToolCall" -> return "subagent"
        "plan" -> return "plan"
    }

    val fullName = (toolName ?: "").trim().lowercase()
    val shortName = shortToolName(fullName).lowercase()
    val name = "$fullName $shortName"
    // Subagent dispatch is a distinct collaboration capability. Resolve it
    // before generic read/file/name heuristics.
    if (containsAny(name, listOf("subagent", "sub_agent", "delegate_agent"))) {
        return "subagent"
    }
    val commandToolType = inferToolTypeFromCommand(arguments)
    if (commandToolType != null && looksLikeCommandToolName(name)) {
        return commandToolType
    }
    if (containsAny(
            name,
            listOf("terminal", "shell", "exec", "command", "bash", "zsh", "powershell"),
        )
    ) {
        return "terminal"
    }
    if (containsAny(shortName, listOf("edit", "write", "patch", "apply_patch"))) {
        return "file"
    }
    if (containsAny(
            name,
            listOf(
                "read",
                "view",
                "open_file",
                "read_file",
                "read_text",
                "read_many_files",
                "cat",
                "sed",
                "list",
                "glob",
                "grep",
                "workspace",
                "file_search",
                "search_file",
            ),
        )
    ) {
        return "workspace"
    }
    if (containsAny(name, listOf("web", "browser", "fetch", "open_url"))) {
        return "browser"
    }
    if (containsAny(name, listOf("search", "query"))) {
        return "search"
    }
    if (containsAny(name, listOf("image", "screenshot", "view_image"))) {
        return "image"
    }
    if (containsAny(name, listOf("memory"))) {
        return "memory"
    }
    if (containsAny(name, listOf("alarm", "reminder"))) {
        return "alarm"
    }
    if (containsAny(name, listOf("schedule", "scheduled", "timer"))) {
        return "schedule"
    }
    if (containsAny(name, listOf("calendar", "calendar_event"))) {
        return "calendar"
    }
    if (canonicalItemType == "mcpToolCall") {
        return "mcp"
    }
    val fallback = fallbackToolType?.trim()
    if (fallback != null && fallback.isNotEmpty()) {
        return fallback
    }
    return "tool"
}

/** Dart (JavaScript) `\s` character class. */
private const val DART_WHITESPACE =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

/**
 * Dart `RegExp(r'(^|[;&|]\s*)(git\s+grep|rg|grep|fd|find|ag|ack)\b')`, with
 * `\s` and `\b` spelled out in Dart's ASCII/JS semantics.
 */
private val searchCommandPattern = Regex(
    "(^|[;&|]$DART_WHITESPACE*)(git$DART_WHITESPACE+grep|rg|grep|fd|find|ag|ack)(?![A-Za-z0-9_])",
)

private fun inferToolTypeFromCommand(arguments: Map<String, Any?>): String? {
    val command = firstToolString(listOf(arguments["command"], arguments["cmd"])) ?: return null
    val normalized = command.trim().lowercase()
    if (normalized.isEmpty()) {
        return null
    }
    if (searchCommandPattern.containsMatchIn(normalized)) {
        return "search"
    }
    return null
}

private fun looksLikeCommandToolName(name: String): Boolean {
    return containsAny(
        name,
        listOf("terminal", "shell", "exec", "command", "bash", "zsh", "powershell"),
    )
}

private val searchTitleShortNames: Set<String> = setOf(
    "rg",
    "grep",
    "git_grep",
    "search",
    "search_files",
    "file_search",
    "tool_search",
)

private fun shouldUseSearchTitle(
    itemType: String,
    toolType: String,
    toolName: String?,
    arguments: Map<String, Any?>,
): Boolean {
    if (toolType != "search") {
        return false
    }
    if (inferToolTypeFromCommand(arguments) == "search") {
        return true
    }
    val canonicalItemType = canonicalAgentItemType(itemType)
    if (canonicalItemType == "webSearch" ||
        canonicalItemType == "tool_search_call" ||
        canonicalItemType == "tool_search_output"
    ) {
        return true
    }
    val shortName = if (toolName == null) "" else shortToolName(toolName).trim().lowercase()
    return searchTitleShortNames.contains(shortName)
}

private fun hasFallbackTitle(fallbackTitle: String?): Boolean =
    fallbackTitle?.trim()?.isNotEmpty() == true

private fun resolveToolTitle(
    raw: Map<String, Any?>,
    itemType: String,
    toolType: String,
    toolName: String?,
    arguments: Map<String, Any?>,
    fallbackTitle: String?,
): String {
    val canonicalItemType = canonicalAgentItemType(itemType)
    val explicit = firstToolString(
        listOf(
            raw["toolTitle"],
            raw["tool_title"],
            raw["displayName"],
            raw["display_name"],
            arguments["toolTitle"],
            arguments["tool_title"],
            arguments["displayName"],
            arguments["display_name"],
            arguments["description"],
            raw["description"],
        ),
    )
    if (explicit != null) {
        return compactTitle(explicit, maxLength = 48)
    }

    if (isCommandLikeItemType(canonicalItemType) || toolType == "terminal") {
        val action = firstAgentCommandAction(arguments)
        if (action != null) {
            val actionTitle = titleFromParsedCommandAction(action)
            if (actionTitle != null) {
                return compactTitle(actionTitle, maxLength = 48)
            }
        }
        val command = firstToolString(
            listOf(
                raw["command"],
                arguments["command"],
                raw["cmd"],
                arguments["cmd"],
                commandFromValue(asToolStringMap(raw["action"])?.get("command")),
                raw["processId"],
                raw["processHandle"],
            ),
        )
        if (command != null) {
            return compactTitle(command, maxLength = 48)
        }
        val acpTitle = firstToolString(listOf(raw["title"]))
        if (acpTitle != null) {
            return compactTitle(acpTitle, maxLength = 48)
        }
        return if (hasFallbackTitle(fallbackTitle)) {
            compactTitle(fallbackTitle!!, maxLength = 48)
        } else {
            "Agent command"
        }
    }

    if (canonicalItemType == "fileChange" || toolType == "file") {
        val acpTitle = firstToolString(listOf(raw["title"]))
        if (acpTitle != null) {
            return compactTitle(acpTitle, maxLength = 48)
        }
        val path = resolvePath(raw, arguments)
        if (path != null) {
            return compactTitle("Edit ${lastPathSegment(path) ?: path}", maxLength = 42)
        }
        return if (hasFallbackTitle(fallbackTitle)) {
            compactTitle(fallbackTitle!!, maxLength = 48)
        } else {
            "Agent file change"
        }
    }

    if (canonicalItemType == "webSearch" || itemType == "web_search_call") {
        val query = firstToolString(
            listOf(
                raw["query"],
                arguments["query"],
                arguments["q"],
                asToolStringMap(raw["action"])?.get("query"),
            ),
        )
        if (query != null) {
            return compactTitle("Search: $query", maxLength = 48)
        }
        return "Web search"
    }

    if (shouldUseSearchTitle(
            itemType = itemType,
            toolType = toolType,
            toolName = toolName,
            arguments = arguments,
        )
    ) {
        val command = firstToolString(listOf(arguments["command"], arguments["cmd"]))
        if (command != null) {
            return compactTitle(command, maxLength = 48)
        }
        val query = firstToolString(
            listOf(
                raw["query"],
                arguments["query"],
                arguments["q"],
                raw["execution"],
                arguments["execution"],
            ),
        )
        if (query != null) {
            return compactTitle("Search: $query", maxLength = 48)
        }
        return if (hasFallbackTitle(fallbackTitle)) {
            compactTitle(fallbackTitle!!, maxLength = 48)
        } else {
            "Agent search"
        }
    }

    if (canonicalItemType == "imageView") {
        val path = resolvePath(raw, arguments)
        if (path != null) {
            return compactTitle("View ${lastPathSegment(path) ?: path}", maxLength = 48)
        }
        return "View image"
    }

    if (canonicalItemType == "imageGeneration") {
        return "Generate image"
    }

    if (canonicalItemType == "collabAgentToolCall" || canonicalItemType == "collabToolCall") {
        val prompt = firstToolString(listOf(raw["prompt"], arguments["prompt"]))
        if (prompt != null) {
            return compactTitle("Subagent: $prompt", maxLength = 48)
        }
        val name = if (toolName == null) "Subagent" else shortToolName(toolName)
        return compactTitle(name, maxLength = 48)
    }

    if (canonicalItemType == "plan" || toolType == "plan") {
        return "Agent plan"
    }

    if (isAgentToolOutputItemType(itemType)) {
        val outputName = if (toolName == null) null else shortToolName(toolName)
        if (outputName != null && outputName.isNotEmpty()) {
            return compactTitle("$outputName output", maxLength = 48)
        }
        return if (hasFallbackTitle(fallbackTitle)) {
            compactTitle(fallbackTitle!!, maxLength = 48)
        } else {
            "Agent tool output"
        }
    }

    // MCP/dynamic/custom/function invocations frequently carry a human-readable
    // `title` (or `description`) in arguments; surface that as the card title.
    if (canonicalItemType == "mcpToolCall" ||
        canonicalItemType == "dynamicToolCall" ||
        canonicalItemType == "function_call" ||
        itemType == "custom_tool_call"
    ) {
        val invocationTitle = firstToolString(
            listOf(
                arguments["title"],
                raw["title"],
                arguments["description"],
                arguments["summary"],
                raw["description"],
            ),
        )
        if (invocationTitle != null) {
            return compactTitle(invocationTitle, maxLength = 64)
        }
    }

    val shortName = if (toolName == null) null else shortToolName(toolName)
    val command = firstToolString(listOf(arguments["command"], arguments["cmd"]))
    if (command != null) {
        return compactTitle(command, maxLength = 48)
    }
    val detail = firstToolString(
        listOf(
            arguments["query"],
            arguments["q"],
            arguments["url"],
            arguments["uri"],
            arguments["path"],
            arguments["file"],
            arguments["target"],
            arguments["filePath"],
            arguments["file_path"],
            arguments["filename"],
            arguments["fileName"],
            arguments["pattern"],
            arguments["regex"],
            arguments["glob"],
            arguments["include"],
            raw["query"],
            raw["url"],
            raw["path"],
            raw["file"],
            raw["target"],
        ),
    )
    if (detail != null) {
        val operationTitle = operationTitle(shortName, detail)
        if (operationTitle != null) {
            return compactTitle(operationTitle, maxLength = 48)
        }
        val detailTitle = if (looksLikePath(detail)) {
            lastPathSegment(detail) ?: detail
        } else {
            detail
        }
        if (shortName != null && shortName.isNotEmpty()) {
            return compactTitle("$shortName: $detailTitle", maxLength = 48)
        }
        return compactTitle(detailTitle, maxLength = 48)
    }

    if (hasFallbackTitle(fallbackTitle)) {
        return compactTitle(fallbackTitle!!, maxLength = 48)
    }
    val acpTitle = firstToolString(listOf(raw["title"]))
    if (acpTitle != null) {
        return compactTitle(acpTitle, maxLength = 48)
    }
    if (shortName != null && shortName.isNotEmpty()) {
        return compactTitle(shortName, maxLength = 48)
    }
    return "Agent tool"
}

private fun operationTitle(shortName: String?, detail: String): String? {
    val name = (shortName ?: "").trim().lowercase()
    if (name.isEmpty()) {
        return null
    }
    val target = if (looksLikePath(detail)) lastPathSegment(detail) ?: detail else detail
    if (name == "read" ||
        name == "read_file" ||
        name == "readfile" ||
        name == "view_file" ||
        name == "open_file" ||
        name == "read_text" ||
        name == "read_many_files" ||
        name == "cat" ||
        name == "sed"
    ) {
        return "Read $target"
    }
    if (name == "list" ||
        name == "list_files" ||
        name == "list_directory" ||
        name == "ls"
    ) {
        return "List $target"
    }
    if (name == "write" || name == "write_file" || name == "writefile") {
        return "Write $target"
    }
    if (name == "edit" || name == "edit_file" || name == "apply_patch") {
        return "Edit $target"
    }
    if (name == "grep" ||
        name == "rg" ||
        name == "fd" ||
        name == "find" ||
        name == "glob" ||
        name == "search" ||
        name == "search_files" ||
        name == "file_search"
    ) {
        return "Search $target"
    }
    return null
}

private fun resolvePath(raw: Map<String, Any?>, args: Map<String, Any?>): String? {
    return firstToolString(
        listOf(
            raw["path"],
            raw["file"],
            raw["target"],
            raw["filePath"],
            raw["file_path"],
            raw["filename"],
            raw["fileName"],
            args["path"],
            args["file"],
            args["target"],
            args["filePath"],
            args["file_path"],
            args["filename"],
            args["fileName"],
            firstPathFromList(raw["files"]),
            firstPathFromList(raw["changes"]),
            firstPathFromList(args["files"]),
            firstPathFromList(args["changes"]),
        ),
    )
}

private fun firstPathFromList(value: Any?): String? {
    if (value is String) {
        val decoded = decodeJson(value)
        return firstPathFromList(decoded)
    }
    if (value !is List<*>) {
        return null
    }
    for (item in value) {
        if (item is String && item.trim().isNotEmpty()) {
            val decoded = decodeJson(item)
            val decodedPath = firstPathFromList(decoded)
            if (decodedPath != null) {
                return decodedPath
            }
            return item.trim()
        }
        val map = asToolStringMap(item)
        val path = firstToolString(
            listOf(
                map?.get("path"),
                map?.get("filePath"),
                map?.get("file_path"),
                map?.get("filename"),
                map?.get("fileName"),
            ),
        )
        if (path != null) {
            return path
        }
    }
    return null
}

private fun defaultToolName(itemType: String, toolType: String): String {
    val canonicalItemType = canonicalAgentItemType(itemType)
    if (canonicalItemType == "local_shell_call") {
        return "agent.localShell"
    }
    if (canonicalItemType == "commandExec") {
        return "agent.commandExec"
    }
    if (canonicalItemType == "processExecution") {
        return "agent.process"
    }
    if (canonicalItemType == "function_call") {
        return "agent.functionCall"
    }
    if (canonicalItemType == "function_call_output") {
        return "agent.functionOutput"
    }
    if (canonicalItemType == "custom_tool_call") {
        return "agent.customTool"
    }
    if (canonicalItemType == "custom_tool_call_output") {
        return "agent.customToolOutput"
    }
    if (canonicalItemType == "tool_search_call") {
        return "agent.toolSearch"
    }
    if (canonicalItemType == "tool_search_output") {
        return "agent.toolSearchOutput"
    }
    if (canonicalItemType == "web_search_call") {
        return "agent.webSearch"
    }
    if (canonicalItemType == "image_generation_call") {
        return "agent.imageGeneration"
    }
    if (canonicalItemType == "mcpToolCall") {
        return "agent.mcp"
    }
    if (canonicalItemType == "dynamicToolCall") {
        return "agent.dynamicTool"
    }
    if (canonicalItemType == "webSearch") {
        return "agent.webSearch"
    }
    if (canonicalItemType == "imageView") {
        return "agent.imageView"
    }
    if (canonicalItemType == "imageGeneration") {
        return "agent.imageGeneration"
    }
    if (canonicalItemType == "collabAgentToolCall" || canonicalItemType == "collabToolCall") {
        return "agent.collabAgent"
    }
    return "agent.$toolType"
}

private fun resultPreviewJson(raw: Map<String, Any?>): String {
    val result = raw["result"]
        ?: raw["output"]
        ?: raw["contentItems"]
        ?: raw["content_items"]
    if (result != null) {
        return safeJson(result)
    }
    val error = raw["error"]
    if (error != null) {
        return safeJson(jsonMapOf("error" to error))
    }
    return ""
}

private fun decodeJson(text: String): Any? {
    val normalized = text.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return runCatching { DartJson.decode(normalized) }.getOrNull()
}

/** This file's Dart `_asStringMap`: decodes JSON strings, always copies. */
private fun asToolStringMap(value: Any?): JsonMap? {
    if (value is String) {
        return asToolStringMap(decodeJson(value))
    }
    if (value !is Map<*, *>) {
        return null
    }
    return copyStringMap(value)
}

/** This file's Dart `_firstString`: only String/num/bool values count. */
private fun firstToolString(values: Iterable<Any?>): String? {
    for (value in values) {
        val text = toolString(value)
        if (text != null && text.trim().isNotEmpty()) {
            return text.trim()
        }
    }
    return null
}

private fun firstOutputString(values: Iterable<Any?>): String? {
    for (value in values) {
        if (value == null) {
            continue
        }
        if (value is String) {
            if (value.isNotEmpty()) {
                return value
            }
            continue
        }
        if (value is Number || value is Boolean) {
            return dartScalarToString(value)
        }
    }
    return null
}

/** Dart `_string`. */
private fun toolString(value: Any?): String? {
    if (value == null) {
        return null
    }
    if (value is String) {
        return value
    }
    if (value is Number || value is Boolean) {
        return dartScalarToString(value)
    }
    return null
}

/** Dart `num.toString()` / `bool.toString()`. */
private fun dartScalarToString(value: Any): String = when (value) {
    is Double -> dartDoubleToString(value)
    is Float -> dartDoubleToString(value.toDouble())
    else -> value.toString()
}

private fun commandFromValue(value: Any?): String? {
    if (value == null) {
        return null
    }
    if (value is String) {
        return if (value.trim().isEmpty()) null else value
    }
    if (value is List<*>) {
        val parts = value
            .mapNotNull { toolString(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
    return toolString(value)
}

private val dartIntLiteral = Regex("^([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))$")

/**
 * This file's Dart `_asInt` (Dart `int` is 64-bit, hence Long). `num.toInt()`
 * throws on NaN/Infinity in Dart; `int.tryParse` accepts a sign and `0x` hex.
 */
private fun asToolInt(value: Any?): Long? {
    if (value is Int) return value.toLong()
    if (value is Long) return value
    if (value is Short) return value.toLong()
    if (value is Byte) return value.toLong()
    if (value is Number) {
        val double = value.toDouble()
        if (!double.isFinite()) {
            throw UnsupportedOperationException("Unsupported operation: $double")
        }
        return double.toLong()
    }
    val text = (value ?: "").toString().trim()
    val match = dartIntLiteral.matchEntire(text) ?: return null
    val sign = match.groupValues[1]
    val hex = match.groupValues[2]
    return if (hex.isNotEmpty()) {
        (sign + hex).toLongOrNull(16)
    } else {
        (sign + match.groupValues[3]).toLongOrNull()
    }
}

private val namespaceSeparator = Regex("[./:]")

private fun shortToolName(value: String): String {
    val normalized = value.trim()
    if (normalized.isEmpty()) {
        return ""
    }
    val withoutNamespace = normalized.split(namespaceSeparator).last()
    val parts = withoutNamespace.split("__").filter { it.isNotEmpty() }
    return if (parts.isEmpty()) withoutNamespace else parts.last()
}

private val trailingPathSeparators = Regex("[/\\\\]+\\z")
private val pathSeparators = Regex("[/\\\\]+")

private fun lastPathSegment(path: String): String? {
    val normalized = path.trim().replace(trailingPathSeparators, "")
    if (normalized.isEmpty()) {
        return null
    }
    val parts = normalized.split(pathSeparators).filter { it.isNotEmpty() }
    return if (parts.isEmpty()) normalized else parts.last()
}

private fun looksLikePath(value: String): Boolean {
    return value.contains('/') || value.contains('\\')
}

private fun containsAny(haystack: String, needles: List<String>): Boolean {
    return needles.any { haystack.contains(it) }
}

private val whitespaceRun = Regex("$DART_WHITESPACE+")

private fun compactTitle(value: String, maxLength: Int): String {
    val normalized = value
        .trim()
        .split("\n")
        .first()
        .trim()
        .replace(whitespaceRun, " ")
    if (normalized.length <= maxLength) {
        return normalized
    }
    return "${normalized.substring(0, maxLength)}..."
}

/** Dart `JsonEncoder.withIndent('  ').convert(value)` with a toString fallback. */
private fun safeJson(value: Any?): String {
    return try {
        val out = StringBuilder()
        writeIndentedJson(out, value, 0)
        out.toString()
    } catch (_: RuntimeException) {
        dartObjectToString(value) ?: ""
    }
}

/** Mirrors Dart's `_JsonPrettyPrintingStringifier` layout. */
private fun writeIndentedJson(out: StringBuilder, value: Any?, level: Int) {
    when (value) {
        is Map<*, *> -> {
            if (value.isEmpty()) {
                out.append("{}")
                return
            }
            // Dart only encodes maps whose keys are all strings.
            if (!value.keys.all { it is String }) {
                throw IllegalArgumentException("Converting object to an encodable object failed: $value")
            }
            out.append("{\n")
            var first = true
            for ((key, nested) in value) {
                if (!first) out.append(",\n")
                first = false
                appendIndent(out, level + 1)
                out.append(DartJson.encode(key as String))
                out.append(": ")
                writeIndentedJson(out, nested, level + 1)
            }
            out.append('\n')
            appendIndent(out, level)
            out.append('}')
        }
        is List<*> -> {
            if (value.isEmpty()) {
                out.append("[]")
                return
            }
            out.append("[\n")
            var first = true
            for (nested in value) {
                if (!first) out.append(",\n")
                first = false
                appendIndent(out, level + 1)
                writeIndentedJson(out, nested, level + 1)
            }
            out.append('\n')
            appendIndent(out, level)
            out.append(']')
        }
        null, is String, is Boolean, is Number -> out.append(DartJson.encode(value))
        else -> throw IllegalArgumentException("Converting object to an encodable object failed: $value")
    }
}

private fun appendIndent(out: StringBuilder, level: Int) {
    repeat(level) { out.append("  ") }
}

/** Dart `Object.toString()` for JSON-like trees (`{a: 1}`, `[1, 2]`). */
private fun dartObjectToString(value: Any?): String? = when (value) {
    null -> null
    is Map<*, *> -> value.entries.joinToString(", ", "{", "}") {
        "${dartObjectToString(it.key) ?: "null"}: ${dartObjectToString(it.value) ?: "null"}"
    }
    is List<*> -> value.joinToString(", ", "[", "]") { dartObjectToString(it) ?: "null" }
    is Double, is Float -> dartScalarToString(value)
    else -> value.toString()
}
