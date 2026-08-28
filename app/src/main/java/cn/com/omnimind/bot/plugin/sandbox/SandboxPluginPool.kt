package cn.com.omnimind.bot.plugin.sandbox

import android.content.Context
import cn.com.omnimind.bot.agent.parseSkillFile
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginDescriptor
import cn.com.omnimind.bot.plugin.OmniPluginProvider
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SandboxPluginPool(
    rootDirectory: File,
    dataRootDirectory: File = File(
        rootDirectory.parentFile ?: rootDirectory,
        "${rootDirectory.name}-data",
    ),
    private val databaseFactory: SandboxPluginDatabaseFactory,
    private val skillManager: SandboxProjectSkillManager = NoOpSandboxProjectSkillManager,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val root = rootDirectory.canonicalFile
    private val dataRoot = dataRootDirectory.canonicalFile
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    constructor(context: Context) : this(
        rootDirectory = File(context.filesDir, USER_POOL_PATH),
        dataRootDirectory = File(context.filesDir, USER_DATA_PATH),
        databaseFactory = AndroidSandboxPluginDatabaseFactory,
        skillManager = AndroidSandboxProjectSkillManager(context),
    )

    fun execute(command: SandboxPluginCommand): SandboxPluginResult = runCatching {
        when (command) {
            is SandboxPluginCommand.CheckProject -> checkProject(
                command.sourceDirectory,
                command.manifest,
            )
            is SandboxPluginCommand.PublishProject -> publishProject(
                command.sourceDirectory,
                command.manifest,
            )
            is SandboxPluginCommand.Insert -> insert(command)
            is SandboxPluginCommand.Query -> query(command)
            is SandboxPluginCommand.Update -> update(command)
            is SandboxPluginCommand.Delete -> delete(command)
        }
    }.fold(
        onSuccess = SandboxPluginResult::success,
        onFailure = SandboxPluginResult::failure,
    )

    fun createProviders(): List<OmniPluginProvider> {
        root.mkdirs()
        return root.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .filterNot { it.name.startsWith('.') }
            .mapNotNull { directory ->
                runCatching {
                    val manifest = readManifest(directory)
                    require(directory == pluginDirectory(manifest.id)) {
                        "Sandbox plugin directory does not match id: ${manifest.id}"
                    }
                    ensureRuntimeCurrent(directory)
                    SandboxPluginProvider(this, directory, manifest)
                }.getOrNull()
            }
            .sortedBy { it.descriptor.name.lowercase() }
            .toList()
    }

    fun requirePermission(pluginId: String, permission: String) {
        require(permission in SandboxProjectPermission.supported) {
            "Unsupported sandbox permission: $permission"
        }
        val manifest = readManifest(requirePluginDirectory(pluginId))
        require(permission in manifest.permissions) {
            "Plugin $pluginId has not declared the $permission permission"
        }
    }

    internal suspend fun executeTool(
        pluginId: String,
        runtimeToolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ): Map<String, Any?> {
        val directory = requirePluginDirectory(pluginId)
        val manifest = readManifest(directory)
        val toolkitSpec = manifest.toolkit
            ?: throw IllegalArgumentException("Plugin $pluginId does not provide Agent tools")
        val toolkit = readToolkit(safeChild(directory, toolkitSpec.path))
        val tool = toolkit.tools.firstOrNull {
            SandboxProjectToolPolicy.runtimeName(pluginId, it) == runtimeToolName
        } ?: throw IllegalArgumentException(
            "Plugin $pluginId does not provide tool $runtimeToolName",
        )
        SandboxProjectToolPolicy.validateArguments(tool, arguments)
        return SandboxProjectConnectorRegistry.execute(
            pool = this,
            pluginId = pluginId,
            executor = SandboxProjectToolPolicy.resolveExecutor(toolkit, tool),
            args = arguments,
        )
    }

    internal suspend fun executeDashboardTool(
        pluginId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ): Map<String, Any?> {
        val directory = requirePluginDirectory(pluginId)
        val manifest = readManifest(directory)
        val toolkitSpec = manifest.toolkit
            ?: throw IllegalArgumentException("Plugin $pluginId does not provide Agent tools")
        val toolkit = readToolkit(safeChild(directory, toolkitSpec.path))
        val tool = toolkit.tools.firstOrNull { it.name == toolName }
            ?: throw IllegalArgumentException(
                "Plugin $pluginId does not provide Dashboard tool $toolName",
            )
        SandboxProjectToolPolicy.validateArguments(tool, arguments)
        return SandboxProjectConnectorRegistry.execute(
            pool = this,
            pluginId = pluginId,
            executor = SandboxProjectToolPolicy.resolveExecutor(toolkit, tool),
            args = arguments,
        )
    }

    internal fun requireAnyPermission(pluginId: String, permissions: Set<String>) {
        require(permissions.isNotEmpty()) { "At least one permission is required" }
        require(permissions.all { it in SandboxProjectPermission.supported }) {
            "Unsupported sandbox permission"
        }
        val manifest = readManifest(requirePluginDirectory(pluginId))
        require(manifest.permissions.any { it in permissions }) {
            "Plugin $pluginId has not declared one of ${permissions.sorted()}"
        }
    }

    internal fun ensureInstalled(directory: File, manifest: SandboxPluginManifest) {
        val database = manifest.database ?: return
        val schemaFile = safeChild(directory, database.schema)
        require(schemaFile.isFile) {
            "${database.schema}: database schema is missing"
        }
        val schemaSql = schemaFile.readText()
        SandboxSqlPolicy.validateSchema(schemaSql)
        migrateLegacyData(directory, manifest.id)
        val databaseFile = databaseFile(manifest.id)
        val marker = schemaMarkerFile(manifest.id)
        val fingerprint = sha256(schemaSql)
        if (databaseFile.isFile && marker.isFile && marker.readText() == fingerprint) return
        databaseFile.parentFile?.mkdirs()
        databaseFactory.open(databaseFile).use { database ->
            database.initialize(schemaSql)
        }
        marker.parentFile?.mkdirs()
        marker.writeText(fingerprint)
    }

    internal fun ensureSkillInstalled(directory: File, manifest: SandboxPluginManifest) {
        val specification = manifest.skill ?: return
        val skillFile = safeChild(directory, specification.path)
        require(skillFile.isFile) { "${specification.path}: SKILL.md is missing" }
        val toolkit = manifest.toolkit?.let { readToolkit(safeChild(directory, it.path)) }
            ?: SandboxProjectToolkit()
        skillManager.install(
            sourceDirectory = requireNotNull(skillFile.parentFile),
            skillId = manifest.skillId(),
            tools = toolkit.tools.map { tool -> tool.definition(manifest.id) },
        )
    }

    internal fun setSkillEnabled(manifest: SandboxPluginManifest, enabled: Boolean) {
        if (manifest.skill != null) skillManager.setEnabled(manifest.skillId(), enabled)
    }

    internal fun remove(directory: File, manifest: SandboxPluginManifest) {
        require(directory.parentFile?.canonicalFile == root) {
            "Refusing to remove a plugin outside the sandbox pool"
        }
        if (manifest.skill != null) skillManager.uninstall(manifest.skillId())
        require(!directory.exists() || directory.deleteRecursively()) {
            "Unable to remove sandbox plugin: ${directory.name}"
        }
    }

    private fun checkProject(
        sourceDirectory: File,
        manifest: SandboxProjectManifest,
    ): Map<String, Any?> {
        val inspection = inspectProject(sourceDirectory, manifest)
        root.mkdirs()
        if (inspection.schemaSql != null) {
            val verificationDirectory = safeChild(root, ".staging/check-${UUID.randomUUID()}")
            try {
                val databaseFile = safeChild(verificationDirectory, "project.db")
                databaseFile.parentFile?.mkdirs()
                databaseFactory.open(databaseFile).use { database ->
                    database.initialize(inspection.schemaSql)
                }
            } catch (error: Throwable) {
                throw IllegalArgumentException(
                    "${manifest.schemaPath}: SQLite verification failed: " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            } finally {
                verificationDirectory.deleteRecursively()
                verificationDirectory.parentFile
                    ?.takeIf { it.listFiles().isNullOrEmpty() }
                    ?.delete()
            }
        }
        return buildMap {
            put("pluginId", "$PROJECT_ID_PREFIX${manifest.slug}")
            put("path", inspection.sourceDirectory.absolutePath)
            put("skillPath", inspection.skillFile.absolutePath)
            put("toolkitPath", inspection.toolkitFile.absolutePath)
            put("connectorCount", inspection.toolkit.connectors.size)
            put("toolCount", inspection.toolkit.tools.size)
            put("fileCount", inspection.fileCount)
            put("sizeBytes", inspection.sizeBytes)
            put("permissions", manifest.permissions)
            put("valid", true)
            put("entryPath", inspection.entryFile.absolutePath)
            put("iconPath", inspection.iconFile.absolutePath)
            inspection.schemaFile?.let { put("schemaPath", it.absolutePath) }
        }
    }

    private fun publishProject(
        sourceDirectory: File,
        draft: SandboxProjectManifest,
    ): Map<String, Any?> = synchronized(PUBLISH_LOCK) {
        val inspection = inspectProject(sourceDirectory, draft)
        checkProject(inspection.sourceDirectory, draft)
        root.mkdirs()
        val pluginId = "$PROJECT_ID_PREFIX${draft.slug}"
        val target = pluginDirectory(pluginId)
        val updating = target.isDirectory
        val previousManifest = if (updating) readManifest(target) else null
        val manifest = SandboxPluginManifest(
            id = pluginId,
            name = draft.name.trim(),
            version = draft.version.trim(),
            description = draft.description.trim(),
            capabilities = capabilitiesFor(draft.permissions),
            permissions = draft.permissions,
            frontend = SandboxPluginFrontend(
                entry = requireNotNull(draft.entryPath),
                icon = requireNotNull(draft.iconPath),
            ),
            database = draft.schemaPath?.let(::SandboxPluginDatabaseSpec),
            skill = SandboxPluginSkillSpec(path = draft.skillPath),
            toolkit = SandboxPluginToolkitSpec(path = draft.toolkitPath),
            createdAtEpochMs = previousManifest?.createdAtEpochMs ?: now(),
        )
        val stagingRoot = safeChild(root, ".staging")
        val temporary = safeChild(stagingRoot, UUID.randomUUID().toString())
        val backup = safeChild(stagingRoot, "${UUID.randomUUID()}-backup")
        try {
            if (updating) {
                migrateLegacyData(target, pluginId)
            }
            copyTree(inspection.sourceDirectory, temporary)
            writeText(temporary, MANIFEST_FILE, json.encodeToString(manifest))
            injectRuntime(temporary)
            if (updating) {
                require(target.renameTo(backup)) {
                    "Unable to stage the existing plugin for update: $pluginId"
                }
            }
            try {
                target.parentFile?.mkdirs()
                require(temporary.renameTo(target)) {
                    "Unable to publish sandbox plugin: $pluginId"
                }
                ensureInstalled(target, manifest)
            } catch (error: Throwable) {
                target.deleteRecursively()
                if (backup.exists()) {
                    require(backup.renameTo(target)) {
                        "Publish failed and the previous plugin could not be restored: $pluginId"
                    }
                }
                throw error
            }
            backup.deleteRecursively()
            buildMap {
                put("pluginId", pluginId)
                put("title", manifest.name)
                val frontend = requireNotNull(manifest.frontend)
                put("entryPath", safeChild(target, frontend.entry).absolutePath)
                put("iconPath", safeChild(target, frontend.icon).absolutePath)
                put("updated", updating)
                put("permissions", manifest.permissions)
                put("skillId", manifest.skillId())
                put("connectorCount", inspection.toolkit.connectors.size)
                put("toolCount", inspection.toolkit.tools.size)
                put("fileCount", inspection.fileCount)
                put("sizeBytes", inspection.sizeBytes)
            }
        } finally {
            temporary.deleteRecursively()
            stagingRoot.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        }
    }

    private fun insert(command: SandboxPluginCommand.Insert): Map<String, Any?> {
        requirePermission(command.pluginId, SandboxProjectPermission.DATABASE)
        SandboxSqlPolicy.requireIdentifier(command.table, "table")
        require(command.values.isNotEmpty()) { "Insert values cannot be empty" }
        command.values.keys.forEach { SandboxSqlPolicy.requireIdentifier(it, "column") }
        val directory = requirePluginDirectory(command.pluginId)
        val manifest = readManifest(directory)
        ensureInstalled(directory, manifest)
        val databaseFile = databaseFile(command.pluginId)
        val rowId = databaseFactory.open(databaseFile).use { database ->
            database.insert(command.table, command.values)
        }
        return mapOf("rowId" to rowId)
    }

    private fun query(command: SandboxPluginCommand.Query): Map<String, Any?> {
        requirePermission(command.pluginId, SandboxProjectPermission.DATABASE)
        SandboxSqlPolicy.requireIdentifier(command.table, "table")
        command.where.keys.forEach { SandboxSqlPolicy.requireIdentifier(it, "where column") }
        SandboxSqlPolicy.validateOrderBy(command.orderBy)
        require(command.limit in 1..MAX_QUERY_LIMIT) {
            "Query limit must be between 1 and $MAX_QUERY_LIMIT"
        }
        val directory = requirePluginDirectory(command.pluginId)
        val manifest = readManifest(directory)
        ensureInstalled(directory, manifest)
        val databaseFile = databaseFile(command.pluginId)
        val rows = databaseFactory.open(databaseFile).use { database ->
            database.query(command.table, command.where, command.orderBy, command.limit)
        }
        return mapOf("rows" to rows, "count" to rows.size)
    }

    private fun update(command: SandboxPluginCommand.Update): Map<String, Any?> {
        requirePermission(command.pluginId, SandboxProjectPermission.DATABASE)
        SandboxSqlPolicy.requireIdentifier(command.table, "table")
        require(command.values.isNotEmpty()) { "Update values cannot be empty" }
        command.values.keys.forEach { SandboxSqlPolicy.requireIdentifier(it, "column") }
        val directory = requirePluginDirectory(command.pluginId)
        val manifest = readManifest(directory)
        ensureInstalled(directory, manifest)
        val databaseFile = databaseFile(command.pluginId)
        val count = databaseFactory.open(databaseFile).use { database ->
            database.update(command.table, command.id, command.values)
        }
        return mapOf("updated" to count)
    }

    private fun delete(command: SandboxPluginCommand.Delete): Map<String, Any?> {
        requirePermission(command.pluginId, SandboxProjectPermission.DATABASE)
        SandboxSqlPolicy.requireIdentifier(command.table, "table")
        val directory = requirePluginDirectory(command.pluginId)
        val manifest = readManifest(directory)
        ensureInstalled(directory, manifest)
        val databaseFile = databaseFile(command.pluginId)
        val count = databaseFactory.open(databaseFile).use { database ->
            database.delete(command.table, command.id)
        }
        return mapOf("deleted" to count)
    }

    private fun inspectProject(
        sourceDirectory: File,
        manifest: SandboxProjectManifest,
    ): ProjectInspection {
        validateManifest(manifest)
        val source = sourceDirectory.canonicalFile
        require(source.isDirectory) { "Project path is not a directory: ${source.absolutePath}" }
        val paths = Files.walk(source.toPath()).use { stream ->
            stream.iterator().asSequence().toList()
        }
        val symbolicLink = paths.firstOrNull(Files::isSymbolicLink)
        require(symbolicLink == null) {
            "Project contains a symbolic link, which cannot be published: $symbolicLink"
        }
        val reservedRuntime = safeChild(source, RUNTIME_ROOT_PATH)
        require(!reservedRuntime.exists()) {
            "$RUNTIME_ROOT_PATH is reserved for the Omni runtime"
        }
        val files = paths.map { it.toFile() }.filter(File::isFile)
        require(files.size <= MAX_PROJECT_FILES) {
            "Project has ${files.size} files; the limit is $MAX_PROJECT_FILES"
        }
        val oversized = files.firstOrNull { it.length() > MAX_PROJECT_FILE_BYTES }
        require(oversized == null) {
            "${oversized?.relativeTo(source)?.path}: file exceeds the $MAX_PROJECT_FILE_BYTES byte limit"
        }
        val totalBytes = files.sumOf(File::length)
        require(totalBytes <= MAX_PROJECT_BYTES) {
            "Project size is $totalBytes bytes; the limit is $MAX_PROJECT_BYTES"
        }
        val entryPath = requireNotNull(manifest.entryPath)
        val entry = safeChild(source, entryPath).also { file ->
            require(file.isFile) { "$entryPath: standalone app entry is missing" }
        }
        val iconPath = requireNotNull(manifest.iconPath)
        val icon = safeChild(source, iconPath).also { file ->
            require(file.isFile) { "$iconPath: standalone app SVG icon is missing" }
            require(file.length() <= MAX_ICON_BYTES) {
                "$iconPath: SVG icon exceeds the $MAX_ICON_BYTES byte limit"
            }
            validateSvgIcon(iconPath, file.readText())
        }
        val schema = manifest.schemaPath?.let { schemaPath ->
            safeChild(source, schemaPath).also { file ->
                require(file.isFile) { "$schemaPath: database schema is missing" }
                require(file.length() <= MAX_SCHEMA_BYTES) {
                    "$schemaPath: schema exceeds the $MAX_SCHEMA_BYTES byte limit"
                }
            }
        }
        val schemaSql = schema?.readText()?.also(SandboxSqlPolicy::validateSchema)
        val skillFile = safeChild(source, manifest.skillPath)
        require(skillFile.isFile) { "${manifest.skillPath}: SKILL.md is missing" }
        require(skillFile.length() <= MAX_SKILL_BYTES) {
            "${manifest.skillPath}: SKILL.md exceeds the $MAX_SKILL_BYTES byte limit"
        }
        val parsedSkill = parseSkillFile(skillFile)
            ?: throw IllegalArgumentException("${manifest.skillPath}: invalid SKILL.md")
        require(parsedSkill.frontmatter["name"]?.trim() == manifest.slug) {
            "${manifest.skillPath}: frontmatter name must equal project slug ${manifest.slug}"
        }
        require(parsedSkill.frontmatter["description"]?.trim().orEmpty().isNotEmpty()) {
            "${manifest.skillPath}: frontmatter description is required"
        }
        val toolkitFile = safeChild(source, manifest.toolkitPath)
        require(toolkitFile.isFile) { "${manifest.toolkitPath}: toolkit definition is missing" }
        require(toolkitFile.length() <= MAX_TOOLKIT_BYTES) {
            "${manifest.toolkitPath}: toolkit exceeds the $MAX_TOOLKIT_BYTES byte limit"
        }
        val toolkit = readToolkit(toolkitFile)
        SandboxProjectToolPolicy.validate(
            pluginId = "$PROJECT_ID_PREFIX${manifest.slug}",
            toolkit = toolkit,
            permissions = manifest.permissions,
            schemaSql = schemaSql,
        )
        validateCapabilityReferences(source, files, manifest.permissions)
        validateDashboardToolReferences(source, files, toolkit)
        return ProjectInspection(
            sourceDirectory = source,
            entryFile = entry,
            iconFile = icon,
            schemaFile = schema,
            schemaSql = schemaSql,
            skillFile = skillFile,
            toolkitFile = toolkitFile,
            toolkit = toolkit,
            fileCount = files.size,
            sizeBytes = totalBytes,
        )
    }

    private fun validateManifest(manifest: SandboxProjectManifest) {
        require(SLUG_PATTERN.matches(manifest.slug)) { "Invalid project slug: ${manifest.slug}" }
        require(manifest.name.trim().length in 1..80) { "Project name must be 1-80 characters" }
        require(manifest.description.trim().length in 1..500) {
            "Project description must be 1-500 characters"
        }
        require(VERSION_PATTERN.matches(manifest.version.trim())) {
            "Invalid project version: ${manifest.version}"
        }
        require(manifest.permissions.size == manifest.permissions.toSet().size) {
            "Project permissions contain duplicates"
        }
        val unsupported = manifest.permissions.firstOrNull {
            it !in SandboxProjectPermission.supported
        }
        require(unsupported == null) { "Unsupported project permission: $unsupported" }
        require(!manifest.entryPath.isNullOrBlank()) {
            "Vibe project requires a standalone app entry"
        }
        val entryPath = requireNotNull(manifest.entryPath)
        safeRelativePath(entryPath, "entry_path")
        require(entryPath.substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS) {
            "entry_path must point to an HTML document"
        }
        require(!manifest.iconPath.isNullOrBlank()) {
            "Vibe project requires a standalone app SVG icon"
        }
        val iconPath = requireNotNull(manifest.iconPath)
        safeRelativePath(iconPath, "icon_path")
        require(iconPath.substringAfterLast('.', "").equals("svg", ignoreCase = true)) {
            "icon_path must point to an SVG document"
        }
        manifest.schemaPath?.let { safeRelativePath(it, "schema_path") }
        require(
            SandboxProjectPermission.DATABASE !in manifest.permissions || manifest.schemaPath != null,
        ) { "database permission requires schema_path" }
        safeRelativePath(manifest.skillPath, "skill_path")
        require(manifest.skillPath.substringAfterLast('/') == "SKILL.md") {
            "skill_path must point to SKILL.md"
        }
        safeRelativePath(manifest.toolkitPath, "toolkit_path")
    }

    private fun validateSvgIcon(iconPath: String, source: String) {
        require(SVG_ROOT_PATTERN.containsMatchIn(source)) {
            "$iconPath: icon must contain an SVG root element"
        }
        require(!UNSAFE_SVG_PATTERN.containsMatchIn(source)) {
            "$iconPath: SVG icon contains unsupported active or external content"
        }
    }

    private fun validateCapabilityReferences(
        rootDirectory: File,
        files: List<File>,
        permissions: List<String>,
    ) {
        val sources = files.filter { it.extension.lowercase() in setOf("html", "htm", "js", "mjs") }
        sources.forEach { source ->
            val text = source.readText()
            if ("omni.app" in text) {
                throw IllegalArgumentException(
                    "${source.relativeTo(rootDirectory).path}: external App bridge is removed; " +
                        "use an MCP/plugin tool instead",
                )
            }
            if (
                ("omni.ai" in text || "omni.xiaowan" in text) &&
                SandboxProjectPermission.XIAOWAN !in permissions &&
                SandboxProjectPermission.AI !in permissions
            ) {
                throw IllegalArgumentException(
                    "${source.relativeTo(rootDirectory).path}: uses Xiaowan without declaring the xiaowan permission",
                )
            }
            if ("omni.db" in text && SandboxProjectPermission.DATABASE !in permissions) {
                throw IllegalArgumentException(
                    "${source.relativeTo(rootDirectory).path}: uses omni.db without declaring the database permission",
                )
            }
        }
    }

    private fun validateDashboardToolReferences(
        rootDirectory: File,
        files: List<File>,
        toolkit: SandboxProjectToolkit,
    ) {
        val declaredTools = toolkit.tools.mapTo(linkedSetOf(), SandboxProjectTool::name)
        files.filter { it.extension.lowercase() in setOf("html", "htm", "js", "mjs") }
            .forEach { source ->
                val text = source.readText()
                val directDatabaseCall = DIRECT_DATABASE_CALL_PATTERN.find(text)
                require(directDatabaseCall == null) {
                    "${source.relativeTo(rootDirectory).path}: Dashboard business data must use " +
                        "window.omni.tools.call with a tool declared in toolkit.json; direct " +
                        "window.omni.db.${directDatabaseCall?.groupValues?.get(1)} calls bypass " +
                        "the shared Link contract"
                }
                DASHBOARD_TOOL_CALL_PATTERN.findAll(text).forEach { match ->
                    val toolName = match.groupValues[1]
                    require(toolName in declaredTools) {
                        "${source.relativeTo(rootDirectory).path}: Dashboard references unknown " +
                            "project tool $toolName"
                    }
                }
            }
    }

    private fun injectRuntime(directory: File) {
        val bridge = safeChild(directory, BRIDGE_PATH)
        writeText(directory, BRIDGE_PATH, BRIDGE_JAVASCRIPT)
        val htmlFiles = Files.walk(directory.toPath()).use { stream ->
            stream.iterator().asSequence()
                .map { it.toFile() }
                .filter(File::isFile)
                .filter { it.extension.equals("html", ignoreCase = true) ||
                    it.extension.equals("htm", ignoreCase = true)
                }
                .toList()
        }
        htmlFiles.forEach { html ->
            val htmlDirectory = requireNotNull(html.parentFile) {
                "HTML document has no parent directory: ${html.path}"
            }
            val bridgeReference = htmlDirectory.toPath()
                .relativize(bridge.toPath())
                .toString()
                .replace(File.separatorChar, '/')
            val source = html.readText()
            val runtimeHead = buildList {
                if (!CSP_PATTERN.containsMatchIn(source)) add(SANDBOX_CSP)
                if (!RUNTIME_SCRIPT_PATTERN.containsMatchIn(source)) {
                    add("<script src=\"$bridgeReference\"></script>")
                }
            }.joinToString("\n")
            if (runtimeHead.isEmpty()) return@forEach
            val headMatch = HTML_HEAD_PATTERN.find(source)
            val linked = when {
                headMatch != null -> source.replaceRange(
                    headMatch.range.last + 1,
                    headMatch.range.last + 1,
                    "\n$runtimeHead",
                )
                else -> {
                    val htmlMatch = HTML_ROOT_PATTERN.find(source)
                    if (htmlMatch != null) {
                        source.replaceRange(
                            htmlMatch.range.last + 1,
                            htmlMatch.range.last + 1,
                            "\n<head>\n$runtimeHead\n</head>",
                        )
                    } else {
                        "<head>\n$runtimeHead\n</head>\n$source"
                    }
                }
            }
            html.writeText(linked)
        }
        writeText(directory, RUNTIME_VERSION_PATH, RUNTIME_VERSION.toString())
    }

    private fun ensureRuntimeCurrent(directory: File) = synchronized(PUBLISH_LOCK) {
        val marker = safeChild(directory, RUNTIME_VERSION_PATH)
        val bridge = safeChild(directory, BRIDGE_PATH)
        if (
            marker.isFile &&
            marker.readText().trim() == RUNTIME_VERSION.toString() &&
            bridge.isFile
        ) {
            return@synchronized
        }
        injectRuntime(directory)
    }

    private fun readManifest(directory: File): SandboxPluginManifest {
        val file = safeChild(directory, MANIFEST_FILE)
        require(file.isFile) { "Sandbox plugin manifest is missing" }
        val manifest = json.decodeFromString<SandboxPluginManifest>(file.readText())
        require(manifest.schemaVersion == 1) {
            "Unsupported sandbox plugin schema: ${manifest.schemaVersion}"
        }
        require(PLUGIN_ID_PATTERN.matches(manifest.id)) { "Invalid sandbox plugin id" }
        require(manifest.visibility in setOf("visible", "hidden")) {
            "Invalid sandbox plugin visibility"
        }
        require(manifest.permissions.all { it in SandboxProjectPermission.supported }) {
            "Sandbox plugin declares an unsupported permission"
        }
        manifest.frontend?.let {
            safeChild(directory, it.entry)
            safeChild(directory, it.icon)
        }
        manifest.database?.let { safeChild(directory, it.schema) }
        manifest.skill?.let { safeChild(directory, it.path) }
        manifest.toolkit?.let { toolkit -> safeChild(directory, toolkit.path) }
        return manifest
    }

    private fun readToolkit(file: File): SandboxProjectToolkit =
        runCatching { json.decodeFromString<SandboxProjectToolkit>(file.readText()) }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "${file.name}: invalid toolkit definition: " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }

    private fun migrateLegacyData(directory: File, pluginId: String) {
        val legacyDatabase = safeChild(directory, LEGACY_DATABASE_PATH)
        val database = databaseFile(pluginId)
        if (!database.exists() && legacyDatabase.isFile) {
            moveFile(legacyDatabase, database)
        }
        val legacyMarker = safeChild(directory, LEGACY_SCHEMA_MARKER_PATH)
        val marker = schemaMarkerFile(pluginId)
        if (!marker.exists() && legacyMarker.isFile) {
            moveFile(legacyMarker, marker)
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            source.copyTo(temporary, overwrite = false)
            require(temporary.renameTo(target)) {
                "Unable to migrate plugin data to ${target.absolutePath}"
            }
            require(source.delete()) {
                "Plugin data migrated but legacy file could not be removed: ${source.absolutePath}"
            }
        } finally {
            temporary.delete()
        }
    }

    private fun requirePluginDirectory(pluginId: String): File {
        val directory = pluginDirectory(pluginId)
        require(directory.isDirectory) { "Unknown sandbox plugin: $pluginId" }
        return directory
    }

    private fun pluginDirectory(pluginId: String): File {
        require(PLUGIN_ID_PATTERN.matches(pluginId)) { "Invalid sandbox plugin id: $pluginId" }
        return safeChild(root, pluginId)
    }

    private fun pluginDataDirectory(pluginId: String): File {
        require(PLUGIN_ID_PATTERN.matches(pluginId)) { "Invalid sandbox plugin id: $pluginId" }
        return safeChild(dataRoot, pluginId)
    }

    private fun databaseFile(pluginId: String): File =
        safeChild(pluginDataDirectory(pluginId), DATABASE_FILE_NAME)

    private fun schemaMarkerFile(pluginId: String): File =
        safeChild(pluginDataDirectory(pluginId), SCHEMA_MARKER_FILE_NAME)

    private fun safeRelativePath(relativePath: String, field: String) {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "$field must be a relative path"
        }
        require(relativePath.replace('\\', '/').split('/').none { it == ".." }) {
            "$field cannot escape the project directory"
        }
    }

    private fun safeChild(parent: File, relativePath: String): File {
        safeRelativePath(relativePath, "Sandbox path")
        val parentCanonical = parent.canonicalFile
        val child = File(parentCanonical, relativePath).canonicalFile
        require(child.path.startsWith(parentCanonical.path + File.separator)) {
            "Sandbox path escapes its plugin directory"
        }
        return child
    }

    private fun copyTree(source: File, target: File) {
        val sourceRoot = source.canonicalFile
        Files.walk(sourceRoot.toPath()).use { stream ->
            stream.iterator().asSequence().toList()
                .sortedBy { it.nameCount }
                .forEach { path ->
                    val relative = sourceRoot.toPath().relativize(path).toString()
                    val destination = if (relative.isBlank()) {
                        target
                    } else {
                        safeChild(target, relative)
                    }
                    if (Files.isDirectory(path)) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        path.toFile().copyTo(destination, overwrite = true)
                    }
                }
        }
    }

    private fun writeText(root: File, relativePath: String, content: String) {
        val target = safeChild(root, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    private fun descriptor(manifest: SandboxPluginManifest): OmniPluginDescriptor =
        OmniPluginDescriptor(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            publisher = manifest.publisher,
            downloadSizeBytes = 0,
            capabilities = manifest.capabilities,
            presentation = buildJsonObject {
                put("visibility", manifest.visibility)
                put("description", buildJsonObject {
                    put("zh", manifest.description)
                    put("en", manifest.description)
                })
            },
        )

    private fun capabilitiesFor(permissions: List<String>): List<String> = buildList {
        add("Xiaowan skill")
        add("Agent-callable business tools")
        if (SandboxProjectPermission.XIAOWAN in permissions ||
            SandboxProjectPermission.AI in permissions
        ) add("Built-in Xiaowan connector")
        if (SandboxProjectPermission.DATABASE in permissions) add("Isolated SQLite data")
        if (SandboxProjectPermission.NETWORK in permissions) add("Read-only public HTTPS data")
    }

    private fun SandboxPluginManifest.skillId(): String = id.removePrefix(PROJECT_ID_PREFIX)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ProjectInspection(
        val sourceDirectory: File,
        val entryFile: File,
        val iconFile: File,
        val schemaFile: File?,
        val schemaSql: String?,
        val skillFile: File,
        val toolkitFile: File,
        val toolkit: SandboxProjectToolkit,
        val fileCount: Int,
        val sizeBytes: Long,
    )

    private class SandboxPluginProvider(
        private val pool: SandboxPluginPool,
        private val directory: File,
        private val manifest: SandboxPluginManifest,
    ) : OmniPluginProvider {
        override val descriptor: OmniPluginDescriptor = pool.descriptor(manifest)

        override suspend fun install() {
            pool.ensureInstalled(directory, manifest)
            pool.ensureSkillInstalled(directory, manifest)
        }

        override suspend fun update() = install()

        override suspend fun uninstall() = pool.remove(directory, manifest)

        override fun create(): OmniPlugin {
            val toolkit = manifest.toolkit?.let { specification ->
                val schemaSql = manifest.database?.let { database ->
                    pool.safeChild(directory, database.schema).readText()
                }
                pool.readToolkit(pool.safeChild(directory, specification.path)).also { definition ->
                    SandboxProjectToolPolicy.validate(
                        pluginId = manifest.id,
                        toolkit = definition,
                        permissions = manifest.permissions,
                        schemaSql = schemaSql,
                    )
                }
            } ?: return object : OmniPlugin {}
            return object : OmniPlugin {
                override fun contribution(): OmniPluginContribution = OmniPluginContribution(
                    toolGroups = listOf(
                        OmniPluginToolGroup(
                            definitions = toolkit.tools.map { tool -> tool.definition(manifest.id) },
                            handlerFactory = {
                                SandboxProjectToolHandler(
                                    pool = pool,
                                    pluginId = manifest.id,
                                    toolkit = toolkit,
                                )
                            },
                        ),
                    ),
                )

                override suspend fun onEnable() {
                    pool.ensureSkillInstalled(directory, manifest)
                    pool.setSkillEnabled(manifest, true)
                }

                override suspend fun onDisable() = pool.setSkillEnabled(manifest, false)
            }
        }
    }

    private companion object {
        const val USER_POOL_PATH = "plugin-pool/user"
        const val USER_DATA_PATH = "plugin-data"
        const val RUNTIME_ROOT_PATH = ".omni"
        const val MANIFEST_FILE = "$RUNTIME_ROOT_PATH/plugin.json"
        const val BRIDGE_PATH = "$RUNTIME_ROOT_PATH/bridge.js"
        const val RUNTIME_VERSION_PATH = "$RUNTIME_ROOT_PATH/runtime.version"
        const val RUNTIME_VERSION = 4
        const val LEGACY_DATABASE_PATH = "$RUNTIME_ROOT_PATH/data/project.db"
        const val LEGACY_SCHEMA_MARKER_PATH = "$RUNTIME_ROOT_PATH/data/.schema-sha256"
        const val DATABASE_FILE_NAME = "project.db"
        const val SCHEMA_MARKER_FILE_NAME = ".schema-sha256"
        const val PROJECT_ID_PREFIX = "local.project."
        const val MAX_PROJECT_FILES = 2_048
        const val MAX_PROJECT_FILE_BYTES = 4 * 1024 * 1024L
        const val MAX_PROJECT_BYTES = 16 * 1024 * 1024L
        const val MAX_SCHEMA_BYTES = 512 * 1024L
        const val MAX_ICON_BYTES = 256 * 1024L
        const val MAX_SKILL_BYTES = 512 * 1024L
        const val MAX_TOOLKIT_BYTES = 512 * 1024L
        const val MAX_QUERY_LIMIT = 500
        val SLUG_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val VERSION_PATTERN = Regex("^[0-9]+(?:\\.[0-9]+){0,2}(?:[-+][A-Za-z0-9.-]+)?$")
        val PLUGIN_ID_PATTERN = Regex("^local\\.project\\.[a-z0-9]+(?:-[a-z0-9]+)*$")
        val PUBLISH_LOCK = Any()
        val HTML_EXTENSIONS = setOf("html", "htm")
        val SVG_ROOT_PATTERN = Regex("(?is)<svg\\b")
        val UNSAFE_SVG_PATTERN = Regex(
            "(?is)<!DOCTYPE|<!ENTITY|<script\\b|<foreignObject\\b|" +
                "(?:href|xlink:href)\\s*=\\s*['\"]\\s*(?:https?:|file:|content:|javascript:)",
        )
        val HTML_HEAD_PATTERN = Regex("(?i)<head(?:\\s[^>]*)?>")
        val HTML_ROOT_PATTERN = Regex("(?i)<html(?:\\s[^>]*)?>")
        val CSP_PATTERN = Regex(
            "(?is)<meta\\b[^>]*http-equiv\\s*=\\s*['\"]Content-Security-Policy['\"][^>]*>",
        )
        val RUNTIME_SCRIPT_PATTERN = Regex(
            "(?is)<script\\b[^>]*src\\s*=\\s*['\"][^'\"]*\\.omni/bridge\\.js['\"][^>]*>",
        )
        val DIRECT_DATABASE_CALL_PATTERN = Regex(
            "(?is)\\b(?:window\\s*\\.\\s*)?omni\\s*\\.\\s*db\\s*\\.\\s*" +
                "(insert|query|update|delete)\\s*\\(",
        )
        val DASHBOARD_TOOL_CALL_PATTERN = Regex(
            "(?is)\\b(?:window\\s*\\.\\s*)?omni\\s*\\.\\s*tools\\s*\\.\\s*call" +
                "\\s*\\(\\s*['\"]([a-z][a-z0-9_]{1,39})['\"]",
        )
        const val SANDBOX_CSP = "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'self' data: blob:; connect-src 'none'; " +
            "script-src 'self' 'unsafe-inline' blob:; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; font-src 'self' data:; " +
            "media-src 'self' data: blob:; worker-src 'none'; object-src 'none'; " +
            "frame-src 'none'; base-uri 'none'; form-action 'none'\">"
        val BRIDGE_JAVASCRIPT = """
            (() => {
              Object.defineProperty(window, '__omniRuntimeVersion', { value: $RUNTIME_VERSION });
              const pending = new Map();
              let sequence = 0;
              const call = (method, params) => new Promise((resolve, reject) => {
                const id = `${'$'}{Date.now()}-${'$'}{++sequence}`;
                pending.set(id, { resolve, reject });
                OmniSandboxBridge.postMessage(JSON.stringify({ id, method, params }));
              });
              window.__omniSandboxResolve = (response) => {
                const request = pending.get(response.id);
                if (!request) return;
                pending.delete(response.id);
                if (response.ok) request.resolve(response.result);
                else request.reject(new Error(response.error || 'Sandbox bridge failed'));
              };
              window.omni = Object.freeze({
                tools: Object.freeze({
                  call: (name, arguments = {}) => {
                    if (typeof name !== 'string' || !name.trim()) {
                      throw new TypeError('tools.call requires a project tool name');
                    }
                    if (!arguments || typeof arguments !== 'object' || Array.isArray(arguments)) {
                      throw new TypeError('tools.call arguments must be an object');
                    }
                    return call('tool.call', { name: name.trim(), arguments });
                  },
                }),
                db: Object.freeze({
                  insert: (table, values) => call('db.insert', { table, values }),
                  query: (table, options = {}) => call('db.query', {
                    table,
                    where: options.where || {},
                    orderBy: options.orderBy || null,
                    limit: options.limit || 100,
                  }),
                  update: (table, id, values) => call('db.update', { table, id, values }),
                  delete: (table, id) => call('db.delete', { table, id }),
                }),
                ai: Object.freeze({
                  generate: (options) => call('ai.generate', typeof options === 'string'
                    ? { prompt: options }
                    : options),
                }),
                xiaowan: Object.freeze({
                  invoke: (options) => call('ai.generate', typeof options === 'string'
                    ? { prompt: options }
                    : options),
                }),
              });
            })();
        """.trimIndent()
    }
}

internal object SandboxSqlPolicy {
    private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    private val tableStatement = Regex("(?is)^CREATE\\s+TABLE\\b")
    private val createdTable = Regex(
        "(?is)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" +
            "[`\\\"\\[]?([A-Za-z_][A-Za-z0-9_]{0,63})",
    )
    private val unsafeDatabaseAccess = Regex(
        "(?i)\\b(?:ATTACH|DETACH|load_extension|writable_schema)\\b|\\bVACUUM\\s+INTO\\b",
    )
    private val orderByPart = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}(?:\\s+(?:ASC|DESC))?$", RegexOption.IGNORE_CASE)

    fun validateSchema(schemaSql: String) {
        val statements = statements(schemaSql)
        require(statements.isNotEmpty()) { "SQL schema cannot be empty" }
        require(statements.any(tableStatement::containsMatchIn)) {
            "SQL schema must create at least one table"
        }
        require(!unsafeDatabaseAccess.containsMatchIn(schemaSql)) {
            "SQL schema attempts to access data outside the plugin database"
        }
    }

    fun statements(schemaSql: String): List<String> = schemaSql
        .split(';')
        .map { it.replace(Regex("(?m)^\\s*--.*$"), "").trim() }
        .filter(String::isNotEmpty)

    fun createdTables(schemaSql: String): Set<String> = createdTable.findAll(schemaSql)
        .map { match -> match.groupValues[1] }
        .toSet()

    fun requireIdentifier(value: String, field: String) {
        require(identifier.matches(value)) { "Invalid $field identifier: $value" }
    }

    fun validateOrderBy(value: String?) {
        value?.trim()?.takeIf(String::isNotEmpty)?.split(',')?.forEach { part ->
            require(orderByPart.matches(part.trim())) { "Invalid orderBy expression: $value" }
        }
    }
}
