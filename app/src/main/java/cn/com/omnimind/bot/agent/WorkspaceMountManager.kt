package cn.com.omnimind.bot.agent

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Workspace mount entries are symlinks under the workspace root. This is the
 * native owner for the format the Flutter `WorkspaceMountService` writes; both
 * sides operate on the same symlinks, so no migration or second store exists.
 */
internal class WorkspaceMountManager(context: Context) {
    private val appContext = context.applicationContext

    private fun rootDirectory(): File = AgentWorkspaceManager.rootDirectory(appContext)

    fun listMounts(): List<WorkspaceMountEntry> {
        val root = rootDirectory()
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty().mapNotNull(::describe).sortedBy { it.alias.lowercase() }
    }

    fun validateAlias(alias: String): AliasValidation {
        val trimmed = alias.trim()
        return when {
            trimmed.isEmpty() -> AliasValidation.EMPTY
            trimmed == "." || trimmed == ".." -> AliasValidation.DOT
            trimmed.contains('/') -> AliasValidation.SLASH
            trimmed.contains('\\') -> AliasValidation.BACKSLASH
            trimmed.contains('\u0000') -> AliasValidation.ILLEGAL
            trimmed == ".omnibot" || trimmed.startsWith(".omnibot/") -> AliasValidation.INTERNAL
            else -> AliasValidation.OK
        }
    }

    fun suggestUniqueAlias(sourcePath: String): String {
        val base = sourcePath.trim().trimEnd('/').substringAfterLast('/')
            .ifEmpty { "mount" }
        var candidate = base
        var index = 2
        while (File(rootDirectory(), candidate).let { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it.toPath()) }) {
            candidate = "$base-$index"
            index += 1
        }
        return candidate
    }

    fun mount(sourcePath: String, alias: String): WorkspaceMountEntry {
        val normalizedAlias = alias.trim()
        require(validateAlias(normalizedAlias) == AliasValidation.OK) { "Invalid mount alias" }

        val sourceDirectory = File(normalizePath(sourcePath))
        require(sourceDirectory.exists()) { "目录不存在：${sourceDirectory.path}" }
        require(sourceDirectory.isDirectory) { "仅支持挂载文件夹：${sourceDirectory.path}" }

        val canonicalRoot = rootDirectory().canonicalFile
        val canonicalSource = sourceDirectory.canonicalFile
        require(canonicalSource != canonicalRoot &&
            !canonicalSource.path.startsWith(canonicalRoot.path + "/")) {
            "不能把 workspace 目录自身再次挂载到 /workspace。"
        }
        require(!canonicalRoot.path.startsWith(canonicalSource.path + "/")) {
            "不能把 workspace 的父级目录直接挂载进 /workspace。"
        }

        val linkFile = File(canonicalRoot, normalizedAlias)
        if (Files.exists(linkFile.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(linkFile.toPath())) {
            val existing = describe(linkFile)
            if (existing != null && existing.sourcePath == canonicalSource.path) return existing
            throw IllegalArgumentException("/workspace/$normalizedAlias 已存在，请更换挂载名称。")
        }
        Files.createSymbolicLink(linkFile.toPath(), canonicalSource.toPath())
        return describe(linkFile) ?: WorkspaceMountEntry(
            alias = normalizedAlias,
            linkPath = linkFile.path,
            sourcePath = canonicalSource.path,
            shellPath = "/workspace/$normalizedAlias",
            sourceExists = true,
            sourceIsDirectory = true,
        )
    }

    fun unmount(linkPath: String) {
        val file = File(normalizePath(linkPath))
        require(Files.isSymbolicLink(file.toPath())) { "挂载入口不存在：$linkPath" }
        Files.delete(file.toPath())
    }

    private fun describe(file: File): WorkspaceMountEntry? {
        if (!Files.isSymbolicLink(file.toPath())) return null
        if (file.parentFile?.let { normalizePath(it.path) } != normalizePath(rootDirectory().path)) return null
        val rawTarget = runCatching { Files.readSymbolicLink(file.toPath()).toString() }.getOrNull()
            ?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val resolvedTarget = if (rawTarget.startsWith("/")) {
            normalizePath(rawTarget)
        } else {
            normalizePath("${file.parentFile?.path}/$rawTarget")
        }
        val target = File(resolvedTarget)
        val alias = file.name
        return WorkspaceMountEntry(
            alias = alias,
            linkPath = normalizePath(file.path),
            sourcePath = resolvedTarget,
            shellPath = "/workspace/$alias",
            sourceExists = target.exists(),
            sourceIsDirectory = target.isDirectory,
        )
    }

    private fun normalizePath(path: String): String =
        path.trim().let { if (it.length > 1) it.trimEnd('/') else it }

    enum class AliasValidation { OK, EMPTY, DOT, SLASH, BACKSLASH, ILLEGAL, INTERNAL }
}

internal data class WorkspaceMountEntry(
    val alias: String,
    val linkPath: String,
    val sourcePath: String,
    val shellPath: String,
    val sourceExists: Boolean,
    val sourceIsDirectory: Boolean,
) {
    val broken: Boolean get() = !sourceExists || !sourceIsDirectory
}
