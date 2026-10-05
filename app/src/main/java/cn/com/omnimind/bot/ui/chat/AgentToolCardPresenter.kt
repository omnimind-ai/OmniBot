package cn.com.omnimind.bot.ui.chat

import cn.com.omnimind.bot.agent.projection.AgentDiffSummary
import cn.com.omnimind.bot.agent.projection.DartJson
import cn.com.omnimind.bot.agent.projection.canonicalAgentToolName
import cn.com.omnimind.bot.agent.projection.dartDoubleToString
import cn.com.omnimind.bot.agent.projection.extractAgentDiffText
import cn.com.omnimind.bot.agent.projection.formatAgentDiffStat
import cn.com.omnimind.bot.agent.projection.isAgentToolItemType
import cn.com.omnimind.bot.agent.projection.isAgentToolUiStyle
import cn.com.omnimind.bot.agent.projection.normalizeAgentToolCall
import cn.com.omnimind.bot.agent.projection.parseAgentDiffText
import cn.com.omnimind.nativeui.chat.AgentDiffFileUi
import cn.com.omnimind.nativeui.chat.AgentDiffLineUi
import cn.com.omnimind.nativeui.chat.AgentDiffStatUi
import cn.com.omnimind.nativeui.chat.AgentDiffUi
import cn.com.omnimind.nativeui.chat.AgentToolActionUi
import cn.com.omnimind.nativeui.chat.AgentToolCardStyle
import cn.com.omnimind.nativeui.chat.AgentToolCardUi
import cn.com.omnimind.nativeui.chat.AgentToolDetailUi
import kotlin.math.max
import kotlin.math.min
import cn.com.omnimind.bot.agent.projection.AgentDiffLineKind as ParsedLineKind
import cn.com.omnimind.nativeui.chat.AgentDiffLineKind as UiLineKind

/*
 * Derives the native `agent_tool_summary` card presentation from raw card
 * data. Method-by-method port of the Dart presentation helpers:
 *
 * - `ui/lib/features/home/pages/chat/tool_activity_utils.dart`
 *   (titles, preview, status/type labels)
 * - `.../cards/terminal_output_utils.dart` (TerminalOutputUtils.buildDisplayOutput)
 * - `.../cards/agent_tool_transcript.dart` (buildAgentToolTranscript, copy
 *   text, follow-up actions, diff summary)
 * - `.../cards/agent_tool_summary_card.dart` (inline vs capsule decisions,
 *   diff stat labels)
 *
 * Every `LegacyTextLocalizer.localize(x)` takes the caller's `english` flag
 * instead of the Dart global locale. Keep names and branch order comparable
 * with the Dart source.
 */

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

internal fun presentAgentToolCard(
    cardData: Map<String, Any?>,
    english: Boolean,
    useAgentToolPresentation: Boolean = true,
): AgentToolCardUi {
    // Dart `CardWidgetFactory` normalizes before choosing the widget.
    val card = AgentAcpCardNormalizer.normalize(cardData)

    val status = dartStr(card["status"] ?: "running")
    val title = resolveAgentToolProgressTitle(card, isEnglish = english)
    val awaiting = isAgentToolAwaitingConfirmation(card)
    val running = status == "running" && !awaiting
    // Capsule badge and inline trailing label share this choice.
    val badgeLabel = inlineToolTrailingLabel(card, status = status, english = english)
    val inline = usesInlineToolStyle(card, useAgentToolPresentation = useAgentToolPresentation)

    var fileName = ""
    var filePath = ""
    var diffStat: AgentDiffStatUi? = null
    var inlineDiff: AgentDiffUi? = null
    val isActive: Boolean
    val opensDetail: Boolean
    if (inline) {
        val isFileTool = isInlineFileTool(card)
        val isAgentTool = isAgentInlineTool(card)
        val diffSummary = if (isFileTool) resolveInlineDiffSummary(card) else null
        if (isFileTool) {
            diffStat = if (diffSummary == null) {
                resolveDiffStatLabel(card)?.let { label ->
                    AgentDiffStatUi(
                        label = label,
                        additions = asNonNegativeInt(card["additions"]),
                        deletions = asNonNegativeInt(card["deletions"]),
                    )
                }
            } else {
                AgentDiffStatUi(
                    label = formatAgentDiffStat(diffSummary.additions, diffSummary.deletions),
                    additions = diffSummary.additions,
                    deletions = diffSummary.deletions,
                )
            }
            filePath = resolveInlineFilePath(card, diffSummary)
            fileName = lastPathSegment(filePath)
            if (diffSummary != null && diffSummary.files.isNotEmpty()) {
                inlineDiff = diffSummary.toUi()
            }
        }
        // Dart `_InlineFileTitleText.shimmer`: only agent-native inline rows.
        isActive = isAgentTool && running
        opensDetail = isAgentTool
    } else {
        diffStat = resolveDiffStatLabel(card)?.let { label ->
            AgentDiffStatUi(
                label = label,
                additions = asNonNegativeInt(card["additions"]),
                deletions = asNonNegativeInt(card["deletions"]),
            )
        }
        isActive = running
        opensDetail = true
    }

    return AgentToolCardUi(
        cardId = dartStr(card["cardId"] ?: card["toolCallId"] ?: ""),
        status = status,
        toolType = dartTrim(dartStr(card["toolType"])),
        style = if (inline) AgentToolCardStyle.Inline else AgentToolCardStyle.Capsule,
        title = title,
        badgeLabel = badgeLabel,
        isActive = isActive,
        opensDetail = opensDetail,
        fileName = fileName,
        filePath = filePath,
        diffStat = diffStat,
        diff = inlineDiff,
        detail = buildAgentToolDetail(card, status, english),
    )
}

