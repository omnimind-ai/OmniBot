package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.SensitiveDataSanitizer
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class AiRequestLogEntry(
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String = UUID.randomUUID().toString(),
    @field:SerializedName(value = "createdAt", alternate = ["b"])
    val createdAt: Long = System.currentTimeMillis(),
    @field:SerializedName(value = "label", alternate = ["c"])
    val label: String = "",
    @field:SerializedName(value = "model", alternate = ["d"])
    val model: String = "",
    @field:SerializedName(value = "protocolType", alternate = ["e"])
    val protocolType: String = "openai_compatible",
    @field:SerializedName(value = "url", alternate = ["f"])
    val url: String = "",
    @field:SerializedName(value = "method", alternate = ["g"])
    val method: String = "POST",
    @field:SerializedName(value = "stream", alternate = ["h"])
    val stream: Boolean = false,
    @field:SerializedName(value = "statusCode", alternate = ["i"])
    val statusCode: Int? = null,
    @field:SerializedName(value = "success", alternate = ["j"])
    val success: Boolean = true,
    @field:SerializedName(value = "requestJson", alternate = ["k"])
    val requestJson: String = "",
    @field:SerializedName(value = "responseJson", alternate = ["l"])
    val responseJson: String = "",
    @field:SerializedName(value = "errorMessage", alternate = ["m"])
    val errorMessage: String? = null,
    @field:SerializedName(value = "requestSizeBytes", alternate = ["n"])
    val requestSizeBytes: Int = 0,
    @field:SerializedName(value = "responseSizeBytes", alternate = ["o"])
    val responseSizeBytes: Int = 0,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "createdAt" to createdAt,
            "label" to label,
            "model" to model,
            "protocolType" to protocolType,
            "url" to url,
            "method" to method,
            "stream" to stream,
            "statusCode" to statusCode,
            "success" to success,
            "requestJson" to requestJson,
            "responseJson" to responseJson,
            "errorMessage" to errorMessage,
            "requestSizeBytes" to requestSizeBytes,
            "responseSizeBytes" to responseSizeBytes
        )
    }
}

object AiRequestLogStore {
    private const val TAG = "AiRequestLogStore"
    private const val KEY_RECENT_AI_REQUEST_LOGS = "recent_ai_request_logs_v1"
    private const val MAX_LOG_COUNT = 10
    private const val MAX_CAPTURE_CHARS = 64 * 1024

    @Volatile
    private var contentCaptureEnabled: Boolean = false

    @Volatile
    private var legacyContentScrubCompleted: Boolean = false

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val listType = object : TypeToken<List<AiRequestLogEntry>>() {}.type

    @Synchronized
    fun append(entry: AiRequestLogEntry) {
        val mmkv = MMKV.defaultMMKV()
        val safeEntry = sanitizeEntry(entry)
        val current = readEntriesLocked(mmkv).map(::sanitizeEntry)
        val updated = buildList {
            add(safeEntry)
            current.forEach { existing ->
                if (existing.id != safeEntry.id) {
                    add(existing)
                }
            }
        }.take(MAX_LOG_COUNT)
        mmkv.encode(KEY_RECENT_AI_REQUEST_LOGS, gson.toJson(updated))
    }

    @Synchronized
    fun listRecent(limit: Int = MAX_LOG_COUNT): List<AiRequestLogEntry> {
        val mmkv = MMKV.defaultMMKV()
        val safeLimit = limit.coerceIn(1, MAX_LOG_COUNT)
        val current = readEntriesLocked(mmkv)
        val sanitized = current.map(::sanitizeEntry)
        if (sanitized != current) {
            mmkv.encode(KEY_RECENT_AI_REQUEST_LOGS, gson.toJson(sanitized.take(MAX_LOG_COUNT)))
        }
        return sanitized.take(safeLimit)
    }

    @Synchronized
    fun setContentCaptureEnabled(enabled: Boolean) {
        // Never make pre-migration request/response bodies readable after a failed startup scrub.
        contentCaptureEnabled = enabled && legacyContentScrubCompleted
        if (!enabled) {
            val mmkv = MMKV.defaultMMKV()
            val sanitized = readEntriesLocked(mmkv).map(::sanitizeEntry)
            mmkv.encode(KEY_RECENT_AI_REQUEST_LOGS, gson.toJson(sanitized.take(MAX_LOG_COUNT)))
        }
    }

    fun isContentCaptureEnabled(): Boolean = contentCaptureEnabled

