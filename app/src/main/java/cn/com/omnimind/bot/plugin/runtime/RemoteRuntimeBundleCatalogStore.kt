package cn.com.omnimind.bot.plugin.runtime

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Keeps the APK catalog as the offline baseline and overlays the latest
 * runtime metadata published by the official Skills repository.
 */
internal class RemoteRuntimeBundleCatalogStore(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val client: OkHttpClient = defaultClient,
) {
    private val mutex = Mutex()

    @Volatile
    private var cacheLoaded = false

    @Volatile
    private var cachedJson: String? = null

    @Volatile
    private var lastAttemptAtMs = 0L

    fun current(context: Context, profile: String): RuntimeBundleCatalog {
        loadCache(context)
        return mergeLocal(context, profile, cachedJson)
    }

    suspend fun refresh(
        context: Context,
        profile: String,
        force: Boolean = false,
    ) {
        loadCache(context)
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAtMs < REFRESH_COOLDOWN_MS) return
        mutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force && lockedNow - lastAttemptAtMs < REFRESH_COOLDOWN_MS) return@withLock
            lastAttemptAtMs = lockedNow
            persistAttempt(context, lockedNow)
            runCatching {
                val source = downloadCatalog()
                // Parse before caching so a malformed remote file never poisons
                // the offline fallback.
                RuntimeBundleCatalog.parse(source)
                cachedJson = source
                persistCatalog(context, source, lockedNow)
            }.onFailure { error ->
                // The remote directory is an optional update channel. The APK
                // catalog is the authoritative offline baseline, so an absent
                // directory (or an offline device) must not look like a plugin
                // runtime failure in logcat or the UI.
                OmniLog.i(
                    TAG,
                    "remote_catalog_unavailable; using packaged catalog: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
        // Force profile parsing here so callers fail closed to the local
        // catalog if the remote file has no entry for this build profile.
        mergeLocal(context, profile, cachedJson)
    }

    private suspend fun downloadCatalog(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(endpoint).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RemoteCatalogHttpException(response.code)
            }
            response.body?.string()?.trim()?.takeIf(String::isNotEmpty)
                ?: error("remote_catalog_empty")
        }
    }

    private class RemoteCatalogHttpException(code: Int) :
        IllegalStateException("remote_catalog_http_$code")

    private fun mergeLocal(
        context: Context,
        profile: String,
        remoteJson: String?,
    ): RuntimeBundleCatalog {
        val local = RuntimeBundleCatalog.load(context.assets, profile)
        val remote = remoteJson?.let {
            runCatching { RuntimeBundleCatalog.parse(it) }
                .onFailure { error -> OmniLog.w(TAG, "remote_catalog_cache_invalid: ${error.message}") }
                .getOrNull()
        }
        return remote?.let(local::mergeRemote) ?: local
    }

    private fun loadCache(context: Context) {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded) return
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            cachedJson = preferences.getString(KEY_CATALOG_JSON, null)
            lastAttemptAtMs = preferences.getLong(KEY_LAST_ATTEMPT_MS, 0L)
            cacheLoaded = true
        }
    }

    private fun persistAttempt(context: Context, timestampMs: Long) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ATTEMPT_MS, timestampMs)
            .apply()
    }

    private fun persistCatalog(context: Context, source: String, timestampMs: Long) {
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CATALOG_JSON, source)
            .apply()
    }

    private companion object {
        const val TAG = "[RemoteRuntimeBundleCatalog]"
        const val PREFERENCES_NAME = "omni_remote_plugin_catalog"
        const val KEY_CATALOG_JSON = "catalog_json"
        const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
        const val REFRESH_COOLDOWN_MS = 5 * 60 * 1000L
        const val DEFAULT_ENDPOINT =
            "https://raw.githubusercontent.com/omnimind-ai/OmniBot/main/plugins/catalog.v1.json"
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
