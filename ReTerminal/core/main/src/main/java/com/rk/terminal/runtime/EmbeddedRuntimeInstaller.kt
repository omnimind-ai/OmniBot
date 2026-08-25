package com.rk.terminal.runtime

import android.content.Context
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import androidx.annotation.VisibleForTesting
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localDir
import com.rk.libcommons.localLibDir
import com.rk.libcommons.RuntimeAbi
import com.rk.terminal.BuildConfig
import com.rk.terminal.ui.screens.terminal.stat
import com.rk.terminal.ui.screens.terminal.vmstat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object EmbeddedRuntimeInstaller {
    data class RuntimeInstallProgress(
        val phase: String,
        val distribution: String,
        val message: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val progress: Double? = null,
        val error: String? = null
    )

    data class RuntimeManifestEntry(
        val id: String,
        val version: String,
        val abi: String,
        val fileName: String,
        val compressedSize: Long,
        val expandedSize: Long,
        val sha256: String,
        val downloadUrl: HttpUrl
    )

    data class InstallStatus(
        val success: Boolean,
        val installed: Boolean,
        val message: String
    )

    private data class RuntimeAssetSpec(
        val outputName: String,
        val assetCandidates: List<String>,
        val executable: Boolean = false
    )

    private const val ASSET_ROOT = "embedded-terminal-runtime"
    private const val PREFS_NAME = "embedded_terminal_runtime_downloads"
    private const val MAX_MANIFEST_BYTES = 1024 * 1024L
    private const val MIN_RUNTIME_BYTES = 1024 * 1024L
    private const val MAX_RUNTIME_BYTES = 512 * 1024 * 1024L
    private const val DISK_SAFETY_BYTES = 16 * 1024 * 1024L
    private const val LEGACY_EXPANDED_MIN_BYTES = 64 * 1024 * 1024L
    private const val MAX_REDIRECTS = 5
    @VisibleForTesting
    internal const val ROOTFS_READY_MARKER_NAME = ".omnibot-rootfs-ready"
    private const val LEGACY_UBUNTU_SHA256 =
        "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
    private const val OFFICIAL_UBUNTU_VERSION = "24.04.4"
    private const val OFFICIAL_UBUNTU_FILE_NAME = "ubuntu-base-24.04.4-base-arm64.tar.gz"
    private const val OFFICIAL_UBUNTU_COMPRESSED_SIZE = 29_870_567L
    private const val OFFICIAL_UBUNTU_EXPANDED_SIZE = 106_649_600L
    private const val OFFICIAL_UBUNTU_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
    // x86_64（模拟器/VM）同版本官方 base；校验值实测自 cdimage.ubuntu.com
    private const val OFFICIAL_UBUNTU_X86_64_FILE_NAME = "ubuntu-base-24.04.4-base-amd64.tar.gz"
    private const val OFFICIAL_UBUNTU_X86_64_COMPRESSED_SIZE = 29_989_394L
    private const val OFFICIAL_UBUNTU_X86_64_EXPANDED_SIZE = 80_000_000L
    private const val OFFICIAL_UBUNTU_X86_64_SHA256 =
        "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"
    private const val OFFICIAL_UBUNTU_X86_64_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-amd64.tar.gz"

    private val installMutex = Mutex()
    private val commonAssets = listOf(
        RuntimeAssetSpec("proot", listOf("proot"), executable = true),
        RuntimeAssetSpec("libtalloc.so.2", listOf("libtalloc.so.2"))
    )
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun ensureRuntimeInstalled(
        context: Context,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
        onProgress: suspend (RuntimeInstallProgress) -> Unit = {}
    ): InstallStatus = withContext(Dispatchers.IO) {
        installMutex.withLock {
            try {
                emit(onProgress, "checking", distribution, "正在校验终端环境运行资源")
                val installedFiles = installCommonAssets(context)
                installDistribution(context, distribution, onProgress)
                installCommonHelpers(installedFiles)
                emit(onProgress, "ready", distribution, "终端环境运行资源已就绪。", progress = 1.0)
                InstallStatus(true, true, "终端环境运行资源已就绪。")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: "安装终端环境运行资源失败。"
                emit(onProgress, "error", distribution, message, error = message)
                InstallStatus(false, false, message)
            }
        }
    }

    fun isCurrentDistributionReady(context: Context): Boolean {
        val distribution = TerminalDistribution.selected()
        val commonReady = commonAssets.all { File(context.filesDir, it.outputName).isFile }
        return commonReady && (
            isRootfsInstalled(context, distribution) ||
                File(context.filesDir, distribution.rootfsArchiveName).isFile
            )
    }

    private suspend fun installDistribution(
        context: Context,
        distribution: TerminalDistribution.Spec,
        onProgress: suspend (RuntimeInstallProgress) -> Unit
    ) {
        if (isRootfsInstalled(context, distribution)) {
            emit(onProgress, "ready", distribution, "${distribution.displayName} 系统已安装。")
            return
        }

        val archive = File(context.filesDir, distribution.rootfsArchiveName)
        if (distribution.id == TerminalDistribution.alpine.id) {
            emit(onProgress, "installing", distribution, "正在安装 Alpine 离线运行资源")
            copyAssetIfChanged(
                context,
                RuntimeAbi.alpineAssetFileNames(RuntimeAbi.currentAbi()),
                archive,
                executable = false
            )
            return
        }

        if (isTrustedExistingUbuntuArchive(context, archive)) {
            val savedExpandedSize = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong("ubuntu_expanded_size", 0L)
            val estimatedExpandedSize = savedExpandedSize.takeIf { it > 0L }
                ?: maxOf(LEGACY_EXPANDED_MIN_BYTES, archive.length() * 4L)
            ensureAvailableSpace(context, estimatedExpandedSize + DISK_SAFETY_BYTES)
            emit(onProgress, "ready", distribution, "已找到可用的 Ubuntu 运行资源。")
            return
        }

        val manifestOverride = BuildConfig.TERMINAL_RUNTIME_MANIFEST_URL.trim()
        val deviceAbi = RuntimeAbi.currentAbi()
        val entry = if (manifestOverride.isBlank()) {
            emit(onProgress, "manifest", distribution, "正在准备 Ubuntu 官方下载")
            officialUbuntuRuntime(deviceAbi)
        } else {
            emit(onProgress, "manifest", distribution, "正在获取 Ubuntu 下载信息")
            fetchManifestEntry(
                requireHttpsUrl(manifestOverride, "终端运行时清单"),
                distribution.id,
                deviceAbi
            )
        }
        ensureDiskSpace(context, entry, File(context.filesDir, "${archive.name}.part"))
        downloadVerifiedArchive(context, distribution, entry, archive, onProgress)
    }

    @VisibleForTesting
    internal fun officialUbuntuRuntime(abi: String = RuntimeAbi.currentAbi()): RuntimeManifestEntry =
        if (RuntimeAbi.normalize(abi) == RuntimeAbi.X86_64) {
            RuntimeManifestEntry(
                id = TerminalDistribution.ubuntu.id,
                version = OFFICIAL_UBUNTU_VERSION,
                abi = RuntimeAbi.X86_64,
                fileName = OFFICIAL_UBUNTU_X86_64_FILE_NAME,
                compressedSize = OFFICIAL_UBUNTU_X86_64_COMPRESSED_SIZE,
                expandedSize = OFFICIAL_UBUNTU_X86_64_EXPANDED_SIZE,
                sha256 = OFFICIAL_UBUNTU_X86_64_SHA256,
                downloadUrl = requireHttpsUrl(OFFICIAL_UBUNTU_X86_64_URL, "Ubuntu 官方下载地址")
            )
        } else {
            RuntimeManifestEntry(
                id = TerminalDistribution.ubuntu.id,
                version = OFFICIAL_UBUNTU_VERSION,
                abi = RuntimeAbi.ARM64,
                fileName = OFFICIAL_UBUNTU_FILE_NAME,
                compressedSize = OFFICIAL_UBUNTU_COMPRESSED_SIZE,
                expandedSize = OFFICIAL_UBUNTU_EXPANDED_SIZE,
                sha256 = LEGACY_UBUNTU_SHA256,
                downloadUrl = requireHttpsUrl(OFFICIAL_UBUNTU_URL, "Ubuntu 官方下载地址")
            )
        }

    private fun installCommonAssets(context: Context): MutableMap<String, File> {
        val installed = mutableMapOf<String, File>()
        commonAssets.forEach { spec ->
            val target = File(context.filesDir, spec.outputName)
            copyAssetIfChanged(context, spec.assetCandidates, target, spec.executable)
            installed[spec.outputName] = target
        }
        return installed
    }

    private fun installCommonHelpers(installedFiles: Map<String, File>) {
        localDir().mkdirs()
        localBinDir().mkdirs()
        localLibDir().mkdirs()
        installedFiles["proot"]?.let {
            copyFileIfChanged(it, File(localBinDir(), "proot"), executable = true)
        }
        installedFiles["libtalloc.so.2"]?.let {
            copyFileIfChanged(it, File(localLibDir(), "libtalloc.so.2"), executable = false)
        }
        writeTextIfChanged(File(localDir(), "stat"), stat)
        writeTextIfChanged(File(localDir(), "vmstat"), vmstat)
    }

    private fun isRootfsInstalled(context: Context, distribution: TerminalDistribution.Spec): Boolean {
        val parent = context.filesDir.parentFile ?: return false
        val rootfs = File(File(parent, "local"), distribution.rootfsDirectoryName)
        return isRootfsInstalled(rootfs, distribution)
    }

    @VisibleForTesting
    internal fun isRootfsInstalled(
        rootfs: File,
        distribution: TerminalDistribution.Spec
    ): Boolean {
        if (!rootfs.isDirectory || !hasMinimumRootfsLayout(rootfs)) return false
        if (File(rootfs, ROOTFS_READY_MARKER_NAME).isFile) return true

        if (!rootfsEntryExists(rootfs, "usr/bin/env")) return false
        return when (distribution.id) {
            TerminalDistribution.ubuntu.id ->
                rootfsEntryExists(rootfs, "usr/bin/apt-get") &&
                    File(rootfs, "var/lib/dpkg/status").isFile
            else ->
                rootfsEntryExists(rootfs, "sbin/apk") &&
                    File(rootfs, "lib/apk/db/installed").isFile &&
                    File(rootfs, "etc/alpine-release").isFile
        }
    }

    private fun hasMinimumRootfsLayout(rootfs: File): Boolean {
        return rootfsEntryExists(rootfs, "bin/sh") &&
            rootfsEntryExists(rootfs, "etc/os-release")
    }

    private fun rootfsEntryExists(rootfs: File, relativePath: String): Boolean {
        val entry = File(rootfs, relativePath)
        return entry.exists() || Files.isSymbolicLink(entry.toPath())
    }

    private fun isTrustedExistingUbuntuArchive(context: Context, archive: File): Boolean {
        if (!archive.isFile || archive.length() <= 0L) return false
        val digest = sha256OrNull(archive) ?: return false
        val savedDigest = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("ubuntu_sha256", null)
        return digest.equals(savedDigest, ignoreCase = true) ||
            digest.equals(LEGACY_UBUNTU_SHA256, ignoreCase = true)
    }

    private suspend fun fetchManifestEntry(
        manifestUrl: HttpUrl,
        distributionId: String,
        abi: String = RuntimeAbi.currentAbi()
    ): RuntimeManifestEntry {
        val request = Request.Builder()
            .url(manifestUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "OpenOmniBot-TerminalRuntime")
            .get()
            .build()
        return executeHttps(request) { response ->
            if (!response.isSuccessful) throw IOException("获取终端运行时清单失败（HTTP ${response.code}）。")
            val body = response.body ?: throw IOException("终端运行时清单为空。")
            val bytes = readBoundedManifest(body.byteStream(), body.contentLength())
            parseManifest(String(bytes, Charsets.UTF_8), distributionId, abi)
        }
    }

    @VisibleForTesting
    internal suspend fun readBoundedManifest(
        input: InputStream,
        declaredSize: Long = -1L
    ): ByteArray {
        if (declaredSize > MAX_MANIFEST_BYTES) throw IOException("终端运行时清单过大。")
        val initialCapacity = declaredSize
            .takeIf { it in 1..MAX_MANIFEST_BYTES }
            ?.toInt()
            ?: DEFAULT_BUFFER_SIZE
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_MANIFEST_BYTES) throw IOException("终端运行时清单过大。")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    @VisibleForTesting
    internal fun parseManifest(
        rawJson: String,
        distributionId: String,
        abi: String = RuntimeAbi.currentAbi()
    ): RuntimeManifestEntry {
        val runtimeAbi = RuntimeAbi.normalize(abi)
        val payload = JSONObject(rawJson)
        if (payload.optInt("schemaVersion", -1) != 1) throw IOException("不支持的终端运行时清单版本。")
        val runtimes = payload.optJSONArray("runtimes") ?: throw IOException("终端运行时清单缺少 runtimes。")
        return buildValidatedEntry(findRuntimeEntry(runtimes, distributionId, runtimeAbi), runtimeAbi)
    }

    private fun findRuntimeEntry(
        runtimes: JSONArray,
        distributionId: String,
        runtimeAbi: String
    ): JSONObject {
        for (index in 0 until runtimes.length()) {
            val item = runtimes.optJSONObject(index) ?: continue
            if (item.optString("id") == distributionId && item.optString("abi") == runtimeAbi) {
                return item
            }
        }
        throw IOException("清单中没有适用于 $runtimeAbi 的 $distributionId 运行时。")
    }

    private fun buildValidatedEntry(item: JSONObject, runtimeAbi: String): RuntimeManifestEntry {
        val id = item.optString("id").trim()
        val version = item.optString("version").trim()
        val abiValue = item.optString("abi").trim()
        val fileName = item.optString("fileName").trim()
        val compressedSize = item.optLong("compressedSize", -1L)
        val expandedSize = item.optLong("expandedSize", -1L)
        val digest = item.optString("sha256").trim().lowercase()
        val downloadUrl = requireHttpsUrl(item.optString("downloadUrl"), "终端运行时下载地址")
        if (id != TerminalDistribution.ubuntu.id || abiValue != runtimeAbi) throw IOException("终端运行时标识无效。")
        if (version.isBlank() || !fileName.matches(Regex("^[A-Za-z0-9._-]+\\.tar\\.gz$"))) {
            throw IOException("终端运行时文件信息无效。")
        }
        if (compressedSize !in MIN_RUNTIME_BYTES..MAX_RUNTIME_BYTES || expandedSize < compressedSize) {
            throw IOException("终端运行时大小信息无效。")
        }
        if (!digest.matches(Regex("^[a-f0-9]{64}$"))) throw IOException("终端运行时 SHA-256 无效。")
        return RuntimeManifestEntry(id, version, abiValue, fileName, compressedSize, expandedSize, digest, downloadUrl)
    }

    private suspend fun downloadVerifiedArchive(
        context: Context,
        distribution: TerminalDistribution.Spec,
        entry: RuntimeManifestEntry,
        archive: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit
    ) {
        val partial = File(context.filesDir, "${archive.name}.part")
        if (partial.length() > entry.compressedSize) partial.delete()
        repeat(2) { attempt ->
            downloadAttempt(distribution, entry, partial, onProgress)
            if (partial.length() == entry.compressedSize && sha256OrNull(partial) == entry.sha256) {
                replaceWithFile(archive, partial)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("${distribution.id}_version", entry.version)
                    .putString("${distribution.id}_sha256", entry.sha256)
                    .putLong("${distribution.id}_expanded_size", entry.expandedSize)
                    .apply()
                return
            }
            partial.delete()
            if (attempt == 0) {
                emit(onProgress, "verifying", distribution, "Ubuntu 校验失败，正在重新完整下载")
            }
        }
        throw IOException("Ubuntu 运行资源校验失败，请检查网络后重试。")
    }

    private suspend fun downloadAttempt(
        distribution: TerminalDistribution.Spec,
        entry: RuntimeManifestEntry,
        partial: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit
    ) {
        partial.parentFile?.mkdirs()
        var existing = partial.takeIf { it.isFile }?.length() ?: 0L
        val builder = Request.Builder()
            .url(entry.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "OpenOmniBot-TerminalRuntime")
            .get()
        if (existing > 0L) builder.header("Range", "bytes=$existing-")
        executeHttps(builder.build()) { response ->
            if (response.code == 416 && existing > 0L) {
                partial.delete()
                return@executeHttps downloadAttempt(distribution, entry, partial, onProgress)
            }
            if (!response.isSuccessful) throw IOException("下载 Ubuntu 运行资源失败（HTTP ${response.code}）。")
            val append = existing > 0L && response.code == 206 &&
                response.header("Content-Range")?.startsWith("bytes $existing-") == true
            if (existing > 0L && !append) {
                existing = 0L
                partial.delete()
            }
            val body = response.body ?: throw IOException("Ubuntu 下载响应为空。")
            var downloaded = existing
            emitDownload(onProgress, distribution, downloaded, entry.compressedSize)
            java.io.FileOutputStream(partial, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        if (downloaded > entry.compressedSize) throw IOException("Ubuntu 下载内容超过清单声明大小。")
                        output.write(buffer, 0, read)
                        emitDownload(onProgress, distribution, downloaded, entry.compressedSize)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    private fun ensureDiskSpace(context: Context, entry: RuntimeManifestEntry, partial: File) {
        val remainingDownload = (entry.compressedSize - partial.length()).coerceAtLeast(0L)
        val required = remainingDownload + entry.expandedSize + DISK_SAFETY_BYTES
        ensureAvailableSpace(context, required)
    }

    private fun ensureAvailableSpace(context: Context, required: Long) {
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (available < required) {
            throw IOException("存储空间不足：Ubuntu 初始化至少还需要 ${formatMiB(required)}，当前可用 ${formatMiB(available)}。")
        }
    }

    private sealed interface HttpsCallResult<out T> {
        data class Complete<T>(val value: T) : HttpsCallResult<T>
        data class Redirect(val request: Request) : HttpsCallResult<Nothing>
    }

    private suspend fun <T> executeHttps(
        initialRequest: Request,
        consume: suspend (Response) -> T
    ): T {
        var request = initialRequest
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (request.url.scheme != "https") throw IOException("终端运行时仅允许通过 HTTPS 下载。")
            when (
                val result = executeCancellableCall(client.newCall(request)) { response ->
                    if (response.code !in 300..399) {
                        HttpsCallResult.Complete(consume(response))
                    } else {
                        val location = response.header("Location")
                        if (redirectCount >= MAX_REDIRECTS || location.isNullOrBlank()) {
                            throw IOException("终端运行时下载重定向无效。")
                        }
                        val next = request.url.resolve(location)
                            ?: throw IOException("终端运行时下载重定向无效。")
                        if (next.scheme != "https") {
                            throw IOException("终端运行时下载拒绝非 HTTPS 重定向。")
                        }
                        HttpsCallResult.Redirect(request.newBuilder().url(next).build())
                    }
                }
            ) {
                is HttpsCallResult.Complete -> return result.value
                is HttpsCallResult.Redirect -> request = result.request
            }
        }
        throw IOException("终端运行时下载重定向过多。")
    }

    @VisibleForTesting
    internal suspend fun <T> executeCancellableCall(
        call: Call,
        consume: suspend (Response) -> T
    ): T = coroutineScope {
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = try {
                call.execute()
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                throw error
            }
            try {
                consume(response)
            } catch (error: IOException) {
                coroutineContext.ensureActive()
                throw error
            } finally {
                response.close()
            }
        } finally {
            cancellationWatcher.cancel()
        }
    }

    private fun requireHttpsUrl(raw: String, label: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw IOException("$label 未配置或格式无效。")
        if (url.scheme != "https" || url.host.isBlank()) throw IOException("$label 必须使用 HTTPS。")
        return url
    }

    private fun copyAssetIfChanged(
        context: Context,
        candidates: List<String>,
        target: File,
        executable: Boolean
    ): Boolean {
        val assetName = candidates.firstOrNull { name ->
            runCatching { context.assets.open("$ASSET_ROOT/$name").close() }.isSuccess
        } ?: error("缺少内置终端运行资源：${candidates.first()}。")
        val assetPath = "$ASSET_ROOT/$assetName"
        val assetDigest = context.assets.open(assetPath).use(::sha256)
        val changed = sha256OrNull(target) != assetDigest
        if (changed) replaceFile(target, executable) { temp ->
            context.assets.open(assetPath).use { input -> temp.outputStream().use(input::copyTo) }
        }
        applyPermissions(target, executable)
        return changed
    }

    private fun copyFileIfChanged(source: File, target: File, executable: Boolean): Boolean {
        val digest = sha256OrNull(source) ?: error("Missing runtime file: ${source.absolutePath}")
        val changed = sha256OrNull(target) != digest
        if (changed) replaceFile(target, executable) { temp -> source.copyTo(temp, overwrite = true) }
        applyPermissions(target, executable)
        return changed
    }

    private fun writeTextIfChanged(target: File, content: String) {
        if (!target.exists() || target.readText() != content) {
            replaceFile(target, executable = false) { it.writeText(content) }
        }
    }

    private fun replaceFile(target: File, executable: Boolean, writer: (File) -> Unit) {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            writer(temp)
            applyPermissions(temp, executable)
            if (target.exists() && !target.delete()) error("Failed to replace ${target.absolutePath}")
            if (!temp.renameTo(target)) error("Failed to move ${target.absolutePath}")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun replaceWithFile(target: File, source: File) {
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("无法替换已校验的终端运行资源。", error)
        }
    }

    private fun applyPermissions(file: File, executable: Boolean) {
        if (!file.exists()) return
        file.setReadable(true, false)
        file.setWritable(true, true)
        if (executable) file.setExecutable(true, false)
    }

    private suspend fun emit(
        callback: suspend (RuntimeInstallProgress) -> Unit,
        phase: String,
        distribution: TerminalDistribution.Spec,
        message: String,
        progress: Double? = null,
        error: String? = null
    ) = callback(RuntimeInstallProgress(phase, distribution.id, message, progress = progress, error = error))

    private suspend fun emitDownload(
        callback: suspend (RuntimeInstallProgress) -> Unit,
        distribution: TerminalDistribution.Spec,
        downloaded: Long,
        total: Long
    ) = callback(
        RuntimeInstallProgress(
            phase = "downloading",
            distribution = distribution.id,
            message = "正在下载 Ubuntu 运行资源 ${formatMiB(downloaded)} / ${formatMiB(total)}",
            downloadedBytes = downloaded,
            totalBytes = total,
            progress = if (total > 0L) downloaded.toDouble() / total else null
        )
    )

    private fun sha256OrNull(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        return file.inputStream().use(::sha256)
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return BigInteger(1, digest.digest()).toString(16).padStart(64, '0')
    }

    private fun formatMiB(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
