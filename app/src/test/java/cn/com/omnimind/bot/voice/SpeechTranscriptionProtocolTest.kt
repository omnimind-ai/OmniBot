package cn.com.omnimind.bot.voice

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTranscriptionProtocolTest {
    @Test
    fun keepsMultipartAudioPayloadBelowTwentyFiveMiBGatewayBoundary() {
        assertEquals(24L * 1024L * 1024L, SpeechTranscriptionProtocol.MAX_AUDIO_BYTES)
        assertTrue(SpeechTranscriptionProtocol.MAX_AUDIO_BYTES < 25L * 1024L * 1024L)
    }

    @Test
    fun resolvesStandardAndDirectByokEndpoints() {
        assertEquals(
            "https://api.example.com/v1/audio/transcriptions",
            SpeechTranscriptionProtocol.resolveEndpoint("https://api.example.com"),
        )
        assertEquals(
            "https://api.example.com/v1/audio/transcriptions",
            SpeechTranscriptionProtocol.resolveEndpoint("https://api.example.com/v1"),
        )
        assertEquals(
            "https://api.example.com/custom/stt",
            SpeechTranscriptionProtocol.resolveEndpoint("https://api.example.com/custom/stt#"),
        )
    }

    @Test
    fun parsesJsonAndPlainTextResponses() {
        assertEquals(
            "你好，OmniBot",
            SpeechTranscriptionProtocol.parseTranscription(
                """{"text":"你好，OmniBot"}""".toByteArray(),
                "application/json; charset=utf-8",
            ),
        )
        assertEquals(
            "plain transcript",
            SpeechTranscriptionProtocol.parseTranscription(
                " plain transcript \n".toByteArray(),
                "text/plain",
            ),
        )
    }

    @Test
    fun recognizesSupportedAudioContainersByMagicBytes() {
        assertEquals("audio/wav", SpeechTranscriptionProtocol.detectAudioMimeType(wavHeader()))
        assertEquals(
            "audio/mpeg",
            SpeechTranscriptionProtocol.detectAudioMimeType("ID3sample".toByteArray()),
        )
        assertEquals(
            "audio/flac",
            SpeechTranscriptionProtocol.detectAudioMimeType("fLaCsample".toByteArray()),
        )
        assertEquals(
            "audio/ogg",
            SpeechTranscriptionProtocol.detectAudioMimeType("OggSsample".toByteArray()),
        )
        assertEquals(
            "audio/mp4",
            SpeechTranscriptionProtocol.detectAudioMimeType(
                byteArrayOf(0, 0, 0, 16) + "ftypM4A ".toByteArray(),
            ),
        )
        assertEquals(
            "audio/webm",
            SpeechTranscriptionProtocol.detectAudioMimeType(
                byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0),
            ),
        )
    }

    @Test
    fun validatesSizeDurationAndFileSignature() {
        val valid = temporaryFile(wavHeader())
        val oversized = temporaryFile(wavHeader())
        try {
            val audio = SpeechTranscriptionProtocol.validateAudioFile(
                file = valid,
                durationMs = 1_500,
            )
            assertEquals("audio/wav", audio.mimeType)
            assertEquals(1_500, audio.durationMs)

            RandomAccessFile(oversized, "rw").use {
                it.setLength(SpeechTranscriptionProtocol.MAX_AUDIO_BYTES + 1)
            }
            val sizeError = runCatching {
                SpeechTranscriptionProtocol.validateAudioFile(oversized, 1_000)
            }.exceptionOrNull()
            assertStableCode(SpeechTranscriptionErrorCode.FILE_TOO_LARGE, sizeError)

            val durationError = runCatching {
                SpeechTranscriptionProtocol.validateAudioFile(
                    valid,
                    SpeechTranscriptionProtocol.MAX_FILE_DURATION_MS + 1,
                )
            }.exceptionOrNull()
            assertStableCode(SpeechTranscriptionErrorCode.DURATION_EXCEEDED, durationError)
        } finally {
            valid.delete()
            oversized.delete()
        }
    }

    @Test
    fun rejectsEmptyTranscriptAndUnknownFileTypeWithStableCodes() {
        val emptyResponse = runCatching {
            SpeechTranscriptionProtocol.parseTranscription(
                """{"text":"  "}""".toByteArray(),
                "application/json",
            )
        }.exceptionOrNull()
        assertStableCode(SpeechTranscriptionErrorCode.EMPTY_TRANSCRIPT, emptyResponse)

        val unknown = temporaryFile("not-an-audio-file".toByteArray())
        try {
            val fileError = runCatching {
                SpeechTranscriptionProtocol.validateAudioFile(unknown, 1_000)
            }.exceptionOrNull()
            assertStableCode(SpeechTranscriptionErrorCode.UNSUPPORTED_AUDIO, fileError)
        } finally {
            unknown.delete()
        }
    }

    private fun assertStableCode(expected: String, raw: Throwable?) {
        assertTrue(raw is SpeechTranscriptionException)
        assertEquals(expected, (raw as SpeechTranscriptionException).stableCode)
    }

    private fun temporaryFile(bytes: ByteArray): File =
        File.createTempFile("omnibot-stt-", ".audio").apply { writeBytes(bytes) }

    private fun wavHeader(): ByteArray = byteArrayOf(
        'R'.code.toByte(),
        'I'.code.toByte(),
        'F'.code.toByte(),
        'F'.code.toByte(),
        0,
        0,
        0,
        0,
        'W'.code.toByte(),
        'A'.code.toByte(),
        'V'.code.toByte(),
        'E'.code.toByte(),
        'f'.code.toByte(),
        'm'.code.toByte(),
        't'.code.toByte(),
        ' '.code.toByte(),
    )
}
