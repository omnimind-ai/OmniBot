package cn.com.omnimind.bot.plugin.runtime

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexEntry
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

private const val INSTALL_MANAGER_BUNDLED = "bundled"

data class RuntimeSkillSpec(
    val componentId: String,
    val componentVersion: String,
    val id: String,
    val packagedAssetPath: String? = null,
    val packagedArchivePath: String? = null,
    val packagedArchiveSha256: String? = null,
    val markerFile: String = "PACKAGED_RUNTIME_SKILL",
    val componentArchiveUrl: String? = null,
    val componentArchiveSha256: String? = null,
    val installTimeoutSeconds: Int = 15 * 60,
) {
    internal fun validated(): RuntimeSkillSpec {
        require(componentId.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$"))) {
            "Invalid runtime component id: $componentId"
        }
        require(componentVersion.matches(SEMVER)) {
            "Invalid runtime component version: $componentVersion"
        }
        require(id.matches(Regex("^[a-z0-9][a-z0-9-]*$"))) {
            "Invalid runtime skill id: $id"
        }
        packagedAssetPath?.let { requireSafeRelativePath(it, "packagedAssetPath") }
        packagedArchivePath?.let { requireSafeRelativePath(it, "packagedArchivePath") }
        requireSafeRelativePath(markerFile, "markerFile")
        require(componentArchiveUrl.isNullOrBlank() == componentArchiveSha256.isNullOrBlank()) {
            "Runtime skill component archive URL and SHA-256 must be configured together"
        }
        require(packagedAssetPath.isNullOrBlank() || packagedArchivePath.isNullOrBlank()) {
            "Runtime skill cannot declare both a packaged directory and archive"
        }
        require(
            packagedArchivePath.isNullOrBlank() ||
                !packagedArchiveChecksum().isNullOrBlank()
        ) {
            "Runtime skill packaged archive SHA-256 is required"
        }
        packagedArchiveSha256?.let { digest ->
            require(digest.matches(Regex("^[a-f0-9]{64}$"))) {
                "Runtime skill packaged archive SHA-256 is invalid"
            }
        }
        require(
            !packagedAssetPath.isNullOrBlank() ||
                !packagedArchivePath.isNullOrBlank() ||
                !componentArchiveUrl.isNullOrBlank()
        ) {
            "Runtime skill must declare a packaged source or component archive"
        }
        componentArchiveUrl?.let { url ->
            require(url.startsWith("https://")) {
                "Runtime skill component archive URL must use HTTPS"
            }
        }
        componentArchiveSha256?.let { digest ->
            require(digest.matches(Regex("^[a-f0-9]{64}$"))) {
                "Runtime skill component archive SHA-256 is invalid"
            }
        }
        require(installTimeoutSeconds in 1..3600) {
            "Invalid runtime install timeout: $installTimeoutSeconds"
        }
        return this
    }

    private companion object {
        val SEMVER = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$")
    }

    private fun requireSafeRelativePath(value: String, field: String) {
        require(value.isNotBlank() && !value.startsWith('/')) {
            "Runtime skill $field must be relative"
        }
        require(value.replace('\\', '/').split('/').none { it == ".." }) {
            "Runtime skill $field cannot escape its root"
        }
    }

    internal fun packagedArchiveChecksum(): String? =
        packagedArchiveSha256 ?: componentArchiveSha256
}

data class RuntimeSkillLocation(
    val androidRoot: File,
    val shellRoot: String,
    val source: String,
    val staged: Boolean = false,
)

internal fun packagedRuntimeSkillNeedsReplacement(
    refresh: Boolean,
    installedMarker: String?,
    packagedMarker: String,
): Boolean = refresh || installedMarker != packagedMarker