/** Dart `_AgentToolDetailContent.build` minus the widgets. */
private fun buildAgentToolDetail(
    cardData: Map<String, Any?>,
    status: String,
    english: Boolean,
): AgentToolDetailUi {
    val transcript = buildAgentToolTranscript(
        cardData,
        english = english,
        maxPreviewLines = 4,
        maxPreviewChars = 420,
    )
    val diffSummary = resolveDiffSummary(cardData)
    val isDiffView = diffSummary?.files?.isNotEmpty() == true
    return AgentToolDetailUi(
        title = resolveAgentToolTitle(cardData, english),
        status = status,
        typeLabel = resolveAgentToolTypeLabel(cardData, english),
        statusLabel = resolveAgentToolStatusLabel(cardData, english),
        promptLine = transcript.promptLine,
        outputText = transcript.outputText,
        copyText = agentToolCopyText(cardData, transcript, isDiffView = isDiffView),
        isTerminal = transcript.isTerminal,
        diff = if (isDiffView) diffSummary.toUi() else null,
        actions = resolveAgentToolActions(cardData, english).map { action ->
            val payload = action["payload"]
            AgentToolActionUi(
                type = dartStr(action["type"]),
                label = dartStr(action["label"]),
                target = dartStr(action["target"]),
                payload = if (payload is Map<*, *>) stringKeyedMap(payload) else emptyMap(),
            )
        },
    )
}

private fun AgentDiffSummary.toUi(): AgentDiffUi = AgentDiffUi(
    files = files.map { file ->
        AgentDiffFileUi(
            displayPath = file.displayPath,
            additions = file.additions,
            deletions = file.deletions,
            isNewFile = file.isNewFile,
            isDeletedFile = file.isDeletedFile,
            lines = file.lines.map { line ->
                AgentDiffLineUi(
                    kind = when (line.kind) {
                        ParsedLineKind.header -> UiLineKind.Header
                        ParsedLineKind.add -> UiLineKind.Addition
                        ParsedLineKind.remove -> UiLineKind.Deletion
                        ParsedLineKind.context -> UiLineKind.Context
                        ParsedLineKind.meta -> UiLineKind.Meta
                    },
                    content = line.content,
                    prefix = line.prefix,
                    oldLineNumber = line.oldLineNumber,
                    newLineNumber = line.newLineNumber,
                )
            },
            statLabel = formatAgentDiffStat(file.additions, file.deletions),
        )
    },
    additions = additions,
    deletions = deletions,
    statLabel = formatAgentDiffStat(additions, deletions),
)

// ---------------------------------------------------------------------------
// tool_activity_utils.dart
// ---------------------------------------------------------------------------

private const val AGENT_TOOL_TITLE_FIELD = "toolTitle"

internal fun resolveAgentToolTitle(cardData: Map<String, Any?>, english: Boolean): String {
    val explicit = dartTrim(dartStr(cardData[AGENT_TOOL_TITLE_FIELD]))
    if (explicit.isNotEmpty()) {
        return LegacyTextLocalizer.localize(explicit, english)
    }

    val fromArgs = extractToolTitleFromArgs(dartStr(cardData["argsJson"]))
    if (fromArgs.isNotEmpty()) {
        return LegacyTextLocalizer.localize(fromArgs, english)
    }

    val summary = dartTrim(dartStr(cardData["summary"]))
    if (summary.isNotEmpty()) {
        return LegacyTextLocalizer.localize(summary, english)
    }

    val displayName = dartTrim(dartStr(cardData["displayName"] ?: "工具调用"))
    val serverName = dartTrim(dartStr(cardData["serverName"]))
    if (dartStr(cardData["toolType"]) == "mcp" && serverName.isNotEmpty()) {
        return "${LegacyTextLocalizer.localize(displayName, english)} · $serverName"
    }
    return LegacyTextLocalizer.localize(
        if (displayName.isEmpty()) "工具调用" else displayName,
        english,
    )
}

/**
 * Live progress label for the executing tool. A display projection only:
 * never a status source (ACP owns the lifecycle).
 */
internal fun resolveAgentToolProgressTitle(cardData: Map<String, Any?>, isEnglish: Boolean): String {
    val status = dartTrim(dartStr(cardData["status"])).lowercase()
    val title = resolveAgentToolTitle(cardData, isEnglish)
    // ACP pending may be streaming input. It does not prove execution or approval.
    if (status == "pending") return title
    if (status != "running" && status != "pending") {
        return title
    }

    val toolType = dartTrim(dartStr(cardData["toolType"])).lowercase()
    val toolName = dartTrim(dartStr(cardData["toolName"])).lowercase()
    if (toolType != "file" &&
        !toolName.contains("file_write") &&
        !toolName.contains("file_edit") &&
        !toolName.endsWith("/write") &&
        !toolName.endsWith("/edit")
    ) {
        return title
    }

    val titleLower = title.lowercase()
    val isWrite = toolName.contains("write") ||
        titleLower.contains("写入") ||
        titleLower.contains("write")
    val isEdit = toolName.contains("edit") ||
        toolName.contains("patch") ||
        titleLower.contains("编辑") ||
        titleLower.contains("修改") ||
        titleLower.contains("edit")
    val action = if (isWrite) {
        if (isEnglish) "Writing file" else "正在写入文件"
    } else if (isEdit) {
        if (isEnglish) "Editing file" else "正在编辑文件"
    } else {
        title
    }
    if (action == title) {
        return action
    }

    val fileName = agentToolFileName(cardData)
    return if (fileName.isEmpty()) {
        action
    } else {
        "$action${if (isEnglish) ": " else "："}$fileName"
    }
}

private fun agentToolFileName(cardData: Map<String, Any?>): String {
    val directPath = dartTrim(dartStr(cardData["filePath"]))
    val argsJson = dartTrim(dartStr(cardData["argsJson"]))
    var decoded: Any? = null
    if (argsJson.isNotEmpty()) {
        decoded = try {
            DartJson.decode(argsJson)
        } catch (_: Exception) {
            null
        }
    }
    val args = if (decoded is Map<*, *>) stringKeyedMap(decoded) else null
    val path = if (directPath.isNotEmpty()) {
        directPath
    } else {
        (
            args?.get("path")
                ?: args?.get("filePath")
                ?: args?.get("file_path")
                ?: args?.get("filename")
                ?: args?.get("fileName")
            )?.let { dartTrim(dartToStringValue(it)) } ?: ""
    }
    if (path.isEmpty()) {
        return ""
    }
    val normalized = path.replace('\\', '/')
    val segments = normalized.split("/").filter { it.isNotEmpty() }
    return if (segments.isEmpty()) normalized else segments.last()
}

internal fun resolveAgentToolTerminalOutput(cardData: Map<String, Any?>): String {
    return TerminalOutputUtils.buildDisplayOutput(
        terminalOutput = dartStr(cardData["terminalOutput"]),
        rawResultJson = dartStr(cardData["rawResultJson"]),
        resultPreviewJson = dartStr(cardData["resultPreviewJson"]),
    )
}

