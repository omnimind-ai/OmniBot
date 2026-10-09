package cn.com.omnimind.bot.ui.workspace

import android.content.Context
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import java.net.URLDecoder

/**
 * `omnibot://` and `/workspace` path mapping for the native resource pages
 * (batch 5e-8a), ported from `OmnibotResourceService.resolveUriToPath`,
 * `resolveUriToShellPath`, `shellPathForAndroidPath` and
 * `androidPathForShellPath`. Pure over the three roots so it runs in JVM tests.
 */
internal class WorkspaceResourcePaths(
    val rootPath: String,
    val internalRootPath: String,
    val shellRootPath: String = AgentWorkspaceManager.SHELL_ROOT_PATH,
) {
    private data class ParsedUri(val authority: String, val segments: List<String>, val absolutePath: String?)

    private fun parse(uri: String): ParsedUri? {
        if (!uri.startsWith(SCHEME)) return null
        val rest = uri.removePrefix(SCHEME).substringBefore('?').substringBefore('#')
        val authority = rest.substringBefore('/').lowercase()
        val path = if ('/' in rest) rest.substring(rest.indexOf('/')) else ""
        val segments = path.split('/').filter { it.isNotEmpty() }.map(::decode)
        val absolute = if (authority.isEmpty()) {
            path.trim().takeIf { it.isNotEmpty() }?.let { decodePath(if (it.startsWith('/')) it else "/$it") }
        } else null
        return ParsedUri(authority, segments.filter { it != ".." }, absolute)
    }

    fun resolveUriToPath(uri: String): String? {
        val parsed = parse(uri) ?: return null
        if (parsed.authority.isEmpty()) {
            val absolute = parsed.absolutePath ?: return null
            return androidPathForShellPath(absolute) ?: absolute.takeIf(::isPublicPath)
        }
        val base = when (parsed.authority) {
            "attachments" -> "$internalRootPath/attachments"
            "workspace" -> rootPath
            "public", "storage" -> "/storage"
            "sdcard" -> "/sdcard"
            "shared" -> "$internalRootPath/shared"
            "offloads" -> "$internalRootPath/offloads"
            "browser" -> "$internalRootPath/browser"
            "skills" -> "$internalRootPath/skills"
            "memory" -> "$internalRootPath/memory"
            else -> return null
        }
        return if (parsed.segments.isEmpty()) base else "$base/${parsed.segments.joinToString("/")}"
    }

    fun shellPathForAndroidPath(path: String): String? = when {
        isPublicPath(path) -> path
        path == rootPath -> shellRootPath
        path.startsWith("$rootPath/") -> "$shellRootPath/${path.substring(rootPath.length + 1)}"
        path == internalRootPath -> "$shellRootPath/.omnibot"
        path.startsWith("$internalRootPath/") -> "$shellRootPath/.omnibot/${path.substring(internalRootPath.length + 1)}"
        else -> null
    }

    fun androidPathForShellPath(shellPath: String): String? {
        val normalized = shellPath.trim()
        val internalShell = "$shellRootPath/.omnibot"
        return when {
            normalized.isEmpty() -> null
            isPublicPath(normalized) -> normalized
            normalized == internalShell -> internalRootPath
            normalized.startsWith("$internalShell/") -> "$internalRootPath/${normalized.substring(internalShell.length + 1)}"
            normalized == shellRootPath -> rootPath
            normalized.startsWith("$shellRootPath/") -> "$rootPath/${normalized.substring(shellRootPath.length + 1)}"
            else -> null
        }
    }

    fun isPublicPath(path: String): Boolean {
        val normalized = path.trim()
        return PUBLIC_PREFIXES.any { normalized == it || normalized.startsWith("$it/") }
    }

    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)

    private fun decodePath(path: String): String = path.split('/').joinToString("/") { decode(it) }

    companion object {
        private const val SCHEME = "omnibot://"
        private val PUBLIC_PREFIXES = listOf("/storage", "/sdcard")

        fun from(context: Context) = WorkspaceResourcePaths(
            AgentWorkspaceManager.androidRootPath(context),
            AgentWorkspaceManager.internalRootPath(context),
        )
    }
}
