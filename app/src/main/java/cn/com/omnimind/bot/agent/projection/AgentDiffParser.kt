package cn.com.omnimind.bot.agent.projection

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

/*
 * Kotlin port of `ui/lib/services/agent_diff_parser.dart`.
 *
 * Pure functions that find unified diffs inside loosely typed agent tool
 * payloads, normalize hunk-only patches into full file diffs and parse them
 * into per-file line lists. Keep this file mechanically comparable with the
 * Dart source: same function names, branch order and fallbacks.
 *
 * Regex note: Dart `RegExp` follows JavaScript semantics. `\s` there is the
 * Unicode whitespace set, `.` excludes `\n`, `\r`, U+2028 and U+2029, and `$`
 * (non-multiline) matches only at the very end of input. Java differs on all
 * three, so the patterns below spell those classes out explicitly.
 */

/** JavaScript / Dart `\s`. */
private const val JS_WS =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

/** JavaScript / Dart `.` (without dotAll). */
private const val JS_DOT = "[^\\n\\r\\u2028\\u2029]"

private val DIFF_HUNK_ANYWHERE_REGEX = Regex("(^|\\n)@@$JS_WS+-\\d")
private val HUNK_ONLY_START_REGEX = Regex("^@@$JS_WS+-\\d")
private val PATH_FILENAME_REGEX = Regex("^[\\w.-]+\\.[A-Za-z0-9]{1,12}\\z")
private val DIFF_GIT_REGEX = Regex("^diff --git$JS_WS+($JS_DOT+?)$JS_WS+($JS_DOT+)\\z")
private val HUNK_HEADER_REGEX =
    Regex("^@@$JS_WS+-(\\d+)(?:,\\d+)?$JS_WS+\\+(\\d+)(?:,\\d+)?")

@Suppress("EnumEntryName")
internal enum class AgentDiffLineKind { header, add, remove, context, meta }

