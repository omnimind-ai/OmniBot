package cn.com.omnimind.bot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomTtsCurlCommandTest {

    @Test
    fun parse_indexTtsMultilineExample_extractsUrlHeaderAndBody() {
        val command = """
            curl https://tts-api.1775885.xyz/v1/audio/speech \
              -H "Content-Type: application/json" \
              -d '{
                "model": "tts-1",
                "voice": "nsfw_female_a",
                "input": "{{text}}",
                "response_format": "wav"
              }' \
              --output speech.wav
        """.trimIndent()

        val parsed = CustomTtsCurlCommand.parse(command)

        assertEquals("https://tts-api.1775885.xyz/v1/audio/speech", parsed.url)
        assertEquals("POST", parsed.method)
        assertEquals(
            listOf("Content-Type" to "application/json"),
            parsed.headers
        )
        val body = requireNotNull(parsed.body)
        assertTrue(body.contains("\"input\": \"{{text}}\""))
        assertTrue(body.contains("\"response_format\": \"wav\""))
    }

    @Test
    fun parse_withoutBody_defaultsToGet() {
        val parsed = CustomTtsCurlCommand.parse("curl https://example.com/voices")
        assertEquals("GET", parsed.method)
        assertNull(parsed.body)
        assertEquals("https://example.com/voices", parsed.url)
    }

    @Test
    fun parse_explicitMethodAndEqualsForms() {
        val parsed = CustomTtsCurlCommand.parse(
            "curl -X POST --url=https://example.com -H 'Authorization: Bearer abc' --data-raw='{\"a\":1}'"
        )
        assertEquals("POST", parsed.method)
        assertEquals("https://example.com", parsed.url)
        assertEquals(listOf("Authorization" to "Bearer abc"), parsed.headers)
        assertEquals("{\"a\":1}", parsed.body)
    }

    @Test
    fun parse_outputFlagValueIsNotMistakenForUrl() {
        val parsed = CustomTtsCurlCommand.parse(
            "curl -s -o out.wav https://example.com/speech -d 'hi'"
        )
        assertEquals("https://example.com/speech", parsed.url)
        assertEquals("hi", parsed.body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parse_missingUrl_throws() {
        CustomTtsCurlCommand.parse("curl -H 'Content-Type: application/json' -d '{}'")
    }

    @Test
    fun substituteText_escapesJsonSpecialCharacters() {
        val out = CustomTtsCurlCommand.substituteText(
            "-d '{\"input\": \"{{text}}\"}'",
            "他说\"你好\"\n新的一行\\结束"
        )
        assertEquals(
            "-d '{\"input\": \"他说\\\"你好\\\"\\n新的一行\\\\结束\"}'",
            out
        )
    }

    @Test
    fun substituteThenParse_bodyContainsEscapedText() {
        val substituted = CustomTtsCurlCommand.substituteText(
            """
            curl https://tts-api.1775885.xyz/v1/audio/speech \
              -H 'Content-Type: application/json' \
              -d '{"input": "{{text}}", "response_format": "wav"}'
            """.trimIndent(),
            "带\"引号\"的文本"
        )
        val parsed = CustomTtsCurlCommand.parse(substituted)
        assertEquals("https://tts-api.1775885.xyz/v1/audio/speech", parsed.url)
        assertEquals("POST", parsed.method)
        assertTrue(
            requireNotNull(parsed.body).contains("\"input\": \"带\\\"引号\\\"的文本\""),
        )
    }
}
