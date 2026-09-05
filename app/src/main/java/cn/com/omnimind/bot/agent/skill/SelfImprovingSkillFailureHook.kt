package cn.com.omnimind.bot.agent

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class FailureLearningHookPayload(
    val entryId: String,
    val logFile: File,
    val logShellPath: String? = null,
    val guidance: String,
    val relatedHints: List<String>,
    val signature: String = "",
    val occurrences: Int = 1,
    val reusedEntry: Boolean = false
) {
    fun toPayload(): Map<String, Any?> {
        return linkedMapOf(
            "skillId" to SelfImprovingSkillFailureHook.SKILL_ID,
            "entryId" to entryId,
            "logPath" to (logShellPath ?: logFile.absolutePath),
            "guidance" to guidance,
            "relatedHints" to relatedHints,
            "signature" to signature,
            "occurrences" to occurrences,
            "reusedEntry" to reusedEntry
        )
    }
}

data class FailureLearningResolution(
    val entryId: String,
    val lesson: String,
    val shouldPromoteDaily: Boolean
)

object SelfImprovingSkillFailureHook {
    const val SKILL_ID = "self-improving-agent"

    private const val ERRORS_FILE_NAME = "ERRORS.md"
    private const val ERRORS_HEADER = "# Errors\n"
    private const val MAX_FIELD_CHARS = 2000
    private const val MAX_GUIDANCE_CHARS = 1200
    private const val MAX_ERROR_ENTRIES = 80
    private const val STATUS_PENDING = "pending"
    private const val STATUS_RESOLVED = "resolved"
    private const val STAGE_EXECUTION = "execution"

    fun resolveInstalledSkill(
        installedSkills: List<SkillIndexEntry>,
        skillLoader: SkillLoader
    ): ResolvedSkillContext? {
        val entry = installedSkills.firstOrNull { it.id == SKILL_ID } ?: return null
        return skillLoader.load(entry, "失败后自动读取")
    }

    fun shouldHandle(result: ToolExecutionResult): Boolean {
        return when (result) {
            is ToolExecutionResult.Error -> true
            is ToolExecutionResult.TerminalResult -> !result.success
            is ToolExecutionResult.Interrupted -> false
            is ToolExecutionResult.ContextResult -> !result.success
            is ToolExecutionResult.MemoryResult -> !result.success
            is ToolExecutionResult.McpResult -> !result.success
            is ToolExecutionResult.ScheduleResult -> !result.success
            else -> false
        }
    }

