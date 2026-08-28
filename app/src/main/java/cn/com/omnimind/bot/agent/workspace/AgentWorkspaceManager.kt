package cn.com.omnimind.bot.agent

import android.content.Context
import android.net.Uri
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.LocalizedText
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val defaultLongMemoryTemplate = LocalizedText(
    zhCN = """
        # MEMORY

        这是长期静态记忆区，用于存储跨会话稳定偏好与长期约束。

        ## 使用约定
        - 仅记录长期稳定且对后续任务有价值的信息。
        - 避免记录一次性临时细节。
        - 每条尽量一句话，必要时加日期来源。

        ## 长期记忆
    """.trimIndent(),
    enUS = """
        # MEMORY

        This is the long-term memory area for stable preferences and cross-session constraints.

        ## Usage Notes
        - Only record information that is stable over time and useful for future tasks.
        - Avoid one-off temporary details.
        - Keep each item to one sentence when possible, and add a date/source if needed.

        ## Long-Term Memory
    """.trimIndent()
)

internal fun defaultLongMemoryTemplateText(locale: PromptLocale): String {
    return defaultLongMemoryTemplate.resolve(locale) + "\n"
}

private fun syncManagedDefaultFile(
    file: File,
    targetText: String,
    managedDefaults: Set<String>
) {
    if (!file.exists()) {
        file.parentFile?.mkdirs()
        file.writeText(targetText)
        return
    }
    val current = runCatching { file.readText() }.getOrNull() ?: return
    if (current == targetText) {
        return
    }
    if (managedDefaults.contains(current)) {
        file.writeText(targetText)
    }
}

internal fun ensureDefaultLongMemoryFile(
    longMemoryFile: File,
    locale: PromptLocale
) {
    val memoryTarget = defaultLongMemoryTemplateText(locale)
    syncManagedDefaultFile(
        longMemoryFile,
        memoryTarget,
        PromptLocale.entries.map(::defaultLongMemoryTemplateText).toSet()
    )
}