class RuntimeSkillBundleManager(
    context: Context,
    private val spec: RuntimeSkillSpec,
    private val allowPackagedFallback: Boolean = true,
    private val preferPackagedFallback: Boolean = false,
) {
    private val tag = "[RuntimeSkillBundleManager:${spec.id}]"
    private val appContext = context.applicationContext

    fun allowsPackagedFallback(): Boolean =
        allowPackagedFallback && (
            !spec.packagedAssetPath.isNullOrBlank() ||
                !spec.packagedArchivePath.isNullOrBlank()
            )

    suspend fun resolve(refresh: Boolean): RuntimeSkillLocation {
        val startedAt = SystemClock.elapsedRealtime()
        OmniLog.i(tag, "resolve_start refresh=$refresh")
        if (preferPackagedFallback) {
            OmniLog.i(tag, "resolve_packaged_preferred")
            return resolvePackaged(refresh)
        }
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        var candidates = installedCandidates(skills)
        val currentMarket = candidates.firstOrNull(::isCompleteMarketCandidate)
        if (currentMarket != null) {
            if (!currentMarket.enabled) skills.setSkillEnabled(currentMarket.id, true)
            return location(currentMarket, startedAt)
        }
        if (!refresh && allowsPackagedFallback()) {
            return resolvePackaged(refresh = false)
        }

        val downloaded = runCatching {
            stageDownloadedComponent(workspace)
        }.getOrElse { error ->
            OmniLog.e(
                tag,
                "component_install_failed: ${error.message ?: error.javaClass.simpleName}",
                error,
            )
            if (!allowsPackagedFallback()) throw error
            OmniLog.w(tag, "component_download_failed; using packaged fallback: ${error.message}")
            null
        }
        if (downloaded != null) {
            return downloaded.also {
                OmniLog.i(
                    tag,
                    "resolve_staged durationMs=${SystemClock.elapsedRealtime() - startedAt}",
                )
            }
        }

        if (!allowsPackagedFallback()) {
            error("runtime_skill_component_download_required:${spec.id}")
        }

        val packagedMarker = packagedMarker()
        if (removeOutdatedPackagedCandidates(skills, candidates, refresh, packagedMarker)) {
            candidates = installedCandidates(skills)
        }
        val selected = packagedCandidate(candidates)
            ?: candidates
                .let(::preferredCandidate)
            ?: installPackaged(skills, packagedMarker)
        if (!selected.enabled) {
            skills.setSkillEnabled(selected.id, true)
        }
        return location(selected, startedAt)
    }

    fun resolvePackaged(refresh: Boolean): RuntimeSkillLocation {
        require(allowsPackagedFallback()) {
            "runtime_skill_packaged_fallback_disabled:${spec.id}"
        }
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val packagedMarker = packagedMarker()
        removeOutdatedPackagedCandidates(
            skills = skills,
            candidates = installedCandidates(skills),
            refresh = refresh,
            packagedMarker = packagedMarker,
        )
        val selected = packagedCandidate(installedCandidates(skills)) ?: installPackaged(
            skills = skills,
            packagedMarker = packagedMarker,
        )
        if (!selected.enabled) {
            skills.setSkillEnabled(selected.id, true)
        }
        return RuntimeSkillLocation(
            androidRoot = File(selected.rootPath).canonicalFile,
            shellRoot = selected.shellRootPath,
            source = selected.source,
        )
    }

    fun setEnabled(enabled: Boolean) {
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        val entry = preferredCandidate(installedCandidates(skills))
            ?: if (enabled && allowsPackagedFallback()) installPackaged(skills) else return
        if (entry.enabled != enabled) {
            skills.setSkillEnabled(entry.id, enabled)
        }
    }

    suspend fun bootstrap(location: RuntimeSkillLocation): RuntimeSkillLocation {
        val startedAt = SystemClock.elapsedRealtime()
        OmniLog.i(tag, "install_start source=${location.source}")
        val install = readRuntimeComponentInstall(
            root = location.androidRoot,
            expectedComponentId = spec.componentId,
            expectedComponentVersion = spec.componentVersion,
            expectedSkillId = spec.id,
        )
        if (install.manager == INSTALL_MANAGER_BUNDLED) {
            return commitPreparedLocation(location, startedAt)
        }
        error("runtime_component_install_manager_unsupported:${spec.id}")
    }

    private fun commitPreparedLocation(
        location: RuntimeSkillLocation,
        startedAt: Long,
    ): RuntimeSkillLocation {
        val ready = if (location.staged) {
            val workspace = AgentWorkspaceManager(appContext)
            val skills = SkillIndexService(appContext, workspace)
            installedCandidates(skills)
                .filter { File(it.rootPath).canonicalFile != location.androidRoot.canonicalFile }
                .forEach { previous ->
                    require(skills.deleteSkillInstallation(previous.rootPath)) {
                        "runtime_skill_previous_cleanup_failed:${spec.id}"
                    }
                }
            RuntimeSkillLocation(
                androidRoot = location.androidRoot.canonicalFile,
                shellRoot = location.shellRoot,
                source = "market",
            )
        } else {
            location
        }
        OmniLog.i(
            tag,
            "install_ready durationMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        return ready
    }

    fun reclaim() {
        val workspace = AgentWorkspaceManager(appContext)
        val skills = SkillIndexService(appContext, workspace)
        installedCandidates(skills).forEach { entry ->
            require(skills.deleteSkillInstallation(entry.rootPath)) {
                "runtime_skill_delete_failed:${spec.id}"
            }
        }
    }

    private fun installedCandidates(skills: SkillIndexService): List<SkillIndexEntry> =
        skills.listSkillsForManagement().filter { it.id == spec.id && it.installed }

    private fun location(
        selected: SkillIndexEntry,
        startedAt: Long,
    ): RuntimeSkillLocation = RuntimeSkillLocation(
        androidRoot = File(selected.rootPath).canonicalFile,
        shellRoot = selected.shellRootPath,
        source = selected.source,
    ).also {
        OmniLog.i(
            tag,
            "resolve_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                "source=${it.source}",
        )
    }

    private fun preferredCandidate(candidates: List<SkillIndexEntry>): SkillIndexEntry? =
        candidates.minByOrNull { candidate ->
            when {
                isCompleteMarketCandidate(candidate) -> 0
                !isPackaged(candidate.rootPath) -> 1
                else -> 2
            }
        }

    private fun packagedCandidate(candidates: List<SkillIndexEntry>): SkillIndexEntry? =
        candidates.firstOrNull { candidate -> isPackaged(candidate.rootPath) }

    private fun isCompleteMarketCandidate(candidate: SkillIndexEntry): Boolean {
        val root = File(candidate.rootPath)
        return File(root, MARKET_MARKER).takeIf(File::isFile)?.readText()?.trim() ==
            spec.componentArchiveSha256 && runCatching {
            val install = readRuntimeComponentInstall(
                root,
                spec.componentId,
                spec.componentVersion,
                spec.id,
            )
            install.manager == INSTALL_MANAGER_BUNDLED
        }.isSuccess
    }

    private suspend fun stageDownloadedComponent(
        workspace: AgentWorkspaceManager,
    ): RuntimeSkillLocation {
        val url = spec.componentArchiveUrl
            ?.takeIf(String::isNotBlank)
            ?: error("runtime_skill_component_url_missing:${spec.id}")
        val expectedSha256 = spec.componentArchiveSha256
            ?.takeIf(String::isNotBlank)
            ?: error("runtime_skill_component_sha256_missing:${spec.id}")
        return withContext(Dispatchers.IO) {
            val cacheRoot = File(appContext.cacheDir, "runtime-components").apply { mkdirs() }
            val archive = File(cacheRoot, "${spec.id}-$expectedSha256.zip")
            downloadVerifiedComponent(url, expectedSha256, archive, spec.id)
            val stagingRoot = File(
                AgentWorkspaceManager.internalRootDirectory(appContext),
                "runtime-component-staging",
            ).apply { mkdirs() }
            val temporary = File(stagingRoot, "${spec.id}-${UUID.randomUUID()}")
            try {
                unpackVerifiedComponentArchive(
                    archive = archive,
                    target = temporary,
                    expectedSha256 = expectedSha256,
                    componentId = spec.componentId,
                    componentVersion = spec.componentVersion,
                    runtimeSkillId = spec.id,
                )
                val skillSource = temporary
                File(skillSource, spec.markerFile).delete()
                File(skillSource, MARKET_MARKER).writeText(expectedSha256)
                val targetDirectory = versionedSkillDirectory(expectedSha256)
                File(workspace.skillsRoot(), targetDirectory)
                    .takeIf(File::exists)
                    ?.let { existing ->
                        require(existing.deleteRecursively()) {
                            "runtime_skill_pending_cleanup_failed:${spec.id}"
                        }
                    }
                val installed = SkillIndexService(appContext, workspace)
                    .installSkillFromDirectory(skillSource.absolutePath, targetDirectory)
                RuntimeSkillLocation(
                    androidRoot = File(installed.rootPath).canonicalFile,
                    shellRoot = installed.shellRootPath,
                    source = "market-pending",
                    staged = true,
                )
            } catch (error: Throwable) {
                throw error
            } finally {
                temporary.deleteRecursively()
                stagingRoot.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
            }
        }
    }

    private fun versionedSkillDirectory(expectedSha256: String): String =
        "${spec.id}-${spec.componentVersion.lowercase().replace(Regex("[^a-z0-9]+"), "-")}" +
            "-${expectedSha256.take(12)}"

    private fun installPackaged(
        skills: SkillIndexService,
        packagedMarker: String = packagedMarker(),
    ): SkillIndexEntry =
        File(appContext.cacheDir, "runtime-skill-${spec.id}-${UUID.randomUUID()}")
            .apply {
                require(mkdirs() || isDirectory) {
                    "runtime_skill_cache_create_failed:${spec.id}"
                }
            }
            .let { temporary ->
            val skillSource = File(temporary, spec.id)
            try {
                val packagedArchivePath = spec.packagedArchivePath?.takeIf(String::isNotBlank)
                if (packagedArchivePath != null) {
                    val archive = File(temporary, "component.zip")
                    appContext.assets.open(packagedArchivePath).use { input ->
                        archive.outputStream().buffered().use(input::copyTo)
                    }
                    unpackVerifiedComponentArchive(
                        archive = archive,
                        target = skillSource,
                        expectedSha256 = requireNotNull(spec.packagedArchiveChecksum()),
                        componentId = spec.componentId,
                        componentVersion = spec.componentVersion,
                        runtimeSkillId = spec.id,
                    )
                } else {
                    copyAssetTree(appContext.assets, requirePackagedAssetPath(), skillSource)
                }
                File(skillSource, spec.markerFile).writeText(packagedMarker)
                skills.installSkillFromDirectory(skillSource.absolutePath)
            } finally {
                temporary.deleteRecursively()
            }
        }

    private fun isPackaged(rootPath: String): Boolean =
        File(rootPath, spec.markerFile).isFile

    private fun installedMarker(rootPath: String): String? =
        File(rootPath, spec.markerFile).takeIf(File::isFile)?.readText()?.trim()

    private fun removeOutdatedPackagedCandidates(
        skills: SkillIndexService,
        candidates: List<SkillIndexEntry>,
        refresh: Boolean,
        packagedMarker: String,
    ): Boolean {
        val outdated = candidates.filter { candidate ->
            isPackaged(candidate.rootPath) && packagedRuntimeSkillNeedsReplacement(
                refresh = refresh,
                installedMarker = installedMarker(candidate.rootPath),
                packagedMarker = packagedMarker,
            )
        }
        outdated.forEach { candidate ->
            val root = File(candidate.rootPath)
            require(!root.exists() || root.deleteRecursively()) {
                "runtime_skill_upgrade_delete_failed:${spec.id}"
            }
        }
        if (outdated.isNotEmpty() && installedCandidates(skills).isEmpty()) {
            skills.deleteSkill(spec.id)
        }
        return outdated.isNotEmpty()
    }

    private fun packagedMarker(): String = spec.packagedArchivePath
        ?.takeIf(String::isNotBlank)
        ?.let { requireNotNull(spec.packagedArchiveChecksum()) }
        ?: appContext.assets.open("${requirePackagedAssetPath()}/${spec.markerFile}")
            .bufferedReader()
            .use { it.readText().trim() }
            .also { marker -> require(marker.isNotEmpty()) { "Runtime skill marker is empty: ${spec.id}" } }

    private fun requirePackagedAssetPath(): String = spec.packagedAssetPath
        ?.takeIf(String::isNotBlank)
        ?: error("runtime_skill_packaged_asset_missing:${spec.id}")

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                target.outputStream().use(input::copyTo)
            }
            return
        }
        target.mkdirs()
        children.forEach { child ->
            copyAssetTree(assets, "$assetPath/$child", File(target, child))
        }
    }

    private companion object {
        const val MARKET_MARKER = "MARKET_RUNTIME_SKILL"
    }
}

