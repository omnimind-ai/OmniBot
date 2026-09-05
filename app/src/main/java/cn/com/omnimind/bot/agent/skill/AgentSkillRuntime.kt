package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.res.AssetManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.update.ApkDownloadSource
import cn.com.omnimind.bot.update.AppUpdateManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.min

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
private const val BUILTIN_SOURCE = "builtin"
private const val OFFICIAL_SOURCE = "official"
private const val USER_SOURCE = "user"
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"
private const val SKILL_REGISTRY_FILE_NAME = ".skill_registry.json"
private const val OFFICIAL_SKILLS_GITHUB_REPOSITORY_URL = "https://github.com/omnimind-ai/OmniBotSkills"
private const val OFFICIAL_SKILLS_CNB_REPOSITORY_URL = "https://cnb.cool/o.a/OmniBotSkills"
private const val OFFICIAL_SKILLS_DIRECTORY_NAME = "OmniBotSkills"
private val RETIRED_BUILTIN_SKILL_IDS = setOf("hatch-pet")
private val SKILL_INDEX_LOCK = Any()

private data class BuiltinSkillManifest(
    val skills: List<BuiltinSkillAsset> = emptyList()
)

private data class BuiltinSkillAsset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val assetPath: String = "",
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasEvals: Boolean = false
)

private data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = USER_SOURCE,
    val installState: String = INSTALL_STATE_INSTALLED
)

data class OfficialSkillsSyncResult(
    val action: String,
    val repositoryUrl: String,
    val rootPath: String,
    val shellRootPath: String,
    val skillCount: Int,
    val skills: List<SkillIndexEntry>,
    val output: String
)

private class SkillRegistryStore(
    private val registryFile: File
) {
    private val gson = Gson()
    private val mapType = object : TypeToken<LinkedHashMap<String, SkillRegistryEntry>>() {}.type

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        if (!registryFile.exists()) {
            return linkedMapOf()
        }
        return runCatching {
            gson.fromJson<LinkedHashMap<String, SkillRegistryEntry>>(
                registryFile.readText(),
                mapType
            ) ?: linkedMapOf()
        }.getOrElse {
            linkedMapOf()
        }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        registryFile.parentFile?.mkdirs()
        val ordered = linkedMapOf<String, SkillRegistryEntry>()
        entries.toSortedMap().forEach { (key, value) ->
            ordered[key] = value
        }
        registryFile.writeText(gson.toJson(ordered))
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        val updated = read()
        updated[skillId] = entry
        write(updated)
    }

    fun remove(skillId: String) {
        val updated = read()
        if (updated.remove(skillId) != null) {
            write(updated)
        }
    }
}

