package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import com.google.gson.Gson
import com.tencent.mmkv.MMKV

object SceneVoiceConfigStore {
    private const val TAG = "SceneVoiceConfigStore"
    private const val KEY_SCENE_VOICE_CONFIG = "scene_voice_config_v1"
    private const val SECRET_CUSTOM_CURL = "scene_voice.custom_curl"

    const val SCENE_ID = "scene.voice"

    const val VOICE_MIMO_DEFAULT = "mimo_default"
    const val VOICE_DEFAULT_ZH = "default_zh"
    const val VOICE_DEFAULT_EN = "default_en"

    const val STYLE_DEFAULT = "默认"
    const val STYLE_NATURAL_DIALOG = "自然对话"
    const val STYLE_GENTLE_COMPANION = "温柔陪伴"
    const val STYLE_PROFESSIONAL_BROADCAST = "专业播报"
    const val STYLE_LIVELY = "活泼元气"
    const val STYLE_BEDTIME = "睡前轻声"
    const val STYLE_SING = "唱歌"

    const val TTS_MODE_BUILTIN = "builtin"
    const val TTS_MODE_CUSTOM_CURL = "custom_curl"
    const val TEXT_PLACEHOLDER = "{{text}}"

    private val gson = Gson()
    private val defaultConfig = SceneVoiceConfig()
    private val allowedVoices = setOf(
        VOICE_MIMO_DEFAULT,
        VOICE_DEFAULT_ZH,
        VOICE_DEFAULT_EN
    )
    private val allowedStylePresets = setOf(
        STYLE_DEFAULT,
        STYLE_NATURAL_DIALOG,
        STYLE_GENTLE_COMPANION,
        STYLE_PROFESSIONAL_BROADCAST,
        STYLE_LIVELY,
        STYLE_BEDTIME,
        STYLE_SING
    )
    private val allowedTtsModes = setOf(
        TTS_MODE_BUILTIN,
        TTS_MODE_CUSTOM_CURL
    )

