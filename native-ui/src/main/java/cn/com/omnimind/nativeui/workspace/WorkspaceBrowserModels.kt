package cn.com.omnimind.nativeui.workspace

import androidx.compose.runtime.Immutable

/*
 * Pure workspace-browser rules (batch 5e-8a), ported from
 * `omnibot_workspace/widgets/omnibot_workspace_browser.dart` and the preview
 * kinds of `omnibot_resource_service.dart`. Paths are plain absolute strings;
 * the app repository owns every filesystem call.
 */

/** Dart `_normalizePath`: drops one trailing slash, keeps "/". */
fun normalizeWorkspacePath(path: String): String =
    if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

fun isSelfOrDescendant(path: String, ancestor: String): Boolean {
    val p = normalizeWorkspacePath(path)
    val a = normalizeWorkspacePath(ancestor)
    return p == a || p.startsWith("$a/")
}

fun workspaceEntryName(path: String): String {
    val normalized = normalizeWorkspacePath(path)
    val slash = normalized.lastIndexOf('/')
    return if (slash < 0 || slash == normalized.length - 1) normalized else normalized.substring(slash + 1)
}

fun workspaceParentPath(path: String): String {
    val normalized = normalizeWorkspacePath(path)
    val slash = normalized.lastIndexOf('/')
    return if (slash <= 0) "/" else normalized.substring(0, slash)
}

@Immutable
data class WorkspaceBreadcrumb(val label: String, val path: String, val current: Boolean, val file: Boolean = false)

/**
 * Dart `_workspaceBreadcrumbs`: the root (labelled with its shell path), then
 * one chip per segment down to [targetPath]; a target outside the root is a
 * single current chip.
 */
fun workspaceBreadcrumbs(
    rootPath: String,
    rootLabel: String,
    targetPath: String,
    targetIsFile: Boolean,
    targetLabel: String = targetPath,
): List<WorkspaceBreadcrumb> {
    val root = normalizeWorkspacePath(rootPath)
    val target = normalizeWorkspacePath(targetPath)
    if (!isSelfOrDescendant(target, root)) {
        return listOf(WorkspaceBreadcrumb(targetLabel, target, current = true, file = targetIsFile))
    }
    val segments = mutableListOf(
        WorkspaceBreadcrumb(rootLabel, root, current = target == root && !targetIsFile),
    )
    if (target == root && !targetIsFile) return segments
    var running = root
    target.removePrefix(root).split('/').filter { it.isNotBlank() }.forEach { part ->
        running = if (running == "/") "/$part" else "$running/$part"
        val current = running == target
        segments += WorkspaceBreadcrumb(part, running, current, file = current && targetIsFile)
    }
    return segments
}

/**
 * Bulk selection as include/exclude directives (Dart `_selectedEntryPaths` /
 * `_excludedEntryPaths`): selecting a folder selects everything below it,
 * and a child can then be excluded. The nearest directive on the path wins.
 */
@Immutable
data class WorkspaceSelection(val selected: Set<String> = emptySet(), val excluded: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = selected.isEmpty()

    /** Directives from [scopeRoot] down to [path], outermost first. */
    private fun trail(path: String, scopeRoot: String, includeSelf: Boolean): List<String> {
        val target = normalizeWorkspacePath(path)
        val scope = normalizeWorkspacePath(scopeRoot)
        if (!isSelfOrDescendant(target, scope)) return emptyList()
        val chain = mutableListOf<String>()
        var cursor = target
        while (true) {
            chain += cursor
            if (cursor == scope) break
            val parent = workspaceParentPath(cursor)
            if (!isSelfOrDescendant(parent, scope)) break
            cursor = parent
        }
        return chain.asReversed().filter { includeSelf || it != target }
    }

    private fun resolve(candidates: List<String>): Boolean {
        var on = false
        for (candidate in candidates) {
            if (candidate in selected) on = true
            if (candidate in excluded) on = false
        }
        return on
    }

    fun isSelected(path: String, scopeRoot: String): Boolean = resolve(trail(path, scopeRoot, includeSelf = true))

    private fun selectedByAncestor(path: String, scopeRoot: String): Boolean =
        resolve(trail(path, scopeRoot, includeSelf = false))

    private fun withoutDirectivesWithin(path: String) = WorkspaceSelection(
        selected.filterNot { isSelfOrDescendant(it, path) }.toSet(),
        excluded.filterNot { isSelfOrDescendant(it, path) }.toSet(),
    )

    fun select(path: String): WorkspaceSelection {
        val normalized = normalizeWorkspacePath(path)
        val cleared = withoutDirectivesWithin(normalized)
        return cleared.copy(selected = cleared.selected + normalized)
    }

    fun deselect(path: String, scopeRoot: String): WorkspaceSelection {
        val normalized = normalizeWorkspacePath(path)
        val byAncestor = selectedByAncestor(normalized, scopeRoot)
        val cleared = withoutDirectivesWithin(normalized)
        return if (byAncestor) cleared.copy(excluded = cleared.excluded + normalized) else cleared
    }

    /** True when a directive other than [path] itself sits below it. */
    fun hasDirectiveBelow(path: String, inExcluded: Boolean): Boolean {
        val normalized = normalizeWorkspacePath(path)
        return (if (inExcluded) excluded else selected).any {
            normalizeWorkspacePath(it) != normalized && isSelfOrDescendant(it, normalized)
        }
    }

    /** Dart `_topLevelSelectedPathsForDelete`: selected paths not covered by another. */
    fun topLevelSelected(): List<String> {
        val collapsed = mutableListOf<String>()
        selected.map(::normalizeWorkspacePath).sortedBy { it.length }.forEach { path ->
            if (collapsed.none { isSelfOrDescendant(path, it) }) collapsed += path
        }
        return collapsed
    }
}

