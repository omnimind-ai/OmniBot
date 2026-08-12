package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.baselib.account.AiRequestAccess
import cn.com.omnimind.baselib.llm.PlatformAiProvisioningStatus
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTranscriptionClientTest {
    @Test
    fun platformUsesCatalogModelAndRefreshesJwtExactlyOnce() = runBlocking {
        var token = "expired-token"
        var refreshCount = 0
        val requests = mutableListOf<Request>()
        val codes = ArrayDeque(listOf(401, 200))
        val access = {
            AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                platformGatewayUrl = "https://gateway.example.com",
                bearerToken = token,
            )
        }
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                requests += request
                response(request, codes.removeFirst(), """{"text":"测试完成"}""")
            },
            accessProvider = access,
            refreshSession = {
                refreshCount += 1
                token = "fresh-token"
            },
        )
        val client = SpeechTranscriptionClient(
            platformExecutor = executor,
            accessProvider = access,
            ensurePlatformStatus = { platformStatus() },
        )
        val file = temporaryWav()
        try {
            val result = client.transcribe(
                audio = SpeechTranscriptionProtocol.ValidatedAudio(
                    file = file,
                    mimeType = "audio/wav",
                    durationMs = 1_000,
                ),
                requestedModel = "untrusted-client-model",
                language = "zh-CN",
            )

            assertEquals("测试完成", result.text)
            assertEquals("official-stt", result.modelId)
            assertTrue(result.platform)
            assertEquals(1, refreshCount)
            assertEquals(2, requests.size)
            assertTrue(requests.all { it.url.encodedPath == "/v1/audio/transcriptions" })
            assertEquals("Bearer expired-token", requests[0].header("Authorization"))
            assertEquals("Bearer fresh-token", requests[1].header("Authorization"))
            val body = Buffer().also { requests[1].body?.writeTo(it) }.readUtf8()
            assertTrue(body.contains("official-stt"))
            assertTrue(body.contains("zh"))
            assertFalse(body.contains("untrusted-client-model"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun mapsPlatformQuotaEnvelopeToStableErrorCode() = runBlocking {
        val requestAccess = {
            AiRequestAccess(
                mode = AiAccessMode.PLATFORM,
                platformGatewayUrl = "https://gateway.example.com",
                bearerToken = "access-token",
            )
        }
        val executor = PlatformMediaGatewayExecutor(
            executeRequest = { request ->
                response(
                    request,
                    200,
                    """{"error":{"code":"insufficient_platform_quota","message":"hidden"}}""",
                )
            },
            accessProvider = requestAccess,
        )
        val client = SpeechTranscriptionClient(
            platformExecutor = executor,
            accessProvider = requestAccess,
            ensurePlatformStatus = { platformStatus() },
        )
        val file = temporaryWav()
        try {
            val error = runCatching {
                client.transcribe(
                    SpeechTranscriptionProtocol.ValidatedAudio(
                        file = file,
                        mimeType = "audio/wav",
                        durationMs = 1_000,
                    )
                )
            }.exceptionOrNull()

            assertTrue(error is SpeechTranscriptionException)
            assertEquals(
                SpeechTranscriptionErrorCode.QUOTA_EXCEEDED,
                (error as SpeechTranscriptionException).stableCode,
            )
            assertFalse(error.message.orEmpty().contains("hidden"))
        } finally {
            file.delete()
        }
    }

    private fun platformStatus(): PlatformAiProvisioningStatus =
        PlatformAiProvisioningStatus(
            ready = true,
            defaultModelId = "official-text",
            models = listOf(ProviderModelOption("official-text")),
            defaultSttModelId = "official-stt",
            sttModels = listOf(ProviderModelOption("official-stt")),
        )

    private fun temporaryWav(): File =
        File.createTempFile("omnibot-stt-client-", ".wav").apply {
            writeBytes(
                byteArrayOf(
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
                )
            )
        }

    private fun response(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