internal fun resolveAgentToolPreview(cardData: Map<String, Any?>, english: Boolean): String {
    if (isAgentToolAwaitingConfirmation(cardData)) {
        val question = dartTrim(dartStr(cardData["question"]))
        if (question.isNotEmpty()) {
            return LegacyTextLocalizer.localize(question, english)
        }
    }
    val toolType = dartStr(cardData["toolType"])
    if (toolType == "terminal") {
        val output = dartTrim(resolveAgentToolTerminalOutput(cardData))
        if (output.isNotEmpty()) {
            val nonEmptyLines = output
                .split("\n")
                .map { dartTrimRight(it) }
                .filter { dartTrim(it).isNotEmpty() }
            if (nonEmptyLines.isNotEmpty()) {
                return nonEmptyLines.last()
            }
            return output
        }
    }
    if (toolType == "file") {
        val additions = asNonNegativeInt(cardData["additions"])
        val deletions = asNonNegativeInt(cardData["deletions"])
        val changedFiles = asNonNegativeInt(cardData["changedFiles"])
        if (changedFiles > 0 || additions > 0 || deletions > 0) {
            val fileLabel = if (changedFiles <= 1) {
                LegacyTextLocalizer.localize("1 个文件", english)
            } else {
                LegacyTextLocalizer.localize("$changedFiles 个文件", english)
            }
            return "$fileLabel · ${formatAgentDiffStat(additions, deletions)}"
        }
        val filePath = dartTrim(dartStr(cardData["filePath"]))
        if (filePath.isNotEmpty()) {
            return filePath
        }
    }

    val progress = dartTrim(dartStr(cardData["progress"]))
    val summary = dartTrim(dartStr(cardData["summary"]))
    val title = resolveAgentToolTitle(cardData, english)
    if (progress.isNotEmpty() && progress != title) {
        return LegacyTextLocalizer.localize(progress, english)
    }
    if (summary.isNotEmpty() && summary != title) {
        return LegacyTextLocalizer.localize(summary, english)
    }
    return resolveAgentToolStatusLabel(cardData, english)
}

internal fun resolveAgentToolStatusLabel(cardData: Map<String, Any?>, english: Boolean): String {
    fun l(text: String) = LegacyTextLocalizer.localize(text, english)
    val explicitStatusLabel = dartTrim(dartStr(cardData["statusLabel"]))
    if (explicitStatusLabel.isNotEmpty()) {
        return l(explicitStatusLabel)
    }
    val status = dartStr(cardData["status"] ?: "running")
    val toolType = dartStr(cardData["toolType"] ?: "builtin")
    if (status == "timeout") return l("超时")
    if (status == "interrupted") return l("中断")
    if (status == "pending") return l("准备中")
    return when (status) {
        "success" -> l("成功")
        "error" -> l("失败")
        else -> when {
            isAgentToolAwaitingConfirmation(cardData) -> l("等待确认")
            toolType == "terminal" -> l("运行中")
            toolType == "browser" -> l("浏览中")
            toolType == "search" -> l("搜索中")
            toolType == "image" -> l("查看中")
            toolType == "mcp" -> l("响应中")
            toolType == "memory" -> l("处理中")
            toolType == "review" -> l("审阅中")
            else -> l("执行中")
        }
    }
}

/**
 * ACP pending alone is not evidence of approval. Only an explicit legacy
 * confirmation (clarify + `*confirmed` missing field) counts.
 */
internal fun isAgentToolAwaitingConfirmation(cardData: Map<String, Any?>): Boolean {
    val toolType = dartTrim(dartStr(cardData["toolType"])).lowercase()
    val status = dartTrim(dartStr(cardData["status"])).lowercase()
    if (toolType != "clarify" || (status != "running" && status != "pending")) {
        return false
    }
    val question = dartTrim(dartStr(cardData["question"]))
    if (question.isEmpty()) return false
    val fields = ArrayList<String>()
    (cardData["missingFields"] as? Iterable<*>)?.forEach { fields.add(dartToStringValue(it)) }
    (cardData["missing_fields"] as? Iterable<*>)?.forEach { fields.add(dartToStringValue(it)) }
    return fields.any { field -> dartTrim(field).lowercase().endsWith("confirmed") }
}

internal fun resolveAgentToolTypeLabel(cardData: Map<String, Any?>, english: Boolean): String {
    fun l(text: String) = LegacyTextLocalizer.localize(text, english)
    val explicitTypeLabel = dartTrim(dartStr(cardData["toolTypeLabel"]))
    if (explicitTypeLabel.isNotEmpty()) {
        return l(explicitTypeLabel)
    }
    return when (dartStr(cardData["toolType"])) {
        "terminal" -> l("终端")
        "browser" -> l("浏览器")
        "search" -> l("搜索")
        "image" -> l("图像")
        "workspace" -> l("工作区")
        "file" -> l("文件")
        "plan" -> l("计划")
        "account" -> l("账户")
        "status" -> l("状态")
        "schedule" -> l("定时")
        "alarm" -> l("提醒")
        "calendar" -> l("日历")
        "memory" -> l("记忆")
        "skill" -> "Skill"
        "subagent" -> l("子任务")
        "review" -> l("审阅")
        "mcp" -> "MCP"
        else -> l("工具")
    }
}

private fun extractToolTitleFromArgs(argsJson: String): String {
    val text = dartTrim(argsJson)
    if (text.isEmpty()) {
        return ""
    }
    try {
        val decoded = DartJson.decode(text)
        if (decoded !is Map<*, *>) {
            return ""
        }
        val map = stringKeyedMap(decoded)
        val explicit = dartTrim(dartStr(map["tool_title"] ?: map["toolTitle"] ?: ""))
        if (explicit.isNotEmpty()) {
            return explicit
        }
        for (key in listOf("command", "cmd", "query", "q", "url", "path", "filePath", "file_path")) {
            val value = map[key]?.let { dartTrim(dartToStringValue(it)) } ?: ""
            if (value.isNotEmpty()) {
                return compactToolTitle(value)
            }
        }
    } catch (_: Exception) {
        return ""
    }
    return ""
}

private fun compactToolTitle(value: String): String {
    val normalized = dartTrim(dartTrim(value).split("\n").first())
        .replace(JS_WS_RUN, " ")
    if (normalized.length <= 48) {
        return normalized
    }
    return "${normalized.substring(0, 48)}..."
}