internal data class AgentDiffLine(
    val kind: AgentDiffLineKind,
    val content: String,
    val prefix: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

internal data class AgentDiffFile(
    val oldPath: String?,
    val newPath: String?,
    val displayPath: String,
    val lines: List<AgentDiffLine>,
    val additions: Int,
    val deletions: Int,
    val isNewFile: Boolean,
    val isDeletedFile: Boolean,
)

internal data class AgentDiffSummary(
    val files: List<AgentDiffFile>,
    val additions: Int,
    val deletions: Int,
    val sourceText: String,
) {
    val hasChanges: Boolean
        get() = files.isNotEmpty() && (additions > 0 || deletions > 0)

    val changedFileCount: Int
        get() = files.size

    val primaryPath: String
        get() {
            for (file in files) {
                if (file.displayPath.trim().isNotEmpty()) {
                    return file.displayPath
                }
            }
            return ""
        }
}

internal fun parseAgentDiffText(diffText: String): AgentDiffSummary {
    val normalized = normalizeNewlines(diffText).trimEnd()
    if (normalized.trim().isEmpty()) {
        return AgentDiffSummary(
            files = emptyList(),
            additions = 0,
            deletions = 0,
            sourceText = "",
        )
    }

    val parser = UnifiedDiffParser(normalized)
    return parser.parse()
}

internal fun extractAgentDiffText(
    source: Any?,
    outputText: String = "",
    progress: String = "",
    summary: String = "",
): String? {
    val candidates = ArrayList<String>()
    candidates.add(outputText)
    candidates.add(progress)
    candidates.add(summary)
    candidates.addAll(extractDiffCandidates(source))
    val unique = LinkedHashSet<String>()
    val matches = ArrayList<String>()
    for (candidate in candidates) {
        val normalized = normalizeNewlines(candidate).trimEnd()
        if (normalized.trim().isEmpty() ||
            !looksLikeAgentDiff(normalized) ||
            !unique.add(normalized)
        ) {
            continue
        }
        matches.add(normalized)
    }
    if (matches.isEmpty()) {
        return null
    }
    return matches.joinToString("\n")
}

internal fun extractAgentDiffPath(source: Any?): String? {
    return extractFirstPathCandidate(source)
}

internal fun looksLikeAgentDiff(value: String): Boolean {
    val text = normalizeNewlines(value).trim()
    if (text.isEmpty()) {
        return false
    }
    if (text.contains("diff --git ") || DIFF_HUNK_ANYWHERE_REGEX.containsMatchIn(text)) {
        return true
    }
    val lines = text.split("\n")
    val hasOldHeader = lines.any { it.startsWith("--- ") }
    val hasNewHeader = lines.any { it.startsWith("+++ ") }
    val hasHunk = lines.any { it.startsWith("@@ ") }
    return hasOldHeader && hasNewHeader && hasHunk
}

internal fun formatAgentDiffStat(additions: Int, deletions: Int): String {
    return "+${compactCount(additions)} -${compactCount(deletions)}"
}

internal fun summarizeAgentDiff(summary: AgentDiffSummary): String {
    if (summary.files.isEmpty()) {
        return ""
    }
    val fileLabel = if (summary.files.size == 1) "1 file" else "${summary.files.size} files"
    return "$fileLabel · ${formatAgentDiffStat(additions = summary.additions, deletions = summary.deletions)}"
}

private fun extractDiffCandidates(value: Any?): List<String> {
    if (value == null) {
        return emptyList()
    }
    if (value is String) {
        val decoded = tryDecodeJson(value)
        if (decoded != null && decoded != value) {
            return listOf(value) + extractDiffCandidates(decoded)
        }
        return listOf(value)
    }
    if (value is Iterable<*>) {
        val out = ArrayList<String>()
        for (item in value) {
            out.addAll(extractDiffCandidates(item))
        }
        return out
    }
    if (value !is Map<*, *>) {
        return listOf(value.toString())
    }

    val map = copyStringMap(value)!!
    val out = ArrayList<String>()
    for (key in listOf(
        "diff",
        "patch",
        "unifiedDiff",
        "unified_diff",
        "diffText",
        "rawResultJson",
        "resultPreviewJson",
        "argsJson",
        "delta",
        "output",
        "text",
        "content",
    )) {
        if (map.containsKey(key)) {
            for (candidate in extractDiffCandidates(map[key])) {
                out.add(normalizeDiffCandidateForContainer(candidate, map))
            }
        }
    }

    val oldText = stringValue(map["oldString"] ?: map["oldText"] ?: map["before"])
    val newText = stringValue(map["newString"] ?: map["newText"] ?: map["after"])
    if (oldText != null && newText != null) {
        out.add(
            buildAgentUnifiedDiffFromStrings(
                oldText = oldText,
                newText = newText,
                path = stringValue(
                    map["path"]
                        ?: map["filePath"]
                        ?: map["file_path"]
                        ?: map["filename"]
                        ?: map["fileName"],
                ),
            ),
        )
    }

    for (key in listOf(
        "item",
        "result",
        "changes",
        "files",
        "fileChanges",
        "entries",
    )) {
        if (map.containsKey(key)) {
            for (candidate in extractDiffCandidates(map[key])) {
                out.add(normalizeDiffCandidateForContainer(candidate, map))
            }
        }
    }
    return out
}

private fun normalizeDiffCandidateForContainer(
    candidate: String,
    container: Map<String, Any?>,
): String {
    val normalized = normalizeNewlines(candidate).trimEnd()
    if (!looksLikeHunkOnlyDiff(normalized)) {
        return normalized
    }
    val pathInfo = diffPathInfoFromMap(container) ?: return normalized
    return buildAgentUnifiedDiffFromPatch(
        diffText = normalized,
        oldPath = pathInfo.oldPath,
        newPath = pathInfo.newPath,
        changeKind = pathInfo.changeKind,
    )
}

private fun looksLikeHunkOnlyDiff(value: String): Boolean {
    val text = normalizeNewlines(value).trimStart()
    if (text.isEmpty() ||
        text.contains("diff --git ") ||
        text.startsWith("--- ") ||
        text.startsWith("+++ ")
    ) {
        return false
    }
    return HUNK_ONLY_START_REGEX.containsMatchIn(text)
}

private fun diffPathInfoFromMap(map: Map<String, Any?>): DiffPathInfo? {
    val changeKind = changeKindFromMap(map)
    val basePath = stringValue(
        map["path"]
            ?: map["filePath"]
            ?: map["file_path"]
            ?: map["filename"]
            ?: map["fileName"],
    )
    val oldPath = stringValue(
        map["oldPath"]
            ?: map["old_path"]
            ?: map["sourcePath"]
            ?: map["source_path"]
            ?: map["fromPath"]
            ?: map["from_path"],
    ) ?: basePath
    val newPath = stringValue(
        map["newPath"]
            ?: map["new_path"]
            ?: map["targetPath"]
            ?: map["target_path"]
            ?: map["movePath"]
            ?: map["move_path"]
            ?: map["toPath"]
            ?: map["to_path"],
    ) ?: basePath
    if (oldPath == null && newPath == null) {
        return null
    }
    return DiffPathInfo(oldPath = oldPath, newPath = newPath, changeKind = changeKind)
}

private fun changeKindFromMap(map: Map<String, Any?>): String? {
    val kind = map["kind"]
    if (kind is Map<*, *>) {
        return stringValue(kind["type"] ?: kind["kind"])
    }
    return stringValue(kind ?: map["changeKind"] ?: map["change_kind"])
}

private data class DiffPathInfo(
    val oldPath: String? = null,
    val newPath: String? = null,
    val changeKind: String? = null,
)

private val ADD_CHANGE_KINDS = setOf("add", "added", "create", "created", "new")
private val DELETE_CHANGE_KINDS = setOf("delete", "deleted", "remove", "removed")

internal fun buildAgentUnifiedDiffFromPatch(
    diffText: String,
    oldPath: String? = null,
    newPath: String? = null,
    changeKind: String? = null,
): String {
    val normalized = normalizeNewlines(diffText).trimEnd()
    if (normalized.trim().isEmpty() || !looksLikeHunkOnlyDiff(normalized)) {
        return normalized
    }

    // Dart `toLowerCase()` is locale-independent.
    val normalizedKind = (changeKind ?: "").trim().lowercase()
    val isAdd = ADD_CHANGE_KINDS.contains(normalizedKind)
    val isDelete = DELETE_CHANGE_KINDS.contains(normalizedKind)
    val effectiveOldPath = if (oldPath?.trim()?.isNotEmpty() == true) oldPath.trim() else newPath?.trim()
    val effectiveNewPath = if (newPath?.trim()?.isNotEmpty() == true) newPath.trim() else oldPath?.trim()
    val displayPath =
        if (effectiveNewPath?.trim()?.isNotEmpty() == true) effectiveNewPath else effectiveOldPath
    if (displayPath == null || displayPath.trim().isEmpty()) {
        return normalized
    }

    val oldHeaderPath = if (isAdd) "/dev/null" else "a/${effectiveOldPath ?: displayPath}"
    val newHeaderPath = if (isDelete) "/dev/null" else "b/${effectiveNewPath ?: displayPath}"
    val diffOldPath = effectiveOldPath ?: displayPath
    val diffNewPath = effectiveNewPath ?: displayPath
    return listOf(
        "diff --git a/$diffOldPath b/$diffNewPath",
        "--- $oldHeaderPath",
        "+++ $newHeaderPath",
        normalized,
    ).joinToString("\n")
}

internal fun buildAgentUnifiedDiffFromStrings(
    oldText: String,
    newText: String,
    path: String? = null,
): String {
    val displayPath = if (path?.trim()?.isNotEmpty() == true) path.trim() else "file"
    val oldLines = normalizeNewlines(oldText).split("\n")
    val newLines = normalizeNewlines(newText).split("\n")
    val oldCount = if (oldText.isEmpty()) 0 else oldLines.size
    val newCount = if (newText.isEmpty()) 0 else newLines.size
    val lines = ArrayList<String>()
    lines.add("--- a/$displayPath")
    lines.add("+++ b/$displayPath")
    lines.add("@@ -1,$oldCount +1,$newCount @@")
    lines.addAll(buildLineDiff(oldText, newText))
    return lines.joinToString("\n")
}

/** LCS line diff (same tie-breaking as the Dart: prefer removal). */
private fun buildLineDiff(oldText: String, newText: String): List<String> {
    val oldLines = if (oldText.isEmpty()) emptyList() else normalizeNewlines(oldText).split("\n")
    val newLines = if (newText.isEmpty()) emptyList() else normalizeNewlines(newText).split("\n")
    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            if (oldLines[i] == newLines[j]) {
                dp[i][j] = dp[i + 1][j + 1] + 1
            } else {
                dp[i][j] = max(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    val out = ArrayList<String>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        if (oldLines[i] == newLines[j]) {
            out.add(" ${oldLines[i]}")
            i += 1
            j += 1
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
            out.add("-${oldLines[i]}")
            i += 1
        } else {
            out.add("+${newLines[j]}")
            j += 1
        }
    }
    while (i < m) {
        out.add("-${oldLines[i]}")
        i += 1
    }
    while (j < n) {
        out.add("+${newLines[j]}")
        j += 1
    }
    return out
}

private fun tryDecodeJson(value: String): Any? {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || !(trimmed.startsWith("{") || trimmed.startsWith("["))) {
        return null
    }
    return runCatching { DartJson.decode(trimmed) }.getOrNull()
}

/** Dart `_stringValue`: untrimmed `toString()`, null when blank. */
private fun stringValue(value: Any?): String? {
    val text = dartToString(value)
    if (text == null || text.trim().isEmpty()) {
        return null
    }
    return text
}

private fun extractFirstPathCandidate(value: Any?): String? {
    if (value == null) {
        return null
    }
    if (value is String) {
        val decoded = tryDecodeJson(value)
        if (decoded != null && decoded != value) {
            return extractFirstPathCandidate(decoded)
        }
        val trimmed = value.trim()
        if (looksLikePathString(trimmed)) {
            return trimmed
        }
        return null
    }
    if (value is Iterable<*>) {
        for (item in value) {
            val path = extractFirstPathCandidate(item)
            if (path != null) {
                return path
            }
        }
        return null
    }
    if (value !is Map<*, *>) {
        return null
    }

    val map = copyStringMap(value)!!
    val direct = stringValue(
        map["path"]
            ?: map["filePath"]
            ?: map["file_path"]
            ?: map["filename"]
            ?: map["fileName"]
            ?: map["newPath"]
            ?: map["new_path"]
            ?: map["targetPath"]
            ?: map["target_path"]
            ?: map["movePath"]
            ?: map["move_path"]
            ?: map["oldPath"]
            ?: map["old_path"]
            ?: map["sourcePath"]
            ?: map["source_path"],
    )
    if (direct != null) {
        return direct
    }

    for (key in listOf(
        "changes",
        "files",
        "fileChanges",
        "entries",
        "arguments",
        "args",
        "input",
        "item",
        "result",
        "rawResultJson",
        "resultPreviewJson",
        "argsJson",
    )) {
        if (!map.containsKey(key)) {
            continue
        }
        val path = extractFirstPathCandidate(map[key])
        if (path != null) {
            return path
        }
    }
    return null
}

private fun looksLikePathString(value: String): Boolean {
    if (value.isEmpty() ||
        value.contains("\n") ||
        looksLikeAgentDiff(value) ||
        value.trim().startsWith("{") ||
        value.trim().startsWith("[")
    ) {
        return false
    }
    return value.contains("/") ||
        value.contains("\\") ||
        PATH_FILENAME_REGEX.containsMatchIn(value)
}

private fun normalizeNewlines(value: String): String =
    value.replace("\r\n", "\n").replace("\r", "\n")

private fun compactCount(value: Int): String {
    if (abs(value) >= 1000000) {
        return "${toStringAsFixed1(value / 1000000.0)}m"
    }
    if (abs(value) >= 1000) {
        return "${toStringAsFixed1(value / 1000.0)}k"
    }
    return value.toString()
}

/**
 * Dart `double.toStringAsFixed(1)`: rounds the exact binary value, ties away
 * from zero, locale independent.
 */
private fun toStringAsFixed1(value: Double): String =
    BigDecimal(value).setScale(1, RoundingMode.HALF_UP).toPlainString()

private class UnifiedDiffParser(val diffText: String) {
    private val files = ArrayList<AgentDiffFile>()

    private var oldPath: String? = null
    private var newPath: String? = null
    private var displayPath: String? = null
    private var additions = 0
    private var deletions = 0
    private var oldLine: Int? = null
    private var newLine: Int? = null
    private var inHunk = false
    private val lines = ArrayList<AgentDiffLine>()

    fun parse(): AgentDiffSummary {
        for (line in diffText.split("\n")) {
            parseLine(line)
        }
        finishFile()
        val additions = files.fold(0) { sum, file -> sum + file.additions }
        val deletions = files.fold(0) { sum, file -> sum + file.deletions }
        return AgentDiffSummary(
            files = files.toList(),
            additions = additions,
            deletions = deletions,
            sourceText = diffText,
        )
    }

    private fun parseLine(line: String) {
        if (line.startsWith("diff --git ")) {
            finishFile()
            startFileFromDiffGit(line)
            return
        }
        if (line.startsWith("--- ")) {
            if (inHunk || lines.isNotEmpty()) {
                finishFile()
            }
            ensureFile()
            oldPath = cleanDiffPath(line.substring(4))
            displayPath = chooseDisplayPath()
            return
        }
        if (line.startsWith("+++ ")) {
            ensureFile()
            newPath = cleanDiffPath(line.substring(4))
            displayPath = chooseDisplayPath()
            return
        }
        if (line.startsWith("@@")) {
            ensureFile()
            val hunk = parseHunkHeader(line)
            oldLine = hunk?.oldStart
            newLine = hunk?.newStart
            inHunk = true
            lines.add(AgentDiffLine(kind = AgentDiffLineKind.header, content = line, prefix = ""))
            return
        }
        if (!inHunk) {
            if (line.trim().isNotEmpty()) {
                ensureFile()
                addMeta(line)
            }
            return
        }

        if (line.startsWith("+")) {
            ensureFile()
            lines.add(
                AgentDiffLine(
                    kind = AgentDiffLineKind.add,
                    content = line.substring(1),
                    prefix = "+",
                    newLineNumber = newLine,
                ),
            )
            newLine = (newLine ?: 0) + 1
            additions += 1
            return
        }
        if (line.startsWith("-")) {
            ensureFile()
            lines.add(
                AgentDiffLine(
                    kind = AgentDiffLineKind.remove,
                    content = line.substring(1),
                    prefix = "-",
                    oldLineNumber = oldLine,
                ),
            )
            oldLine = (oldLine ?: 0) + 1
            deletions += 1
            return
        }
        if (line.startsWith("\\ No newline")) {
            lines.add(AgentDiffLine(kind = AgentDiffLineKind.header, content = line, prefix = ""))
            return
        }

        val content = if (line.startsWith(" ")) line.substring(1) else line
        lines.add(
            AgentDiffLine(
                kind = AgentDiffLineKind.context,
                content = content,
                prefix = " ",
                oldLineNumber = oldLine,
                newLineNumber = newLine,
            ),
        )
        oldLine = (oldLine ?: 0) + 1
        newLine = (newLine ?: 0) + 1
    }

    private fun startFileFromDiffGit(line: String) {
        val match = DIFF_GIT_REGEX.find(line)
        oldPath = if (match == null) null else cleanDiffPath(match.groupValues[1])
        newPath = if (match == null) null else cleanDiffPath(match.groupValues[2])
        displayPath = chooseDisplayPath()
    }

    private fun ensureFile() {
        if (displayPath == null) {
            displayPath = chooseDisplayPath()
        }
    }

    private fun addMeta(line: String) {
        lines.add(AgentDiffLine(kind = AgentDiffLineKind.meta, content = line, prefix = ""))
    }

    private fun finishFile() {
        if (displayPath == null && lines.isEmpty()) {
            return
        }
        val oldPath = this.oldPath
        val newPath = this.newPath
        val displayPath = this.displayPath ?: chooseDisplayPath() ?: "Changes"
        files.add(
            AgentDiffFile(
                oldPath = oldPath,
                newPath = newPath,
                displayPath = displayPath,
                lines = lines.toList(),
                additions = additions,
                deletions = deletions,
                isNewFile = oldPath == null || oldPath == "/dev/null",
                isDeletedFile = newPath == null || newPath == "/dev/null",
            ),
        )
        this.oldPath = null
        this.newPath = null
        this.displayPath = null
        oldLine = null
        newLine = null
        inHunk = false
        additions = 0
        deletions = 0
        lines.clear()
    }

    private fun chooseDisplayPath(): String? {
        val next = pathOrNull(newPath)
        if (next != null) {
            return next
        }
        return pathOrNull(oldPath)
    }

    private fun pathOrNull(value: String?): String? {
        if (value == null || value == "/dev/null") {
            return null
        }
        return value
    }
}

private data class HunkHeader(val oldStart: Int, val newStart: Int)

private fun parseHunkHeader(line: String): HunkHeader? {
    val match = HUNK_HEADER_REGEX.find(line) ?: return null
    return HunkHeader(
        oldStart = match.groupValues[1].toIntOrNull() ?: 0,
        newStart = match.groupValues[2].toIntOrNull() ?: 0,
    )
}

private fun cleanDiffPath(raw: String): String {
    var path = raw.trim()
    if (path.startsWith("\"") && path.endsWith("\"") && path.length >= 2) {
        path = path.substring(1, path.length - 1)
    }
    val tabIndex = path.indexOf('\t')
    if (tabIndex >= 0) {
        path = path.substring(0, tabIndex)
    }
    if (path == "/dev/null") {
        return path
    }
    if (path.startsWith("a/") || path.startsWith("b/")) {
        return path.substring(2)
    }
    return path
}