class AgentWorkspaceManager(
    private val context: Context
) {
    companion object {
        const val SHELL_ROOT_PATH = "/workspace"
        const val URI_SCHEME = "omnibot"
        const val PUBLIC_STORAGE_ROOT_PATH = PublicStorageAccess.PUBLIC_STORAGE_ROOT_PATH

        const val LEGACY_EXTERNAL_ROOT_PATH = "/storage/emulated/0/workspace"

        private const val ROOT_DIR_NAME = "workspace"
        private const val INTERNAL_DIR = ".omnibot"
        private const val WORKSPACE_MIGRATION_MARKER = ".workspace_migrated_v1"
        private const val DIR_ATTACHMENTS = "attachments"
        private const val DIR_WORKSPACE = "workspace"
        private const val DIR_PUBLIC = "public"
        private const val PUBLIC_URI_PREFIX = "$URI_SCHEME://$DIR_PUBLIC"
        private const val DIR_SHARED = "shared"
        private const val DIR_OFFLOADS = "offloads"
        private const val DIR_BROWSER = "browser"
        private const val DIR_SKILLS = "skills"
        private const val DIR_MEMORY = "memory"
        private const val DIR_AUDIO = "audio"
        private const val DIR_PETS = "pets"
        private const val DIR_BUILTIN_PETS_ASSETS = "builtin_pets"
        private const val FILE_MEMORY = "MEMORY.md"
        private const val DIR_SHORT_MEMORIES = "short-memories"
        private const val DIR_MEMORY_INDEX = "index"

        fun rootDirectory(context: Context): File {
            return File(context.applicationInfo.dataDir, ROOT_DIR_NAME)
        }

        fun internalRootDirectory(context: Context): File {
            return File(rootDirectory(context), INTERNAL_DIR)
        }

        /** Voice synthesis cache directory under the managed workspace. */
        fun audioDirectory(context: Context): File {
            return File(internalRootDirectory(context), DIR_AUDIO)
        }

        fun androidRootPath(context: Context): String {
            return rootDirectory(context).absolutePath
        }

        fun internalRootPath(context: Context): String {
            return internalRootDirectory(context).absolutePath
        }

        fun workspacePathSnapshot(context: Context): Map<String, String> {
            AgentWorkspaceManager(context).ensureRuntimeDirectories()
            return linkedMapOf(
                "rootPath" to androidRootPath(context),
                "shellRootPath" to SHELL_ROOT_PATH,
                "internalRootPath" to internalRootPath(context)
            )
        }

        fun isPublicStoragePath(path: String): Boolean {
            val trimmed = path.trim()
            return trimmed == PUBLIC_STORAGE_ROOT_PATH ||
                trimmed.startsWith("$PUBLIC_STORAGE_ROOT_PATH/")
        }

        fun isPublicUri(uriText: String): Boolean {
            return storagePathForPublicUri(uriText) != null
        }

        fun publicUriForStoragePath(path: String): String? {
            val trimmed = path.trim()
            if (!isPublicStoragePath(trimmed)) {
                return null
            }
            val relativeSegments = trimmed
                .removePrefix(PUBLIC_STORAGE_ROOT_PATH)
                .trimStart('/')
                .split('/')
                .filter { it.isNotBlank() }
            return if (relativeSegments.isEmpty()) {
                PUBLIC_URI_PREFIX
            } else {
                "$PUBLIC_URI_PREFIX/${relativeSegments.joinToString("/")}"
            }
        }

        fun storagePathForPublicUri(uriText: String): String? {
            val trimmed = uriText.trim()
            if (trimmed == PUBLIC_URI_PREFIX || trimmed.startsWith("$PUBLIC_URI_PREFIX/")) {
                val segments = trimmed
                    .removePrefix(PUBLIC_URI_PREFIX)
                    .trimStart('/')
                    .split('/')
                    .filter { it.isNotBlank() && it != ".." }
                return if (segments.isEmpty()) {
                    PUBLIC_STORAGE_ROOT_PATH
                } else {
                    "$PUBLIC_STORAGE_ROOT_PATH/${segments.joinToString("/")}"
                }
            }
            if (!trimmed.startsWith("$URI_SCHEME://")) {
                return null
            }
            val absolutePath = absoluteOmnibotPath(trimmed) ?: return null
            return if (PublicStorageAccess.isPublicStoragePath(absolutePath)) {
                absolutePath
            } else {
                null
            }
        }

        private fun normalizeAbsoluteOmnibotPath(path: String): String {
            val trimmed = path.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        }

        private fun absoluteOmnibotPath(uriText: String): String? {
            val remainder = uriText.trim().removePrefix("$URI_SCHEME://")
            return when {
                remainder.startsWith('/') -> normalizeAbsoluteOmnibotPath(remainder)
                remainder == "storage" || remainder.startsWith("storage/") -> "/$remainder"
                remainder == "sdcard" || remainder.startsWith("sdcard/") -> "/$remainder"
                remainder == "workspace" || remainder.startsWith("workspace/") -> "/$remainder"
                else -> null
            }
        }
    }

    private val rootDir = rootDirectory(context)
    private val legacyInternalRootDir = File(context.applicationContext.filesDir, ROOT_DIR_NAME)
    private val internalDir = File(rootDir, INTERNAL_DIR)
    private val attachmentsDir = File(internalDir, DIR_ATTACHMENTS)
    private val sharedDir = File(internalDir, DIR_SHARED)
    private val offloadsDir = File(internalDir, DIR_OFFLOADS)
    private val browserDir = File(internalDir, DIR_BROWSER)
    private val skillsDir = File(internalDir, DIR_SKILLS)
    private val memoryDir = File(internalDir, DIR_MEMORY)
    private val petsDir = File(internalDir, DIR_PETS)
    private val longMemoryFile = File(memoryDir, FILE_MEMORY)
    private val shortMemoriesDir = File(memoryDir, DIR_SHORT_MEMORIES)
    private val memoryIndexDir = File(memoryDir, DIR_MEMORY_INDEX)
    private val migrationMarker = File(internalDir, WORKSPACE_MIGRATION_MARKER)
    private val legacyRootDir = File(LEGACY_EXTERNAL_ROOT_PATH)
    private val publicStorageRootDir = File(PUBLIC_STORAGE_ROOT_PATH)

    private data class WorkspaceMountLink(
        val alias: String,
        val linkFile: File,
        val sourceDir: File
    )

    fun ensureRuntimeDirectories() {
        migrateLegacyWorkspaceIfNeeded()
        listOf(
            rootDir,
            internalDir,
            attachmentsDir,
            sharedDir,
            offloadsDir,
            browserDir,
            skillsDir,
            memoryDir,
            audioDirectory(context),
            petsDir,
            shortMemoriesDir,
            memoryIndexDir
        ).forEach { directory ->
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
        ensureBuiltinPets()
        ensureDefaultLongMemoryFile()
    }

    private fun ensureBuiltinPets() {
        val assetManager = context.assets
        val petIds = runCatching {
            assetManager.list(DIR_BUILTIN_PETS_ASSETS)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        petIds
            .filter { it.isNotBlank() && !it.startsWith(".") }
            .forEach { petId ->
                val assetDir = "$DIR_BUILTIN_PETS_ASSETS/$petId"
                val assetFiles = runCatching {
                    assetManager.list(assetDir)?.toList().orEmpty()
                }.getOrDefault(emptyList())
                if (assetFiles.isEmpty()) return@forEach
                val targetDir = File(petsDir, sanitizeSegment(petId))
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                assetFiles
                    .filter(::isSupportedBuiltinPetAsset)
                    .forEach { fileName ->
                        val assetPath = "$assetDir/$fileName"
                        val nestedFiles = runCatching {
                            assetManager.list(assetPath)?.toList().orEmpty()
                        }.getOrDefault(emptyList())
                        if (nestedFiles.isNotEmpty()) return@forEach
                        val targetFile = File(targetDir, fileName)
                        runCatching {
                            assetManager.open(assetPath).use { input ->
                                if (targetFile.exists() &&
                                    targetFile.length() == input.available().toLong()
                                ) {
                                    return@use
                                }
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
            }
    }

    private fun isSupportedBuiltinPetAsset(fileName: String): Boolean {
        return fileName == "pet.json" ||
            fileName == "spritesheet.webp" ||
            fileName == "spritesheet.png" ||
            fileName == "current.svg" ||
            fileName == "current.png" ||
            fileName == "current.webp" ||
            fileName == "current.jpg" ||
            fileName == "current.gif"
    }

    private fun ensureDefaultLongMemoryFile() {
        ensureDefaultLongMemoryFile(
            longMemoryFile = longMemoryFile,
            locale = AppLocaleManager.resolvePromptLocale(context)
        )
    }

    private fun migrateLegacyWorkspaceIfNeeded() {
        if (migrationMarker.exists()) {
            return
        }
        runCatching {
            rootDir.mkdirs()
            val migrationSources = buildList {
                val internalLegacy = legacyInternalRootDir
                if (
                    internalLegacy.exists() &&
                    internalLegacy.canonicalPath != rootDir.canonicalPath
                ) {
                    add(internalLegacy)
                }
                val externalLegacy = legacyRootDir
                if (externalLegacy.exists()) {
                    add(externalLegacy)
                }
            }
            migrationSources.forEach { source ->
                source.listFiles()?.forEach { child ->
                    val target = File(rootDir, child.name)
                    if (!target.exists()) {
                        if (child.name == INTERNAL_DIR && child.isDirectory) {
                            target.mkdirs()
                            child.listFiles()
                                ?.filterNot { it.name == "models" }
                                ?.forEach { internalChild ->
                                    internalChild.copyRecursively(
                                        File(target, internalChild.name),
                                        overwrite = false
                                    )
                                }
                        } else {
                            child.copyRecursively(target, overwrite = false)
                        }
                    }
                }
            }
            markMigrationCompleted()
        }
    }

    private fun markMigrationCompleted() {
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        runCatching {
            migrationMarker.writeText("migrated=true\n")
        }
    }

    fun skillsRoot(): File {
        ensureRuntimeDirectories()
        return skillsDir
    }

    fun petsRoot(): File {
        ensureRuntimeDirectories()
        return petsDir
    }

    fun buildWorkspaceDescriptor(
        conversationId: Long?,
        agentRunId: String
    ): AgentWorkspaceDescriptor {
        ensureRuntimeDirectories()
        val conversationKey = conversationKey(conversationId)
        val workspaceRoot = rootDir.canonicalFile
        val uriRoot = uriForFile(workspaceRoot) ?: buildRootUri("workspace")
        return AgentWorkspaceDescriptor(
            id = conversationKey,
            rootPath = SHELL_ROOT_PATH,
            androidRootPath = workspaceRoot.absolutePath,
            uriRoot = uriRoot,
            currentCwd = SHELL_ROOT_PATH,
            androidCurrentCwd = workspaceRoot.absolutePath,
            shellRootPath = SHELL_ROOT_PATH,
            retentionPolicy = "shared_root"
        )
    }

    fun offloadsDirectory(agentRunId: String): File {
        ensureRuntimeDirectories()
        return File(offloadsDir, sanitizeSegment(agentRunId)).apply { mkdirs() }
    }

    fun browserDirectory(agentRunId: String): File {
        ensureRuntimeDirectories()
        return File(browserDir, sanitizeSegment(agentRunId)).apply { mkdirs() }
    }

    fun newOffloadFile(
        agentRunId: String,
        prefix: String,
        extension: String
    ): File {
        return newManagedFile(
            parent = offloadsDirectory(agentRunId),
            prefix = prefix,
            extension = extension
        )
    }

    fun newBrowserFile(
        agentRunId: String,
        prefix: String,
        extension: String
    ): File {
        return newManagedFile(
            parent = browserDirectory(agentRunId),
            prefix = prefix,
            extension = extension
        )
    }

    fun attachmentsDirectory(): File {
        ensureRuntimeDirectories()
        return attachmentsDir
    }

    fun sharedDirectory(): File {
        ensureRuntimeDirectories()
        return sharedDir
    }

    fun longTermMemoryMarkdownFile(): File {
        ensureRuntimeDirectories()
        return longMemoryFile
    }

    fun shortMemoriesDirectory(): File {
        ensureRuntimeDirectories()
        return shortMemoriesDir
    }

    fun memoryIndexDirectory(): File {
        ensureRuntimeDirectories()
        return memoryIndexDir
    }

    fun dailyShortMemoryFile(date: LocalDate): File {
        ensureRuntimeDirectories()
        val fileName = date.format(DateTimeFormatter.ofPattern("yy-MM-dd")) + ".md"
        return File(shortMemoriesDir, fileName)
    }

    fun writeOffload(
        agentRunId: String,
        extension: String,
        content: String
    ): ArtifactRef {
        val target = newOffloadFile(
            agentRunId = agentRunId,
            prefix = "offload",
            extension = extension
        )
        target.writeText(content)
        return buildArtifactForFile(
            file = target,
            sourceTool = "offload",
            title = target.name
        )
    }

    fun buildArtifactForFile(
        file: File,
        sourceTool: String,
        title: String = file.name,
        actions: List<ArtifactAction> = defaultActionsForFile(file)
    ): ArtifactRef {
        ensureRuntimeDirectories()
        val canonical = file.canonicalFile
        require(isWithinArtifactRoots(canonical)) { "File must stay inside supported roots" }
        if (canonical.parentFile?.exists() != true) {
            canonical.parentFile?.mkdirs()
        }
        val uri = uriForFile(canonical)
            ?: throw IllegalArgumentException("Unsupported artifact location: ${canonical.absolutePath}")
        return ArtifactRef(
            id = stableIdForPath(canonical.absolutePath),
            uri = uri,
            title = title,
            mimeType = guessMimeType(canonical),
            size = canonical.length(),
            sourceTool = sourceTool,
            workspacePath = shellPathForAndroid(canonical) ?: canonical.absolutePath,
            androidPath = canonical.absolutePath,
            previewKind = previewKindForMime(guessMimeType(canonical)),
            actions = actions
        )
    }

    fun resolvePath(
        inputPath: String,
        workspace: AgentWorkspaceDescriptor,
        allowRootDirectories: Boolean = false,
        allowPublicStorage: Boolean = false
    ): File {
        ensureRuntimeDirectories()
        val trimmed = inputPath.trim()
        require(trimmed.isNotEmpty()) { "path 不能为空" }

        val resolved = when {
            trimmed.startsWith("$URI_SCHEME://") -> resolveUri(trimmed)
            trimmed.startsWith("$SHELL_ROOT_PATH/") || trimmed == SHELL_ROOT_PATH -> {
                androidPathForShell(trimmed)
                    ?: throw IllegalArgumentException("无法解析 shell 路径：$inputPath")
            }
            trimmed.startsWith("/") -> File(trimmed)
            else -> File(workspace.androidCurrentCwd, trimmed)
        }.canonicalFile

        val allowed = if (allowPublicStorage && isWithinPublicStorage(resolved)) {
            true
        } else if (allowRootDirectories) {
            isWithinWorkspaceRoots(resolved)
        } else {
            isWithinWritableRoots(resolved, workspace)
        }
        require(allowed) { "路径超出允许范围：$inputPath" }
        return resolved
    }

    fun shellPathForAndroid(file: File): String? {
        val canonical = file.canonicalFile
        workspaceMountForFile(canonical)?.let { (mount, relative) ->
            return if (relative.isBlank()) {
                "$SHELL_ROOT_PATH/${mount.alias}"
            } else {
                "$SHELL_ROOT_PATH/${mount.alias}/$relative"
            }
        }
        if (isWithinPublicStorage(canonical)) {
            return canonical.absolutePath
        }
        if (!isWithinRoot(canonical)) return null
        val relative = canonical.absolutePath.removePrefix(rootDir.canonicalPath).trimStart('/')
        return if (relative.isBlank()) {
            SHELL_ROOT_PATH
        } else {
            "$SHELL_ROOT_PATH/$relative"
        }
    }

    fun androidPathForShell(shellPath: String): File? {
        val trimmed = shellPath.trim()
        if (isPublicStoragePath(trimmed)) {
            return File(trimmed)
        }
        if (!(trimmed == SHELL_ROOT_PATH || trimmed.startsWith("$SHELL_ROOT_PATH/"))) {
            return null
        }
        val relative = trimmed.removePrefix(SHELL_ROOT_PATH).trimStart('/')
        return if (relative.isBlank()) {
            rootDir
        } else {
            File(rootDir, relative)
        }
    }

    fun resolveShellPath(
        inputPath: String,
        workspace: AgentWorkspaceDescriptor,
        allowRootDirectories: Boolean = false,
        allowPublicStorage: Boolean = false
    ): String {
        val trimmed = inputPath.trim()
        require(trimmed.isNotEmpty()) { "path 不能为空" }
        val androidFile = resolvePath(
            trimmed,
            workspace,
            allowRootDirectories = allowRootDirectories,
            allowPublicStorage = allowPublicStorage
        )
        return shellPathForAndroid(androidFile)
            ?: throw IllegalArgumentException("无法映射 shell 路径：$inputPath")
    }

    private fun resolveUri(uriText: String): File {
        storagePathForPublicUri(uriText)?.let { publicPath ->
            return File(publicPath)
        }
        absoluteWorkspacePathForOmnibotUri(uriText)?.let { workspacePath ->
            return File(workspacePath)
        }
        val uri = Uri.parse(uriText)
        require(uri.scheme == URI_SCHEME) { "Unsupported uri scheme: ${uri.scheme}" }
        val authority = uri.authority.orEmpty()
        val base = when (authority) {
            DIR_ATTACHMENTS -> attachmentsDir
            DIR_WORKSPACE -> rootDir
            DIR_SHARED -> sharedDir
            DIR_OFFLOADS -> offloadsDir
            DIR_BROWSER -> browserDir
            DIR_SKILLS -> skillsDir
            DIR_MEMORY -> memoryDir
            DIR_PETS -> petsDir
            else -> throw IllegalArgumentException("未知 omnibot uri：$uriText")
        }
        var target = base
        uri.pathSegments.forEach { segment ->
            if (segment.isNotBlank()) {
                target = File(target, segment)
            }
        }
        return target
    }

    private fun isWithinWritableRoots(
        file: File,
        workspace: AgentWorkspaceDescriptor
    ): Boolean {
        val workspaceRoot = File(workspace.androidRootPath).canonicalFile
        return isWithin(workspaceRoot, file) ||
            isWithinMountedWorkspace(file) ||
            isWithin(attachmentsDir.canonicalFile, file) ||
            isWithin(sharedDir.canonicalFile, file) ||
            isWithin(offloadsDir.canonicalFile, file) ||
            isWithin(browserDir.canonicalFile, file) ||
            isWithin(skillsDir.canonicalFile, file) ||
            isWithin(memoryDir.canonicalFile, file) ||
            isWithin(petsDir.canonicalFile, file)
    }

    private fun isWithinArtifactRoots(file: File): Boolean {
        return isWithinRoot(file) || isWithinMountedWorkspace(file) || isWithinPublicStorage(file)
    }

    private fun isWithinRoot(file: File): Boolean {
        return isWithin(rootDir.canonicalFile, file)
    }

    private fun isWithinWorkspaceRoots(file: File): Boolean {
        return isWithinRoot(file) || isWithinMountedWorkspace(file)
    }

    private fun isWithinPublicStorage(file: File): Boolean {
        return isWithin(publicStorageRootDir.canonicalFile, file)
    }

    private fun isWithin(parent: File, file: File): Boolean {
        val parentPath = parent.canonicalPath
        val targetPath = file.canonicalPath
        return targetPath == parentPath || targetPath.startsWith("$parentPath/")
    }

    private fun relativePathFrom(base: File, file: File): String {
        return file.canonicalPath.removePrefix(base.canonicalPath).trimStart('/')
    }

    private fun buildUriForBase(authority: String, base: File, file: File): String {
        val relative = relativePathFrom(base, file)
        val builder = Uri.Builder().scheme(URI_SCHEME).authority(authority)
        if (relative.isNotBlank()) {
            relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
                builder.appendPath(segment)
            }
        }
        return builder.build().toString()
    }

    private fun buildRootUri(authority: String, vararg segments: String): String {
        val builder = Uri.Builder().scheme(URI_SCHEME).authority(authority)
        segments.filter { it.isNotBlank() }.forEach { builder.appendPath(it) }
        return builder.build().toString()
    }

    private fun conversationKey(conversationId: Long?): String {
        return if (conversationId == null) "conversation_default" else "conversation_$conversationId"
    }

    private fun sanitizeSegment(value: String): String {
        return value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun newManagedFile(
        parent: File,
        prefix: String,
        extension: String
    ): File {
        val normalizedPrefix = sanitizeSegment(prefix).ifBlank { "artifact" }
        val normalizedExt = extension.trim().removePrefix(".").ifBlank { "txt" }
        val fileName =
            "${normalizedPrefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$normalizedExt"
        return File(parent, fileName)
    }

    private fun stableIdForPath(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charset.forName("UTF-8")))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(16)
    }

    private fun defaultActionsForFile(file: File): List<ArtifactAction> {
        val uri = uriForFile(file).orEmpty()
        val path = file.absolutePath
        val shellPath = shellPathForAndroid(file).orEmpty()
        return listOf(
            ArtifactAction(
                type = "preview",
                label = "预览",
                target = uri,
                payload = mapOf("path" to path, "shellPath" to shellPath)
            ),
            ArtifactAction(
                type = "save",
                label = "保存到本地",
                target = uri,
                payload = mapOf("path" to path, "shellPath" to shellPath)
            )
        )
    }

    fun guessMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "md" -> "text/markdown"
            "txt", "log", "json", "jsonl", "csv", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts", "html", "htm", "css", "sh" -> {
                when (file.extension.lowercase()) {
                    "md" -> "text/markdown"
                    "json" -> "application/json"
                    "jsonl" -> "application/x-ndjson"
                    "csv" -> "text/csv"
                    "xml" -> "application/xml"
                    "yaml", "yml" -> "application/yaml"
                    "html", "htm" -> "text/html"
                    else -> "text/plain"
                }
            }
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "docm" -> "application/vnd.ms-word.document.macroEnabled.12"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "pptm" -> "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    fun previewKindForMime(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("text/") -> "text"
            mimeType == "application/json" ||
                mimeType == "application/xml" ||
                mimeType == "application/yaml" ||
                mimeType == "application/x-ndjson" -> "code"
            mimeType == "text/html" -> "html"
            mimeType == "application/pdf" -> "pdf"
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mimeType == "application/vnd.ms-word.document.macroEnabled.12" -> "office_word"
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                mimeType == "application/vnd.ms-excel.sheet.macroEnabled.12" -> "office_sheet"
            mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
                mimeType == "application/vnd.ms-powerpoint.presentation.macroEnabled.12" -> "office_slide"
            mimeType.startsWith("audio/") -> "audio"
            mimeType.startsWith("video/") -> "video"
            else -> "file"
        }
    }

    fun uriForFile(file: File): String? {
        val canonical = file.canonicalFile
        workspaceMountForFile(canonical)?.let { (mount, relative) ->
            return if (relative.isBlank()) {
                buildRootUri(DIR_WORKSPACE, mount.alias)
            } else {
                buildRootUri(
                    DIR_WORKSPACE,
                    mount.alias,
                    *relative.split('/').filter { it.isNotBlank() }.toTypedArray()
                )
            }
        }
        if (isWithinPublicStorage(canonical)) {
            return publicUriForStoragePath(canonical.absolutePath)
        }
        if (!isWithinRoot(canonical)) return null
        return when {
            isWithin(attachmentsDir.canonicalFile, canonical) -> buildUriForBase(DIR_ATTACHMENTS, attachmentsDir, canonical)
            isWithin(sharedDir.canonicalFile, canonical) -> buildUriForBase(DIR_SHARED, sharedDir, canonical)
            isWithin(offloadsDir.canonicalFile, canonical) -> buildUriForBase(DIR_OFFLOADS, offloadsDir, canonical)
            isWithin(browserDir.canonicalFile, canonical) -> buildUriForBase(DIR_BROWSER, browserDir, canonical)
            isWithin(skillsDir.canonicalFile, canonical) -> buildUriForBase(DIR_SKILLS, skillsDir, canonical)
            isWithin(memoryDir.canonicalFile, canonical) -> buildUriForBase(DIR_MEMORY, memoryDir, canonical)
            isWithin(petsDir.canonicalFile, canonical) -> buildUriForBase(DIR_PETS, petsDir, canonical)
            isWithin(internalDir.canonicalFile, canonical) -> null
            else -> buildUriForBase(DIR_WORKSPACE, rootDir, canonical)
        }
    }

    private fun workspaceMountLinks(): List<WorkspaceMountLink> {
        ensureRuntimeDirectories()
        return rootDir.listFiles()
            ?.mapNotNull { child ->
                if (!Files.isSymbolicLink(child.toPath())) {
                    return@mapNotNull null
                }
                val targetPath = runCatching {
                    Files.readSymbolicLink(child.toPath())
                }.getOrNull() ?: return@mapNotNull null
                val resolvedTarget = if (targetPath.isAbsolute) {
                    targetPath
                } else {
                    child.parentFile?.toPath()?.resolve(targetPath) ?: targetPath
                }
                val sourceDir = runCatching {
                    resolvedTarget.toFile().canonicalFile
                }.getOrElse {
                    resolvedTarget.toFile().absoluteFile
                }
                WorkspaceMountLink(
                    alias = child.name,
                    linkFile = child,
                    sourceDir = sourceDir
                )
            }
            ?.sortedByDescending { it.sourceDir.absolutePath.length }
            ?: emptyList()
    }

    private fun workspaceMountForFile(file: File): Pair<WorkspaceMountLink, String>? {
        val canonical = file.canonicalFile
        return workspaceMountLinks().firstNotNullOfOrNull { mount ->
            if (isWithin(mount.sourceDir, canonical)) {
                mount to relativePathFrom(mount.sourceDir, canonical)
            } else {
                null
            }
        }
    }

    private fun isWithinMountedWorkspace(file: File): Boolean {
        return workspaceMountForFile(file) != null
    }

    private fun absoluteWorkspacePathForOmnibotUri(uriText: String): String? {
        val trimmed = uriText.trim()
        if (!trimmed.startsWith("$URI_SCHEME://")) {
            return null
        }
        val absoluteShellPath = absoluteOmnibotPath(trimmed) ?: return null
        if (!(absoluteShellPath == SHELL_ROOT_PATH || absoluteShellPath.startsWith("$SHELL_ROOT_PATH/"))) {
            return null
        }
        return androidPathForShell(absoluteShellPath)?.absolutePath
    }
}
