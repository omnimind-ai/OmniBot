package cn.com.omnimind.bot.preferences

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.loader.cat.PetSpriteAtlasSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Shared owner for pet selection, package discovery and the existing overlay runtime. */
internal class PetAppearanceRepository private constructor(context: Context) {
    private val application = context.applicationContext
    private val workspace = AgentWorkspaceManager(application)
    private val workspaceRoot by lazy { File(AgentWorkspaceManager.androidRootPath(application)).canonicalFile }
    private val petsRoot by lazy {
        val existing = File(workspaceRoot, ".omnibot/pets")
        if (existing.isDirectory) PetPackageInstaller().recoverInterruptedInstall(existing)
        workspace.petsRoot().canonicalFile
    }
    private val previews by lazy { PetPreviewRenderer(workspaceRoot) }
    private val native = application.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
    private val flutter = application.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    init { DraggableBallInstance.initialize(application) }

    suspend fun state(): PetAppearanceState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = stateLocked()
            val option = snapshot.options.firstOrNull { it.id == snapshot.selectedId && !it.builtIn }
            if (option != null && snapshot.selectedPath.isNotBlank() &&
                sameFile(snapshot.selectedPath, option.sourcePath) &&
                !sameFile(snapshot.selectedPath, option.playbackPath)) {
                // Existing SVG/HTML selections become renderable PNGs without changing pet identity.
                commitSelection(option)
                stateLocked(snapshot.options)
            } else snapshot
        }
    }

    suspend fun select(id: String): PetAppearanceState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val options = discover()
            val choice = options.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Pet is no longer available")
            commitSelection(choice)
            stateLocked(options)
        }
    }

    suspend fun import(uri: Uri): PetAppearanceState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val input = application.contentResolver.openInputStream(uri)
                ?: throw PetPackageException("Unable to open pet package")
            installAndSelect(input)
        }
    }

    suspend fun import(path: String): PetAppearanceState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = File(path)
            require(source.isFile && source.canRead()) { "Pet package does not exist" }
            installAndSelect(source.inputStream())
        }
    }

    private suspend fun installAndSelect(input: java.io.InputStream): PetAppearanceState {
        val installed = input.use { PetPackageInstaller().install(it, petsRoot) }
        val choice = optionForFile(installed.atlas) ?: throw PetPackageException("Imported pet is not discoverable")
        commitSelection(choice)
        return stateLocked()
    }

    private suspend fun commitSelection(choice: PetAppearanceOption) {
        val oldPath = selectedPath()
        val oldId = selectedId()
        val path = if (choice.builtIn) "" else choice.playbackPath
        check(native.edit().putString(KEY_PATH, path).putString(KEY_ID, choice.id).commit()) {
            "Unable to save pet selection"
        }
        if (!flutter.edit().putString(FLUTTER_PATH, path).putString(FLUTTER_ID, choice.id).commit()) {
            native.edit().putString(KEY_PATH, oldPath).putString(KEY_ID, oldId).commit()
            flutter.edit().putString(FLUTTER_PATH, oldPath).putString(FLUTTER_ID, oldId).commit()
            error("Unable to mirror pet selection to Flutter")
        }
        withContext(Dispatchers.Main) {
            runCatching { DraggableBallInstance.refreshPetAppearance() }
                .onFailure { OmniLog.w("PetAppearance", "Unable to refresh the live pet overlay", it) }
        }
    }

    private fun stateLocked(existing: List<PetAppearanceOption>? = null): PetAppearanceState {
        val options = existing ?: discover()
        val path = selectedPath()
        val storedId = selectedId()
        val found = if (path.isBlank()) options.firstOrNull { it.id == BUILTIN_ID }
            else options.firstOrNull { !it.builtIn &&
                (sameFile(path, it.playbackPath) || sameFile(path, it.sourcePath)) }
        val missing = if (path.isNotBlank() && found == null) {
            val english = AppLocaleManager.isEnglish(application)
            PetAppearanceOption(storedId.takeIf { it.isNotBlank() && it != BUILTIN_ID }
                ?: "custom:missing:${path.hashCode()}",
                if (english) "Missing selected pet" else "已选桌宠文件不可用",
                if (english) "Choose another pet to restore the overlay"
                else "请选择其他桌宠以恢复悬浮窗", "", path, false, "", path)
        } else null
        val visibleOptions = if (missing == null) options else options + missing
        return PetAppearanceState(
            options = visibleOptions, selectedId = found?.id ?: missing?.id ?: BUILTIN_ID,
            selectedPath = path,
            workspaceRootPath = workspaceRoot.absolutePath,
            petsDirectoryPath = petsRoot.absolutePath,
            showing = DraggableBallInstance.isShowing(),
            visiblePreference = native.getBoolean(KEY_VISIBLE, false),
        )
    }

    private fun discover(): List<PetAppearanceOption> {
        val choices = mutableListOf(PetAppearanceOption(BUILTIN_ID, "小万", "默认的桌面悬浮窗宠物", "", "", true, "", ""))
        val roots = listOf(petsRoot, File(workspaceRoot, "pets")).filter { it.isDirectory && insideWorkspace(it) }
        val discovered = LinkedHashMap<String, File>()
        for (root in roots) {
            val entries = root.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.take(MAX_SCAN_ENTRIES).orEmpty()
            preferredFromMetadata(root)?.let { consider(discovered, "current", it) }
            preferredFromFiles(root)?.let { consider(discovered, "current", it) }
            for (entry in entries) {
                if (!insideWorkspace(entry) || entry.name.startsWith('.')) continue
                when {
                    entry.isDirectory -> firstInDirectory(entry)?.let { consider(discovered,
                        "dir:${entry.name.lowercase(Locale.ROOT)}", it) }
                    entry.isFile && !isGenerated(entry) && !isAtlasOrActionFrame(entry) -> {
                        if (entry.name.substringBeforeLast('.').equals("current", true)) continue
                        if (previews.preview(entry) != null) consider(discovered, "loose:${identityName(entry)}", entry)
                    }
                }
            }
        }
        val selected = selectedPath()
        if (selected.isNotBlank()) {
            val file = File(selected)
            if (previews.usableImage(file) && discovered.values.none { sameFile(it.path, selected) }) {
                consider(discovered, "selected:${file.canonicalPath}", file)
            }
        }
        val ordered = discovered.values.distinctBy { it.canonicalPath }
            .sortedWith(compareBy<File>({ sortTimestamp(it) }, { it.name.lowercase(Locale.ROOT) }))
        ordered.mapNotNullTo(choices, ::optionForFile)
        return choices
    }

    private fun firstInDirectory(dir: File): File? {
        preferredFromMetadata(dir)?.let { return it }
        preferredFromFiles(dir)?.let { return it }
        val entries = dir.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }?.take(MAX_SCAN_ENTRIES).orEmpty()
        for (file in entries) {
            if (!file.isFile || isGenerated(file) || isAtlasOrActionFrame(file) && !previews.isAtlas(file)) continue
            if (previews.preview(file) != null) return file
        }
        val html = entries.filter { it.isFile && it.extension.equals("html", true) }
            .sortedBy { if (it.name == "index.html") 0 else if (it.name == "preview.html") 1 else 2 }
        return html.firstOrNull { previews.previewHtml(it) != null }
    }

    private fun preferredFromFiles(dir: File): File? {
        for (name in PREFERRED_NAMES) {
            val file = File(dir, name)
            if (previews.preview(file) != null) return file
        }
        return null
    }

    private fun preferredFromMetadata(dir: File): File? {
        val metadata = readJson(File(dir, "pet.json")) ?: return null
        val keys = listOf("imagePath", "image_path", "previewPath", "preview_path", "preview",
            "iconPath", "icon_path", "spritesheetPath", "spritesheet_path", "atlasPath", "atlas_path")
        for (key in keys) {
            val source = resolveMetadataPath(metadata.optString(key), dir) ?: continue
            if (previews.preview(source) != null) return source
        }
        return null
    }

    private fun resolveMetadataPath(raw: String, base: File): File? {
        val path = Uri.decode(raw.trim().substringBefore('#').substringBefore('?'))
        if (path.isBlank() || path.startsWith("http:", true) || path.startsWith("https:", true)
            || path.startsWith("data:", true)) return null
        val resolved = when {
            path == "/workspace" -> workspaceRoot
            path.startsWith("/workspace/") -> File(workspaceRoot, path.removePrefix("/workspace/"))
            path.startsWith("file://") -> File(Uri.parse(path).path ?: return null)
            File(path).isAbsolute -> File(path)
            else -> File(base, path)
        }
        return resolved.takeIf { insideWorkspace(it) && it.isFile }
    }

    private fun optionForFile(source: File): PetAppearanceOption? {
        val preview = previews.preview(source) ?: return null
        val needsRaster = source.extension.equals("html", true) || source.extension.equals("htm", true) ||
            source.extension.equals("svg", true)
        val playback = if (needsRaster) preview else source
        val legacyPlayback = if (needsRaster && preview.name.endsWith(".omnibot-preview.png"))
            File(preview.path.removeSuffix(".omnibot-preview.png")) else source
        val metadata = readMetadata(source)
        val name = firstText(metadata, "displayName", "display_name", "name") ?: displayName(source)
        val description = metadataDescription(metadata) ?: "$name，适合桌面悬浮的自定义电子宠物"
        val version = if (previews.isAtlas(source)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            PetSpriteAtlasSpec.detect(bounds.outWidth, bounds.outHeight)
        } else null
        val label = if (version != null) "Codex 动态宠物 · 7 类动作" else ""
        val id = if (insideWorkspace(source)) "custom:${source.canonicalFile.relativeTo(workspaceRoot).invariantSeparatorsPath}"
            else "custom:${source.canonicalPath}"
        return PetAppearanceOption(id, name, description, preview.absolutePath,
            playback.absolutePath, false, label, legacyPlayback.absolutePath)
    }

    private fun readMetadata(source: File): Map<String, String> {
        val candidates = listOf(File(source.parentFile, "pet.json"),
            File(source.parentFile, source.nameWithoutExtension + ".json"))
        for (file in candidates) {
            val json = readJson(file) ?: continue
            return json.keys().asSequence().associateWith { json.opt(it)?.toString().orEmpty() }
        }
        for (file in listOf(File(source.parentFile, source.nameWithoutExtension + "_readme.md"),
            File(source.parentFile, source.nameWithoutExtension + ".md"), File(source.parentFile, "README.md"))) {
            if (!file.isFile || file.length() > MAX_METADATA_BYTES) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val values = linkedMapOf<String, String>()
            for (line in lines) {
                val match = Regex("^\\s*[-*#]*\\s*(名称|名字|name|简介|描述|description)\\s*[:：]\\s*(.+)$",
                    RegexOption.IGNORE_CASE).find(line) ?: continue
                values[match.groupValues[1].lowercase(Locale.ROOT)] = match.groupValues[2].trim()
            }
            if (values.isNotEmpty()) return values
        }
        return emptyMap()
    }

    private fun metadataDescription(values: Map<String, String>): String? {
        val parts = listOf("petType", "pet_type", "type", "visualStyle", "visual_style", "style",
            "personality", "personalitySetting", "personality_setting")
            .mapNotNull { values[it]?.trim()?.takeIf(String::isNotEmpty) }
        val source = if (parts.isNotEmpty()) parts.joinToString("，") else
            firstText(values, "description", "summary", "简介", "描述") ?: return null
        val compact = source.replace('\n', ' ').split(Regex("[，,；;。.]"))
            .filter(String::isNotBlank).take(3).joinToString("，")
        return compact.take(28).trim().trimEnd('，', ',', ';', '；', '。', '.').let { if (it.isEmpty()) null else "$it。" }
    }

    private fun firstText(values: Map<String, String>, vararg keys: String): String? {
        for (key in keys) {
            values[key]?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
        }
        return null
    }

    private fun displayName(file: File): String {
        val parent = file.parentFile
        val raw = if (parent == petsRoot || parent == File(workspaceRoot, "pets")) {
            if (file.nameWithoutExtension == "current") "自定义宠物" else identityName(file)
        } else parent?.name ?: identityName(file)
        return raw.split(Regex("[-_]+|\\s+")).filter(String::isNotBlank)
            .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun identityName(file: File): String = file.nameWithoutExtension
        .replace(Regex("[-_](full|preview|current)$", RegexOption.IGNORE_CASE), "")
        .lowercase(Locale.ROOT)

    private fun consider(map: MutableMap<String, File>, key: String, file: File) {
        val current = map[key]
        if (current == null || candidateRank(file) < candidateRank(current)) map[key] = file
    }

    private fun candidateRank(file: File): Int {
        val name = file.nameWithoutExtension.lowercase(Locale.ROOT)
        return when {
            name == "current" && file.path.contains("/.omnibot/pets/") -> 0
            name.endsWith("_full") || name.endsWith("-full") -> 1
            name == "current" -> 2
            else -> 3
        }
    }

    private fun sortTimestamp(file: File): Long = listOf(file, file.parentFile, File(file.parentFile, "pet.json"))
        .filterNotNull().mapNotNull { runCatching { it.lastModified() }.getOrNull() }.maxOrNull() ?: 0L

    private fun readJson(file: File): JSONObject? = if (!file.isFile || file.length() > MAX_METADATA_BYTES) null
        else runCatching { JSONObject(file.readText()) }.getOrNull()

    private fun selectedPath(): String = native.getString(KEY_PATH, null)
        ?: flutter.getString(FLUTTER_PATH, "").orEmpty()

    private fun selectedId(): String = native.getString(KEY_ID, null)
        ?: flutter.getString(FLUTTER_ID, BUILTIN_ID).orEmpty()

    private fun insideWorkspace(file: File): Boolean = runCatching {
        val path = file.canonicalPath
        path == workspaceRoot.path || path.startsWith(workspaceRoot.path + File.separator)
    }.getOrDefault(false)

    private fun sameFile(a: String, b: String): Boolean = if (a.isBlank() || b.isBlank()) false
        else runCatching { File(a).canonicalFile == File(b).canonicalFile }.getOrDefault(false)

    private fun isGenerated(file: File): Boolean = file.name.endsWith(".omnibot-preview.png", true) ||
        file.name.endsWith(".omnibot-preview.svg", true)

    private fun isAtlasOrActionFrame(file: File): Boolean = previews.isAtlas(file) ||
        Regex("[-_](idle|working|thinking|waiting|done|sleeping)$", RegexOption.IGNORE_CASE)
            .containsMatchIn(file.nameWithoutExtension)

    companion object {
        private const val BUILTIN_ID = "builtin:xiaowan"
        private const val KEY_PATH = "pet_overlay_image_path"
        private const val KEY_ID = "pet_overlay_selected_id"
        private const val KEY_VISIBLE = "pet_overlay_visible"
        private const val FLUTTER_PATH = "flutter.pet_overlay_image_path"
        private const val FLUTTER_ID = "flutter.pet_overlay_selected_id"
        private const val MAX_SCAN_ENTRIES = 512
        private const val MAX_METADATA_BYTES = 2L * 1024 * 1024
        private val PREFERRED_NAMES = listOf("current.webp", "current.png", "current.jpg", "current.gif", "current.svg",
            "pet.webp", "pet.png", "pet.jpg", "pet.gif", "pet.svg")
        @Volatile private var instance: PetAppearanceRepository? = null
        fun get(context: Context): PetAppearanceRepository = instance ?: synchronized(this) {
            instance ?: PetAppearanceRepository(context).also { instance = it }
        }
    }
}

internal data class PetAppearanceOption(val id: String, val name: String, val description: String,
    val previewPath: String, val playbackPath: String, val builtIn: Boolean,
    val animationLabel: String, val sourcePath: String) {
    fun toMap(): Map<String, Any> = mapOf("id" to id, "name" to name, "description" to description,
        "imagePath" to previewPath, "playbackPath" to playbackPath,
        "isBuiltin" to builtIn, "animationLabel" to animationLabel)
}

internal data class PetAppearanceState(val options: List<PetAppearanceOption>, val selectedId: String,
    val selectedPath: String, val workspaceRootPath: String, val petsDirectoryPath: String,
    val showing: Boolean, val visiblePreference: Boolean) {
    fun toMap(): Map<String, Any> = mapOf(
        "options" to options.map(PetAppearanceOption::toMap),
        "selectedId" to selectedId, "selectedPath" to selectedPath,
        "workspaceRootPath" to workspaceRootPath, "petsDirectoryPath" to petsDirectoryPath,
        "shellWorkspaceRootPath" to AgentWorkspaceManager.SHELL_ROOT_PATH,
        "showing" to showing, "visiblePreference" to visiblePreference,
    )
}