/** Dart `_asNonNegativeInt` (identical in tool_activity_utils and the card). */
private fun asNonNegativeInt(value: Any?): Int {
    val parsed = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toDouble().toInt()
        else -> dartIntTryParse(if (value == null) "" else dartToStringValue(value)) ?: 0
    }
    return if (parsed < 0) 0 else parsed
}

// ---------------------------------------------------------------------------
// terminal_output_utils.dart
// ---------------------------------------------------------------------------

internal object TerminalOutputUtils {
    /** Shared detail/copy source: never discards terminal output. */
    fun trim(value: String): String = value

    fun buildDisplayOutput(
        terminalOutput: String,
        rawResultJson: String,
        resultPreviewJson: String,
    ): String {
        if (dartTrim(terminalOutput).isNotEmpty()) {
            return trim(terminalOutput)
        }

        val rawMap = decodeJsonMap(rawResultJson)
        val previewMap = decodeJsonMap(resultPreviewJson)
        val source = if (rawMap.isNotEmpty()) rawMap else previewMap
        if (source.isEmpty()) {
            return ""
        }

        val directTerminalOutput = dartStr(source["terminalOutput"])
        if (dartTrim(directTerminalOutput).isNotEmpty()) {
            return trim(directTerminalOutput)
        }

        val segments = ArrayList<String>()
        val liveFallbackReason = dartStr(source["liveFallbackReason"])
        if (dartTrim(liveFallbackReason).isNotEmpty()) {
            segments.add("[实时输出已回退]\n$liveFallbackReason")
        }

        val stdout = dartTrimRight(dartStr(source["stdout"]))
        if (stdout.isNotEmpty()) {
            segments.add(stdout)
        }

        val stderr = dartTrimRight(dartStr(source["stderr"]))
        if (stderr.isNotEmpty()) {
            segments.add(if (segments.isEmpty()) stderr else "[stderr]\n$stderr")
        }

        val errorMessage = dartTrim(dartStr(source["errorMessage"]))
        if (errorMessage.isNotEmpty() && !segments.any { it.contains(errorMessage) }) {
            segments.add(errorMessage)
        }

        return trim(segments.joinToString("\n\n"))
    }

    fun decodeJsonMap(value: String): Map<String, Any?> = decodeJsonMapOrEmpty(value)
}

// ---------------------------------------------------------------------------
// agent_tool_transcript.dart
// ---------------------------------------------------------------------------

internal data class AgentToolTranscript(
    val promptLine: String,
    val outputText: String,
    val previewText: String,
    val isTerminal: Boolean,
)

internal fun buildAgentToolTranscript(
    cardData: Map<String, Any?>,
    english: Boolean = false,
    maxOutputLines: Int? = null,
    maxPreviewLines: Int = 2,
    maxPreviewChars: Int = 220,
): AgentToolTranscript {
    val toolType = dartTrim(dartStr(cardData["toolType"]))
    val isTerminal = toolType == "terminal"
    val promptLine = if (isTerminal) {
        buildTerminalPromptLine(cardData)
    } else {
        buildToolPromptLine(cardData)
    }
    val outputText = if (isTerminal) {
        buildTerminalOutputText(cardData)
    } else {
        buildStructuredOutputText(cardData, english, maxOutputLines = maxOutputLines)
    }
    val previewText = buildPreviewText(
        outputText,
        isTerminal = isTerminal,
        maxLines = maxPreviewLines,
        maxChars = maxPreviewChars,
    )
    return AgentToolTranscript(
        promptLine = promptLine,
        outputText = outputText,
        previewText = previewText,
        isTerminal = isTerminal,
    )
}

private fun buildTerminalPromptLine(cardData: Map<String, Any?>): String {
    val args = decodeJsonMapOrEmpty(dartStr(cardData["argsJson"]))
    val toolName = dartTrim(dartStr(cardData["toolName"]))
    val workingDirectory = dartTrim(dartStr(args["workingDirectory"] ?: args["cwd"] ?: ""))
    val command = dartTrim(dartStr(args["command"]))

    if (command.isNotEmpty()) {
        if (workingDirectory.isEmpty()) {
            return "\$ $command"
        }
        return "\$ cd ${quoteShellValue(workingDirectory)} && $command"
    }

    if (toolName == "terminal_session_start") {
        if (workingDirectory.isNotEmpty()) {
            return "\$ cd ${quoteShellValue(workingDirectory)}"
        }
        return "\$ sh"
    }
    if (toolName == "terminal_session_stop") {
        return "\$ exit"
    }
    if (toolName == "terminal_session_read") {
        val sessionId = dartTrim(dartStr(args["sessionId"] ?: cardData["terminalSessionId"] ?: ""))
        return if (sessionId.isEmpty()) "\$ tail -f session.log" else "\$ tail -f $sessionId"
    }

    return buildToolPromptLine(cardData)
}

private fun buildToolPromptLine(cardData: Map<String, Any?>): String {
    val toolName = if (dartTrim(dartStr(cardData["toolName"])).isEmpty()) {
        dartTrim(dartStr(cardData["displayName"] ?: "tool"))
    } else {
        dartTrim(dartStr(cardData["toolName"]))
    }
    val agentName = resolveAgentToolPromptAgentName(cardData)
    if (isInternalAgentToolName(toolName) && agentName.isNotEmpty()) {
        val title = resolveAgentToolPromptTitle(cardData, toolName)
        return "$agentName · $title"
    }
    val args = decodeJsonMapOrEmpty(dartStr(cardData["argsJson"]))
    val segments = arrayListOf(toolName)

    for ((rawKey, value) in args) {
        val key = dartTrim(rawKey)
        if (key.isEmpty() || key == "tool_title" || key == "toolTitle") {
            continue
        }
        segments.addAll(formatCliArguments(key, value))
    }

    return "\$ ${dartTrim(segments.joinToString(" "))}"
}

private fun resolveAgentToolPromptAgentName(cardData: Map<String, Any?>): String {
    val explicit = dartTrim(dartStr(cardData["agentName"]))
    if (explicit.isNotEmpty()) {
        return explicit
    }
    // Id fallback for custom / newly installed harnesses.
    return dartTrim(dartStr(cardData["agentId"]))
}

