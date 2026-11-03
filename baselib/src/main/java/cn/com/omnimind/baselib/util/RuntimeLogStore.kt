package cn.com.omnimind.baselib.util

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.tencent.mmkv.MMKV
import java.io.StringReader
import java.util.UUID

data class RuntimeLogEntry(
    @field:SerializedName(value = "id", alternate = ["a"])
    val id: String = UUID.randomUUID().toString(),
    @field:SerializedName(value = "createdAt", alternate = ["b"])
    val createdAt: Long = System.currentTimeMillis(),
    @field:SerializedName(value = "level", alternate = ["c"])
    val level: String = "INFO",
    @field:SerializedName(value = "tag", alternate = ["d"])
    val tag: String = "",
    @field:SerializedName(value = "message", alternate = ["e"])
    val message: String = "",
    @field:SerializedName(value = "stackTrace", alternate = ["f"])
    val stackTrace: String? = null,
    @field:SerializedName(value = "isCrash", alternate = ["g"])
    val isCrash: Boolean = false,
) {
    fun toMap(): Map<String, Any?> {
        return linkedMapOf(
            "id" to id,
            "createdAt" to createdAt,
            "level" to level,
            "tag" to tag,
            "message" to message,
            "stackTrace" to stackTrace,
            "isCrash" to isCrash,
        )
    }
}

object RuntimeLogStore {
    @Synchronized
    fun append(entry: RuntimeLogEntry) {
        if (entry.isSuppressedRuntimeLogNoise()) return
        journal()?.append(entry)
    }

    @Synchronized
    fun listRecent(limit: Int = RuntimeLogLimits.MAX_COUNT): List<RuntimeLogEntry> =
        journal()?.listRecent(limit).orEmpty()

    @Synchronized
    fun clear() {
        journal()?.clear()
    }

    private fun journal(): RuntimeLogJournal? = MMKV.defaultMMKV()?.let { mmkv ->
        RuntimeLogJournal(object : RuntimeLogStorage {
            override fun contains(key: String) = mmkv.containsKey(key)
            override fun size(key: String) = mmkv.getValueActualSize(key)
            override fun read(key: String) = mmkv.decodeString(key)
            override fun write(key: String, value: String) = mmkv.encode(key, value)
            override fun remove(key: String) = mmkv.removeValueForKey(key)
        })
    }
}

/** Kept separate from MMKV so limits and migration can be exercised on the JVM. */
internal interface RuntimeLogStorage {
    fun contains(key: String): Boolean
    fun size(key: String): Int
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String)
}

internal class RuntimeLogJournal(private val storage: RuntimeLogStorage) {
    companion object {
        const val LEGACY_KEY = "runtime_logs_v1"
        const val KEY = "runtime_logs_v2"
        private val gson = GsonBuilder().disableHtmlEscaping().create()

        /**
         * Conservative upper bound: MMKV 2.4.2 encodeString uses JNI GetStringUTFChars.
         * Budget for modified
         * UTF-8 (up to three bytes per UTF-16 unit), not the channel's UTF-8.
         * getValueActualSize strips the protobuf length prefix, so it needs no
         * additional varint allowance. This also bounds implementations which
         * encode valid surrogate pairs as four bytes instead of six.
         */
        internal fun storageStringByteUpperBound(value: String): Int {
            var bytes = 0
            for (char in value) {
                bytes += when {
                    char.code in 1..0x7f -> 1
                    char.code <= 0x7ff -> 2
                    else -> 3
                }
            }
            return bytes
        }

        /** These are the exact value types in RuntimeLogEntry.toMap(). */
        internal fun channelEntryBytes(entry: RuntimeLogEntry): Int {
            fun stringBytes(value: String): Int {
                val bytes = value.toByteArray(Charsets.UTF_8).size
                val sizeBytes = when {
                    bytes < 254 -> 1
                    bytes <= 65535 -> 3
                    else -> 5
                }
                return 1 + sizeBytes + bytes
            }
            // Map type + seven-field count; longs are encoded as one tag + eight bytes.
            return 2 + entry.toMap().entries.sumOf { (key, value) ->
                stringBytes(key) + when (value) {
                    is String -> stringBytes(value)
                    is Long -> 9
                    null, is Boolean -> 1
                    else -> error("Unsupported runtime log channel value")
                }
            }
        }
    }

    private data class Loaded(
        val entries: List<RuntimeLogEntry>,
        val canRemoveLegacy: Boolean = false,
        val writable: Boolean = true,
    )

    private data class Snapshot(val entries: List<RuntimeLogEntry>, val json: String)

