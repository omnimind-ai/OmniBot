package cn.com.omnimind.bot.agent

import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import java.net.URI
import java.util.Locale

interface BrowserHostKeyValueStore {
    fun getString(key: String): String?
    fun putString(
        key: String,
        value: String?
    )

    fun getBoolean(
        key: String,
        defaultValue: Boolean
    ): Boolean

    fun putBoolean(
        key: String,
        value: Boolean
    )

    fun getLong(
        key: String,
        defaultValue: Long
    ): Long

    fun putLong(
        key: String,
        value: Long
    )
}

class MmkvBrowserHostKeyValueStore(
    private val mmkv: MMKV
) : BrowserHostKeyValueStore {
    override fun getString(key: String): String? = mmkv.decodeString(key)

    override fun putString(
        key: String,
        value: String?
    ) {
        if (value == null) {
            mmkv.removeValueForKey(key)
        } else {
            mmkv.encode(key, value)
        }
    }

    override fun getBoolean(
        key: String,
        defaultValue: Boolean
    ): Boolean = mmkv.decodeBool(key, defaultValue)

    override fun putBoolean(
        key: String,
        value: Boolean
    ) {
        mmkv.encode(key, value)
    }

    override fun getLong(
        key: String,
        defaultValue: Long
    ): Long = mmkv.decodeLong(key, defaultValue)

    override fun putLong(
        key: String,
        value: Long
    ) {
        mmkv.encode(key, value)
    }
}

@Serializable
data class BrowserBookmarkRecord(
    val url: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BrowserHistoryRecord(
    val url: String,
    val title: String,
    val visitedAt: Long
)

@Serializable
data class BrowserUserscriptRecord(
    val id: Long,
    val name: String,
    val description: String = "",
    val version: String = "",
    val source: String,
    val sourceUrl: String? = null,
    val updateUrl: String? = null,
    val downloadUrl: String? = null,
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val runAt: String = "document-end",
    val grants: List<String> = emptyList(),
    val blockedGrants: List<String> = emptyList(),
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BrowserDownloadTaskRecord(
    val id: String,
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val destinationPath: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val supportsResume: Boolean = false,
    val errorMessage: String? = null
)

object BrowserUrlNormalizer {
    fun normalizeHttpUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        val lower = trimmed.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null
        }
        if (lower.startsWith("about:") || lower.startsWith("blob:") || lower.startsWith("data:")) {
            return null
        }
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return trimmed
            val normalizedHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            val portPart = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            val path = uri.rawPath?.ifBlank { "/" } ?: "/"
            buildString {
                append(scheme)
                append("://")
                append(normalizedHost)
                append(portPart)
                append(path)
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let {
                    append('?')
                    append(it)
                }
            }
        }.getOrElse { trimmed }
    }

    fun normalizeTitle(
        title: String?,
        fallbackUrl: String
    ): String {
        val trimmed = title?.trim().orEmpty()
        return if (trimmed.isBlank()) fallbackUrl else trimmed
    }
}

