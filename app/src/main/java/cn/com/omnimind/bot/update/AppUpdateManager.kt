package cn.com.omnimind.bot.update

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.com.omnimind.baselib.account.CloudServiceAccessState
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfig
import cn.com.omnimind.baselib.llm.OfficialVlmOperationConfigStore
import cn.com.omnimind.baselib.service.DeviceInfoService
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.manager.ExternalApkInstallResult
import cn.com.omnimind.bot.manager.ExternalApkInstaller
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    val cloudServicePolicyKnown: Boolean = false,
    val cloudServicePolicyEnabled: Boolean = false,
    val cloudServiceAccessAllowed: Boolean = false,
    val cloudServiceMinimumVersion: String = "",
    val cloudServicePolicyMessage: String = "",
    val cloudServicePolicyCheckedAt: Long = 0L,
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
        "cloudServicePolicyKnown" to cloudServicePolicyKnown,
        "cloudServicePolicyEnabled" to cloudServicePolicyEnabled,
        "cloudServiceAccessAllowed" to cloudServiceAccessAllowed,
        "cloudServiceMinimumVersion" to cloudServiceMinimumVersion,
        "cloudServicePolicyMessage" to cloudServicePolicyMessage,
        "cloudServicePolicyCheckedAt" to cloudServicePolicyCheckedAt,
    )
}

