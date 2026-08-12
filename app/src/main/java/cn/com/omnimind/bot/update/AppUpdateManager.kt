package cn.com.omnimind.bot.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.com.omnimind.baselib.service.DeviceInfoService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.manager.ExternalApkInstallResult
import cn.com.omnimind.bot.manager.ExternalApkInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateState(
    val currentVersion: String,
    val latestVersion: String,
    val hasUpdate: Boolean,
    val checkedAt: Long,
    val publishedAt: Long,
    val releaseUrl: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSha256: String = ""
) {
    fun toMap(): Map<String, Any> = mapOf(
        "currentVersion" to currentVersion,
        "latestVersion" to latestVersion,
        "hasUpdate" to hasUpdate,
        "checkedAt" to checkedAt,
        "publishedAt" to publishedAt,
        "releaseUrl" to releaseUrl,
        "releaseNotes" to releaseNotes,
        "apkName" to apkName,
        "apkDownloadUrl" to apkDownloadUrl,
        "apkSha256" to apkSha256
    )
}

@VisibleForTesting
internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sha256: String = ""
)

@VisibleForTesting
internal enum class ApkDownloadSource(val value: String) {
    WORKER("worker"),
    GITHUB("github");

    companion object {
        fun fromValue(raw: String?): ApkDownloadSource {
            return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                GITHUB.value -> GITHUB
                else -> WORKER
            }
        }
    }
}

@VisibleForTesting
internal enum class ReleaseTrack {
    STABLE,
    BETA,
    UNSUPPORTED
}

@VisibleForTesting
internal data class ReleaseCandidate(
    val version: String,
    val track: ReleaseTrack,
    val publishedAt: Long,
    val releaseUrl: String,
    val releaseNotes: String,
    val assets: List<ReleaseAsset>
)

@VisibleForTesting
internal class AppUpdateCheckCoordinator {
    internal data class Lease(
        val generation: Long,
        val force: Boolean,
        val isLeader: Boolean,
        internal val completion: CompletableDeferred<Result<AppUpdateState>>
    )

    private val lock = Any()
    private var generation = 0L
    private var active: Lease? = null

    fun acquire(force: Boolean): Lease = synchronized(lock) {
        val current = active
        if (current != null && current.generation == generation) {
            if (current.force || !force) {
                return@synchronized current.copy(isLeader = false)
            }
            // A user-requested forced check supersedes a passive cache check.
            // The passive result is now stale and must not be persisted.
            generation += 1L
            active = null
        }

        Lease(
            generation = generation,
            force = force,
            isLeader = true,
            completion = CompletableDeferred()
        ).also { active = it }
    }

    fun invalidate(block: () -> Unit = {}) = synchronized(lock) {
        generation += 1L
        active = null
        block()
    }

    fun applyIfCurrent(lease: Lease, block: () -> Unit): Boolean = synchronized(lock) {
        if (lease.generation != generation) {
            return@synchronized false
        }
        block()
        true
    }

    suspend fun await(lease: Lease): AppUpdateState {
        return lease.completion.await().getOrThrow()
    }

    fun complete(lease: Lease, result: Result<AppUpdateState>) {
        lease.completion.complete(result)
        synchronized(lock) {
            if (active?.completion === lease.completion) {
                active = null
            }
        }
    }
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PREFS_NAME = "app_update_state"
    private const val KEY_BETA_OPT_IN = "beta_opt_in"
    private const val KEY_LATEST_VERSION = "latest_version"
    private const val KEY_HAS_UPDATE = "has_update"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_PUBLISHED_AT = "published_at"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_APK_NAME = "apk_name"
    private const val KEY_APK_DOWNLOAD_URL = "apk_download_url"
    private const val KEY_APK_SHA256 = "apk_sha256"
    private const val KEY_APK_DOWNLOAD_SOURCE = "apk_download_source"
    private const val KEY_INSTALL_ID = "install_id"

