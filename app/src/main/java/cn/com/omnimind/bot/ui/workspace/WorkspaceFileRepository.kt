package cn.com.omnimind.bot.ui.workspace

import cn.com.omnimind.nativeui.workspace.WorkspaceEntryUi
import cn.com.omnimind.nativeui.workspace.WorkspaceSelection
import cn.com.omnimind.nativeui.workspace.isSelfOrDescendant
import cn.com.omnimind.nativeui.workspace.normalizeWorkspacePath
import cn.com.omnimind.nativeui.workspace.workspaceEntryName
import cn.com.omnimind.nativeui.workspace.workspaceParentPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem operations of the native workspace browser (batch 5e-8a), the
 * native owner of what `omnibot_workspace_browser.dart` did with `dart:io`.
 * Pure `java.nio` over [rootPath], so it runs in JVM tests; callers run it on
 * an IO dispatcher.
 *
 * Symlinks are never followed when deleting: a mount (a symlink at the root)
 * is unmounted by removing the link, and a link deeper down is removed as a
 * link. Following them would delete the user's host files.
 */
internal class WorkspaceFileRepository(rootPath: String) {
    val rootPath: String = normalizeWorkspacePath(rootPath)

    data class Listing(val exists: Boolean, val entries: List<WorkspaceEntryUi>)

    data class TextContent(val text: String, val truncated: Boolean)

    private fun path(value: String): Path = File(value).toPath()

    private fun isLink(value: String) = Files.isSymbolicLink(path(value))

    private fun existsNoFollow(value: String) = Files.exists(path(value), LinkOption.NOFOLLOW_LINKS)

    fun isInside(value: String) = isSelfOrDescendant(value, rootPath)

    /** A mount is a symlink directly under the root (Dart `WorkspaceMountService.describeMountEntry`). */
    fun isMountRoot(value: String): Boolean =
        workspaceParentPath(value) == rootPath && isLink(value)

    /** Dart `_readSortedEntries`: folders (links to folders included) first, then case-insensitive by path. */
    fun list(directory: String): Listing {
        val dir = File(directory)
        if (!dir.isDirectory) return Listing(exists = false, entries = emptyList())
        val children = dir.listFiles() ?: throw IOException("Unable to read $directory")
        val entries = children.map { child ->
            val childPath = normalizeWorkspacePath(child.path)
            val mount = isMountRoot(childPath)
            WorkspaceEntryUi(
                path = childPath,
                name = child.name,
                // File.isDirectory follows links, matching the Dart directory-like set.
                directory = child.isDirectory,
                mount = mount,
                mountBroken = mount && !child.isDirectory,
            )
        }.sortedWith(compareBy<WorkspaceEntryUi>({ !it.directory }, { it.path.lowercase() }))
        return Listing(exists = true, entries = entries)
    }

    fun exists(value: String) = existsNoFollow(value)

    /**
     * Reads at most [limit] bytes as UTF-8. Malformed bytes become U+FFFD
     * instead of failing the whole preview, and a larger file reports
     * [TextContent.truncated] so it opens read-only.
     */
    fun readText(value: String, limit: Int): TextContent {
        val file = File(value)
        val out = ByteArrayOutputStream()
        var truncated = false
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val room = limit - out.size()
                if (read > room) {
                    out.write(buffer, 0, room)
                    truncated = true
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = decoder.decode(ByteBuffer.wrap(out.toByteArray())).toString()
        return TextContent(text, truncated)
    }

    /**
     * Writes through a sibling temp file and an atomic rename, so an
     * interrupted save leaves the previous content instead of a truncated file
     * (the Dart preview wrote in place). A symlinked file is written at its
     * target so the link survives.
     */
    fun writeText(value: String, text: String) {
        val target = path(value).let { if (Files.isSymbolicLink(it)) it.toRealPath() else it }
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temp, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    enum class RenameResult { Renamed, Missing, Unchanged, Taken, Outside }

    fun rename(value: String, newName: String): RenameResult {
        val source = normalizeWorkspacePath(value)
        if (!existsNoFollow(source)) return RenameResult.Missing
        if (newName == workspaceEntryName(source)) return RenameResult.Unchanged
        val destination = "${workspaceParentPath(source)}/$newName"
        if (!isInside(destination)) return RenameResult.Outside
        if (existsNoFollow(destination)) return RenameResult.Taken
        Files.move(path(source), path(destination))
        return RenameResult.Renamed
    }

    /** Dart `_handleDropMove`; the caller validated it with `workspaceMoveError`. */
    fun move(source: String, targetDirectory: String) {
        val destination = "${normalizeWorkspacePath(targetDirectory)}/${workspaceEntryName(source)}"
        Files.move(path(source), path(destination))
    }

    fun destinationTaken(source: String, targetDirectory: String): Boolean =
        existsNoFollow("${normalizeWorkspacePath(targetDirectory)}/${workspaceEntryName(source)}")

    fun isDirectory(value: String) = File(value).isDirectory

    /** Removes a file, an empty-or-not folder, or a link (never its target). */
    fun delete(value: String) = deleteTree(path(value))

    fun unmount(linkPath: String) {
        require(isMountRoot(linkPath)) { "Not a workspace mount: $linkPath" }
        Files.delete(path(linkPath))
    }

    private fun deleteTree(target: Path) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.newDirectoryStream(target).use { stream -> stream.forEach(::deleteTree) }
        }
        Files.delete(target)
    }

    data class BulkResult(val deleted: Int, val failed: Int)

    /**
     * Dart `_confirmAndDeleteSelectedEntries` / `_deleteSelectedPathRecursively`:
     * deletes the top-level selections, descending only where an exclusion or
     * a nested selection makes a folder partial. A fully selected mount is
     * unmounted; a partial one only loses its selected children.
     */
    fun deleteSelected(selection: WorkspaceSelection, scopeRoot: String): BulkResult {
        var deleted = 0
        var failed = 0
        selection.topLevelSelected().forEach { top ->
            if (!existsNoFollow(top)) return@forEach
            try {
                deleteSelectedRecursively(top, selection, scopeRoot)
                deleted += 1
            } catch (_: Exception) {
                failed += 1
            }
        }
        return BulkResult(deleted, failed)
    }

    private fun deleteSelectedRecursively(value: String, selection: WorkspaceSelection, scopeRoot: String) {
        val normalized = normalizeWorkspacePath(value)
        if (!existsNoFollow(normalized)) return
        val selected = selection.isSelected(normalized, scopeRoot)
        val selectedBelow = selection.hasDirectiveBelow(normalized, inExcluded = false)
        val excludedBelow = selection.hasDirectiveBelow(normalized, inExcluded = true)
        val mount = isMountRoot(normalized)
        if (!File(normalized).isDirectory || (isLink(normalized) && !mount)) {
            if (selected) Files.delete(path(normalized))
            return
        }
        if (mount) {
            if (selected && !excludedBelow) {
                Files.delete(path(normalized))
                return
            }
            if (!selectedBelow && !selected) return
        } else if (selected && !excludedBelow) {
            deleteTree(path(normalized))
            return
        }
        if (!selected && !selectedBelow) return
        File(normalized).listFiles().orEmpty().forEach {
            deleteSelectedRecursively(it.path, selection, scopeRoot)
        }
        if (!selected || mount) return
        if (File(normalized).list().isNullOrEmpty()) Files.delete(path(normalized))
    }
}