private val componentDownloadClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .protocols(listOf(Protocol.HTTP_1_1))
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

private suspend fun downloadVerifiedComponent(
    url: String,
    expectedSha256: String,
    target: File,
    runtimeId: String,
) {
    if (target.isFile && sha256Hex(target) == expectedSha256) return
    if (target.exists()) target.delete()
    val partial = File(target.parentFile, "${target.name}.part")
    var lastError: Throwable? = null
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        try {
            val offset = partial.takeIf(File::isFile)?.length() ?: 0L
            val request = Request.Builder().url(url).apply {
                if (offset > 0L) header("Range", "bytes=$offset-")
            }.build()
            componentDownloadClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "runtime_component_http_${response.code}:$runtimeId"
                }
                val append = offset > 0L && response.code == 206
                val body = response.body ?: error("runtime_component_empty_body:$runtimeId")
                FileOutputStream(partial, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(sha256Hex(partial) == expectedSha256) {
                "runtime_component_checksum_mismatch:$runtimeId"
            }
            require(partial.renameTo(target)) {
                "runtime_component_cache_commit_failed:$runtimeId"
            }
            return
        } catch (error: Throwable) {
            lastError = error
            if (error.message.orEmpty().contains("checksum_mismatch")) partial.delete()
            if (attempt == 2) throw error
        }
    }
    throw requireNotNull(lastError)
}

