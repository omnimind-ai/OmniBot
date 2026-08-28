package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSpeechProtocolTest {
    @Test
    fun buildsStandardNonStreamingSpeechRequest() {
        val body = PlatformSpeechProtocol.buildRequestBody(
            text = " 你好 ",
            modelId = "tts-model",
            config = SceneVoiceConfig(
                voiceId = "default_zh",
                stylePreset = SceneVoiceConfigStore.STYLE_NATURAL_DIALOG,
                customStyle = "慢一点",
            ),
        )
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals("tts-model", json["model"].asString)
        assertEquals("你好", json["input"].asString)
        assertEquals("default_zh", json["voice"].asString)
        assertEquals("wav", json["response_format"].asString)
        assertTrue(json["instructions"].asString.contains("慢一点"))
        assertFalse(json.has("stream_format"))
    }

    @Test
    fun acceptsWavMagicAndRejectsJsonDisguisedAsAudio() {
        val wav = ByteArray(16).apply {
            "RIFF".toByteArray().copyInto(this, 0)
            "WAVE".toByteArray().copyInto(this, 8)
        }

        assertEquals("wav", PlatformSpeechProtocol.detectAudioFormat(wav, "audio/wav"))
        assertNull(
            PlatformSpeechProtocol.detectAudioFormat(
                "{\"error\":{}}".toByteArray(),
                "audio/wav",
            )
        )
    }

    @Test
    fun rejectsOversizedSpeechInputBeforeNetworkRequest() {
        val oversized = "a".repeat(PlatformSpeechProtocol.MAX_INPUT_UTF8_BYTES + 1)

        val error = runCatching {
            PlatformSpeechProtocol.buildRequestBody(
                text = oversized,
                modelId = "tts-model",
                config = SceneVoiceConfig(voiceId = "default_en"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("64 KB"))
    }
}