    /**
     * Runs synchronously immediately after MMKV initialization. On any decode/write/verification
     * failure the entire legacy value is removed, and content capture stays disabled.
     */
    @Synchronized
    fun initializeAndScrubLegacyContent(): Boolean {
        contentCaptureEnabled = false
        legacyContentScrubCompleted = false
        val mmkv = MMKV.defaultMMKV()
        return runCatching {
            val raw = mmkv.decodeString(KEY_RECENT_AI_REQUEST_LOGS)?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val scrubbed = scrubLegacyContentJson(raw)
                if (scrubbed == null || !mmkv.encode(KEY_RECENT_AI_REQUEST_LOGS, scrubbed)) {
                    return@runCatching removeLegacyValueAndVerify(mmkv)
                }
                val persisted = mmkv.decodeString(KEY_RECENT_AI_REQUEST_LOGS)?.trim().orEmpty()
                if (!containsMetadataOnlyEntries(persisted)) {
                    return@runCatching removeLegacyValueAndVerify(mmkv)
                }
            }
            true
        }.getOrElse {
            OmniLog.w(TAG, "legacy AI request log scrub failed; removing stored logs")
            runCatching { removeLegacyValueAndVerify(mmkv) }.getOrDefault(false)
        }.also { success ->
            legacyContentScrubCompleted = success
        }
    }

    @Synchronized
    fun clear() {
        MMKV.defaultMMKV().removeValueForKey(KEY_RECENT_AI_REQUEST_LOGS)
    }

    fun prettyJsonOrRaw(raw: String?): String {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return ""
        }
        return runCatching {
            when {
                normalized.startsWith("{") -> JSONObject(normalized).toString(2)
                normalized.startsWith("[") -> JSONArray(normalized).toString(2)
                else -> normalized
            }
        }.getOrElse { normalized }
    }

    fun buildStreamResponseJson(events: List<String>): String {
        if (events.isEmpty()) {
            return ""
        }
        val jsonArray = JSONArray()
        events.forEach { raw ->
            val normalized = raw.trim()
            if (normalized.isEmpty() || normalized == "[DONE]") {
                return@forEach
            }
            jsonArray.put(parseJsonLikeValue(normalized))
        }
        return if (jsonArray.length() == 0) "" else jsonArray.toString(2)
    }

    private fun parseJsonLikeValue(raw: String): Any {
        return runCatching {
            when {
                raw.startsWith("{") -> JSONObject(raw)
                raw.startsWith("[") -> JSONArray(raw)
                else -> raw
            }
        }.getOrElse { raw }
    }

    private fun readEntriesLocked(mmkv: MMKV): List<AiRequestLogEntry> {
        val raw = mmkv.decodeString(KEY_RECENT_AI_REQUEST_LOGS)?.trim().orEmpty()
        if (raw.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<AiRequestLogEntry>>(raw, listType) ?: emptyList()
        }.getOrElse {
            // Do not include parser messages because they can contain fragments of persisted data.
            OmniLog.w(TAG, "read AI request logs failed")
            emptyList()
        }
    }

    internal fun scrubLegacyContentJson(raw: String): String? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) return "[]"
        return runCatching {
            val entries = gson.fromJson<List<AiRequestLogEntry>>(normalized, listType)
                ?: return@runCatching null
            gson.toJson(
                entries
                    .take(MAX_LOG_COUNT)
                    .map { entry -> sanitizeEntry(entry, captureContent = false) },
            )
        }.getOrNull()
    }

    private fun containsMetadataOnlyEntries(raw: String): Boolean {
        if (raw.isEmpty()) return false
        return runCatching {
            val entries = gson.fromJson<List<AiRequestLogEntry>>(raw, listType) ?: return false
            entries.all { entry -> entry.requestJson.isEmpty() && entry.responseJson.isEmpty() }
        }.getOrDefault(false)
    }

    private fun removeLegacyValueAndVerify(mmkv: MMKV): Boolean {
        mmkv.removeValueForKey(KEY_RECENT_AI_REQUEST_LOGS)
        return !mmkv.containsKey(KEY_RECENT_AI_REQUEST_LOGS)
    }

    private fun sanitizeEntry(
        entry: AiRequestLogEntry,
        captureContent: Boolean = contentCaptureEnabled,
    ): AiRequestLogEntry {
        val requestBytes = entry.requestSizeBytes.takeIf { it > 0 }
            ?: entry.requestJson.toByteArray(Charsets.UTF_8).size
        val responseBytes = entry.responseSizeBytes.takeIf { it > 0 }
            ?: entry.responseJson.toByteArray(Charsets.UTF_8).size
        return entry.copy(
            url = SensitiveDataSanitizer.sanitize(entry.url, maxChars = 2_048),
            requestJson = if (captureContent) {
                SensitiveDataSanitizer.sanitize(entry.requestJson, maxChars = MAX_CAPTURE_CHARS)
            } else {
                ""
            },
            responseJson = if (captureContent) {
                SensitiveDataSanitizer.sanitize(entry.responseJson, maxChars = MAX_CAPTURE_CHARS)
            } else {
                ""
            },
            errorMessage = entry.errorMessage?.let {
                SensitiveDataSanitizer.sanitize(it, maxChars = 1_024)
            },
            requestSizeBytes = requestBytes,
            responseSizeBytes = responseBytes,
        )
    }
}