internal fun unpackVerifiedComponentArchive(
    archive: File,
    target: File,
    expectedSha256: String,
    componentId: String,
    componentVersion: String,
    runtimeSkillId: String,
) {
    require(archive.isFile) { "runtime_component_archive_missing:$runtimeSkillId" }
    require(sha256Hex(archive) == expectedSha256) {
        "runtime_component_checksum_mismatch:$runtimeSkillId"
    }
    val canonicalTarget = target.canonicalFile
    var extractedBytes = 0L
    ZipInputStream(archive.inputStream().buffered()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: break
            val output = File(canonicalTarget, entry.name).canonicalFile
            require(
                output == canonicalTarget ||
                    output.path.startsWith(canonicalTarget.path + File.separator)
            ) { "runtime_component_unsafe_entry:${entry.name}" }
            if (entry.isDirectory) {
                output.mkdirs()
            } else {
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        extractedBytes += count
                        require(extractedBytes <= 512L * 1024L * 1024L) {
                            "runtime_component_unpacked_size_exceeded:$runtimeSkillId"
                        }
                        sink.write(buffer, 0, count)
                    }
                }
            }
            input.closeEntry()
        }
    }
    readRuntimeComponentInstall(
        canonicalTarget,
        componentId,
        componentVersion,
        runtimeSkillId,
    )
}