private fun resolveAgentToolPromptTitle(cardData: Map<String, Any?>, toolName: String): String {
    for (value in listOf(cardData["toolTitle"], cardData["displayName"])) {
        val title = dartTrim(dartStr(value))
        if (title.isNotEmpty() && title != toolName && !isInternalAgentToolName(title)) {
            return title
        }
    }
    val separator = toolName.indexOf('.')
    val fallback = if (separator >= 0) dartTrim(toolName.substring(separator + 1)) else toolName
    return if (fallback.isEmpty()) "tool" else fallback
}

private fun isInternalAgentToolName(value: String): Boolean {
    val normalized = canonicalAgentToolName(value) ?: dartTrim(value)
    return normalized.startsWith("agent.") || normalized.startsWith("agent/")
}

private fun buildTerminalOutputText(cardData: Map<String, Any?>): String {
    val output = dartTrimRight(resolveAgentToolTerminalOutput(cardData))
    if (output.isNotEmpty()) {
        return output
    }

    val status = dartTrim(dartStr(cardData["status"]))
    val summary = dartTrim(dartStr(cardData["summary"]))
    val progress = dartTrim(dartStr(cardData["progress"]))
    val fallback = if (progress.isNotEmpty()) progress else summary
    if (status == "running" && isGenericTerminalProgressMessage(fallback)) {
        return ""
    }
    if (fallback.isEmpty()) {
        // Persisted cards can predate the current ACP projection; reuse the
        // same result parser without rewriting history or lifecycle status.
        val restored = normalizeAgentToolCall(cardData)
        return if (restored.terminalOutput.isNotEmpty()) {
            dartTrimRight(restored.terminalOutput)
        } else {
            restored.summary
        }
    }
    return fallback
}

private fun buildStructuredOutputText(
    cardData: Map<String, Any?>,
    english: Boolean,
    maxOutputLines: Int?,
): String {
    val status = dartTrim(dartStr(cardData["status"]))
    val summary = dartTrim(dartStr(cardData["summary"]))
    val progress = dartTrim(dartStr(cardData["progress"]))
    val previewMap = decodeJsonMapOrEmpty(dartStr(cardData["resultPreviewJson"]))
    val previewResult = dartStr(cardData["resultPreviewJson"])
    val rawResult = dartStr(cardData["rawResultJson"])
    val rawMap = decodeJsonMapOrEmpty(rawResult)
    val lines = ArrayList<String>()

    if (status == "running") {
        appendUniqueLine(lines, if (progress.isNotEmpty()) progress else summary)
    } else if (status == "timeout" || status == "error" || status == "interrupted") {
        appendUniqueLine(lines, summary)
    }

    // Detail and copy must expose the complete persisted result.
    val detailMap = if (maxOutputLines == null && rawMap.isNotEmpty()) rawMap else previewMap
    val structuredResult = buildStructuredLines(detailMap, maxLines = maxOutputLines)
    if (structuredResult.isNotEmpty()) {
        val existing = ArrayList(lines)
        lines.addAll(structuredResult.filter { !existing.contains(it) })
    } else {
        val structuredRaw = buildStructuredLines(rawMap, maxLines = maxOutputLines)
        val existing = ArrayList(lines)
        lines.addAll(structuredRaw.filter { !existing.contains(it) })
    }

    // A valid tool result can be a JSON array or scalar rather than an object.
    if (lines.isEmpty()) {
        val detailResult = if (maxOutputLines == null && dartTrim(rawResult).isNotEmpty()) {
            rawResult
        } else {
            previewResult
        }
        appendUniqueLine(lines, formatCompleteResultPayload(detailResult))
    }

    if (lines.isEmpty()) {
        appendUniqueLine(lines, progress)
        appendUniqueLine(lines, summary)
        if (lines.isEmpty()) {
            lines.add(resolveAgentToolStatusLabel(cardData, english))
        }
    }

    val normalized = dartTrim(lines.joinToString("\n"))
    return trimStructuredOutput(normalized, maxLines = maxOutputLines)
}

private fun formatCompleteResultPayload(value: String): String {
    val normalized = dartTrimRight(value)
    if (normalized.isEmpty()) {
        return ""
    }
    return try {
        DartJson.encodeIndented(DartJson.decode(normalized))
    } catch (_: Exception) {
        normalized
    }
}

private fun buildPreviewText(
    outputText: String,
    isTerminal: Boolean,
    maxLines: Int,
    maxChars: Int,
): String {
    val lines = outputText
        .split("\n")
        .map { dartTrimRight(it) }
        .filter { dartTrim(it).isNotEmpty() }
    if (lines.isEmpty()) {
        return ""
    }
    val selected = if (isTerminal) {
        lines.subList(max(0, lines.size - maxLines), lines.size)
    } else {
        lines.take(maxLines)
    }
    val preview = selected.joinToString("\n")
    if (preview.length <= maxChars) {
        return preview
    }
    return "${dartTrimRight(preview.substring(0, maxChars - 1))}…"
}

private fun formatCliArguments(key: String, value: Any?): List<String> {
    val flag = "--$key"
    if (value == null) {
        return emptyList()
    }
    if (value is Boolean) {
        return if (value) listOf(flag) else listOf("$flag=false")
    }
    if (value is Number) {
        return listOf(flag, dartToStringValue(value))
    }
    if (value is String) {
        val trimmed = dartTrim(value)
        if (trimmed.isEmpty()) {
            return emptyList()
        }
        return listOf(flag, quoteShellValue(trimmed))
    }
    if (value is List<*>) {
        val segments = ArrayList<String>()
        for (item in value) {
            if (item == null) {
                continue
            }
            if (item is Map<*, *> || item is List<*>) {
                segments.addAll(listOf(flag, quoteShellValue(DartJson.encode(item))))
                continue
            }
            val itemText = dartTrim(dartToStringValue(item))
            if (itemText.isEmpty()) {
                continue
            }
            segments.addAll(listOf(flag, quoteShellValue(itemText)))
        }
        return segments
    }
    return listOf(flag, quoteShellValue(DartJson.encode(value)))
}

