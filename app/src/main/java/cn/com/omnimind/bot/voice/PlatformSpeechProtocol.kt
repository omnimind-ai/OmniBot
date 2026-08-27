package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PlatformSpeechProtocol {
    const val RESPONSE_FORMAT = "wav"
    const val MAX_INPUT_UTF8_BYTES: Int = 64 * 1024
    const val MAX_INSTRUCTIONS_UTF8_BYTES: Int = 8 * 1024
    const val MAX_AUDIO_BYTES: Long = 16L * 1024L * 1024L
    const val MAX_JSON_BYTES: Long = 24L * 1024L * 1024L

    fun buildRequestBody(
        text: String,
        modelId: String,
        config: SceneVoiceConfig,
    ): String {
        val normalizedText = text.trim()
        val normalizedModel = modelId.trim()
        val normalizedVoice = config.voiceId.trim()
        val instructions = buildInstructions(config)
        require(normalizedText.isNotEmpty()) { "speech input is empty" }
        require(normalizedText.toByteArray(Charsets.UTF_8).size <= MAX_INPUT_UTF8_BYTES) {
            "speech input exceeds the 64 KB limit"
        }
        require(normalizedModel.isNotEmpty()) { "speech model is empty" }
        require(normalizedVoice.isNotEmpty()) { "speech voice is empty" }
        require(instructions.toByteArray(Charsets.UTF_8).size <= MAX_INSTRUCTIONS_UTF8_BYTES) {
            "speech instructions exceed the 8 KB limit"
        }
        return buildJsonObject {
            put("model", normalizedModel)
            put("input", normalizedText)
            put("voice", normalizedVoice)
            put("response_format", RESPONSE_FORMAT)
            instructions.takeIf(String::isNotEmpty)?.let { value ->
                put("instructions", value)
            }
        }.toString()
    }

    fun buildInstructions(config: SceneVoiceConfig): String = buildString {
        val preset = config.stylePreset.trim()
        if (preset.isNotEmpty() && preset != SceneVoiceConfigStore.STYLE_DEFAULT) {
            append(preset)
        }
        val custom = config.customStyle.trim()
        if (custom.isNotEmpty()) {
            if (isNotEmpty()) append('，')
            append(custom)
        }
    }

    fun detectAudioFormat(bytes: ByteArray, contentType: String?): String? {
        if (isWav(bytes)) return "wav"
        if (bytes.size >= 3 &&
            bytes[0] == 'I'.code.toByte() &&
            bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()
        ) return "mp3"
        if (bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            (bytes[1].toInt() and 0xE0) == 0xE0
        ) return "mp3"
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "fLaC") {
            return "flac"
        }
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "OggS") {
            return "ogg"
        }
        // A declared audio type is not sufficient on its own: JSON error bodies
        // and proxy pages must never be written to disk as playable audio.
        return when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/wav", "audio/x-wav", "audio/mpeg", "audio/mp3", "audio/flac", "audio/ogg" -> null
            else -> null
        }
    }

    fun isWav(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() &&
        bytes[11] == 'E'.code.toByte()
}