private class BuiltinSkillAssetStore(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager
) {
    private val gson = Gson()

    fun listBuiltins(): List<BuiltinSkillAsset> {
        return runCatching {
            context.assets.open(BUILTIN_SKILL_MANIFEST_ASSET).bufferedReader().use { reader ->
                gson.fromJson(reader, BuiltinSkillManifest::class.java)?.skills.orEmpty()
            }
        }.getOrElse {
            emptyList()
        }.filter { skill ->
            skill.id.isNotBlank() && skill.assetPath.isNotBlank()
        }
    }

    fun findBuiltin(skillId: String): BuiltinSkillAsset? {
        return listBuiltins().firstOrNull { it.id == skillId }
    }

    fun seedMissingBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        listBuiltins().forEach { builtin ->
            val registryEntry = registry[builtin.id]
            if (registryEntry?.installState == INSTALL_STATE_REMOVED_BUILTIN) {
                return@forEach
            }
            if (registryEntry?.source == USER_SOURCE) {
                return@forEach
            }
            installBuiltinInternal(builtin)
            if (registryEntry?.source == BUILTIN_SOURCE &&
                registryEntry.installState == INSTALL_STATE_INSTALLED
            ) {
                return@forEach
            }
            registry[builtin.id] = SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
            changed = true
        }
        if (changed) {
            registryStore.write(registry)
        }
    }

    fun pruneRetiredBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        RETIRED_BUILTIN_SKILL_IDS.forEach { skillId ->
            val targetDir = File(workspaceManager.skillsRoot(), skillId)
            val registryEntry = registry[skillId]
            val isBundledCopy = registryEntry?.source == BUILTIN_SOURCE ||
                (registryEntry == null && looksLikeRetiredBundledCopy(targetDir, skillId))
            if (!isBundledCopy) {
                return@forEach
            }
            val deleted = !targetDir.exists() || targetDir.deleteRecursively()
            if (deleted && registry.remove(skillId) != null) {
                changed = true
            }
        }
        if (changed) {
            registryStore.write(registry)
        }
    }

    fun installBuiltin(skillId: String, registryStore: SkillRegistryStore) {
        val builtin = findBuiltin(skillId)
            ?: throw IllegalArgumentException("未找到内置 skill：$skillId")
        installBuiltinInternal(builtin)
        registryStore.set(
            skillId,
            SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
        )
    }

    fun targetDirFor(builtin: BuiltinSkillAsset): File {
        return File(workspaceManager.skillsRoot(), builtin.id)
    }

    private fun installBuiltinInternal(builtin: BuiltinSkillAsset) {
        val targetDir = targetDirFor(builtin)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        copyAssetRecursively(
            assetManager = context.assets,
            assetPath = builtin.assetPath,
            target = targetDir
        )
    }

    private fun looksLikeRetiredBundledCopy(targetDir: File, skillId: String): Boolean {
        if (!targetDir.isDirectory) return false
        val skillFile = File(targetDir, "SKILL.md")
        if (!skillFile.isFile) return false
        val text = runCatching { skillFile.readText() }.getOrDefault("")
        return text.contains("name: $skillId") &&
            text.contains("/workspace/.omnibot/pets/<pet-id>")
    }

    private fun copyAssetRecursively(
        assetManager: AssetManager,
        assetPath: String,
        target: File
    ) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        if (!target.exists()) {
            target.mkdirs()
        }
        children.forEach { child ->
            val childAssetPath = "$assetPath/$child"
            copyAssetRecursively(
                assetManager = assetManager,
                assetPath = childAssetPath,
                target = File(target, child)
            )
        }
    }
}