    fun getConfig(): SceneVoiceConfig {
        val mmkv = MMKV.defaultMMKV() ?: return defaultConfig
        val raw = mmkv.decodeString(KEY_SCENE_VOICE_CONFIG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return defaultConfig
        val parsed = parse(raw) ?: return defaultConfig
        if (parsed.customCurlCommand.isNotBlank()) {
            return migrateLegacyCustomCurl(mmkv, parsed)
        }
        val securedCommand = AppSecretStore.read(SECRET_CUSTOM_CURL).orEmpty()
        return if (parsed.ttsMode == TTS_MODE_CUSTOM_CURL && securedCommand.isBlank()) {
            parsed.copy(ttsMode = TTS_MODE_BUILTIN, customCurlCommand = "")
        } else {
            parsed.copy(customCurlCommand = securedCommand)
        }
    }

    @Synchronized
    fun saveConfig(
        config: SceneVoiceConfig,
        replaceCustomCurlCommand: Boolean = true,
        clearCustomCurlCommand: Boolean = false,
    ): SceneVoiceConfig {
        require(!(replaceCustomCurlCommand && clearCustomCurlCommand)) {
            "custom TTS command cannot be replaced and cleared together"
        }
        val mmkv = MMKV.defaultMMKV() ?: error("Voice configuration storage is unavailable")
        val previousMetadata = mmkv.decodeString(KEY_SCENE_VOICE_CONFIG)
        val previousSecretRead = AppSecretStore.readWithStatus(SECRET_CUSTOM_CURL)
        check(previousSecretRead.succeeded) { "Secure custom TTS storage is unavailable" }
        val previousCommand = previousSecretRead.value.orEmpty()
        val replacement = config.customCurlCommand.trim()
        if (replaceCustomCurlCommand && replacement.isNotEmpty()) {
            require(isCustomCurlTransportSafe(replacement)) {
                "Custom TTS destination must use secure transport"
            }
        }
        val nextCommand = when {
            clearCustomCurlCommand -> ""
            replaceCustomCurlCommand -> replacement
            else -> previousCommand
        }
        var normalized = normalize(config).copy(customCurlCommand = nextCommand)
        if (normalized.ttsMode == TTS_MODE_CUSTOM_CURL && nextCommand.isBlank()) {
            normalized = normalized.copy(ttsMode = TTS_MODE_BUILTIN)
        }
        try {
            check(writeAndVerifySecret(nextCommand)) {
                "Failed to store custom TTS command"
            }
            val nextMetadata = gson.toJson(forPersistence(normalized))
            check(writeAndVerifyMetadata(mmkv, nextMetadata)) {
                "Failed to store voice configuration"
            }
            return normalized
        } catch (failure: Exception) {
            val secretRestored = try {
                writeAndVerifySecret(previousCommand)
            } catch (_: Exception) {
                false
            }
            val metadataRestored = try {
                if (previousMetadata == null) {
                    mmkv.removeValueForKey(KEY_SCENE_VOICE_CONFIG)
                    mmkv.decodeString(KEY_SCENE_VOICE_CONFIG) == null
                } else {
                    writeAndVerifyMetadata(mmkv, previousMetadata)
                }
            } catch (_: Exception) {
                false
            }
            if (!secretRestored || !metadataRestored) {
                // A partial rollback must never leave a command bound to stale
                // voice metadata. Remove both sides and force built-in mode.
                AppSecretStore.delete(SECRET_CUSTOM_CURL)
                mmkv.removeValueForKey(KEY_SCENE_VOICE_CONFIG)
            }
            throw failure
        }
    }

    @Synchronized
    fun reset() {
        val mmkv = MMKV.defaultMMKV() ?: error("Voice configuration storage is unavailable")
        val secretCleared = AppSecretStore.delete(SECRET_CUSTOM_CURL) &&
            AppSecretStore.readWithStatus(SECRET_CUSTOM_CURL).let { it.succeeded && it.value == null }
        mmkv.removeValueForKey(KEY_SCENE_VOICE_CONFIG)
        val metadataCleared = mmkv.decodeString(KEY_SCENE_VOICE_CONFIG) == null
        check(secretCleared && metadataCleared) { "Failed to reset voice configuration" }
    }

    fun normalize(config: SceneVoiceConfig): SceneVoiceConfig {
        val normalizedVoiceId = config.voiceId.trim()
            .takeIf { allowedVoices.contains(it) }
            ?: defaultConfig.voiceId
        val normalizedStylePreset = config.stylePreset.trim()
            .takeIf { allowedStylePresets.contains(it) }
            ?: defaultConfig.stylePreset
        val normalizedTtsMode = config.ttsMode.trim()
            .takeIf { allowedTtsModes.contains(it) }
            ?: defaultConfig.ttsMode
        return SceneVoiceConfig(
            autoPlay = config.autoPlay,
            voiceId = normalizedVoiceId,
            stylePreset = normalizedStylePreset,
            customStyle = config.customStyle.trim(),
            ttsMode = normalizedTtsMode,
            customCurlCommand = config.customCurlCommand.trim()
        )
    }

    internal fun parse(raw: String): SceneVoiceConfig? {
        val parsed = try {
            gson.fromJson(raw, SceneVoiceConfig::class.java)
        } catch (failure: Exception) {
            OmniLog.w(TAG, "parse voice config failed: ${failure.message}")
            null
        }
        return parsed?.let(::normalize)
    }

    internal fun forPersistence(config: SceneVoiceConfig): SceneVoiceConfig =
        normalize(config).copy(customCurlCommand = "")

    private fun migrateLegacyCustomCurl(mmkv: MMKV, parsed: SceneVoiceConfig): SceneVoiceConfig {
        val command = parsed.customCurlCommand
        val safeConfig = parsed.copy(customCurlCommand = command)
        val sanitizedMetadata = gson.toJson(forPersistence(safeConfig))
        val migrated = try {
            isCustomCurlTransportSafe(command) &&
                writeAndVerifySecret(command) &&
                writeAndVerifyMetadata(mmkv, sanitizedMetadata) &&
                !mmkv.decodeString(KEY_SCENE_VOICE_CONFIG).orEmpty().contains(command)
        } catch (_: Exception) {
            false
        }
        if (migrated) return safeConfig

        AppSecretStore.delete(SECRET_CUSTOM_CURL)
        val failedClosed = parsed.copy(ttsMode = TTS_MODE_BUILTIN, customCurlCommand = "")
        val scrubbed = try {
            val scrubbedMetadata = gson.toJson(forPersistence(failedClosed))
            writeAndVerifyMetadata(mmkv, scrubbedMetadata) &&
                !mmkv.decodeString(KEY_SCENE_VOICE_CONFIG).orEmpty().contains(command)
        } catch (_: Exception) {
            false
        }
        if (!scrubbed) {
            mmkv.removeValueForKey(KEY_SCENE_VOICE_CONFIG)
        }
        OmniLog.w(TAG, "legacy custom TTS credential migration failed closed")
        return failedClosed
    }

    internal fun isCustomCurlTransportSafe(command: String): Boolean {
        val urls = URL_PATTERN.findAll(command).map { it.value }.toList()
        if (urls.isEmpty()) return false
        return urls.all { url ->
            try {
                CredentialEndpointSecurity.requireSafe(
                    rawUrl = url,
                    hasCredential = true,
                    allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun writeAndVerifySecret(command: String): Boolean {
        val written = if (command.isEmpty()) {
            AppSecretStore.delete(SECRET_CUSTOM_CURL)
        } else {
            AppSecretStore.write(SECRET_CUSTOM_CURL, command)
        }
        if (!written) return false
        val read = AppSecretStore.readWithStatus(SECRET_CUSTOM_CURL)
        return read.succeeded && read.value.orEmpty() == command
    }

    private fun writeAndVerifyMetadata(mmkv: MMKV, metadata: String): Boolean {
        return mmkv.encode(KEY_SCENE_VOICE_CONFIG, metadata) &&
            mmkv.decodeString(KEY_SCENE_VOICE_CONFIG) == metadata
    }

    private val URL_PATTERN = Regex("https?://[^\\s'\\\"]+", RegexOption.IGNORE_CASE)
}