    fun capture(
        skillsRoot: File,
        skill: ResolvedSkillContext,
        userMessage: String,
        toolName: String,
        toolType: String,
        argumentsJson: String?,
        result: ToolExecutionResult,
        agentRunId: String = "unknown",
        failureStage: String = STAGE_EXECUTION
    ): FailureLearningHookPayload? {
        return runCatching {
            val skillRoot = File(skillsRoot, SKILL_ID)
            val dataDir = File(skillRoot, "data").apply { mkdirs() }
            val errorsFile = File(dataDir, ERRORS_FILE_NAME)
            ensureErrorsFile(errorsFile)

            val summary = truncateText(redactSensitiveText(failureSummary(result)), 160)
            val details = truncateText(
                redactSensitiveText(failureDetails(result)),
                MAX_FIELD_CHARS
            )
            val userGoal = truncateText(redactSensitiveText(userMessage.trim()), 400)
            val argsBlock = truncateText(
                redactSensitiveText(argumentsJson?.trim().orEmpty()),
                800
            )
            val argumentKeys = extractArgumentKeys(argumentsJson)
            val signature = failureSignature(
                toolName,
                "$summary\n${details.take(600)}"
            )
            val timestamp = Instant.now().toString()
            val blocks = extractBlocks(errorsFile.readText()).toMutableList()
            val existingIndex = blocks.indexOfLast { block ->
                metadataValue(block, "指纹") == signature
            }

            val entryId: String
            val occurrences: Int
            val reusedEntry: Boolean
            if (existingIndex >= 0) {
                val previous = blocks.removeAt(existingIndex)
                entryId = entryIdFromBlock(previous) ?: newEntryId()
                occurrences = (metadataValue(previous, "出现次数")?.toIntOrNull() ?: 1) + 1
                var updated = replaceStatus(previous, STATUS_PENDING)
                updated = upsertMetadataLine(updated, "出现次数", occurrences.toString())
                updated = upsertMetadataLine(updated, "最近发生", timestamp)
                updated = upsertMetadataLine(updated, "最近 Agent run", agentRunId)
                updated = upsertMetadataLine(updated, "失败阶段", failureStage)
                if (argumentKeys.isNotEmpty()) {
                    updated = upsertMetadataLine(
                        updated,
                        "参数键",
                        argumentKeys.joinToString(", ")
                    )
                }
                blocks += updated
                reusedEntry = true
            } else {
                entryId = newEntryId()
                occurrences = 1
                reusedEntry = false
                blocks += buildString {
                    appendLine("## [$entryId] $toolName")
                    appendLine()
                    appendLine("**记录时间**: $timestamp")
                    appendLine("**优先级**: high")
                    appendLine("**状态**: $STATUS_PENDING")
                    appendLine("**领域**: runtime")
                    appendLine()
                    appendLine("### 摘要")
                    appendLine(summary)
                    appendLine()
                    appendLine("### Error")
                    appendLine("```")
                    appendLine(details)
                    appendLine("```")
                    appendLine()
                    appendLine("### Context")
                    appendLine("- 用户目标: ${userGoal.ifBlank { "（空）" }}")
                    appendLine("- 工具名称: $toolName")
                    appendLine("- 工具类型: $toolType")
                    appendLine("- 工具参数: ${argsBlock.ifBlank { "（空）" }}")
                    appendLine()
                    appendLine("### 建议修复")
                    appendLine("（待补充）")
                    appendLine()
                    appendLine("### 元数据")
                    appendLine("- 来源: auto_failure_hook")
                    appendLine("- 作用域: skill")
                    appendLine("- 关联技能: $SKILL_ID")
                    appendLine("- 指纹: $signature")
                    appendLine("- 出现次数: 1")
                    appendLine("- 首次 Agent run: $agentRunId")
                    appendLine("- 最近 Agent run: $agentRunId")
                    appendLine("- 最近发生: $timestamp")
                    appendLine("- 失败阶段: $failureStage")
                    if (argumentKeys.isNotEmpty()) {
                        appendLine("- 参数键: ${argumentKeys.joinToString(", ")}")
                    }
                    appendLine()
                    appendLine("---")
                }.trim()
            }
            persistBlocks(errorsFile, blocks)

            val relatedHints = relatedHints(errorsFile, toolName, entryId)
            val guidance = buildGuidance(skill, relatedHints, occurrences)
            FailureLearningHookPayload(
                entryId = entryId,
                logFile = errorsFile,
                guidance = truncateText(guidance, MAX_GUIDANCE_CHARS),
                relatedHints = relatedHints,
                signature = signature,
                occurrences = occurrences,
                reusedEntry = reusedEntry
            )
        }.getOrNull()
    }

    /**
     * Close the most recent failure from this run when the same tool later
     * succeeds. This is the after-tool half of the learning hook for failures
     * where the remediation is directly observable: argument parse/schema
     * failures followed by a successful call. Runtime failures still need a
     * concrete model-authored fix and remain pending.
     */
    fun resolveAfterSuccess(
        skillsRoot: File,
        agentRunId: String,
        toolName: String,
        argumentsJson: String?,
        result: ToolExecutionResult
    ): FailureLearningResolution? {
        if (!isSuccessfulResult(result)) return null
        return runCatching {
            val errorsFile = File(File(File(skillsRoot, SKILL_ID), "data"), ERRORS_FILE_NAME)
            if (!errorsFile.exists()) return null
            val blocks = extractBlocks(errorsFile.readText()).toMutableList()
            val index = blocks.indexOfLast { block ->
                toolNameFromBlock(block).equals(toolName, ignoreCase = true) &&
                    metadataValue(block, "最近 Agent run") == agentRunId &&
                    statusFromBlock(block) == STATUS_PENDING
            }
            if (index < 0) return null

            val block = blocks[index]
            val entryId = entryIdFromBlock(block) ?: return null
            val stage = metadataValue(block, "失败阶段") ?: STAGE_EXECUTION
            val argumentRecovery = stage == "argument_parse" || stage == "argument_validation"
            if (!argumentRecovery) return null
            val summary = extractSectionFirstLine(block, "### 摘要")
                ?.trim()
                .orEmpty()
                .ifBlank { "工具调用失败" }
            val capturedKeys = metadataValue(block, "参数键")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val successfulKeys = extractArgumentKeys(argumentsJson)
            val relevantKeys = (capturedKeys + successfulKeys)
                .distinct()
                .take(12)
            val lesson = buildString {
                append("调用 ").append(toolName).append(" 遇到“")
                    .append(summary.take(120)).append("”时，")
                append("先按工具 schema 检查并修正参数")
                if (relevantKeys.isNotEmpty()) {
                    append("（涉及字段：").append(relevantKeys.joinToString(", ")).append("）")
                }
                append("；修正后已在同一 Agent 运行中验证成功。")
            }
            val resolvedAt = Instant.now().toString()
            var updated = replaceStatus(block, STATUS_RESOLVED)
            updated = replaceSectionFirstLine(updated, "### 建议修复", lesson)
            updated = upsertMetadataLine(updated, "解决时间", resolvedAt)
            updated = upsertMetadataLine(updated, "解决方式", "same_run_verified_success")
            blocks[index] = updated
            persistBlocks(errorsFile, blocks)
            FailureLearningResolution(
                entryId = entryId,
                lesson = lesson,
                shouldPromoteDaily = true
            )
        }.getOrNull()
    }