class SkillIndexService(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager
) {
    private fun registryStore(): SkillRegistryStore {
        return SkillRegistryStore(File(workspaceManager.skillsRoot(), SKILL_REGISTRY_FILE_NAME))
    }

    private fun builtinStore(): BuiltinSkillAssetStore {
        return BuiltinSkillAssetStore(context.applicationContext, workspaceManager)
    }

    fun seedBuiltinSkillsIfNeeded() {
        synchronized(SKILL_INDEX_LOCK) {
            val registryStore = registryStore()
            val builtinStore = builtinStore()
            builtinStore.pruneRetiredBuiltins(registryStore)
            builtinStore.seedMissingBuiltins(registryStore)
        }
    }

    fun listSkillsForManagement(): List<SkillIndexEntry> {
        synchronized(SKILL_INDEX_LOCK) {
            seedBuiltinSkillsIfNeeded()
            val registryStore = registryStore()
            val registry = registryStore.read()
            val builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
            val installedEntries = scanInstalledEntries(registry, builtinAssets)
            val installedIds = installedEntries.mapTo(mutableSetOf()) { it.id }
            val removedBuiltinEntries = builtinAssets.values
                .asSequence()
                .filter { builtin ->
                    builtin.id !in installedIds &&
                        registry[builtin.id]?.installState == INSTALL_STATE_REMOVED_BUILTIN
                }
                .map { builtin ->
                    buildBuiltinPlaceholderEntry(
                        builtin = builtin,
                        registryState = registry[builtin.id]
                    )
                }
                .toList()

            return (installedEntries + removedBuiltinEntries).sortedWith(
                compareByDescending<SkillIndexEntry> { it.installed }
                    .thenBy { sourceRank(it.source) }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    fun listInstalledSkills(): List<SkillIndexEntry> {
        return listSkillsForManagement().filter { it.installed && it.enabled }
    }

    fun findInstalledSkill(identifier: String): SkillIndexEntry? {
        return findManagedInstalledSkill(identifier, includeDisabled = false)
    }

    private fun findManagedInstalledSkill(
        identifier: String,
        includeDisabled: Boolean
    ): SkillIndexEntry? {
        val normalizedIdentifier = normalizeSkillLookup(identifier)
        if (normalizedIdentifier.isBlank()) return null
        val entries = listSkillsForManagement().filter { entry ->
            entry.installed && (includeDisabled || entry.enabled)
        }
        return entries.firstOrNull { normalizeSkillLookup(it.id) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.name) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.shellSkillFilePath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.skillFilePath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.shellRootPath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.rootPath) == normalizedIdentifier }
    }

    fun installSkillFromDirectory(sourcePath: String): SkillIndexEntry {
        val sourceDir = File(sourcePath).canonicalFile
        return installSkillFromDirectory(sourcePath, sourceDir.name)
    }

    internal fun installSkillFromDirectory(
        sourcePath: String,
        targetDirectoryName: String,
    ): SkillIndexEntry {
        synchronized(SKILL_INDEX_LOCK) {
            val sourceDir = File(sourcePath).canonicalFile
            require(sourceDir.isDirectory) { "skill source 必须是目录" }
            val skillFile = File(sourceDir, "SKILL.md")
            require(skillFile.exists()) { "skill source 缺少 SKILL.md" }
            require(SKILL_TARGET_DIRECTORY.matches(targetDirectoryName)) {
                "skill target directory 非法：$targetDirectoryName"
            }
            val targetDir = File(workspaceManager.skillsRoot(), targetDirectoryName)
            copyRecursively(sourceDir, targetDir)
            val entry = buildInstalledEntry(
                skillDir = targetDir,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
            ) ?: throw IllegalStateException("安装 skill 后索引失败")
            registryStore().set(
                entry.id,
                SkillRegistryEntry(
                    enabled = true,
                    source = USER_SOURCE,
                    installState = INSTALL_STATE_INSTALLED
                )
            )
            return entry.copy(enabled = true, source = USER_SOURCE, installed = true)
        }
    }

    internal fun deleteSkillInstallation(rootPath: String): Boolean {
        synchronized(SKILL_INDEX_LOCK) {
            val canonicalRoot = File(rootPath).canonicalFile
            val skillsRoot = workspaceManager.skillsRoot().canonicalFile
            require(
                canonicalRoot.parentFile == skillsRoot && canonicalRoot.name != SKILL_REGISTRY_FILE_NAME
            ) { "skill 安装目录非法" }
            val entry = buildInstalledEntry(
                skillDir = canonicalRoot,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
            ) ?: return false
            val deleted = !canonicalRoot.exists() || canonicalRoot.deleteRecursively()
            if (!deleted) return false
            val stillInstalled = scanInstalledEntries(
                root = skillsRoot,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
            ).any { it.id == entry.id }
            if (!stillInstalled) {
                registryStore().remove(entry.id)
            }
            return true
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): SkillIndexEntry {
        val entry = findManagedInstalledSkill(skillId, includeDisabled = true)
            ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
        registryStore().set(
            entry.id,
            SkillRegistryEntry(
                enabled = enabled,
                source = entry.source,
                installState = INSTALL_STATE_INSTALLED
            )
        )
        return entry.copy(enabled = enabled)
    }

    fun deleteSkill(skillId: String): Boolean {
        val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
            ?: return false
        val targetDir = File(entry.rootPath)
        if (entry.source == BUILTIN_SOURCE) {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
                if (targetDir.exists()) {
                    return false
                }
            }
            registryStore().set(
                entry.id,
                SkillRegistryEntry(
                    enabled = false,
                    source = BUILTIN_SOURCE,
                    installState = INSTALL_STATE_REMOVED_BUILTIN
                )
            )
            return true
        }
        val deleted = !targetDir.exists() || targetDir.deleteRecursively()
        registryStore().remove(entry.id)
        return deleted
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        val builtinStore = builtinStore()
        builtinStore.installBuiltin(skillId, registryStore())
        return findInstalledSkill(skillId)
            ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
    }

    suspend fun syncOfficialSkillsRepository(): OfficialSkillsSyncResult {
        val targetDir = File(workspaceManager.skillsRoot(), OFFICIAL_SKILLS_DIRECTORY_NAME)
        val shellTargetPath = workspaceManager.shellPathForAndroid(targetDir)
            ?: "${AgentWorkspaceManager.SHELL_ROOT_PATH}/.omnibot/skills/$OFFICIAL_SKILLS_DIRECTORY_NAME"
        val wasInstalled = File(targetDir, ".git").isDirectory
        val repositoryUrl = resolveOfficialSkillsRepositoryUrl()
        val commandResult = EmbeddedTerminalRuntime.executeCommand(
            context = context.applicationContext,
            command = buildOfficialSkillsSyncCommand(repositoryUrl, shellTargetPath),
            workingDirectory = AgentWorkspaceManager.SHELL_ROOT_PATH,
            timeoutSeconds = 15 * 60
        )
        if (!commandResult.success) {
            val message = commandResult.errorMessage
                ?: commandResult.output.takeLast(1200).ifBlank { "同步官方 skills 失败" }
            throw IllegalStateException(message)
        }

        val officialEntries = markOfficialRepositorySkills(targetDir)
        val allSkills = listSkillsForManagement()
        return OfficialSkillsSyncResult(
            action = if (wasInstalled) "updated" else "installed",
            repositoryUrl = repositoryUrl,
            rootPath = targetDir.absolutePath,
            shellRootPath = shellTargetPath,
            skillCount = officialEntries.size,
            skills = allSkills,
            output = commandResult.output
        )
    }

    private fun resolveOfficialSkillsRepositoryUrl(): String {
        return when (AppUpdateManager.getApkDownloadSource(context.applicationContext)) {
            ApkDownloadSource.WORKER -> OFFICIAL_SKILLS_CNB_REPOSITORY_URL
            ApkDownloadSource.GITHUB -> OFFICIAL_SKILLS_GITHUB_REPOSITORY_URL
        }
    }

    private fun scanInstalledEntries(
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>
    ): List<SkillIndexEntry> {
        return scanInstalledEntries(
            root = workspaceManager.skillsRoot(),
            registry = registry,
            builtinAssets = builtinAssets
        )
    }

    private fun scanInstalledEntries(
        root: File,
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>
    ): List<SkillIndexEntry> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { directory -> directory.name != ".git" }
            .filter { file -> file.isFile && file.name == "SKILL.md" }
            .mapNotNull { skillFile ->
                buildInstalledEntry(
                    skillDir = skillFile.parentFile ?: return@mapNotNull null,
                    registry = registry,
                    builtinAssets = builtinAssets
                )
            }
            .distinctBy { it.rootPath }
            .toList()
    }

    private fun markOfficialRepositorySkills(repositoryDir: File): List<SkillIndexEntry> {
        val registryStore = registryStore()
        val registry = registryStore.read()
        val builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
        val scannedEntries = scanInstalledEntries(
            root = repositoryDir,
            registry = registry,
            builtinAssets = builtinAssets
        )
        val officialIds = scannedEntries.mapTo(mutableSetOf()) { it.id }
        val updatedRegistry = linkedMapOf<String, SkillRegistryEntry>()
        registry.forEach { (skillId, entry) ->
            if (entry.source != OFFICIAL_SOURCE || skillId in officialIds) {
                updatedRegistry[skillId] = entry
            }
        }
        scannedEntries.forEach { entry ->
            val current = registry[entry.id]
            updatedRegistry[entry.id] = SkillRegistryEntry(
                enabled = current?.enabled ?: true,
                source = OFFICIAL_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
        }
        registryStore.write(updatedRegistry)
        return scanInstalledEntries(
            root = repositoryDir,
            registry = updatedRegistry,
            builtinAssets = builtinAssets
        )
    }

    private fun buildOfficialSkillsSyncCommand(repositoryUrl: String, shellTargetPath: String): String {
        val quotedRepo = TermuxCommandBuilder.quoteForShell(repositoryUrl)
        val quotedTarget = TermuxCommandBuilder.quoteForShell(shellTargetPath)
        return """
            set -eu
            REPO=$quotedRepo
            TARGET=$quotedTarget
            mkdir -p "${'$'}(dirname "${'$'}TARGET")"
            if ! command -v git >/dev/null 2>&1; then
              apk update
              apk add --no-cache git ca-certificates
            fi
            if [ -d "${'$'}TARGET/.git" ]; then
              git -C "${'$'}TARGET" remote set-url origin "${'$'}REPO" || true
              git -C "${'$'}TARGET" fetch --prune origin
              git -C "${'$'}TARGET" pull --ff-only --autostash
            elif [ -e "${'$'}TARGET" ]; then
              echo "官方 skills 目录已存在但不是 git 仓库：${'$'}TARGET" >&2
              exit 2
            else
              git clone "${'$'}REPO" "${'$'}TARGET"
            fi
            find "${'$'}TARGET" -name SKILL.md -type f | wc -l
        """.trimIndent()
    }

    private fun buildInstalledEntry(
        skillDir: File,
        registry: Map<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>
    ): SkillIndexEntry? {
        val canonicalSkillDir = skillDir.canonicalFile
        val skillFile = File(canonicalSkillDir, "SKILL.md")
        val parsed = parseSkillFile(skillFile) ?: return null
        val frontmatter = parsed.frontmatter
        val id = sanitizeSkillId(canonicalSkillDir.name, frontmatter["name"])
        val metadata = frontmatter["metadata"]
            ?.let { raw -> parseIndentedBlock(raw) }
            ?: emptyMap()
        val registryState = registry[id]
        val builtinAsset = builtinAssets[id]
        val shellRootPath = workspaceManager.shellPathForAndroid(canonicalSkillDir)
            ?: canonicalSkillDir.absolutePath
        val shellSkillFilePath = workspaceManager.shellPathForAndroid(skillFile)
            ?: skillFile.absolutePath
        return SkillIndexEntry(
            id = id,
            name = frontmatter["name"]?.ifBlank { id } ?: id,
            description = frontmatter["description"]?.trim().orEmpty(),
            compatibility = frontmatter["compatibility"]?.trim(),
            metadata = metadata,
            rootPath = canonicalSkillDir.absolutePath,
            shellRootPath = shellRootPath,
            skillFilePath = skillFile.absolutePath,
            shellSkillFilePath = shellSkillFilePath,
            hasScripts = File(canonicalSkillDir, "scripts").isDirectory,
            hasReferences = File(canonicalSkillDir, "references").isDirectory,
            hasAssets = File(canonicalSkillDir, "assets").isDirectory,
            hasEvals = File(canonicalSkillDir, "evals").isDirectory,
            enabled = registryState?.enabled ?: true,
            source = registryState?.source?.ifBlank { null }
                ?: if (builtinAsset != null) BUILTIN_SOURCE else USER_SOURCE,
            installed = true
        )
    }

    private fun buildBuiltinPlaceholderEntry(
        builtin: BuiltinSkillAsset,
        registryState: SkillRegistryEntry?
    ): SkillIndexEntry {
        val targetDir = File(workspaceManager.skillsRoot(), builtin.id)
        val skillFile = File(targetDir, "SKILL.md")
        return SkillIndexEntry(
            id = builtin.id,
            name = builtin.name.ifBlank { builtin.id },
            description = builtin.description,
            rootPath = targetDir.absolutePath,
            shellRootPath = workspaceManager.shellPathForAndroid(targetDir)
                ?: targetDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            shellSkillFilePath = workspaceManager.shellPathForAndroid(skillFile)
                ?: skillFile.absolutePath,
            hasScripts = builtin.hasScripts,
            hasReferences = builtin.hasReferences,
            hasAssets = builtin.hasAssets,
            hasEvals = builtin.hasEvals,
            enabled = registryState?.enabled ?: false,
            source = BUILTIN_SOURCE,
            installed = false
        )
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
            return
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    private fun normalizeSkillLookup(value: String): String {
        return value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun sourceRank(source: String): Int {
        return when (source) {
            BUILTIN_SOURCE -> 0
            OFFICIAL_SOURCE -> 1
            else -> 2
        }
    }

    private companion object {
        val SKILL_TARGET_DIRECTORY = Regex("^[a-z0-9][a-z0-9-]{0,79}$")
    }
}

class SkillLoader(
    private val workspaceManager: AgentWorkspaceManager
) {
    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? {
        if (!entry.installed) {
            return null
        }
        val skillDir = File(entry.rootPath)
        val parsed = parseSkillFile(File(skillDir, "SKILL.md")) ?: return null
        val referencesDir = File(skillDir, "references")
        val loadedReferences = if (referencesDir.isDirectory) {
            referencesDir.listFiles()
                ?.filter { it.isFile }
                ?.map { file -> workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }
                ?.sorted()
                ?: emptyList()
        } else {
            emptyList()
        }
        return ResolvedSkillContext(
            skillId = entry.id,
            frontmatter = parsed.frontmatter,
            metadata = entry.metadata,
            bodyMarkdown = parsed.body,
            loadedReferences = loadedReferences,
            scriptsDir = File(skillDir, "scripts")
                .takeIf { it.isDirectory }
                ?.let { workspaceManager.shellPathForAndroid(it) ?: it.absolutePath },
            assetsDir = File(skillDir, "assets")
                .takeIf { it.isDirectory }
                ?.let { workspaceManager.shellPathForAndroid(it) ?: it.absolutePath },
            triggerReason = triggerReason
        )
    }
}

object SkillTriggerMatcher {
    fun resolveMatches(
        userMessage: String,
        entries: List<SkillIndexEntry>,
        maxMatches: Int = 2
    ): List<SkillMatchResult> {
        val normalizedMessage = normalize(userMessage)
        if (normalizedMessage.isBlank()) return emptyList()
        return entries.mapNotNull { entry ->
            if (!entry.installed || !entry.enabled) {
                return@mapNotNull null
            }
            val confidence = score(entry, normalizedMessage)
            if (confidence <= 0.0) return@mapNotNull null
            val reason = when {
                normalizedMessage.contains(normalize(entry.id)) -> "用户消息命中 skill id"
                normalizedMessage.contains(normalize(entry.name)) -> "用户消息命中 skill 名称"
                else -> "用户消息命中 skill 描述关键词"
            }
            SkillMatchResult(entry = entry, confidence = confidence, triggerReason = reason)
        }.sortedByDescending { it.confidence }
            .take(maxMatches)
    }

    private fun score(entry: SkillIndexEntry, normalizedMessage: String): Double {
        var score = 0.0
        val normalizedId = normalize(entry.id)
        val normalizedName = normalize(entry.name)
        if (normalizedId.isNotBlank() && normalizedMessage.contains(normalizedId)) {
            score += 1.0
        }
        if (normalizedName.isNotBlank() && normalizedMessage.contains(normalizedName)) {
            score += 0.9
        }
        extractCandidatePhrases(entry.description).forEach { phrase ->
            if (matchesTriggerPhrase(normalizedMessage, phrase)) {
                score += 0.35
            }
        }
        return min(score, 1.5)
    }

    private fun matchesTriggerPhrase(normalizedMessage: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false
        if ('*' !in normalizedPhrase) return normalizedMessage.contains(normalizedPhrase)
        val segments = normalizedPhrase.split('*').filter(String::isNotBlank)
        if (segments.size < 2) return false
        var searchFrom = 0
        segments.forEach { segment ->
            val matchAt = normalizedMessage.indexOf(segment, startIndex = searchFrom)
            if (matchAt < 0) return false
            searchFrom = matchAt + segment.length
        }
        return true
    }

    private fun extractCandidatePhrases(description: String): List<String> {
        val quoted = Regex("[\"“”'‘’]([^\"“”'‘’]{2,40})[\"“”'‘’]")
            .findAll(description)
            .map { it.groupValues[1] }
            .toList()
        val fallback = description.split(Regex("[,，。;；、\\n]"))
            .map { it.trim() }
            .filter { it.length in 2..24 }
        return (quoted + fallback).distinct().take(20)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
    }
}

internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String
)

internal fun parseSkillFile(skillFile: File): ParsedSkillFile? {
    if (!skillFile.exists() || !skillFile.isFile) return null
    val raw = skillFile.readText()
    if (!raw.startsWith("---")) {
        return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
    }
    val markerIndex = raw.indexOf("\n---", startIndex = 3)
    if (markerIndex <= 0) {
        return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
    }
    val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
    val body = raw.substring(markerIndex + 4).trim()
    return ParsedSkillFile(
        frontmatter = parseSimpleFrontmatter(frontmatterText),
        body = body
    )
}

internal fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
    if (frontmatter.isBlank()) return emptyMap()
    val lines = frontmatter.lines()
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        if (rawLine.isBlank()) {
            index += 1
            continue
        }
        val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
        if (keyMatch == null) {
            index += 1
            continue
        }
        val key = keyMatch.groupValues[1]
        val value = keyMatch.groupValues[2]
        if (value == ">" || value == "|") {
            val builder = StringBuilder()
            index += 1
            while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                val next = lines[index]
                if (next.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(next.trim())
                }
                index += 1
            }
            result[key] = builder.toString().trim()
            continue
        }
        if (value.isBlank()) {
            val builder = StringBuilder()
            index += 1
            while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(lines[index].trimEnd())
                index += 1
            }
            result[key] = builder.toString().trim()
            continue
        }
        result[key] = value.trim().trim('"')
        index += 1
    }
    return result
}

internal fun parseIndentedBlock(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lines().mapNotNull { line ->
        val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
        match.groupValues[1] to match.groupValues[2].trim().trim('"')
    }.toMap()
}