    private const val WORKER_UPDATES_PATH = "updates"
    private const val WORKER_DOWNLOADS_PATH = "downloads"
    private const val GITHUB_RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/omnimind-ai/OpenOmniBot/releases/download"
    private const val WORK_NAME = "app_update_periodic_check"
    private const val PERIODIC_CHECK_HOURS = 12L
    private const val SILENT_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val USER_AGENT = "OpenOmniBot-App"
    private const val EDITION_STANDARD = "standard"
    private val editionApkNamePattern =
        Regex("^openomnibot-.+-[a-z0-9_]+\\.apk$", RegexOption.IGNORE_CASE)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val checkCoordinator = AppUpdateCheckCoordinator()

    fun isSelfUpdateAvailable(): Boolean {
        return isSelfUpdateEnabledForDistribution(
            edition = BuildConfig.APP_EDITION,
            enabled = BuildConfig.APP_SELF_UPDATE_ENABLED
        )
    }

    @VisibleForTesting
    internal fun isSelfUpdateEnabledForDistribution(
        edition: String?,
        enabled: Boolean
    ): Boolean {
        return enabled && edition?.trim()?.equals(EDITION_STANDARD, ignoreCase = true) == true
    }

    /**
     * Cancels work left by a direct-install build when the same app is upgraded
     * to a distribution (for example Google Play) that forbids APK self-update.
     */
    fun enforceDistributionPolicy(context: Context) {
        if (isSelfUpdateAvailable()) return
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }.onFailure {
            OmniLog.w(TAG, "Unable to cancel disabled app update work: ${it.message}")
        }
    }

    fun schedulePeriodicChecks(context: Context) {
        if (!isSelfUpdateAvailable()) {
            enforceDistributionPolicy(context)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
            PERIODIC_CHECK_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    fun requestSilentCheckIfDue(context: Context) {
        if (!isSelfUpdateAvailable()) {
            enforceDistributionPolicy(context)
            return
        }
        schedulePeriodicChecks(context)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                checkNow(context.applicationContext, force = false)
            }.onFailure {
                OmniLog.w(TAG, "Silent app update check failed: ${it.message}")
            }
        }
    }

    fun getCachedStatus(context: Context): AppUpdateState {
        val appContext = context.applicationContext
        if (!isSelfUpdateAvailable()) {
            return emptyState(currentVersion(appContext))
        }
        return readState(
            context = appContext,
            currentVersion = currentVersion(appContext),
            includeBeta = isBetaOptIn(appContext)
        )
    }

    fun isBetaOptIn(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BETA_OPT_IN, false)
    }

    internal fun getApkDownloadSource(context: Context): ApkDownloadSource {
        val rawValue = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APK_DOWNLOAD_SOURCE, null)
        return ApkDownloadSource.fromValue(rawValue)
    }

    fun setBetaOptIn(context: Context, enabled: Boolean): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val changed = prefs.getBoolean(KEY_BETA_OPT_IN, false) != enabled
        val persist = {
            prefs.edit().apply {
                putBoolean(KEY_BETA_OPT_IN, enabled)
                if (changed) {
                    putLong(KEY_CHECKED_AT, 0L)
                }
            }.apply()
        }
        if (changed) {
            checkCoordinator.invalidate(persist)
        } else {
            persist()
        }
        return enabled
    }

    internal fun setApkDownloadSource(context: Context, rawValue: String?): ApkDownloadSource {
        val source = ApkDownloadSource.fromValue(rawValue)
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persist = {
            prefs.edit()
                .putString(KEY_APK_DOWNLOAD_SOURCE, source.value)
                .apply()
        }
        if (ApkDownloadSource.fromValue(prefs.getString(KEY_APK_DOWNLOAD_SOURCE, null)) != source) {
            checkCoordinator.invalidate(persist)
        } else {
            persist()
        }
        return source
    }

    suspend fun checkNow(context: Context, force: Boolean): AppUpdateState {
        val appContext = context.applicationContext
        if (!isSelfUpdateAvailable()) {
            return emptyState(currentVersion(appContext))
        }
        val lease = checkCoordinator.acquire(force)
        if (!lease.isLeader) {
            return checkCoordinator.await(lease)
        }

        return try {
            val now = System.currentTimeMillis()
            val currentVersion = currentVersion(appContext)
            val includeBeta = isBetaOptIn(appContext)
            val downloadSource = getApkDownloadSource(appContext)
            val cached = readState(appContext, currentVersion, includeBeta)
            val resolved = if (!force && now - cached.checkedAt < SILENT_CHECK_INTERVAL_MS) {
                cached
            } else {
                val fetched = fetchLatestReleaseState(
                    currentVersion = currentVersion,
                    includeBeta = includeBeta,
                    downloadSource = downloadSource,
                    deviceStatsParams = buildDeviceStatsParams(appContext)
                ).copy(checkedAt = now)
                val saved = checkCoordinator.applyIfCurrent(lease) {
                    saveState(appContext, fetched)
                }
                if (saved) fetched else getCachedStatus(appContext)
            }
            checkCoordinator.complete(lease, Result.success(resolved))
            resolved
        } catch (failure: Throwable) {
            checkCoordinator.complete(lease, Result.failure(failure))
            throw failure
        }
    }

    suspend fun installLatestApk(context: Context): ExternalApkInstallResult {
        if (!isSelfUpdateAvailable()) {
            return ExternalApkInstallResult(
                success = false,
                status = ExternalApkInstaller.STATUS_INSTALL_FAILED,
                message = "APK self-update is unavailable in this distribution."
            )
        }
        val installState = resolveInstallState(context)
        if (!installState.hasUpdate || installState.apkDownloadUrl.isBlank()) {
            return ExternalApkInstallResult(
                success = false,
                status = ExternalApkInstaller.STATUS_INSTALL_FAILED,
                message = "当前没有可安装的新版本。"
            )
        }

        val safeFileName = installState.apkName.ifBlank {
            "OpenOmniBot-v${installState.latestVersion}.apk"
        }
        return ExternalApkInstaller.downloadAndInstall(
            context = context,
            downloadUrl = installState.apkDownloadUrl,
            apkFileName = safeFileName,
            displayName = "OpenOmniBot",
            expectedSha256 = installState.apkSha256,
            expectedPackageName = context.applicationContext.packageName
        )
    }

    @VisibleForTesting
    internal fun normalizeVersion(raw: String?): String {
        return raw
            ?.trim()
            ?.removePrefix("v")
            ?.removePrefix("V")
            ?.substringBefore('+')
            ?.trim()
            .orEmpty()
    }

    @VisibleForTesting
    internal fun compareVersions(leftRaw: String?, rightRaw: String?): Int {
        val left = normalizeVersion(leftRaw)
        val right = normalizeVersion(rightRaw)
        if (left == right) return 0

        val leftParts = parseNumericVersionParts(left)
        val rightParts = parseNumericVersionParts(right)
        if (leftParts != null && rightParts != null) {
            val maxLength = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until maxLength) {
                val leftValue = leftParts.getOrElse(index) { 0 }
                val rightValue = rightParts.getOrElse(index) { 0 }
                if (leftValue != rightValue) {
                    return leftValue.compareTo(rightValue)
                }
            }
            return 0
        }

        return left.compareTo(right)
    }

    @VisibleForTesting
    internal fun versionSegmentCount(raw: String?): Int {
        val normalized = normalizeVersion(raw)
        if (normalized.isBlank()) return 0
        val parts = normalized.split('.')
        if (parts.any { part -> part.isBlank() || part.any { !it.isDigit() } }) {
            return 0
        }
        return parts.size
    }

    @VisibleForTesting
    internal fun classifyReleaseTrack(rawVersion: String?, prerelease: Boolean = false): ReleaseTrack {
        if (prerelease) {
            return ReleaseTrack.BETA
        }
        return when (versionSegmentCount(rawVersion)) {
            3 -> ReleaseTrack.STABLE
            4 -> ReleaseTrack.BETA
            else -> ReleaseTrack.UNSUPPORTED
        }
    }

    @VisibleForTesting
    internal fun selectLatestRelease(
        candidates: List<ReleaseCandidate>,
        includeBeta: Boolean
    ): ReleaseCandidate? {
        var selected: ReleaseCandidate? = null
        for (candidate in candidates) {
            if (!shouldIncludeTrack(candidate.track, includeBeta)) continue
            val currentSelected = selected
            if (
                currentSelected == null ||
                compareVersions(candidate.version, currentSelected.version) > 0 ||
                (
                    compareVersions(candidate.version, currentSelected.version) == 0 &&
                        candidate.publishedAt > currentSelected.publishedAt
                    )
            ) {
                selected = candidate
            }
        }
        return selected
    }

    @VisibleForTesting
    internal fun selectPreferredApkAsset(
        assets: List<ReleaseAsset>,
        edition: String = BuildConfig.APP_EDITION,
    ): ReleaseAsset? {
        val apkAssets = assets.filter { it.name.lowercase(Locale.ROOT).endsWith(".apk") }
        if (apkAssets.isEmpty()) return null

        val normalizedEdition = normalizeEdition(edition)
        val editionAsset = apkAssets.firstOrNull {
            isEditionApkAsset(it.name, normalizedEdition)
        }
        if (editionAsset != null) return editionAsset

        if (apkAssets.any { isKnownEditionApkAsset(it.name) }) {
            return null
        }

        val preferred = apkAssets.firstOrNull {
            it.name.startsWith("OpenOmniBot-v", ignoreCase = true) &&
                it.name.lowercase(Locale.ROOT).endsWith(".apk")
        }
        if (preferred != null) return preferred
        return apkAssets.firstOrNull()
    }

    private suspend fun resolveInstallState(context: Context): AppUpdateState {
        val cached = getCachedStatus(context)
        if (cached.hasUpdate && cached.apkDownloadUrl.isNotBlank()) {
            return cached
        }
        return checkNow(context, force = true)
    }

    private fun readState(context: Context, currentVersion: String, includeBeta: Boolean): AppUpdateState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedState = AppUpdateState(
            currentVersion = currentVersion,
            latestVersion = prefs.getString(KEY_LATEST_VERSION, currentVersion).orEmpty().ifBlank {
                currentVersion
            },
            hasUpdate = prefs.getBoolean(KEY_HAS_UPDATE, false),
            checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L),
            publishedAt = prefs.getLong(KEY_PUBLISHED_AT, 0L),
            releaseUrl = prefs.getString(KEY_RELEASE_URL, "").orEmpty(),
            releaseNotes = prefs.getString(KEY_RELEASE_NOTES, "").orEmpty(),
            apkName = prefs.getString(KEY_APK_NAME, "").orEmpty(),
            apkDownloadUrl = prefs.getString(KEY_APK_DOWNLOAD_URL, "").orEmpty(),
            apkSha256 = normalizeSha256(prefs.getString(KEY_APK_SHA256, ""))
        )
        val stateWithPreferredSource = applyPreferredDownloadSource(
            storedState,
            getApkDownloadSource(context)
        )
        if (!shouldIncludeTrack(classifyReleaseTrack(stateWithPreferredSource.latestVersion), includeBeta)) {
            return emptyState(currentVersion = currentVersion, checkedAt = stateWithPreferredSource.checkedAt)
        }
        return stateWithPreferredSource.copy(
            hasUpdate = stateWithPreferredSource.hasUpdate &&
                compareVersions(stateWithPreferredSource.latestVersion, currentVersion) > 0 &&
                isSecureDownloadUrl(stateWithPreferredSource.apkDownloadUrl) &&
                stateWithPreferredSource.apkSha256.isNotBlank()
        )
    }

    private fun saveState(context: Context, state: AppUpdateState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LATEST_VERSION, state.latestVersion)
            .putBoolean(KEY_HAS_UPDATE, state.hasUpdate)
            .putLong(KEY_CHECKED_AT, state.checkedAt)
            .putLong(KEY_PUBLISHED_AT, state.publishedAt)
            .putString(KEY_RELEASE_URL, state.releaseUrl)
            .putString(KEY_RELEASE_NOTES, state.releaseNotes)
            .putString(KEY_APK_NAME, state.apkName)
            .putString(KEY_APK_DOWNLOAD_URL, state.apkDownloadUrl)
            .putString(KEY_APK_SHA256, normalizeSha256(state.apkSha256))
            .apply()
    }

    private fun currentVersion(context: Context): String {
        return DeviceInfoService.getAppVersion(context)["versionName"]?.toString()
            ?.trim()
            ?.ifBlank { "0.0.0" }
            ?: "0.0.0"
    }

    private fun fetchLatestReleaseState(
        currentVersion: String,
        includeBeta: Boolean,
        downloadSource: ApkDownloadSource,
        deviceStatsParams: Map<String, String> = emptyMap()
    ): AppUpdateState {
        val checkedAt = System.currentTimeMillis()
        val updatesUrl = buildWorkerCheckUrl(
            workerUrl = BuildConfig.APP_UPDATE_WORKER_URL,
            currentVersion = currentVersion,
            includeBeta = includeBeta,
            downloadSource = downloadSource,
            edition = BuildConfig.APP_EDITION,
            deviceStatsParams = deviceStatsParams
        )
        if (updatesUrl == null) {
            OmniLog.w(TAG, "App update worker URL is not configured")
            return emptyState(currentVersion, checkedAt = checkedAt)
        }

        val request = Request.Builder()
            .url(updatesUrl)
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("App update worker request failed with code ${response.code}")
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                throw IOException("App update worker response body is empty")
            }
            val payload = JSONObject(body)
            return parseWorkerUpdateState(
                payload = payload,
                currentVersion = currentVersion,
                includeBeta = includeBeta,
                downloadSource = downloadSource,
                edition = BuildConfig.APP_EDITION,
                checkedAt = checkedAt
            )
        }
    }

    @VisibleForTesting
    internal fun buildWorkerCheckUrl(
        workerUrl: String,
        currentVersion: String,
        includeBeta: Boolean,
        downloadSource: ApkDownloadSource,
        edition: String,
        deviceStatsParams: Map<String, String> = emptyMap()
    ): HttpUrl? {
        val normalizedBase = workerUrl.trim().trimEnd('/')
        if (normalizedBase.isBlank()) return null

        val updatesUrl = if (normalizedBase.endsWith("/$WORKER_UPDATES_PATH", ignoreCase = true)) {
            normalizedBase
        } else {
            "$normalizedBase/$WORKER_UPDATES_PATH"
        }
        val parsedUpdatesUrl = updatesUrl.toHttpUrlOrNull()
            ?.takeIf { it.scheme == "https" }
            ?: return null
        val builder = parsedUpdatesUrl
            .newBuilder()
            .addQueryParameter("currentVersion", normalizeVersion(currentVersion))
            .addQueryParameter("includeBeta", includeBeta.toString())
            .addQueryParameter("edition", normalizeEdition(edition))
            .addQueryParameter("source", downloadSource.value)
        deviceStatsParams["installId"]
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("installId", it) }
        return builder.build()
    }

    /**
     * Optional update statistics are intentionally limited to a random,
     * per-install identifier. The app version is already a required update-check
     * parameter. No identifier is generated or sent before explicit consent.
     */
    private fun buildDeviceStatsParams(context: Context): Map<String, String> {
        return buildDeviceStatsParams(
            hasOptionalTelemetryConsent = PrivacyConsentStore.hasOptionalTelemetryConsent(context),
            installIdProvider = { installId(context) }
        )
    }

    @VisibleForTesting
    internal fun buildDeviceStatsParams(
        hasOptionalTelemetryConsent: Boolean,
        installIdProvider: () -> String
    ): Map<String, String> {
        if (!hasOptionalTelemetryConsent) return emptyMap()
        return mapOf("installId" to installIdProvider())
    }

    private fun installId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
        return generated
    }

    @VisibleForTesting
    internal fun parseWorkerUpdateState(
        payload: JSONObject,
        currentVersion: String,
        includeBeta: Boolean,
        downloadSource: ApkDownloadSource,
        edition: String = BuildConfig.APP_EDITION,
        checkedAt: Long = System.currentTimeMillis()
    ): AppUpdateState {
        val release = payload.optJSONObject("release") ?: payload
        val expectedEdition = normalizeEdition(edition)
        val responseEditions = listOf(
            firstString(payload, "edition", "appEdition", "app_edition"),
            firstString(release, "edition", "appEdition", "app_edition")
        ).map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        if (responseEditions.any { it != expectedEdition }) {
            return emptyState(currentVersion, checkedAt = checkedAt)
        }
        val version = normalizeVersion(
            firstString(release, "latestVersion", "version", "tag", "tagName", "tag_name")
        )
        val track = parseReleaseTrack(release, version)
        if (version.isBlank() || !shouldIncludeTrack(track, includeBeta)) {
            return emptyState(currentVersion, checkedAt = checkedAt)
        }

        val assets = parseWorkerAssets(release.optJSONArray("assets"), downloadSource)
        val payloadAsset = releaseAssetFromPayload(release, downloadSource)
        val candidateAssets = buildList {
            addAll(assets)
            payloadAsset?.let(::add)
        }
        val preferredAsset = selectPreferredApkAsset(candidateAssets, expectedEdition)
        val downloadUrl = preferredAsset?.let { asset ->
            asset.downloadUrl.ifBlank {
                resolveApkDownloadUrl(downloadSource, version, asset)
            }
        }.orEmpty()
        val apkSha256 = normalizeSha256(preferredAsset?.sha256)
        val hasInstallableUpdate = isInstallableUpdate(
            version = version,
            currentVersion = currentVersion,
            asset = preferredAsset,
            downloadUrl = downloadUrl
        )

        return AppUpdateState(
            currentVersion = currentVersion,
            latestVersion = version,
            hasUpdate = hasInstallableUpdate,
            checkedAt = checkedAt,
            publishedAt = parseTimestampToMillis(
                firstValue(release, "publishedAt", "published_at", "createdAt", "created_at")
            ),
            releaseUrl = firstString(release, "releaseUrl", "htmlUrl", "html_url", "url"),
            releaseNotes = firstString(release, "releaseNotes", "notes", "body"),
            apkName = preferredAsset?.name.orEmpty(),
            apkDownloadUrl = downloadUrl,
            apkSha256 = apkSha256
        )
    }

    private fun parseReleaseTrack(release: JSONObject, version: String): ReleaseTrack {
        return when (firstString(release, "track").lowercase(Locale.ROOT)) {
            "stable" -> ReleaseTrack.STABLE
            "beta", "prerelease", "pre-release" -> ReleaseTrack.BETA
            else -> classifyReleaseTrack(
                rawVersion = version,
                prerelease = release.optBoolean("prerelease")
            )
        }
    }

    private fun parseWorkerAssets(
        array: JSONArray?,
        downloadSource: ApkDownloadSource
    ): List<ReleaseAsset> {
        if (array == null) return emptyList()
        val assets = mutableListOf<ReleaseAsset>()
        for (index in 0 until array.length()) {
            val raw = array.optJSONObject(index) ?: continue
            val name = firstString(raw, "name", "fileName", "filename")
            if (!name.lowercase(Locale.ROOT).endsWith(".apk")) continue
            val downloadUrl = when (downloadSource) {
                ApkDownloadSource.WORKER -> firstString(
                    raw,
                    "workerDownloadUrl",
                    "worker_download_url",
                    "r2DownloadUrl",
                    "r2_download_url",
                    "downloadUrl",
                    "apkDownloadUrl",
                    "cnbDownloadUrl",
                    "cnb_download_url",
                    "browser_download_url",
                    "githubDownloadUrl",
                    "github_download_url"
                )
                ApkDownloadSource.GITHUB -> firstString(
                    raw,
                    "githubDownloadUrl",
                    "github_download_url",
                    "browser_download_url",
                    "downloadUrl",
                    "cnbDownloadUrl",
                    "cnb_download_url"
                )
            }
            assets += ReleaseAsset(
                name = name,
                downloadUrl = downloadUrl,
                sha256 = normalizeSha256(firstString(raw, "sha256", "sha256sum", "checksum"))
            )
        }
        return assets
    }

    private fun releaseAssetFromPayload(
        payload: JSONObject,
        downloadSource: ApkDownloadSource
    ): ReleaseAsset? {
        val name = firstString(payload, "apkName", "assetName")
        if (!name.lowercase(Locale.ROOT).endsWith(".apk")) return null
        val downloadUrl = when (downloadSource) {
            ApkDownloadSource.WORKER -> firstString(
                payload,
                "workerDownloadUrl",
                "worker_download_url",
                "r2DownloadUrl",
                "r2_download_url",
                "apkDownloadUrl",
                "downloadUrl",
                "cnbDownloadUrl",
                "cnb_download_url",
                "githubDownloadUrl",
                "github_download_url"
            )
            ApkDownloadSource.GITHUB -> firstString(
                payload,
                "githubDownloadUrl",
                "github_download_url",
                "apkDownloadUrl",
                "downloadUrl",
                "cnbDownloadUrl",
                "cnb_download_url"
            )
        }
        return ReleaseAsset(
            name = name,
            downloadUrl = downloadUrl,
            sha256 = normalizeSha256(
                firstString(payload, "apkSha256", "apk_sha256", "sha256", "sha256sum", "checksum")
            )
        )
    }

    private fun applyPreferredDownloadSource(
        state: AppUpdateState,
        downloadSource: ApkDownloadSource
    ): AppUpdateState {
        if (state.latestVersion.isBlank() || state.apkName.isBlank()) {
            return state
        }
        return state.copy(
            apkDownloadUrl = resolveApkDownloadUrl(
                downloadSource = downloadSource,
                version = state.latestVersion,
                asset = ReleaseAsset(
                    name = state.apkName,
                    downloadUrl = state.apkDownloadUrl,
                    sha256 = state.apkSha256
                )
            )
        )
    }

    @VisibleForTesting
    internal fun resolveApkDownloadUrl(
        downloadSource: ApkDownloadSource,
        version: String,
        asset: ReleaseAsset
    ): String {
        if (asset.name.isBlank()) {
            return asset.downloadUrl
        }
        val normalizedVersion = normalizeVersion(version)
        if (normalizedVersion.isBlank()) {
            return asset.downloadUrl
        }
        val releaseTag = "v${encodePathSegment(normalizedVersion)}"
        val fileName = encodePathSegment(asset.name)
        val prefix = when (downloadSource) {
            ApkDownloadSource.WORKER -> normalizedWorkerBaseUrl()?.let {
                "$it/$WORKER_DOWNLOADS_PATH"
            } ?: return asset.downloadUrl
            ApkDownloadSource.GITHUB -> GITHUB_RELEASE_DOWNLOAD_PREFIX
        }
        return "$prefix/$releaseTag/$fileName"
    }

    private fun normalizedWorkerBaseUrl(): String? {
        var normalizedBase = BuildConfig.APP_UPDATE_WORKER_URL.trim().trimEnd('/')
        if (normalizedBase.isBlank()) return null
        if (normalizedBase.endsWith("/$WORKER_UPDATES_PATH", ignoreCase = true)) {
            normalizedBase = normalizedBase.dropLast(WORKER_UPDATES_PATH.length + 1)
        }
        if (normalizedBase.endsWith("/admin/releases", ignoreCase = true)) {
            normalizedBase = normalizedBase.dropLast("/admin/releases".length)
        }
        return normalizedBase
            .ifBlank { null }
            ?.takeIf { it.toHttpUrlOrNull()?.scheme == "https" }
    }

    @VisibleForTesting
    internal fun normalizeSha256(raw: String?): String {
        val normalized = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized.takeIf { SHA256_PATTERN.matches(it) }.orEmpty()
    }

    @VisibleForTesting
    internal fun isSecureDownloadUrl(raw: String?): Boolean {
        return raw?.trim()?.toHttpUrlOrNull()?.scheme == "https"
    }

    @VisibleForTesting
    internal fun isInstallableUpdate(
        version: String,
        currentVersion: String,
        asset: ReleaseAsset?,
        downloadUrl: String
    ): Boolean {
        return asset != null &&
            compareVersions(version, currentVersion) > 0 &&
            isSecureDownloadUrl(downloadUrl) &&
            normalizeSha256(asset.sha256).isNotBlank()
    }

    private fun firstString(raw: JSONObject, vararg keys: String): String {
        return firstValue(raw, *keys)?.toString()?.trim().orEmpty().takeIf {
            it != "null"
        }.orEmpty()
    }

    private fun firstValue(raw: JSONObject, vararg keys: String): Any? {
        for (key in keys) {
            if (!raw.has(key)) continue
            val value = raw.opt(key)
            if (value == null || value == JSONObject.NULL) continue
            if (value is String && value.isBlank()) continue
            return value
        }
        return null
    }

    private fun parseTimestampToMillis(raw: Any?): Long {
        return when (raw) {
            is Number -> normalizeTimestampNumber(raw.toLong())
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isBlank()) {
                    0L
                } else {
                    trimmed.toLongOrNull()?.let { normalizeTimestampNumber(it) }
                        ?: runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrDefault(0L)
                }
            }
            else -> 0L
        }
    }

    private fun normalizeTimestampNumber(value: Long): Long {
        if (value <= 0L) return 0L
        return if (value < 10_000_000_000L) value * 1000L else value
    }

    private fun normalizeEdition(raw: String?): String {
        val normalized = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (normalized) {
            "", "legacy" -> EDITION_STANDARD
            else -> normalized
        }
    }

    private fun isEditionApkAsset(name: String, edition: String): Boolean {
        return name.lowercase(Locale.ROOT).endsWith("-$edition.apk")
    }

    private fun isKnownEditionApkAsset(name: String): Boolean {
        return editionApkNamePattern.matches(name)
    }

    private fun encodePathSegment(raw: String): String {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private fun emptyState(currentVersion: String, checkedAt: Long = 0L): AppUpdateState {
        return AppUpdateState(
            currentVersion = currentVersion,
            latestVersion = currentVersion,
            hasUpdate = false,
            checkedAt = checkedAt,
            publishedAt = 0L,
            releaseUrl = "",
            releaseNotes = "",
            apkName = "",
            apkDownloadUrl = ""
        )
    }

    private fun parseNumericVersionParts(raw: String): List<Int>? {
        if (raw.isBlank()) return null
        val parts = raw.split('.')
        if (parts.any { part -> part.isBlank() || part.any { !it.isDigit() } }) {
            return null
        }
        return parts.map { it.toInt() }
    }

    private fun shouldIncludeTrack(track: ReleaseTrack, includeBeta: Boolean): Boolean {
        return when (track) {
            ReleaseTrack.STABLE -> true
            ReleaseTrack.BETA -> includeBeta
            ReleaseTrack.UNSUPPORTED -> false
        }
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
}

class AppUpdateWorker(
    appContext: Context,
    workerParams: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        if (!AppUpdateManager.isSelfUpdateAvailable()) {
            AppUpdateManager.enforceDistributionPolicy(applicationContext)
            return androidx.work.ListenableWorker.Result.success()
        }
        return runCatching {
            AppUpdateManager.checkNow(applicationContext, force = false)
            androidx.work.ListenableWorker.Result.success()
        }.getOrElse {
            OmniLog.w("AppUpdateWorker", "Periodic app update check failed: ${it.message}")
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