    fun append(entry: RuntimeLogEntry) {
        if (entry.isSuppressedRuntimeLogNoise()) return
        val loaded = load()
        check(loaded.writable) { "Runtime logs could not be read safely; retained for explicit clearing" }
        val snapshot = snapshot(
            sequenceOf(entry) + loaded.entries.asSequence().filter { it.id != entry.id },
        )
        check(storage.write(KEY, snapshot.json)) { "Could not persist runtime logs" }
        // A failed migration must not erase the only copy. Oversized or malformed
        // legacy data stays untouched until the user explicitly clears the logs.
        if (loaded.canRemoveLegacy) storage.remove(LEGACY_KEY)
    }

    fun listRecent(limit: Int = RuntimeLogLimits.MAX_COUNT): List<RuntimeLogEntry> =
        snapshot(load().entries.asSequence(), limit.coerceIn(1, RuntimeLogLimits.MAX_COUNT)).entries

    fun clear() {
        storage.remove(KEY)
        storage.remove(LEGACY_KEY)
    }

    private fun load(): Loaded {
        val legacy = !storage.contains(KEY)
        val key = if (legacy) LEGACY_KEY else KEY
        if (!storage.contains(key)) return Loaded(emptyList())
        val budget = if (legacy) RuntimeLogLimits.MAX_LEGACY_READ_BYTES else RuntimeLogLimits.MAX_BYTES
        // decodeString allocates the whole value. Check its native size first.
        val bytes = storage.size(key)
        if (bytes < 0 || bytes > budget) {
            return unavailable(legacy, oversized = true)
        }
        return try {
            val raw = storage.read(key).orEmpty()
            val entries = mutableListOf<RuntimeLogEntry>()
            if (raw.isNotBlank()) {
                JsonReader(StringReader(raw)).use { reader ->
                    reader.beginArray()
                    // Even a bounded JSON string can contain thousands of tiny
                    // objects. Materialize only the newest retained records.
                    while (reader.hasNext()) {
                        if (entries.size < RuntimeLogLimits.MAX_COUNT) {
                            val entry = gson.fromJson<RuntimeLogEntry?>(reader, RuntimeLogEntry::class.java)
                            if (entry != null) entries += entry.bounded()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                    check(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected runtime log JSON suffix" }
                }
            }
            Loaded(entries, canRemoveLegacy = legacy)
        } catch (_: Exception) {
            unavailable(legacy, oversized = false)
        }
    }

    private fun unavailable(legacy: Boolean, oversized: Boolean): Loaded {
        val reason = if (oversized) "体积过大" else "格式异常"
        val source = if (legacy) "旧运行日志" else "运行日志"
        return Loaded(
            entries = listOf(
                RuntimeLogEntry(
                    id = "runtime-log-unavailable",
                    level = "WARN",
                    tag = "RuntimeLogStore",
                    message = "$source$reason，已跳过加载。原始数据仍保留，清空运行日志时会一并删除。",
                ),
            ),
            writable = legacy,
        )
    }

    private fun snapshot(
        source: Sequence<RuntimeLogEntry>,
        limit: Int = RuntimeLogLimits.MAX_COUNT,
    ): Snapshot {
        val entries = mutableListOf<RuntimeLogEntry>()
        val encoded = StringBuilder("[")
        var jsonBytes = 2 // brackets
        var channelBytes = 3 // success envelope, List tag, count (at most 200)
        for (raw in source) {
            if (entries.size >= limit) break
            if (raw.isSuppressedRuntimeLogNoise()) continue
            val entry = raw.bounded()
            val json = gson.toJson(entry)
            val addedJsonBytes = storageStringByteUpperBound(json) + if (entries.isEmpty()) 0 else 1
            val addedChannelBytes = channelEntryBytes(entry)
            if (jsonBytes + addedJsonBytes > RuntimeLogLimits.MAX_BYTES ||
                channelBytes + addedChannelBytes > RuntimeLogLimits.MAX_BYTES
            ) break
            if (entries.isNotEmpty()) encoded.append(',')
            encoded.append(json)
            entries += entry
            jsonBytes += addedJsonBytes
            channelBytes += addedChannelBytes
        }
        return Snapshot(entries, encoded.append(']').toString())
    }
}

internal fun RuntimeLogEntry.isSuppressedRuntimeLogNoise(): Boolean {
    return !isCrash &&
        stackTrace.isNullOrBlank() &&
        level.equals("ERROR", ignoreCase = true) &&
        tag == "[AssistsCoreManager]" &&
        message.length <= 128 &&
        message.trim() == "setChannel"
}
