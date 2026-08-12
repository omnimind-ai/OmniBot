package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object SpeechTranscriptionProtocol {
    const val MAX_RECORDING_DURATION_MS: Long = 60_000L
    const val MAX_FILE_DURATION_MS: Long = 10L * 60L * 1000L
    // Leave room below the gateway's multipart request limit for boundaries and fields.
    const val MAX_AUDIO_BYTES: Long = 24L * 1024L * 1024L
    const val MAX_RESPONSE_BYTES: Long = 1024L * 1024L
    const val DEFAULT_BYOK_MODEL = "whisper-1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class ValidatedAudio(
        val file: File,
        val mimeType: String,
        val durationMs: Long,
    )

    fun validateAudioFile(
        file: File,
        durationMs: Long?,
        mimeTypeHint: String? = null,
    ): ValidatedAudio {
        validateAudioFileBasics(file)
        val resolvedDuration = durationMs?.takeIf { it > 0L }
            ?: throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.INVALID_AUDIO,
                "无法读取音频时长",
            )
        if (resolvedDuration > MAX_FILE_DURATION_MS) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.DURATION_EXCEEDED,
                "音频时长超过 10 分钟上限",
            )
        }
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(16)
            val count = input.read(bytes)
            if (count <= 0) ByteArray(0) else bytes.copyOf(count)
        }
        val detected = detectAudioMimeType(header)
            ?: throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.UNSUPPORTED_AUDIO,
                "仅支持 WAV、MP3、M4A、AAC、FLAC、Ogg 或 WebM 音频",
            )
        val hinted = normalizeSupportedMimeType(mimeTypeHint)
        val mimeType = hinted?.takeIf { it == detected } ?: detected
        return ValidatedAudio(file, mimeType, resolvedDuration)
    }

    /** Cheap checks that must run before Android asks a media parser to inspect the file. */
    fun validateAudioFileBasics(file: File) {
        if (!file.exists() || !file.isFile) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.FILE_NOT_FOUND,
                "找不到要转写的音频文件",
            )
        }
        val size = file.length()
        if (size <= 0L) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.INVALID_AUDIO,
                "音频文件为空",
            )
        }
        if (size > MAX_AUDIO_BYTES) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.FILE_TOO_LARGE,
                "音频文件超过 24 MiB 上限",
            )
        }
    }

    fun detectAudioMimeType(bytes: ByteArray): String? {
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE"
        ) return "audio/wav"
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "ID3") {
            return "audio/mpeg"
        }
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1].toInt() and 0xE0) == 0xE0
        ) {
            return if ((bytes[1].toInt() and 0xF6) == 0xF0) "audio/aac" else "audio/mpeg"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC") {
            return "audio/flac"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS") {
            return "audio/ogg"
        }
        if (bytes.size >= 8 && bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp") {
            return "audio/mp4"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) return "audio/webm"
        return null
    }

    fun parseTranscription(bytes: ByteArray, contentType: String?): String {
        if (bytes.isEmpty()) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.INVALID_RESPONSE,
                "语音转写服务返回了空结果",
            )
        }
        val raw = bytes.toString(Charsets.UTF_8).trim()
        val normalizedContentType = contentType?.substringBefore(';')?.trim()?.lowercase()
        val text = if (normalizedContentType == "text/plain") {
            raw
        } else {
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw SpeechTranscriptionException(
                    SpeechTranscriptionErrorCode.INVALID_RESPONSE,
                    "语音转写服务返回了无效结果",
                )
            when (val value = root["text"] ?: root["transcript"]) {
                is JsonPrimitive -> value.contentOrNull.orEmpty()
                else -> ""
            }.trim()
        }
        if (text.isEmpty()) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.EMPTY_TRANSCRIPT,
                "没有识别到可用文字",
            )
        }
        if (text.length > 200_000) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.INVALID_RESPONSE,
                "语音转写结果过长",
            )
        }
        return text
    }

    fun resolveEndpoint(baseUrl: String): String {
        val raw = baseUrl.trim()
        if (raw.isEmpty()) {
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.PROVIDER_NOT_CONFIGURED,
                "BYOK Provider 地址为空",
            )
        }
        val stripped = ModelProviderConfigStore.stripDirectRequestUrlMarker(raw).trimEnd('/')
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(raw)) return stripped
        if (stripped.endsWith("/v1/audio/transcriptions", true) ||
            stripped.endsWith("/audio/transcriptions", true)
        ) return stripped
        return if (stripped.endsWith("/v1", true)) {
            "$stripped/audio/transcriptions"
        } else {
            "$stripped/v1/audio/transcriptions"
        }
    }

    private fun normalizeSupportedMimeType(raw: String?): String? =
        when (raw?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/wav", "audio/x-wav" -> "audio/wav"
            "audio/mpeg", "audio/mp3" -> "audio/mpeg"
            "audio/aac" -> "audio/aac"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "audio/mp4"
            "audio/flac", "audio/x-flac" -> "audio/flac"
            "audio/ogg" -> "audio/ogg"
            "audio/webm" -> "audio/webm"
            else -> null
        }
}

internal object SpeechTranscriptionErrorCode {
    const val PERMISSION_DENIED = "STT_PERMISSION_DENIED"
    const val BUSY = "STT_BUSY"
    const val RECORDING_FAILED = "STT_RECORDING_FAILED"
    const val FILE_NOT_FOUND = "STT_FILE_NOT_FOUND"
    const val FILE_TOO_LARGE = "STT_FILE_TOO_LARGE"
    const val DURATION_EXCEEDED = "STT_DURATION_EXCEEDED"
    const val UNSUPPORTED_AUDIO = "STT_UNSUPPORTED_AUDIO"
    const val INVALID_AUDIO = "STT_INVALID_AUDIO"
    const val PLATFORM_UNAVAILABLE = "STT_PLATFORM_UNAVAILABLE"
    const val AUTH_REQUIRED = "STT_AUTH_REQUIRED"
    const val QUOTA_EXCEEDED = "STT_QUOTA_EXCEEDED"
    const val PROVIDER_NOT_CONFIGURED = "STT_PROVIDER_NOT_CONFIGURED"
    const val REQUEST_FAILED = "STT_REQUEST_FAILED"
    const val INVALID_RESPONSE = "STT_INVALID_RESPONSE"
    const val EMPTY_TRANSCRIPT = "STT_EMPTY_TRANSCRIPT"
    const val CANCELLED = "STT_CANCELLED"
}

internal class SpeechTranscriptionException(
    val stableCode: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