internal data class RuntimeComponentInstall(
    val manager: String,
    val sitePackagesPath: String = "",
)

internal fun readRuntimeComponentInstall(
    root: File,
    expectedComponentId: String,
    expectedComponentVersion: String,
    expectedSkillId: String,
): RuntimeComponentInstall {
    val manifest = File(root, "component.json")
    require(manifest.isFile) { "runtime_component_manifest_missing:$expectedSkillId" }
    val json = Json.parseToJsonElement(manifest.readText()).jsonObject
    require(json.getValue("schemaVersion").jsonPrimitive.int == 1) {
        "runtime_component_schema_unsupported:$expectedSkillId"
    }
    require(json.getValue("id").jsonPrimitive.content == expectedComponentId) {
        "runtime_component_id_mismatch:$expectedSkillId"
    }
    require(json.getValue("version").jsonPrimitive.content == expectedComponentVersion) {
        "runtime_component_version_mismatch:$expectedSkillId"
    }
    val skill = json.getValue("skill").jsonObject
    require(skill.getValue("id").jsonPrimitive.content == expectedSkillId) {
        "runtime_component_skill_id_mismatch:$expectedSkillId"
    }
    require(File(root, "SKILL.md").isFile) {
        "runtime_component_skill_missing:$expectedSkillId"
    }
    val install = json.getValue("install").jsonObject
    val manager = install.getValue("manager").jsonPrimitive.content
    if (manager == INSTALL_MANAGER_BUNDLED) {
        val sitePackages = safeComponentPath(
            install.getValue("sitePackages").jsonPrimitive.content,
            "sitePackages",
        )
        require(File(root, sitePackages).isDirectory) {
            "runtime_component_site_packages_missing:$expectedSkillId"
        }
        return RuntimeComponentInstall(manager = manager, sitePackagesPath = sitePackages)
    }
    error("runtime_component_install_manager_unsupported:$expectedSkillId")
}

private fun safeComponentPath(
    value: String,
    field: String,
    allowCurrentDirectory: Boolean = false,
): String {
    val normalized = value.replace('\\', '/').trim().trimEnd('/')
    require(
        (allowCurrentDirectory && normalized == ".") ||
            (normalized.isNotBlank() && !normalized.startsWith('/') &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." })
    ) { "runtime_component_path_invalid:$field" }
    return normalized
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