private fun buildStructuredLines(source: Map<String, Any?>, maxLines: Int?): List<String> {
    if (source.isEmpty()) {
        return emptyList()
    }

    val lines = ArrayList<String>()
    val compactValues = maxLines != null

    fun canAdd(): Boolean = maxLines == null || lines.size < maxLines

    fun addLine(line: String) {
        val normalized = dartTrimRight(line)
        if (normalized.isEmpty() || lines.contains(normalized) || !canAdd()) {
            return
        }
        lines.add(normalized)
    }

    fun appendValue(label: String, value: Any?, depth: Int) {
        if (!canAdd() || value == null) {
            return
        }

        if (value is Map<*, *>) {
            val normalizedMap = stringKeyedMap(value)
            val summary = if (compactValues) summarizeMap(normalizedMap) else null
            if (summary != null && summary.isNotEmpty()) {
                addLine(if (label.isEmpty()) summary else "$label: $summary")
                return
            }
            for (entry in prioritizeEntries(normalizedMap.entries)) {
                val key = dartTrim(entry.key)
                if (shouldSkipStructuredKey(key)) {
                    continue
                }
                val nextLabel = if (label.isEmpty()) key else "$label.$key"
                appendValue(nextLabel, entry.value, depth + 1)
                if (!canAdd()) {
                    return
                }
            }
            return
        }

        if (value is List<*>) {
            if (value.isEmpty()) {
                return
            }
            if (canInlineScalarList(value) || !compactValues) {
                addLine("$label: ${value.joinToString(", ") { formatInlineValue(it, compact = compactValues) }}")
                return
            }
            val itemLimit = min(value.size, if (depth <= 1) 5 else 3)
            for (index in 0 until itemLimit) {
                val item = value[index]
                if (item is Map<*, *>) {
                    val normalizedMap = stringKeyedMap(item)
                    val summary = if (compactValues) summarizeMap(normalizedMap) else null
                    if (summary != null && summary.isNotEmpty()) {
                        addLine("$label[$index]: $summary")
                    } else {
                        appendValue("$label[$index]", normalizedMap, depth + 1)
                    }
                } else {
                    val formatted = formatScalarLine(item, compact = compactValues)
                    if (formatted != null) {
                        addLine("$label[$index]: $formatted")
                    }
                }
                if (!canAdd()) {
                    return
                }
            }
            if (value.size > itemLimit && canAdd()) {
                addLine("$label: ... +${value.size - itemLimit} more")
            }
            return
        }

        val formatted = formatScalarLine(value, compact = compactValues)
        if (formatted != null) {
            addLine(if (label.isEmpty()) formatted else "$label: $formatted")
        }
    }

    for (entry in prioritizeEntries(source.entries)) {
        val key = dartTrim(entry.key)
        if (shouldSkipStructuredKey(key)) {
            continue
        }
        appendValue(key, entry.value, 0)
        if (!canAdd()) {
            break
        }
    }

    return lines
}

private val structuredKeyPriority: Map<String, Int> = mapOf(
    "message" to 0,
    "question" to 1,
    "errorMessage" to 2,
    "path" to 3,
    "targetPath" to 4,
    "query" to 5,
    "url" to 6,
    "currentUrl" to 7,
    "count" to 8,
    "name" to 9,
    "title" to 10,
    "taskId" to 11,
    "goal" to 12,
    "content" to 13,
    "snippet" to 14,
    "items" to 15,
)

private fun prioritizeEntries(
    entries: Collection<Map.Entry<String, Any?>>,
): List<Map.Entry<String, Any?>> {
    return entries.sortedWith { left, right ->
        val leftRank = structuredKeyPriority[left.key] ?: 99
        val rightRank = structuredKeyPriority[right.key] ?: 99
        if (leftRank != rightRank) {
            leftRank.compareTo(rightRank)
        } else {
            left.key.compareTo(right.key)
        }
    }
}

private fun summarizeMap(value: Map<String, Any?>): String? {
    val parts = ArrayList<String>()
    val path = firstNonBlank(value, listOf("path", "targetPath", "sourcePath"))
    val name = firstNonBlank(value, listOf("name", "title", "label", "id"))
    val url = firstNonBlank(value, listOf("currentUrl", "url"))
    val matchType = dartTrim(dartStr(value["matchType"]))
    val snippet = firstNonBlank(value, listOf("snippet", "content", "message"))

    if (name.isNotEmpty()) {
        parts.add(name)
    }
    if (path.isNotEmpty() && !parts.contains(path)) {
        parts.add(path)
    }
    if (url.isNotEmpty() && !parts.contains(url)) {
        parts.add(url)
    }
    if (matchType.isNotEmpty()) {
        parts.add(matchType)
    }
    if (value["isDirectory"] == true && !parts.contains("dir")) {
        parts.add("dir")
    }
    val sizeValue = value["size"]
    if (sizeValue is Number && sizeValue.toDouble() > 0) {
        parts.add(formatBytes(sizeValue.toLong()))
    }
    if (snippet.isNotEmpty()) {
        parts.add(truncateInline(snippet))
    }

    if (parts.isEmpty()) {
        return null
    }
    return parts.joinToString(" | ")
}

private fun trimStructuredOutput(value: String, maxLines: Int?, maxChars: Int? = null): String {
    if (value.isEmpty()) {
        return value
    }
    var candidate = value
    if (maxChars != null && candidate.length > maxChars) {
        candidate = dartTrimRight(candidate.substring(0, maxChars))
        candidate = "$candidate\n...[truncated]"
    }
    val lines = candidate.split("\n")
    if (maxLines != null && lines.size > maxLines) {
        candidate = (lines.take(maxLines) + "...[truncated]").joinToString("\n")
    }
    return dartTrimRight(candidate)
}

private fun canInlineScalarList(value: List<*>): Boolean {
    if (value.isEmpty() || value.any { it is Map<*, *> || it is List<*> }) {
        return false
    }
    val rendered = value.joinToString(", ") { formatInlineValue(it) }
    return rendered.length <= 120
}

private fun formatInlineValue(value: Any?, compact: Boolean = true): String {
    if (value == null) {
        return "null"
    }
    return if (compact) truncateInline(dartToStringValue(value)) else dartToStringValue(value)
}

private fun formatScalarLine(value: Any?, compact: Boolean = true): String? {
    if (value == null) {
        return null
    }
    if (value is Boolean || value is Number) {
        return dartToStringValue(value)
    }
    val normalized = dartTrim(dartToStringValue(value))
    if (normalized.isEmpty()) {
        return null
    }
    return if (compact) truncateInline(normalized) else normalized
}

private fun truncateInline(value: String, maxLength: Int = 140): String {
    val collapsed = dartTrim(value.replace(JS_WS_RUN, " "))
    if (collapsed.length <= maxLength) {
        return collapsed
    }
    return "${dartTrimRight(collapsed.substring(0, maxLength - 1))}…"
}