@VisibleForTesting
internal data class ReleaseAsset(
    val name: String,
    val downloadUrl: String
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

private data class ParsedCloudServicePolicy(
    val enabled: Boolean,
    val access: CloudServiceAccessState,
    val checkedAt: Long,
)

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
    private const val KEY_APK_DOWNLOAD_SOURCE = "apk_download_source"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_CLOUD_SERVICE_POLICY_KNOWN = "cloud_service_policy_known"
    private const val KEY_CLOUD_SERVICE_POLICY_ENABLED = "cloud_service_policy_enabled"
    private const val KEY_CLOUD_SERVICE_MINIMUM_VERSION = "cloud_service_minimum_version"
    private const val KEY_CLOUD_SERVICE_POLICY_MESSAGE = "cloud_service_policy_message"
    private const val KEY_CLOUD_SERVICE_POLICY_CHECKED_AT = "cloud_service_policy_checked_at"

    private const val WORKER_UPDATES_PATH = "updates"
    private const val WORKER_DOWNLOADS_PATH = "downloads"
    private const val GITHUB_RELEASE_DOWNLOAD_PREFIX =
        "https://github.com/omnimind-ai/OpenOmniBot/releases/download"
    private const val WORK_NAME = "app_update_periodic_check"
    private const val PERIODIC_CHECK_HOURS = 12L
    private const val SILENT_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val CLOUD_SERVICE_POLICY_MAX_AGE_MS = 24 * 60 * 60 * 1000L
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

    fun schedulePeriodicChecks(context: Context) {
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
        schedulePeriodicChecks(context)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                checkNow(context.applicationContext, force = true)
            }.onFailure {
                OmniLog.w(TAG, "Silent app update check failed: ${it.message}")
            }
        }
    }

    fun getCachedStatus(context: Context): AppUpdateState {
        val appContext = context.applicationContext
        return readState(
            context = appContext,
            currentVersion = currentVersion(appContext),
            includeBeta = isBetaOptIn(appContext)
        )
    }

    fun getCloudServiceAccessState(context: Context): CloudServiceAccessState {
        val appContext = context.applicationContext
        val state = readState(
            context = appContext,
            currentVersion = currentVersion(appContext),
            includeBeta = isBetaOptIn(appContext),
        )
        return CloudServiceAccessState(
            allowed = state.cloudServiceAccessAllowed,
            policyKnown = state.cloudServicePolicyKnown,
            currentVersion = state.currentVersion,
            minimumVersion = state.cloudServiceMinimumVersion,
            message = state.cloudServicePolicyMessage,
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
        prefs.edit().apply {
            putBoolean(KEY_BETA_OPT_IN, enabled)
            if (changed) {
                putLong(KEY_CHECKED_AT, 0L)
            }
        }.apply()
        return enabled
    }

    internal fun setApkDownloadSource(context: Context, rawValue: String?): ApkDownloadSource {
        val source = ApkDownloadSource.fromValue(rawValue)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APK_DOWNLOAD_SOURCE, source.value)
            .apply()
        return source
    }

    suspend fun checkNow(context: Context, force: Boolean): AppUpdateState {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val currentVersion = currentVersion(appContext)
        val includeBeta = isBetaOptIn(appContext)
        val downloadSource = getApkDownloadSource(appContext)
        val cached = readState(appContext, currentVersion, includeBeta)
        if (!force && now - cached.checkedAt < SILENT_CHECK_INTERVAL_MS) {
            return cached
        }

        val fetched = fetchLatestReleaseState(
            currentVersion = currentVersion,
            includeBeta = includeBeta,
            downloadSource = downloadSource,
            deviceStatsParams = buildDeviceStatsParams(appContext)
        ).copy(checkedAt = now)
        saveState(appContext, fetched)
        return fetched
    }

    suspend fun installLatestApk(context: Context): ExternalApkInstallResult {
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
            displayName = "OpenOmniBot"
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
        val policyEnabled = prefs.getBoolean(KEY_CLOUD_SERVICE_POLICY_ENABLED, false)
        val policyCheckedAt = prefs.getLong(KEY_CLOUD_SERVICE_POLICY_CHECKED_AT, 0L)
        val cloudServiceAccess = resolveCloudServiceAccessState(
            currentVersion = currentVersion,
            policyKnown = prefs.getBoolean(KEY_CLOUD_SERVICE_POLICY_KNOWN, false),
            policyEnabled = policyEnabled,
            minimumVersion = prefs.getString(KEY_CLOUD_SERVICE_MINIMUM_VERSION, "").orEmpty(),
            message = prefs.getString(KEY_CLOUD_SERVICE_POLICY_MESSAGE, "").orEmpty(),
            checkedAt = policyCheckedAt,
        )
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
            cloudServicePolicyKnown = cloudServiceAccess.policyKnown,
            cloudServicePolicyEnabled = policyEnabled,
            cloudServiceAccessAllowed = cloudServiceAccess.allowed,
            cloudServiceMinimumVersion = cloudServiceAccess.minimumVersion,
            cloudServicePolicyMessage = cloudServiceAccess.message,
            cloudServicePolicyCheckedAt = policyCheckedAt,
        )
        val stateWithPreferredSource = applyPreferredDownloadSource(
            storedState,
            getApkDownloadSource(context)
        )
        if (!shouldIncludeTrack(classifyReleaseTrack(stateWithPreferredSource.latestVersion), includeBeta)) {
            return clearReleaseState(stateWithPreferredSource)
        }
        return stateWithPreferredSource.copy(
            hasUpdate = stateWithPreferredSource.hasUpdate &&
                compareVersions(stateWithPreferredSource.latestVersion, currentVersion) > 0
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
            .putBoolean(KEY_CLOUD_SERVICE_POLICY_KNOWN, state.cloudServicePolicyKnown)
            .putBoolean(KEY_CLOUD_SERVICE_POLICY_ENABLED, state.cloudServicePolicyEnabled)
            .putString(KEY_CLOUD_SERVICE_MINIMUM_VERSION, state.cloudServiceMinimumVersion)
            .putString(KEY_CLOUD_SERVICE_POLICY_MESSAGE, state.cloudServicePolicyMessage)
            .putLong(KEY_CLOUD_SERVICE_POLICY_CHECKED_AT, state.cloudServicePolicyCheckedAt)
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
            cacheOfficialVlmOperationConfig(body)
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

    private fun cacheOfficialVlmOperationConfig(payloadJson: String) {
        val config = parseOfficialVlmOperationConfig(payloadJson) ?: return
        val saved = OfficialVlmOperationConfigStore.saveConfig(config)
        OmniLog.i(
            TAG,
            "Official VLM config cached: enabled=${saved.enabled}, " +
                "configured=${saved.isConfigured()}"
        )
    }

    @VisibleForTesting
    internal fun parseOfficialVlmOperationConfig(
        payloadJson: String
    ): OfficialVlmOperationConfig? {
        val payload = runCatching {
            JsonParser.parseString(payloadJson).asJsonObject
        }.getOrNull() ?: return null
        val raw = firstJsonObject(
            payload,
            "officialVlmOperation",
            "official_vlm_operation",
            "officialVLMOperation"
        ) ?: return null
        return OfficialVlmOperationConfig(
            enabled = raw.get("enabled")?.runCatching { asBoolean }?.getOrNull() ?: false,
            apiBase = firstJsonString(
                raw,
                "apiBase",
                "api_base",
                "baseUrl",
                "base_url",
                "url"
            ),
            model = firstJsonString(raw, "model", "modelId", "model_id"),
            wireApi = firstJsonString(raw, "wireApi", "wire_api")
                .ifEmpty { OpenAiWireApi.CHAT_COMPLETIONS }
        )
    }

    private fun firstJsonObject(raw: JsonObject, vararg keys: String): JsonObject? {
        return keys.firstNotNullOfOrNull { key ->
            raw.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        }
    }

    private fun firstJsonString(raw: JsonObject, vararg keys: String): String {
        return keys.firstNotNullOfOrNull { key ->
            raw.get(key)
                ?.takeUnless { it.isJsonNull }
                ?.runCatching { asString.trim() }
                ?.getOrNull()
                ?.takeIf(String::isNotEmpty)
        }.orEmpty()
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
        val builder = updatesUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("currentVersion", normalizeVersion(currentVersion))
            ?.addQueryParameter("includeBeta", includeBeta.toString())
            ?.addQueryParameter("edition", normalizeEdition(edition))
            ?.addQueryParameter("source", downloadSource.value)
            ?: return null
        deviceStatsParams.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.addQueryParameter(key, value)
            }
        }
        return builder.build()
    }

    /**
     * Anonymous, per-install statistics sent to the website backend so the admin
     * console can chart device models, OS versions and daily active checks.
     * Contains no account or hardware identifiers;
     * the install id is a random UUID generated on first use.
     */
    private fun buildDeviceStatsParams(context: Context): Map<String, String> {
        return mapOf(
            "deviceBrand" to (android.os.Build.BRAND ?: ""),
            "deviceModel" to (android.os.Build.MODEL ?: ""),
            "osVersion" to (android.os.Build.VERSION.RELEASE ?: ""),
            "sdkInt" to android.os.Build.VERSION.SDK_INT.toString(),
            "installId" to installId(context)
        )
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
        val cloudServicePolicy = parseCloudServicePolicy(
            payload = payload,
            currentVersion = currentVersion,
            checkedAt = checkedAt,
        )
        val release = payload.optJSONObject("release") ?: payload
        val version = normalizeVersion(
            firstString(release, "latestVersion", "version", "tag", "tagName", "tag_name")
        )
        val track = parseReleaseTrack(release, version)
        if (version.isBlank() || !shouldIncludeTrack(track, includeBeta)) {
            return applyCloudServicePolicy(
                emptyState(currentVersion, checkedAt = checkedAt),
                cloudServicePolicy,
            )
        }

        val assets = parseWorkerAssets(release.optJSONArray("assets"), downloadSource)
        val payloadAsset = releaseAssetFromPayload(release, downloadSource)
        val preferredAsset = selectPreferredApkAsset(assets, edition) ?: payloadAsset
        val hasInstallableUpdate = preferredAsset != null &&
            compareVersions(version, currentVersion) > 0
        val downloadUrl = preferredAsset?.let { asset ->
            asset.downloadUrl.ifBlank {
                resolveApkDownloadUrl(downloadSource, version, asset)
            }
        }.orEmpty()

        return applyCloudServicePolicy(AppUpdateState(
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
            apkDownloadUrl = downloadUrl
        ), cloudServicePolicy)
    }

    private fun parseCloudServicePolicy(
        payload: JSONObject,
        currentVersion: String,
        checkedAt: Long,
    ): ParsedCloudServicePolicy {
        val raw = payload.optJSONObject("cloudServicePolicy")
        if (raw == null) {
            return ParsedCloudServicePolicy(
                enabled = false,
                access = resolveCloudServiceAccessState(
                    currentVersion = currentVersion,
                    policyKnown = false,
                    policyEnabled = false,
                    minimumVersion = "",
                    message = "",
                    checkedAt = checkedAt,
                    now = checkedAt,
                ),
                checkedAt = checkedAt,
            )
        }
        val enabledValue = raw.opt("enabled")
        if (raw.optInt("schemaVersion", 0) != 1 || enabledValue !is Boolean) {
            return ParsedCloudServicePolicy(
                enabled = false,
                access = resolveCloudServiceAccessState(
                    currentVersion = currentVersion,
                    policyKnown = false,
                    policyEnabled = false,
                    minimumVersion = "",
                    message = "云服务最低版本策略无效，请稍后重试",
                    checkedAt = checkedAt,
                    now = checkedAt,
                ),
                checkedAt = checkedAt,
            )
        }
        val enabled = enabledValue
        val minimumVersion = normalizeVersion(
            firstString(raw, "minimumVersion", "minimum_version", "minVersion", "min_version")
        )
        val message = firstString(raw, "message", "reason")
        return ParsedCloudServicePolicy(
            enabled = enabled,
            access = resolveCloudServiceAccessState(
                currentVersion = currentVersion,
                policyKnown = true,
                policyEnabled = enabled,
                minimumVersion = minimumVersion,
                message = message,
                checkedAt = checkedAt,
                now = checkedAt,
            ),
            checkedAt = checkedAt,
        )
    }

    private fun applyCloudServicePolicy(
        state: AppUpdateState,
        policy: ParsedCloudServicePolicy,
    ): AppUpdateState = state.copy(
        cloudServicePolicyKnown = policy.access.policyKnown,
        cloudServicePolicyEnabled = policy.enabled,
        cloudServiceAccessAllowed = policy.access.allowed,
        cloudServiceMinimumVersion = policy.access.minimumVersion,
        cloudServicePolicyMessage = policy.access.message,
        cloudServicePolicyCheckedAt = policy.checkedAt,
    )

    @VisibleForTesting
    internal fun resolveCloudServiceAccessState(
        currentVersion: String,
        policyKnown: Boolean,
        policyEnabled: Boolean,
        minimumVersion: String,
        message: String,
        checkedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): CloudServiceAccessState {
        val normalizedCurrent = normalizeVersion(currentVersion)
        val normalizedMinimum = normalizeVersion(minimumVersion)
        val policyFresh = checkedAt > 0L && now >= checkedAt &&
            now - checkedAt <= CLOUD_SERVICE_POLICY_MAX_AGE_MS
        if (!policyKnown || !policyFresh) {
            return CloudServiceAccessState(
                allowed = false,
                policyKnown = false,
                currentVersion = normalizedCurrent,
                minimumVersion = normalizedMinimum,
                message = "无法验证云服务最低版本，请联网检查更新",
            )
        }
        if (!policyEnabled) {
            return CloudServiceAccessState(
                allowed = true,
                policyKnown = true,
                currentVersion = normalizedCurrent,
            )
        }
        if (
            parseNumericVersionParts(normalizedCurrent) == null ||
            parseNumericVersionParts(normalizedMinimum) == null
        ) {
            return CloudServiceAccessState(
                allowed = false,
                policyKnown = false,
                currentVersion = normalizedCurrent,
                minimumVersion = normalizedMinimum,
                message = "云服务最低版本策略无效，请稍后重试",
            )
        }
        val allowed = compareVersions(normalizedCurrent, normalizedMinimum) >= 0
        return CloudServiceAccessState(
            allowed = allowed,
            policyKnown = true,
            currentVersion = normalizedCurrent,
            minimumVersion = normalizedMinimum,
            message = if (allowed) {
                ""
            } else {
                message.ifBlank {
                    "当前版本过旧，请升级至 v$normalizedMinimum 或更高版本后使用账号与官方云服务"
                }
            },
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
            assets += ReleaseAsset(name = name, downloadUrl = downloadUrl)
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
        return ReleaseAsset(name = name, downloadUrl = downloadUrl)
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
                    downloadUrl = state.apkDownloadUrl
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
        return normalizedBase.ifBlank { null }
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
        return EDITION_STANDARD
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

    private fun clearReleaseState(state: AppUpdateState): AppUpdateState = state.copy(
        latestVersion = state.currentVersion,
        hasUpdate = false,
        publishedAt = 0L,
        releaseUrl = "",
        releaseNotes = "",
        apkName = "",
        apkDownloadUrl = "",
    )

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
}

class AppUpdateWorker(
    appContext: Context,
    workerParams: androidx.work.WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return runCatching {
            AppUpdateManager.checkNow(applicationContext, force = true)
            androidx.work.ListenableWorker.Result.success()
        }.getOrElse {
            OmniLog.w("AppUpdateWorker", "Periodic app update check failed: ${it.message}")
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
