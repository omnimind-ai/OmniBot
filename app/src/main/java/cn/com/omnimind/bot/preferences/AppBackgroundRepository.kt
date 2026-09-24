package cn.com.omnimind.bot.preferences

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Owns the existing Flutter background preference and its app-private images.
 *
 * Flutter's path_provider_android 2.3.1 resolves getApplicationSupportDirectory()
 * to Context.filesDir, so both hosts use the same `filesDir/backgrounds` folder.
 * The JSON shape is AppBackgroundConfig.toJson() in app_background_service.dart.
 */
class AppBackgroundRepository private constructor(context: Context) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val managedDirectory = File(application.filesDir, "backgrounds")
    private val mutex = Mutex()

    val snapshots: Flow<Map<String, Any>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == CONFIG_KEY) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { read() }.flowOn(Dispatchers.IO)

    /** Return every key in the old Flutter JSON, including defaults when absent or malformed. */
    fun read(): Map<String, Any> = readConfig().toMap()

    /**
     * Serialize writers, commit before deleting an obsolete image, and keep
     * compatibility with an existing legacy local path outside the managed folder.
     * Newly selected local images must first pass through importFromUri/Path.
     */
    suspend fun save(config: Map<String, Any?>): Map<String, Any> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = readConfig()
            val next = BackgroundConfig.fromMap(config).forSaving()
            validate(next, previous)
            check(preferences.edit().putString(CONFIG_KEY, JSONObject(next.toMap()).toString()).commit()) {
                "Unable to save background settings"
            }
            if (previous.localImagePath.isNotEmpty() && previous.localImagePath != next.localImagePath) {
                // An orphaned image is preferable to reporting a failed save after commit.
                runCatching { deleteManagedImageLocked(previous.localImagePath) }
                    .onFailure { OmniLog.w("AppBackground", "Unable to remove obsolete background image", it) }
            }
            next.toMap()
        }
    }

    /** Copy a picker URI into the same private folder used by the Flutter UI. */
    suspend fun importFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stream = application.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Unable to open selected image")
            importImage(stream)
        }
    }

    /** Compatibility for a file picker that supplies a filesystem path. */
    suspend fun importFromPath(sourcePath: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = File(sourcePath)
            require(source.isFile && source.canRead()) { "Selected image does not exist" }
            importImage(source.inputStream())
        }
    }

    /** Delete only an unreferenced direct child of filesDir/backgrounds. */
    suspend fun deleteManagedImage(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock { deleteManagedImageLocked(path) }
    }

    /** Restore Flutter defaults by removing its key, then clean the former image. */
    suspend fun reset(): Map<String, Any> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = readConfig()
            check(preferences.edit().remove(CONFIG_KEY).commit()) { "Unable to reset background settings" }
            if (previous.localImagePath.isNotEmpty()) {
                runCatching { deleteManagedImageLocked(previous.localImagePath) }
                    .onFailure { OmniLog.w("AppBackground", "Unable to remove obsolete background image", it) }
            }
            BackgroundConfig.DEFAULT.toMap()
        }
    }

    private fun readConfig(): BackgroundConfig {
        val raw = runCatching { preferences.getString(CONFIG_KEY, null) }.getOrNull()
        if (raw.isNullOrBlank()) return BackgroundConfig.DEFAULT
        return runCatching { BackgroundConfig.fromJson(JSONObject(raw)) }.getOrDefault(BackgroundConfig.DEFAULT)
    }

    private fun validate(next: BackgroundConfig, previous: BackgroundConfig) {
        when (next.sourceType) {
            "local" -> {
                require(next.localImagePath.isNotEmpty()) { "Select a local image first" }
                val image = File(next.localImagePath)
                require(image.isFile && image.canRead()) { "Local image is missing" }
                require(isManagedImage(image) || sameFile(next.localImagePath, previous.localImagePath)) {
                    "Import the selected image before saving"
                }
            }
            "remote" -> {
                val url = runCatching { Uri.parse(next.remoteImageUrl) }.getOrNull()
                require(url != null && url.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                    !url.host.isNullOrBlank()) { "Enter a valid HTTP image URL" }
            }
        }
    }

    private fun importImage(source: InputStream): String {
        source.use { input ->
            check(managedDirectory.isDirectory || managedDirectory.mkdirs()) { "Unable to create background directory" }
            val staging = File.createTempFile("background_", ".tmp", managedDirectory)
            var target: File? = null
            var completed = false
            try {
                staging.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_IMAGE_BYTES) { "Selected image is too large" }
                        output.write(buffer, 0, count)
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(staging.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not an image" }
                val extension = when (bounds.outMimeType?.lowercase(Locale.ROOT)) {
                    "image/png" -> ".png"
                    "image/jpeg" -> ".jpg"
                    "image/webp" -> ".webp"
                    "image/gif" -> ".gif"
                    else -> throw IllegalArgumentException("Only PNG, JPEG, WebP and GIF images are supported")
                }
                val importedFile = File(managedDirectory, "background_${UUID.randomUUID()}$extension")
                target = importedFile
                try {
                    Files.move(staging.toPath(), importedFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    check(staging.renameTo(importedFile)) { "Unable to finish importing image" }
                }
                completed = true
                return importedFile.absolutePath
            } finally {
                // A failed read/format check must never leave a partial import.
                staging.delete()
                if (!completed) target?.delete()
            }
        }
    }

    private fun deleteManagedImageLocked(path: String) {
        if (path.isBlank()) return
        val image = File(path.trim())
        if (!isManagedImage(image)) return
        if (sameFile(path, readConfig().localImagePath)) return
        if (image.isFile && !image.delete()) throw IllegalStateException("Unable to delete background image")
    }

    private fun isManagedImage(image: File): Boolean = runCatching {
        image.canonicalFile.parentFile == managedDirectory.canonicalFile
    }.getOrDefault(false)

    private fun sameFile(first: String, second: String): Boolean {
        if (first.isBlank() || second.isBlank()) return false
        return runCatching { File(first).canonicalFile == File(second).canonicalFile }.getOrDefault(false)
    }

    companion object {
        private const val PREFERENCES_NAME = "FlutterSharedPreferences"
        private const val CONFIG_KEY = "flutter.app_background_config_v1"
        private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
        @Volatile private var instance: AppBackgroundRepository? = null

        fun get(context: Context): AppBackgroundRepository = instance ?: synchronized(this) {
            instance ?: AppBackgroundRepository(context).also { instance = it }
        }
    }
}

