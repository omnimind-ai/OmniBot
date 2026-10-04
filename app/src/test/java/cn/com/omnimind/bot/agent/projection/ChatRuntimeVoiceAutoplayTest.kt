package cn.com.omnimind.bot.agent.projection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `voice_playback_coordinator_test.dart` and `scene_voice_text_processing_test.dart`. */
class ChatRuntimeVoiceAutoplayTest {
    private data class Spoken(val messageId: String, val text: String, val enqueue: Boolean)

    @Test
    fun `auto play queues sealed segments incrementally`() {
        val spoken = ArrayList<Spoken>()
        val autoplay = ChatRuntimeVoiceAutoplay(autoplayEnabled = { true }) { id, text, enqueue ->
            spoken.add(Spoken(id, text, enqueue))
            true
        }
        autoplay.onAssistantMessageUpdated("message-1", "第一句。第二句", isFinal = false)
        assertEquals(listOf(Spoken("message-1", "第一句。", false)), spoken)

        autoplay.onAssistantMessageCompleted("message-1", "第一句。第二句")
        assertEquals(2, spoken.size)
        assertEquals(Spoken("message-1", "第二句", true), spoken.last())
    }

    @Test
    fun `auto play stays silent when the voice scene is unavailable`() {
        val spoken = ArrayList<String>()
        val autoplay = ChatRuntimeVoiceAutoplay(autoplayEnabled = { false }) { _, text, _ ->
            spoken.add(text)
            true
        }
        autoplay.onAssistantMessageUpdated("m", "第一句。", isFinal = false)
        autoplay.onAssistantMessageCompleted("m", "第一句。第二句")
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `a stale shorter prefix does not respeak and a rewrite restarts`() {
        val spoken = ArrayList<String>()
        val autoplay = ChatRuntimeVoiceAutoplay(autoplayEnabled = { true }) { _, text, _ ->
            spoken.add(text)
            true
        }
        autoplay.onAssistantMessageUpdated("m", "一。二。", isFinal = false)
        autoplay.onAssistantMessageUpdated("m", "一。", isFinal = false)
        assertEquals(listOf("一。", "二。"), spoken)
        autoplay.onAssistantMessageUpdated("m", "改写。", isFinal = false)
        assertEquals(listOf("一。", "二。", "改写。"), spoken)
    }

    @Test
    fun `sanitizeForSpeech removes markdown links urls and fenced code`() {
        val sanitized = SceneVoiceTextProcessing.sanitizeForSpeech(
            "这是一个[链接标题](https://example.com)\n```dart\nprint(\"hello\");\n```\n访问 https://openai.com 看看\n",
        )
        assertTrue(sanitized.contains("这是一个链接标题"))
        assertFalse(sanitized.contains("https://example.com"))
        assertFalse(sanitized.contains("print(\"hello\")"))
        assertFalse(sanitized.contains("https://openai.com"))
    }

    @Test
    fun `extractSealedSegments splits on punctuation and flushes tail on final`() {
        val partial = SceneVoiceTextProcessing.extractSealedSegments("第一句。第二句", 0, isFinal = false)
        assertEquals(listOf("第一句。"), partial.segments)
        assertEquals("第一句。".length, partial.nextIndex)
        val completed = SceneVoiceTextProcessing.extractSealedSegments("第一句。第二句", partial.nextIndex, isFinal = true)
        assertEquals(listOf("第二句"), completed.segments)
        assertEquals("第一句。第二句".length, completed.nextIndex)
    }

    @Test
    fun `extractSealedSegments does not split inside fenced code blocks`() {
        val result = SceneVoiceTextProcessing.extractSealedSegments("前言```code();\nnext();```结尾。", 0, isFinal = true)
        assertEquals(listOf("前言 结尾。"), result.segments)
    }

    @Test
    fun `sanitize treats unicode spaces like dart`() {
        assertEquals("a b", SceneVoiceTextProcessing.sanitizeForSpeech("a　 b"))
        assertEquals("见", SceneVoiceTextProcessing.sanitizeForSpeech("见 https://x.cn/路径　"))
    }
}