/** Why a typed file or folder name was refused (Dart `_validateEntryName`). */
enum class EntryNameError { Empty, Dot, Slash, Backslash, Illegal }

fun validateEntryName(name: String): EntryNameError? = when {
    name.isBlank() -> EntryNameError.Empty
    name == "." || name == ".." -> EntryNameError.Dot
    name.contains('/') -> EntryNameError.Slash
    name.contains('\\') -> EntryNameError.Backslash
    name.contains('\u0000') -> EntryNameError.Illegal
    else -> null
}

/** Why a move was refused (Dart `_handleDropMove`). */
enum class MoveError { OutsideWorkspace, IntoSelf, AlreadyThere, IntoDescendant, MountRoot, NameTaken }

/**
 * Dart `_canMovePayloadToDirectory` without the filesystem: [destinationExists]
 * reports whether `target/name` is taken.
 */
fun workspaceMoveError(
    sourcePath: String,
    targetDirectory: String,
    rootPath: String,
    sourceIsDirectory: Boolean,
    sourceIsMountRoot: Boolean,
    destinationExists: Boolean,
): MoveError? {
    val source = normalizeWorkspacePath(sourcePath)
    val target = normalizeWorkspacePath(targetDirectory)
    return when {
        sourceIsMountRoot -> MoveError.MountRoot
        !isSelfOrDescendant(source, rootPath) || !isSelfOrDescendant(target, rootPath) -> MoveError.OutsideWorkspace
        source == target -> MoveError.IntoSelf
        workspaceParentPath(source) == target -> MoveError.AlreadyThere
        sourceIsDirectory && target.startsWith("$source/") -> MoveError.IntoDescendant
        destinationExists -> MoveError.NameTaken
        else -> null
    }
}

/** Dart `previewKind`, grouped by how the native page shows a file. */
enum class WorkspaceFileKind { Image, Text, Code, Html, Pdf, Audio, Video, Office, Other }

private fun String.endsWithAny(vararg suffixes: String) = suffixes.any { endsWith(it) }

/** Dart `OmnibotResourceService._guessPreviewKind`. */
fun workspaceFileKind(path: String): WorkspaceFileKind {
    val lower = path.lowercase()
    return when {
        lower.endsWithAny(".png", ".jpg", ".jpeg", ".gif", ".webp") -> WorkspaceFileKind.Image
        lower.endsWithAny(".docx", ".docm", ".xlsx", ".xlsm", ".pptx", ".pptm") -> WorkspaceFileKind.Office
        lower.endsWithAny(".html", ".htm") -> WorkspaceFileKind.Html
        lower.endsWith(".pdf") -> WorkspaceFileKind.Pdf
        lower.endsWithAny(".mp3", ".m4a", ".wav") -> WorkspaceFileKind.Audio
        lower.endsWithAny(".mp4", ".mov") -> WorkspaceFileKind.Video
        lower.endsWithAny(".json", ".jsonl", ".yaml", ".yml", ".xml") -> WorkspaceFileKind.Code
        lower.endsWithAny(".md", ".txt", ".log", ".kt", ".java", ".py", ".js", ".ts", ".css", ".sh", ".csv") ->
            WorkspaceFileKind.Text
        else -> WorkspaceFileKind.Other
    }
}

/** Dart `OmnibotResourceService._guessMimeType`. */
fun workspaceMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".md") -> "text/markdown"
        lower.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        lower.endsWith(".docm") -> "application/vnd.ms-word.document.macroEnabled.12"
        lower.endsWith(".xlsx") -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        lower.endsWith(".xlsm") -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        lower.endsWith(".pptx") -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        lower.endsWith(".pptm") -> "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".jsonl") -> "application/x-ndjson"
        lower.endsWithAny(".yaml", ".yml") -> "application/yaml"
        lower.endsWith(".xml") -> "application/xml"
        lower.endsWith(".csv") -> "text/csv"
        lower.endsWithAny(".html", ".htm") -> "text/html"
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".png") -> "image/png"
        lower.endsWithAny(".jpg", ".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".mp4") -> "video/mp4"
        lower.endsWith(".mov") -> "video/quicktime"
        workspaceFileKind(path).let { it == WorkspaceFileKind.Text || it == WorkspaceFileKind.Code } -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** Text and code files open in the editor (Dart `_canEditEntry`). */
val WorkspaceFileKind.editable: Boolean get() = this == WorkspaceFileKind.Text || this == WorkspaceFileKind.Code

/** Dart `_preferMonospace`. */
fun workspacePrefersMonospace(path: String): Boolean =
    workspaceFileKind(path) == WorkspaceFileKind.Code
