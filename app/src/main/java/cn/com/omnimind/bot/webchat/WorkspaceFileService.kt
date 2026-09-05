package cn.com.omnimind.bot.webchat

import android.content.Context
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import java.io.File

class WorkspaceFileService(
    context: Context
) {
    private val workspaceManager = AgentWorkspaceManager(context)

    fun getWorkspaceDescriptor(
        conversationId: Long? = null,
        agentRunId: String = "webchat"
    ): AgentWorkspaceDescriptor {
        return workspaceManager.buildWorkspaceDescriptor(
            conversationId = conversationId,
            agentRunId = agentRunId
        )
    }

    fun bootstrapPayload(): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        return linkedMapOf(
            "workspace" to workspace.toPayload(),
            "root" to filePayload(File(workspace.androidRootPath), workspace)
        )
    }

    fun list(
        path: String?,
        recursive: Boolean = false,
        maxDepth: Int? = null,
        limit: Int? = null
    ): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        val directory = if (path.isNullOrBlank()) {
            File(workspace.androidRootPath)
        } else {
            workspaceManager.resolvePath(
                inputPath = path,
                workspace = workspace
            )
        }
        require(directory.exists() && directory.isDirectory) {
            "目录不存在：${directory.absolutePath}"
        }
        val items = selectWorkspaceFiles(
            directory = directory,
            recursive = recursive,
            maxDepth = maxDepth,
            limit = limit
        )
        return linkedMapOf(
            "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
            "androidPath" to directory.absolutePath,
            "items" to items.map { filePayload(it, workspace) }
        )
    }

    fun stat(path: String): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        val file = workspaceManager.resolvePath(
            inputPath = path,
            workspace = workspace,
            allowRootDirectories = true
        )
        require(file.exists()) { "路径不存在：${file.absolutePath}" }
        return filePayload(file, workspace)
    }

    fun readFile(
        path: String,
        maxChars: Int? = null,
        offset: Int = 0,
        lineStart: Int? = null,
        lineCount: Int? = null
    ): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        val file = workspaceManager.resolvePath(
            inputPath = path,
            workspace = workspace
        )
        require(file.exists() && file.isFile) { "文件不存在：${file.absolutePath}" }
        val content = file.readText()
        val renderedContent = sliceWorkspaceFileText(
            content = content,
            maxChars = maxChars,
            offset = offset,
            lineStart = lineStart,
            lineCount = lineCount
        )
        return linkedMapOf(
            "file" to filePayload(file, workspace),
            "content" to renderedContent.content,
            "truncated" to renderedContent.truncated
        )
    }

    fun writeFile(
        path: String,
        content: String,
        append: Boolean = false
    ): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        val file = workspaceManager.resolvePath(
            inputPath = path,
            workspace = workspace
        )
        file.parentFile?.mkdirs()
        if (append) {
            file.appendText(content)
        } else {
            file.writeText(content)
        }
        publishWorkspaceChanged("write", file, workspace)
        return filePayload(file, workspace)
    }

    fun move(
        sourcePath: String,
        targetPath: String,
        overwrite: Boolean = false
    ): Map<String, Any?> {
        val workspace = getWorkspaceDescriptor()
        val source = workspaceManager.resolvePath(
            inputPath = sourcePath,
            workspace = workspace
        )
        val target = workspaceManager.resolvePath(
            inputPath = targetPath,
            workspace = workspace
        )
        require(source.exists()) { "源文件不存在：${source.absolutePath}" }
        require(overwrite || !target.exists()) { "目标已存在：${target.absolutePath}" }
        target.parentFile?.mkdirs()
        if (overwrite && target.exists()) {
            target.deleteRecursively()
        }
        source.copyRecursively(target, overwrite = overwrite)
        source.deleteRecursively()
        publishWorkspaceChanged("move", target, workspace)
        return linkedMapOf(
            "source" to filePayload(source, workspace),
            "target" to filePayload(target, workspace)
        )
    }

    fun delete(
        path: String,
        recursive: Boolean = false
    ) {
        val workspace = getWorkspaceDescriptor()
        val file = workspaceManager.resolvePath(
            inputPath = path,
            workspace = workspace
        )
        require(file.exists()) { "路径不存在：${file.absolutePath}" }
        if (file.isDirectory) {
            require(recursive) { "删除目录需要 recursive=true" }
            file.deleteRecursively()
        } else {
            require(file.delete()) { "删除失败：${file.absolutePath}" }
        }
        publishWorkspaceChanged("delete", file, workspace)
    }

    fun resolveDownloadFile(path: String): Pair<File, String> {
        val workspace = getWorkspaceDescriptor()
        val file = workspaceManager.resolvePath(
            inputPath = path,
            workspace = workspace
        )
        require(file.exists() && file.isFile) { "文件不存在：${file.absolutePath}" }
        return file to workspaceManager.guessMimeType(file)
    }

    private fun publishWorkspaceChanged(
        action: String,
        file: File,
        workspace: AgentWorkspaceDescriptor
    ) {
        val payload = linkedMapOf<String, Any?>(
            "action" to action,
            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
            "workspaceId" to workspace.id
        )
        RealtimeHub.publish("workspace_changed", payload)
    }

    private fun filePayload(
        file: File,
        workspace: AgentWorkspaceDescriptor
    ): Map<String, Any?> {
        val canonical = file.canonicalFile
        val displayName = file.name.ifBlank { canonical.name.ifBlank { "/" } }
        val mimeType = if (canonical.isFile) {
            workspaceManager.guessMimeType(canonical)
        } else {
            "inode/directory"
        }
        return linkedMapOf(
            "name" to displayName,
            "path" to (workspaceManager.shellPathForAndroid(canonical) ?: canonical.absolutePath),
            "androidPath" to canonical.absolutePath,
            "uri" to workspaceManager.uriForFile(canonical),
            "isDirectory" to canonical.isDirectory,
            "isFile" to canonical.isFile,
            "size" to if (canonical.isFile) canonical.length() else 0L,
            "lastModified" to canonical.lastModified(),
            "mimeType" to mimeType,
            "previewKind" to workspaceManager.previewKindForMime(mimeType),
            "workspaceId" to workspace.id
        )
    }

    private fun AgentWorkspaceDescriptor.toPayload(): Map<String, Any?> {
        return mapOf(
            "id" to id,
            "rootPath" to rootPath,
            "androidRootPath" to androidRootPath,
            "uriRoot" to uriRoot,
            "currentCwd" to currentCwd,
            "androidCurrentCwd" to androidCurrentCwd,
            "shellRootPath" to shellRootPath,
            "retentionPolicy" to retentionPolicy
        )
    }
}