class BrowserHostStore(
    private val workspaceId: String,
    private val keyValueStore: BrowserHostKeyValueStore,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val KEY_BOOKMARKS = "bookmarks_json"
        private const val KEY_HISTORY = "history_json"
        private const val KEY_DOWNLOADS = "downloads_json"
        private const val KEY_USERSCRIPTS = "userscripts_json"
        private const val KEY_PENDING_USERSCRIPT = "pending_userscript_json"
        private const val KEY_DESKTOP_MODE = "desktop_mode_enabled"
        private const val KEY_SCRIPT_COUNTER = "userscript_counter"
        private const val KEY_VALUES_PREFIX = "userscript_values_"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(workspaceId: String): BrowserHostStore {
            val mmkv = MMKV.defaultMMKV()
                ?: error("MMKV.defaultMMKV() unavailable")
            return BrowserHostStore(
                workspaceId = workspaceId,
                keyValueStore = MmkvBrowserHostKeyValueStore(mmkv)
            )
        }
    }

    private fun scopedKey(name: String): String = "browser_host.$workspaceId.$name"

    fun listBookmarks(): List<BrowserBookmarkRecord> {
        return decodeList<BrowserBookmarkRecord>(KEY_BOOKMARKS)
            .sortedByDescending { it.updatedAt }
    }

    fun listHistory(): List<BrowserHistoryRecord> {
        return decodeList<BrowserHistoryRecord>(KEY_HISTORY)
            .sortedByDescending { it.visitedAt }
    }

    fun listDownloads(): List<BrowserDownloadTaskRecord> {
        return decodeList<BrowserDownloadTaskRecord>(KEY_DOWNLOADS)
            .sortedByDescending { it.updatedAt }
    }

    fun listUserscripts(): List<BrowserUserscriptRecord> {
        return decodeList<BrowserUserscriptRecord>(KEY_USERSCRIPTS)
            .sortedByDescending { it.updatedAt }
    }

    fun readPendingUserscript(): BrowserUserscriptRecord? {
        val raw = keyValueStore.getString(scopedKey(KEY_PENDING_USERSCRIPT)).orEmpty()
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            json.decodeFromString<BrowserUserscriptRecord>(raw)
        }.getOrNull()
    }

    fun writePendingUserscript(value: BrowserUserscriptRecord?) {
        val encoded = value?.let { json.encodeToString(it) }
        keyValueStore.putString(scopedKey(KEY_PENDING_USERSCRIPT), encoded)
    }

    fun nextUserscriptId(): Long {
        val next = keyValueStore.getLong(scopedKey(KEY_SCRIPT_COUNTER), 0L) + 1L
        keyValueStore.putLong(scopedKey(KEY_SCRIPT_COUNTER), next)
        return next
    }

    fun isBookmarked(url: String?): Boolean {
        val normalized = BrowserUrlNormalizer.normalizeHttpUrl(url) ?: return false
        return listBookmarks().any { it.url == normalized }
    }

    fun toggleBookmark(
        url: String?,
        title: String?
    ): Boolean {
        val normalized = BrowserUrlNormalizer.normalizeHttpUrl(url) ?: return false
        val now = clock()
        val current = listBookmarks()
        val existing = current.firstOrNull { it.url == normalized }
        return if (existing != null) {
            writeList(KEY_BOOKMARKS, current.filterNot { it.url == normalized })
            false
        } else {
            val updated = buildList {
                add(
                    BrowserBookmarkRecord(
                        url = normalized,
                        title = BrowserUrlNormalizer.normalizeTitle(title, normalized),
                        createdAt = now,
                        updatedAt = now
                    )
                )
                addAll(current)
            }
            writeList(KEY_BOOKMARKS, updated)
            true
        }
    }

    fun removeBookmark(url: String?) {
        val normalized = BrowserUrlNormalizer.normalizeHttpUrl(url) ?: return
        writeList(KEY_BOOKMARKS, listBookmarks().filterNot { it.url == normalized })
    }

    fun clearHistory() {
        writeList(KEY_HISTORY, emptyList<BrowserHistoryRecord>())
    }

    fun updateTitle(
        url: String?,
        title: String?
    ) {
        val normalized = BrowserUrlNormalizer.normalizeHttpUrl(url) ?: return
        val normalizedTitle = BrowserUrlNormalizer.normalizeTitle(title, normalized)
        val bookmarks = listBookmarks().map { item ->
            if (item.url == normalized) {
                item.copy(
                    title = normalizedTitle,
                    updatedAt = clock()
                )
            } else {
                item
            }
        }
        val history = listHistory().map { item ->
            if (item.url == normalized) {
                item.copy(title = normalizedTitle)
            } else {
                item
            }
        }
        writeList(KEY_BOOKMARKS, bookmarks)
        writeList(KEY_HISTORY, history)
    }

    fun recordVisit(
        url: String?,
        title: String?,
        isReload: Boolean
    ) {
        val normalized = BrowserUrlNormalizer.normalizeHttpUrl(url) ?: return
        if (isReload) {
            updateTitle(normalized, title)
            return
        }
        val now = clock()
        val normalizedTitle = BrowserUrlNormalizer.normalizeTitle(title, normalized)
        val current = listHistory()
        val updated = buildList {
            add(
                BrowserHistoryRecord(
                    url = normalized,
                    title = normalizedTitle,
                    visitedAt = now
                )
            )
            addAll(current.filterNot { it.url == normalized })
        }
        writeList(KEY_HISTORY, updated)
    }

    fun getDesktopModeEnabled(defaultValue: Boolean = false): Boolean {
        return keyValueStore.getBoolean(scopedKey(KEY_DESKTOP_MODE), defaultValue)
    }

    fun setDesktopModeEnabled(enabled: Boolean) {
        keyValueStore.putBoolean(scopedKey(KEY_DESKTOP_MODE), enabled)
    }

    fun upsertDownload(record: BrowserDownloadTaskRecord) {
        val next = buildList {
            add(record)
            addAll(listDownloads().filterNot { it.id == record.id })
        }
        writeList(KEY_DOWNLOADS, next)
    }

    fun removeDownload(id: String) {
        writeList(KEY_DOWNLOADS, listDownloads().filterNot { it.id == id })
    }

    fun upsertUserscript(record: BrowserUserscriptRecord) {
        val next = buildList {
            add(record.copy(updatedAt = clock()))
            addAll(listUserscripts().filterNot { it.id == record.id })
        }
        writeList(KEY_USERSCRIPTS, next)
    }

    fun removeUserscript(id: Long) {
        writeList(KEY_USERSCRIPTS, listUserscripts().filterNot { it.id == id })
        keyValueStore.putString(scopedKey("$KEY_VALUES_PREFIX$id"), null)
    }

    fun getUserscriptValueMap(scriptId: Long): MutableMap<String, String> {
        val raw = keyValueStore.getString(scopedKey("$KEY_VALUES_PREFIX$scriptId")).orEmpty()
        if (raw.isBlank()) {
            return linkedMapOf()
        }
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw).toMutableMap()
        }.getOrElse {
            linkedMapOf()
        }
    }

    fun putUserscriptValue(
        scriptId: Long,
        key: String,
        value: String?
    ) {
        val values = getUserscriptValueMap(scriptId)
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
        keyValueStore.putString(
            scopedKey("$KEY_VALUES_PREFIX$scriptId"),
            json.encodeToString(values)
        )
    }

    private inline fun <reified T> decodeList(key: String): List<T> {
        val raw = keyValueStore.getString(scopedKey(key)).orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<T>>(raw)
        }.getOrElse {
            emptyList()
        }
    }

    private inline fun <reified T> writeList(
        key: String,
        value: List<T>
    ) {
        keyValueStore.putString(
            scopedKey(key),
            json.encodeToString(value)
        )
    }
}
