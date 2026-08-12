package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneVoiceConfigStoreTest {

    @Test
    fun normalize_fallsBackToDefaultsForUnknownVoiceAndStyle() {
        val normalized = SceneVoiceConfigStore.normalize(
            SceneVoiceConfig(
                autoPlay = true,
                voiceId = "unknown_voice",
                stylePreset = "unknown_style",
                customStyle = "  更柔和一点  "
            )
        )

        assertTrue(normalized.autoPlay)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_ZH, normalized.voiceId)
        assertEquals(SceneVoiceConfigStore.STYLE_DEFAULT, normalized.stylePreset)
        assertEquals("更柔和一点", normalized.customStyle)
    }

    @Test
    fun normalize_defaultsTtsModeAndTrimsCurlCommand() {
        val unknownMode = SceneVoiceConfigStore.normalize(
            SceneVoiceConfig(ttsMode = "weird_mode", customCurlCommand = "  curl x  ")
        )
        assertEquals(SceneVoiceConfigStore.TTS_MODE_BUILTIN, unknownMode.ttsMode)
        assertEquals("curl x", unknownMode.customCurlCommand)

        val customMode = SceneVoiceConfigStore.normalize(
            SceneVoiceConfig(
                ttsMode = SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL,
                customCurlCommand = "curl https://example.com -d '{{text}}'"
            )
        )
        assertEquals(SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL, customMode.ttsMode)
        assertEquals("curl https://example.com -d '{{text}}'", customMode.customCurlCommand)
    }

    @Test
    fun parse_readsTtsModeAndCurlCommand() {
        val parsed = SceneVoiceConfigStore.parse(
            """
            {
              "autoPlay": false,
              "ttsMode": "custom_curl",
              "customCurlCommand": "curl https://tts.example.com -d '{{text}}'"
            }
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertEquals(SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL, parsed.ttsMode)
        assertEquals(
            "curl https://tts.example.com -d '{{text}}'",
            parsed.customCurlCommand
        )
    }

    @Test
    fun parse_defaultsTtsModeWhenAbsent() {
        val parsed = SceneVoiceConfigStore.parse(
            """
            { "voiceId": "default_zh" }
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertEquals(SceneVoiceConfigStore.TTS_MODE_BUILTIN, parsed.ttsMode)
        assertEquals("", parsed.customCurlCommand)
    }

    @Test
    fun parse_returnsNormalizedVoiceConfig() {
        val parsed = SceneVoiceConfigStore.parse(
            """
            {
              "autoPlay": true,
              "voiceId": "default_en",
              "stylePreset": "专业播报",
              "customStyle": "  节奏慢一点  "
            }
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertTrue(parsed.autoPlay)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_EN, parsed.voiceId)
        assertEquals(SceneVoiceConfigStore.STYLE_PROFESSIONAL_BROADCAST, parsed.stylePreset)
        assertEquals("节奏慢一点", parsed.customStyle)
    }

    @Test
    fun persistenceNeverContainsCustomCurlCredential() {
        val safe = SceneVoiceConfigStore.forPersistence(
            SceneVoiceConfig(
                ttsMode = SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL,
                customCurlCommand = "curl -H 'Authorization: Bearer secret' https://tts.example.com",
            ),
        )

        assertEquals("", safe.customCurlCommand)
        assertTrue(
            SceneVoiceConfigStore.isCustomCurlTransportSafe(
                "curl -H 'Authorization: Bearer secret' https://tts.example.com",
            ),
        )
        assertTrue(
            !SceneVoiceConfigStore.isCustomCurlTransportSafe(
                "curl -H 'Authorization: Bearer secret' http://tts.example.com",
            ),
        )
    }
}