/**
 * WebChat is a workspace viewer, not a second policy layer over the Agent
 * filesystem. Limits are opt-in request parameters; omitting them returns the
 * complete requested tree.
 */
internal fun selectWorkspaceFiles(
    directory: File,
    recursive: Boolean,
    maxDepth: Int?,
    limit: Int?,
): List<File> {
    val requestedDepth = maxDepth?.takeIf { it > 0 }
    val requestedLimit = limit?.takeIf { it > 0 }
    return if (recursive) {
        val walk = requestedDepth?.let { directory.walkTopDown().maxDepth(it) }
            ?: directory.walkTopDown()
        val files = walk.drop(1)
        (requestedLimit?.let(files::take) ?: files).toList()
    } else {
        val files = directory.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        requestedLimit?.let { files.take(it) } ?: files
    }
}

internal data class WorkspaceFileTextSlice(
    val content: String,
    val truncated: Boolean,
)

internal fun sliceWorkspaceFileText(
    content: String,
    maxChars: Int?,
    offset: Int,
    lineStart: Int?,
    lineCount: Int?,
): WorkspaceFileTextSlice {
    val sliced = when {
        lineStart != null -> {
            val lines = content.lines()
            val from = (lineStart - 1).coerceAtLeast(0).coerceAtMost(lines.size)
            val until = if (lineCount != null) {
                (from + lineCount.coerceAtLeast(1)).coerceAtMost(lines.size)
            } else {
                lines.size
            }
            lines.subList(from, until).joinToString("\n")
        }
        offset > 0 -> content.drop(offset.coerceAtLeast(0))
        else -> content
    }
    val rendered = maxChars
        ?.takeIf { it > 0 }
        ?.let { sliced.take(it) }
        ?: sliced
    return WorkspaceFileTextSlice(
        content = rendered,
        truncated = rendered.length < sliced.length
    )
}