private val distributionProgressRegex = Regex(
    "^(正在调用内嵌 (Alpine|Ubuntu) 环境执行命令|" +
        "正在执行内嵌 (Alpine|Ubuntu) 环境命令|" +
        "(Alpine|Ubuntu) 输出更新中|" +
        "Running a command in the embedded (Alpine|Ubuntu) environment|" +
        "Executing a command in the embedded (Alpine|Ubuntu) environment|" +
        "(Alpine|Ubuntu) output is updating|" +
        "Updating (Alpine|Ubuntu) output)\\z",
)

private fun isGenericTerminalProgressMessage(value: String): Boolean {
    val normalized = dartTrim(value)
    if (normalized.isEmpty()) {
        return true
    }
    return distributionProgressRegex.containsMatchIn(normalized) ||
        normalized == "正在调用内嵌 Alpine 终端执行命令" ||
        normalized == "正在执行内嵌 Alpine 终端命令" ||
        normalized == "正在调用内嵌终端环境执行命令" ||
        normalized == "正在执行内嵌终端环境命令" ||
        normalized == "终端输出更新中" ||
        normalized == "Running a command in the embedded Alpine terminal" ||
        normalized == "Executing a command in the embedded Alpine terminal" ||
        normalized == "Running a command in the embedded terminal environment" ||
        normalized == "Executing a command in the embedded terminal environment" ||
        normalized == "Updating terminal output"
}

private fun firstNonBlank(value: Map<String, Any?>, keys: List<String>): String {
    for (key in keys) {
        val candidate = dartTrim(dartStr(value[key]))
        if (candidate.isNotEmpty()) {
            return candidate
        }
    }
    return ""
}

private val structuredExactNoise: Set<String> = setOf(
    "success",
    "summary",
    "toolTitle",
    "tool_title",
    "terminalOutput",
    "terminalOutputLength",
    "stdout",
    "stdoutLength",
    "stderr",
    "stderrLength",
    "rawExtras",
    "artifacts",
    "actions",
    "uri",
    "logUri",
    "androidPath",
    "androidRootPath",
    "androidSkillFilePath",
    "androidSourcePath",
    "androidTargetPath",
    "androidLogPath",
    "liveSessionId",
    "liveStreamState",
    "liveFallbackReason",
    "timedOut",
)

private fun shouldSkipStructuredKey(key: String): Boolean {
    val normalized = dartTrim(key)
    if (normalized.isEmpty()) {
        return true
    }
    if (structuredExactNoise.contains(normalized)) {
        return true
    }
    val lower = normalized.lowercase()
    return lower.contains("html") ||
        lower.contains("trace") ||
        lower.contains("bodymarkdown") ||
        lower.contains("raw")
}

private val shellSafeRegex = Regex("^[A-Za-z0-9_./:@%+=,-]+\\z")

private fun quoteShellValue(value: String): String {
    if (shellSafeRegex.containsMatchIn(value)) {
        return value
    }
    return "'${value.replace("'", "'\"'\"'")}'"
}

private fun appendUniqueLine(lines: MutableList<String>, value: String) {
    val normalized = dartTrim(value)
    if (normalized.isEmpty() || lines.contains(normalized)) {
        return
    }
    lines.add(normalized)
}

private fun formatBytes(value: Long): String {
    if (value >= 1024 * 1024) {
        return "${dartToStringAsFixed1(value / (1024.0 * 1024.0))} MB"
    }
    if (value >= 1024) {
        return "${dartToStringAsFixed1(value / 1024.0)} KB"
    }
    return "$value B"
}

/** Dart `double.toStringAsFixed(1)` (round half away from zero on the exact binary value). */
private fun dartToStringAsFixed1(value: Double): String =
    java.math.BigDecimal(value).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()

private fun agentToolCopyText(
    cardData: Map<String, Any?>,
    transcript: AgentToolTranscript,
    isDiffView: Boolean,
): String {
    val body = if (isDiffView) dartStr(cardData["diffText"]) else transcript.outputText
    val prompt = dartTrimRight(transcript.promptLine)
    val output = dartTrimRight(body)
    if (prompt.isEmpty()) return output
    if (output.isEmpty()) return prompt
    return "$prompt\n$output"
}

private fun resolveAgentToolActions(
    cardData: Map<String, Any?>,
    english: Boolean,
): List<Map<String, Any?>> {
    val actions = ArrayList<Map<String, Any?>>()
    val rawActions = cardData["actions"]
    if (rawActions is List<*>) {
        actions.addAll(rawActions.filterIsInstance<Map<*, *>>().map { stringKeyedMap(it) })
    }
    val workspaceId = dartTrim(dartStr(cardData["workspaceId"]))
    val hasWorkspaceAction = actions.any { action -> dartTrim(dartStr(action["type"])) == "workspace" }
    if (workspaceId.isNotEmpty() && !hasWorkspaceAction) {
        actions.add(
            linkedMapOf(
                "type" to "workspace",
                "label" to if (english) "Open workspace" else "打开工作区",
                "payload" to linkedMapOf<String, Any?>("workspaceId" to workspaceId),
            ),
        )
    }
    val toolType = dartTrim(dartStr(cardData["toolType"]))
    if (cardData["showScheduleAction"] == true || toolType == "schedule") {
        actions.add(
            linkedMapOf(
                "type" to "route",
                "label" to if (english) "View scheduled tasks" else "查看定时任务",
                "target" to "/task/scheduled_tasks",
            ),
        )
    }
    if (cardData["showAlarmAction"] == true || toolType == "alarm") {
        actions.add(
            linkedMapOf(
                "type" to "route",
                "label" to if (english) "View alarms" else "查看闹钟列表",
                "target" to "/task/scheduled_tasks?tab=alarm",
            ),
        )
    }
    return actions.filter { action -> dartTrim(dartStr(action["label"])).isNotEmpty() }
}

/** Dart `_resolveDiffSummary` (transcript) / `_resolveInlineDiffSummary` (card): identical bodies. */
private fun resolveDiffSummary(cardData: Map<String, Any?>): AgentDiffSummary? =
    resolveInlineDiffSummary(cardData)

// ---------------------------------------------------------------------------
// agent_tool_summary_card.dart
// ---------------------------------------------------------------------------