/** The persisted fields and clamps mirror Flutter's AppBackgroundConfig.fromJson. */
private data class BackgroundConfig(
    val enabled: Boolean,
    val sourceType: String,
    val localImagePath: String,
    val remoteImageUrl: String,
    val blurSigma: Double,
    val frostOpacity: Double,
    val brightness: Double,
    val focalX: Double,
    val focalY: Double,
    val imageScale: Double,
    val chatTextSize: Double,
    val chatTextColorMode: String,
    val chatTextHexColor: String,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "enabled" to enabled, "sourceType" to sourceType, "localImagePath" to localImagePath,
        "remoteImageUrl" to remoteImageUrl, "blurSigma" to blurSigma, "frostOpacity" to frostOpacity,
        "brightness" to brightness, "focalX" to focalX, "focalY" to focalY,
        "imageScale" to imageScale, "chatTextSize" to chatTextSize,
        "chatTextColorMode" to chatTextColorMode, "chatTextHexColor" to chatTextHexColor,
    )

    fun forSaving(): BackgroundConfig = when (sourceType) {
        "local" -> copy(enabled = enabled && localImagePath.isNotEmpty(), remoteImageUrl = "")
        "remote" -> copy(enabled = enabled && remoteImageUrl.isNotEmpty(), localImagePath = "")
        else -> copy(enabled = false, localImagePath = "", remoteImageUrl = "")
    }

    companion object {
        val DEFAULT = BackgroundConfig(false, "none", "", "", 8.0, 0.18, 1.0, 0.0, 0.0,
            1.0, 14.0, "auto", "")

        fun fromJson(json: JSONObject): BackgroundConfig = fromMap(
            json.keys().asSequence().associateWith { json.opt(it) }
        )

        fun fromMap(map: Map<String, Any?>): BackgroundConfig {
            val sourceType = (map["sourceType"] as? String)?.trim()
                ?.takeIf { it in setOf("none", "local", "remote") } ?: "none"
            val colorMode = (map["chatTextColorMode"] as? String)?.trim()
                ?.takeIf { it in setOf("auto", "custom") } ?: "auto"
            val color = normalizeHex((map["chatTextHexColor"] as? String).orEmpty())
            return BackgroundConfig(
                enabled = map["enabled"] == true,
                sourceType = sourceType,
                localImagePath = (map["localImagePath"] as? String).orEmpty().trim(),
                remoteImageUrl = (map["remoteImageUrl"] as? String).orEmpty().trim(),
                blurSigma = number(map["blurSigma"], DEFAULT.blurSigma, 0.0, 24.0),
                frostOpacity = number(map["frostOpacity"], DEFAULT.frostOpacity, 0.0, 0.55),
                brightness = number(map["brightness"], DEFAULT.brightness, 0.5, 1.5),
                focalX = number(map["focalX"], DEFAULT.focalX, -1.0, 1.0),
                focalY = number(map["focalY"], DEFAULT.focalY, -1.0, 1.0),
                imageScale = number(map["imageScale"], DEFAULT.imageScale, 1.0, 3.0),
                chatTextSize = number(map["chatTextSize"], DEFAULT.chatTextSize, 12.0, 22.0),
                chatTextColorMode = if (colorMode == "custom" && color == null) "auto" else colorMode,
                chatTextHexColor = color.orEmpty(),
            )
        }

        private fun number(value: Any?, fallback: Double, min: Double, max: Double): Double =
            (value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(min, max) ?: fallback

        private fun normalizeHex(raw: String): String? {
            val digits = raw.trim().replace("#", "")
            if (digits.length != 6 && digits.length != 8) return null
            if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return "#${digits.uppercase(Locale.ROOT)}"
        }
    }
}
