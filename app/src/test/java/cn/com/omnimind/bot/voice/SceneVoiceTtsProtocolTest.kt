package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SceneVoiceTtsProtocolTest {

    @Test
    fun buildChatCompletionRequest_usesBoundModelAudioAndStyledContent() {
        val request = SceneVoiceTtsProtocol.buildChatCompletionRequest(
            text = "你好，欢迎使用。",
            modelId = "mimo-v2-tts",
            config = SceneVoiceConfig(
                autoPlay = true,
                voiceId = SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
                stylePreset = SceneVoiceConfigStore.STYLE_NATURAL_DIALOG,
                customStyle = "更轻柔一点"
            ),
            format = "pcm16",
            stream = true
        )

        assertEquals("mimo-v2-tts", request.model)
        assertEquals(true, request.stream)
        assertEquals("pcm16", request.audio?.format)
        assertEquals(SceneVoiceConfigStore.VOICE_DEFAULT_ZH, request.audio?.voice)
        assertEquals("assistant", request.messages.single().role)
        assertEquals(
            JsonPrimitive("<style>自然对话，更轻柔一点</style>你好，欢迎使用。"),
            request.messages.single().content
        )
    }

    @Test
    fun composeStylePayload_usesSingOnlyWhenPresetIsSing() {
        val payload = SceneVoiceTtsProtocol.composeStylePayload(
            SceneVoiceConfig(
                voiceId = SceneVoiceConfigStore.VOICE_MIMO_DEFAULT,
                stylePreset = SceneVoiceConfigStore.STYLE_SING,
                customStyle = "忽略这段"
            )
        )

        assertEquals("<style>唱歌</style>", payload)
    }

    @Test
    fun parsesStreamingAndNonStreamingAudioPayloads() {
        val streamBase64 = SceneVoiceTtsProtocol.extractStreamAudioBase64(
            """
            {
              "choices": [
                {
                  "delta": {
                    "audio": {
                      "data": "UENNREFUQQ==",
                      "format": "pcm16"
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val nonStreaming = SceneVoiceTtsProtocol.parseNonStreamingAudio(
            """
            {
              "choices": [
                {
                  "message": {
                    "audio": {
                      "data": "V0FWREFUQQ==",
                      "format": "wav"
                    }
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("UENNREFUQQ==", streamBase64)
        assertNotNull(nonStreaming)
        assertEquals("V0FWREFUQQ==", nonStreaming?.base64Data)
        assertEquals("wav", nonStreaming?.format)
    }

    @Test
    fun cacheKey_changesWhenVoiceOrStyleChanges() {
        val baseline = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = "message-1",
            text = "同一段文本",
            providerProfileId = "provider-1",
            modelId = "mimo-v2-tts",
            voiceId = SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
            stylePayload = "<style>自然对话</style>"
        )
        val changedVoice = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = "message-1",
            text = "同一段文本",
            providerProfileId = "provider-1",
            modelId = "mimo-v2-tts",
            voiceId = SceneVoiceConfigStore.VOICE_DEFAULT_EN,
            stylePayload = "<style>自然对话</style>"
        )
        val changedStyle = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = "message-1",
            text = "同一段文本",
            providerProfileId = "provider-1",
            modelId = "mimo-v2-tts",
            voiceId = SceneVoiceConfigStore.VOICE_DEFAULT_ZH,
            stylePayload = "<style>专业播报</style>"
        )

        assertNotEquals(baseline, changedVoice)
        assertNotEquals(baseline, changedStyle)
    }
}