    private fun buildGuidance(
        skill: ResolvedSkillContext,
        relatedHints: List<String>,
        occurrences: Int
    ): String {
        val base = buildString {
            appendLine(
                if (occurrences > 1) {
                    "self-improving-agent 已合并同类失败（累计 $occurrences 次），没有重复追加日志。"
                } else {
                    "self-improving-agent 已自动读取并记录本次失败。"
                }
            )
            appendLine(skill.stepGuidance(800))
            if (relatedHints.isNotEmpty()) {
                appendLine("相关历史：")
                relatedHints.forEach { hint ->
                    appendLine("- $hint")
                }
            }
            append("不要机械重复刚刚失败的同一步骤；先依据失败结果修正方案。")
            appendLine()
            append(
                "找到修复方法后，用 memory_write_daily 写一条“遇到X先Y”的简短规则" +
                    "（跨会话稳定则用 memory_upsert_longterm），并回填本条 ERRORS 的“建议修复”与状态。" +
                    "历史失败教训已纳入记忆检索，相关操作前会被自动召回。"
            )
        }.trim()
        return base
    }

    /**
     * Compact one-liners extracted from `data/ERRORS.md` for the memory
     * retrieval index, so past tool/environment failures surface proactively via
     * `memory_search` / prefetch — not only when the same tool fails again.
     * Format: `[toolName] summary` (plus ` → fix` once 建议修复 is filled).
     * Newest first, capped at [limit].
     */
    fun collectSearchableLessons(skillsRoot: File, limit: Int = 40): List<String> {
        return runCatching {
            val errorsFile = File(File(File(skillsRoot, SKILL_ID), "data"), ERRORS_FILE_NAME)
            if (!errorsFile.exists()) {
                return emptyList()
            }
            extractBlocks(errorsFile.readText())
                .asReversed()
                .mapNotNull { block -> lessonLineFromBlock(block) }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    private fun lessonLineFromBlock(block: String): String? {
        val toolName = Regex("^## \\[[^\\]]+]\\s*(.*)$", RegexOption.MULTILINE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val summary = extractSectionFirstLine(block, "### 摘要")?.trim().orEmpty()
        if (summary.isBlank()) {
            return null
        }
        val fix = extractSectionFirstLine(block, "### 建议修复")
            ?.trim()
            ?.takeUnless { it.isBlank() || it == "（待补充）" }
        val occurrences = metadataValue(block, "出现次数")
            ?.toIntOrNull()
            ?.takeIf { it > 1 }
        return buildString {
            if (toolName.isNotBlank()) {
                append("[").append(toolName).append("] ")
            }
            append(summary)
            if (occurrences != null) {
                append(" (×").append(occurrences).append(")")
            }
            if (fix != null) {
                append(" → ").append(fix)
            }
        }.trim().takeUnless { it.isBlank() }
    }

    private fun failureSummary(result: ToolExecutionResult): String {
        return when (result) {
            is ToolExecutionResult.Error -> result.message
            is ToolExecutionResult.TerminalResult -> result.summaryText
            is ToolExecutionResult.Interrupted -> result.summaryText
            is ToolExecutionResult.ContextResult -> result.summaryText
            is ToolExecutionResult.MemoryResult -> result.summaryText
            is ToolExecutionResult.McpResult -> result.summaryText
            is ToolExecutionResult.ScheduleResult -> result.summaryText
            else -> "工具调用失败"
        }.ifBlank { "工具调用失败" }
    }

    private fun failureDetails(result: ToolExecutionResult): String {
        return when (result) {
            is ToolExecutionResult.Error -> result.message
            is ToolExecutionResult.TerminalResult -> {
                result.rawResultJson.ifBlank {
                    result.terminalOutput.ifBlank { result.summaryText }
                }
            }
            is ToolExecutionResult.Interrupted -> {
                result.rawResultJson.ifBlank {
                    result.terminalOutput.ifBlank { result.summaryText }
                }
            }
            is ToolExecutionResult.ContextResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.MemoryResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.McpResult -> result.rawResultJson.ifBlank { result.summaryText }
            is ToolExecutionResult.ScheduleResult -> result.previewJson.ifBlank { result.summaryText }
            else -> "工具调用失败"
        }.ifBlank { "工具调用失败" }
    }

    private fun ensureErrorsFile(file: File) {
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.writeText(ERRORS_HEADER)
        }
    }

    private fun isSuccessfulResult(result: ToolExecutionResult): Boolean {
        return when (result) {
            is ToolExecutionResult.TerminalResult -> result.success
            is ToolExecutionResult.ContextResult -> result.success
            is ToolExecutionResult.MemoryResult -> result.success
            is ToolExecutionResult.McpResult -> result.success
            is ToolExecutionResult.ScheduleResult -> result.success
            else -> false
        }
    }

    private fun failureSignature(toolName: String, evidence: String): String {
        val normalized = evidence
            .lowercase(Locale.ROOT)
            .replace(Regex("[0-9a-f]{8,}", RegexOption.IGNORE_CASE), "#")
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
        val input = "${toolName.trim().lowercase(Locale.ROOT)}|$normalized"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun extractArgumentKeys(argumentsJson: String?): List<String> {
        if (argumentsJson.isNullOrBlank()) return emptyList()
        return Regex("\\\"([^\\\"]+)\\\"\\s*:")
            .findAll(argumentsJson)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(12)
            .toList()
    }

    private fun redactSensitiveText(raw: String): String {
        if (raw.isBlank()) return raw
        val sensitiveAssignment = Regex(
            "(?i)([\\\"']?(?:(?:x[-_])?api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|secret|password|authorization|cookie|credential|private[_-]?key)[\\\"']?\\s*[:=]\\s*)([\\\"'][^\\\"'\\n]*[\\\"']|[^\\s,}]+)"
        )
        return sensitiveAssignment.replace(raw) { match ->
            "${match.groupValues[1]}\"[REDACTED]\""
        }
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
            .replace(Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b"), "[REDACTED_KEY]")
    }

    private fun persistBlocks(file: File, blocks: List<String>) {
        val pendingIndexes = blocks.indices
            .filter { index -> statusFromBlock(blocks[index]) == STATUS_PENDING }
            .takeLast(MAX_ERROR_ENTRIES / 2)
            .toSet()
        val retainedIndexes = buildSet {
            addAll(pendingIndexes)
            for (index in blocks.indices.reversed()) {
                if (size >= MAX_ERROR_ENTRIES) break
                add(index)
            }
        }
        val retained = blocks.filterIndexed { index, _ -> index in retainedIndexes }
        val content = buildString {
            append(ERRORS_HEADER)
            if (retained.isNotEmpty()) {
                appendLine()
                append(retained.joinToString("\n\n") { it.trim() })
                appendLine()
            }
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            file.writeText(content)
            temp.delete()
        }
    }

    private fun entryIdFromBlock(block: String): String? {
        return Regex("^## \\[([^]]+)]", RegexOption.MULTILINE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun toolNameFromBlock(block: String): String {
        return Regex("^## \\[[^]]+]\\s*(.*)$", RegexOption.MULTILINE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
    }

    private fun statusFromBlock(block: String): String? {
        return Regex("^\\*\\*状态\\*\\*:\\s*(\\S+)", RegexOption.MULTILINE)
            .find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun replaceStatus(block: String, status: String): String {
        val regex = Regex("^\\*\\*状态\\*\\*:\\s*.*$", RegexOption.MULTILINE)
        return if (regex.containsMatchIn(block)) {
            regex.replaceFirst(block, "**状态**: $status")
        } else {
            block.replaceFirst("**优先级**: high", "**优先级**: high\n**状态**: $status")
        }
    }

    private fun metadataValue(block: String, label: String): String? {
        val regex = Regex(
            "^- ${Regex.escape(label)}:\\s*(.*)$",
            RegexOption.MULTILINE
        )
        return regex.find(block)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun upsertMetadataLine(block: String, label: String, value: String): String {
        val line = "- $label: $value"
        val regex = Regex(
            "^- ${Regex.escape(label)}:\\s*.*$",
            RegexOption.MULTILINE
        )
        if (regex.containsMatchIn(block)) {
            return regex.replaceFirst(block, line)
        }
        val heading = "### 元数据"
        val headingIndex = block.indexOf(heading)
        if (headingIndex < 0) {
            return block.trimEnd() + "\n\n$heading\n$line\n"
        }
        val insertAt = headingIndex + heading.length
        return block.substring(0, insertAt) + "\n$line" + block.substring(insertAt)
    }

    private fun replaceSectionFirstLine(
        block: String,
        heading: String,
        value: String
    ): String {
        val lines = block.lines().toMutableList()
        val headingIndex = lines.indexOfFirst { it.trim() == heading }
        if (headingIndex < 0) return block
        for (index in headingIndex + 1 until lines.size) {
            val current = lines[index].trim()
            if (current.startsWith("### ")) break
            if (current.isNotEmpty()) {
                lines[index] = value
                return lines.joinToString("\n")
            }
        }
        lines.add(headingIndex + 1, value)
        return lines.joinToString("\n")
    }

    private fun newEntryId(): String {
        val date = DateTimeFormatter.BASIC_ISO_DATE
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val suffix = UUID.randomUUID().toString()
            .replace("-", "")
            .take(3)
            .uppercase(Locale.US)
        return "ERR-$date-$suffix"
    }

    private fun relatedHints(
        errorsFile: File,
        toolName: String,
        currentEntryId: String,
        limit: Int = 2
    ): List<String> {
        val normalizedToolName = toolName.trim().lowercase(Locale.ROOT)
        if (normalizedToolName.isBlank() || !errorsFile.exists()) {
            return emptyList()
        }
        return extractBlocks(errorsFile.readText())
            .asReversed()
            .mapNotNull { block ->
                val entryId = Regex("^## \\[([^\\]]+)]", RegexOption.MULTILINE)
                    .find(block)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?: return@mapNotNull null
                if (entryId == currentEntryId) {
                    return@mapNotNull null
                }
                if (toolNameFromBlock(block).lowercase(Locale.ROOT) != normalizedToolName) {
                    return@mapNotNull null
                }
                val summary = extractSectionFirstLine(block, "### 摘要")
                    ?: return@mapNotNull null
                "$entryId $summary"
            }
            .take(limit)
    }

    private fun extractBlocks(content: String): List<String> {
        val matcher = Regex("^## \\[", RegexOption.MULTILINE)
            .findAll(content)
            .toList()
        if (matcher.isEmpty()) {
            return emptyList()
        }
        return matcher.mapIndexed { index, matchResult ->
            val start = matchResult.range.first
            val end = matcher.getOrNull(index + 1)?.range?.first ?: content.length
            content.substring(start, end).trim()
        }
    }

    private fun extractSectionFirstLine(
        block: String,
        heading: String
    ): String? {
        val lines = block.lines()
        val startIndex = lines.indexOfFirst { it.trim() == heading }
        if (startIndex < 0) {
            return null
        }
        for (index in startIndex + 1 until lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) {
                continue
            }
            if (line.startsWith("### ")) {
                return null
            }
            return line
        }
        return null
    }

    private fun truncateText(text: String, maxChars: Int): String {
        val normalized = text.replace("\r\n", "\n").trim()
        return if (normalized.length <= maxChars) {
            normalized
        } else {
            normalized.take(maxChars) + "\n..."
        }
    }
}