private fun resolveDiffStatLabel(cardData: Map<String, Any?>): String? {
    if (dartStr(cardData["toolType"]) != "file") {
        return null
    }
    val additions = asNonNegativeInt(cardData["additions"])
    val deletions = asNonNegativeInt(cardData["deletions"])
    val changedFiles = asNonNegativeInt(cardData["changedFiles"])
    if (changedFiles <= 0 && additions <= 0 && deletions <= 0) {
        return null
    }
    return formatAgentDiffStat(additions, deletions)
}

private fun isInlineFileTool(cardData: Map<String, Any?>): Boolean {
    return dartTrim(dartStr(cardData["toolType"])) == "file"
}

private fun isAgentInlineTool(cardData: Map<String, Any?>): Boolean {
    if (isAgentToolUiStyle(cardData["uiStyle"])) {
        return true
    }
    val toolName = dartTrim(dartStr(cardData["toolName"]))
    val canonicalToolName = canonicalAgentToolName(toolName) ?: toolName
    if (canonicalToolName.startsWith("agent.") || canonicalToolName.startsWith("agent/")) {
        return true
    }
    for (rawJson in listOf(dartStr(cardData["rawResultJson"]), dartStr(cardData["resultPreviewJson"]))) {
        val decoded = decodeJsonMapOrEmpty(rawJson)
        val itemType = dartStr(decoded["type"])
        if (isAgentToolItemType(itemType)) {
            return true
        }
    }
    return false
}

private fun usesInlineToolStyle(cardData: Map<String, Any?>, useAgentToolPresentation: Boolean): Boolean {
    return isInlineFileTool(cardData) ||
        (useAgentToolPresentation && isAgentInlineTool(cardData))
}

private fun resolveInlineDiffSummary(cardData: Map<String, Any?>): AgentDiffSummary? {
    val diffText = dartStr(cardData["diffText"])
    val source = LinkedHashMap(cardData)
    if (diffText.isNotEmpty()) source["diffText"] = diffText
    val extracted = extractAgentDiffText(
        source,
        outputText = if (diffText.isNotEmpty()) diffText else resolveAgentToolTerminalOutput(cardData),
        progress = dartStr(cardData["progress"]),
        summary = dartStr(cardData["summary"]),
    )
    if (extracted == null || dartTrim(extracted).isEmpty()) {
        return null
    }
    val summary = parseAgentDiffText(extracted)
    return if (summary.files.isEmpty()) null else summary
}

private fun resolveInlineFilePath(cardData: Map<String, Any?>, diffSummary: AgentDiffSummary?): String {
    val filePath = dartTrim(dartStr(cardData["filePath"]))
    if (filePath.isNotEmpty()) {
        return filePath
    }
    val primaryPath = dartTrim(diffSummary?.primaryPath ?: "")
    if (primaryPath.isNotEmpty()) {
        return primaryPath
    }
    return ""
}

private fun lastPathSegment(path: String): String {
    val trimmed = dartTrim(path)
    if (trimmed.isEmpty()) {
        return ""
    }
    val normalized = trimmed.replace('\\', '/')
    val segments = normalized.split("/").filter { it.isNotEmpty() }
    if (segments.isEmpty()) {
        return normalized
    }
    return segments.last()
}

/** Dart `_inlineToolTrailingLabel`. */
private fun inlineToolTrailingLabel(cardData: Map<String, Any?>, status: String, english: Boolean): String {
    val label = if (status == "running" && !isAgentToolAwaitingConfirmation(cardData)) {
        resolveAgentToolTypeLabel(cardData, english)
    } else {
        resolveAgentToolStatusLabel(cardData, english)
    }
    return dartTrim(label)
}

// ---------------------------------------------------------------------------
// Dart semantics helpers (shared by this package)
// ---------------------------------------------------------------------------

/** JavaScript / Dart `.` (without dotAll). */
internal const val JS_DOT = "[^\\n\\r\\u2028\\u2029]"

/** JavaScript / Dart `\s`. */
internal const val JS_WS =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

private val JS_WS_RUN = Regex("$JS_WS+")

/** Dart `String.trim` whitespace set (Unicode White_Space + BOM). */
private fun isDartWhitespace(char: Char): Boolean = when (char) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u0085', ' ', ' ',
    ' ', ' ', ' ', ' ', '　', '﻿',
    -> true
    else -> char in ' '..' '
}

internal fun dartTrim(value: String): String = value.trim { isDartWhitespace(it) }

internal fun dartTrimRight(value: String): String = value.trimEnd { isDartWhitespace(it) }

/** Dart `Object.toString()` for JSON-like values (`{a: 1}`, `[1, 2]`, `1.0`). */
internal fun dartToStringValue(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Double -> dartDoubleToString(value)
    is Float -> dartDoubleToString(value.toDouble())
    is Map<*, *> -> value.entries.joinToString(", ", "{", "}") {
        "${dartToStringValue(it.key)}: ${dartToStringValue(it.value)}"
    }
    is Iterable<*> -> value.joinToString(", ", "[", "]") { dartToStringValue(it) }
    else -> value.toString()
}

/** Dart `(value ?? '').toString()`. */
internal fun dartStr(value: Any?): String = if (value == null) "" else dartToStringValue(value)

private val dartIntLiteral = Regex("^([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))$")

/** Dart `int.tryParse` (surrounding whitespace, sign, `0x` hex). */
private fun dartIntTryParse(text: String): Int? {
    val match = dartIntLiteral.matchEntire(dartTrim(text)) ?: return null
    val sign = match.groupValues[1]
    val hex = match.groupValues[2]
    val parsed = if (hex.isNotEmpty()) {
        (sign + hex).toLongOrNull(16)
    } else {
        (sign + match.groupValues[3]).toLongOrNull()
    }
    return parsed?.toInt()
}

private fun stringKeyedMap(value: Map<*, *>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    for ((key, nested) in value) result[dartToStringValue(key)] = nested
    return result
}

/** Dart `_decodeJsonMap`: object JSON as a string-keyed map, else empty. */
private fun decodeJsonMapOrEmpty(raw: String): Map<String, Any?> {
    val trimmed = dartTrim(raw)
    if (trimmed.isEmpty()) {
        return emptyMap()
    }
    return try {
        val decoded = DartJson.decode(trimmed)
        if (decoded is Map<*, *>) stringKeyedMap(decoded) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}
