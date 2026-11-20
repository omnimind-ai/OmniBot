package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

internal const val SPEECH_INPUT_SAMPLE_RATE = 16_000
internal const val SPEECH_INPUT_MAX_BYTES = SPEECH_INPUT_SAMPLE_RATE * 2 * 60

internal class DeepSeekSpeechProvider(
    private val key: () -> String?,
    private val createCall: (Request) -> Call = { http.newCall(it) },
) : SpeechRecognitionProvider, SpeechSynthesisProvider {
    override suspend fun transcribe(frames: Flow<ByteArray>): String {
        val credential = requireKey()
        val pcm = ByteArrayOutputStream()
        frames.collect { frame ->
            if (frame.size % 2 != 0 || frame.size > SPEECH_INPUT_MAX_BYTES - pcm.size()) {
                throw SpeechProviderException("audio_format", "录音格式或长度无效")
            }
            pcm.write(frame)
        }
        if (pcm.size() == 0) throw SpeechProviderException("no_speech", "没有录到语音")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", encodeSpeechWav(pcm.toByteArray()).toRequestBody(wavType))
            .build()
        return withContext(Dispatchers.IO) {
            readResponse(request("asr", credential, body)) { response ->
                val source = response.body.source()
                if (source.request(64 * 1024L + 1)) throw invalidResponse()
                val text = try {
                    val json = JsonParser.parseString(source.readUtf8()).asJsonObject
                    json.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString?.trim()
                } catch (_: RuntimeException) {
                    throw invalidResponse()
                }
                if (text == null) throw invalidResponse()
                if (text.isEmpty()) throw SpeechProviderException("no_speech", "没有识别到语音")
                text
            }
        }
    }

    override fun synthesize(text: String, voiceId: String): Flow<ByteArray> = flow {
        val credential = requireKey()
        val chunks = splitDeepSeekSpeechText(text)
        if (chunks.isEmpty()) throw SpeechProviderException("invalid_input", "没有可以朗读的文本")
        for (chunk in chunks) {
            val body = JsonObject().apply {
                addProperty("text", chunk)
                addProperty("voice_id", voiceId)
                addProperty("format", "pcm")
                addProperty("filter_markdown", true)
            }.toString().toByteArray(Charsets.UTF_8)
            check(body.size <= 64 * 1024)
            readResponse(request("tts", credential, body.toRequestBody(jsonType)), synthesis = true) { response ->
                val audio = response.body
                val type = audio.contentType()
                if (type?.type == "text" || type?.subtype?.contains("json") == true) throw invalidResponse()
                val source = audio.source()
                val buffer = ByteArray(8192)
                var pending = 0
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer, pending, buffer.size - pending)
                    if (count == -1) break
                    val size = pending + count
                    val evenSize = size and -2
                    if (evenSize > 0) {
                        total += evenSize
                        if (total > 24_000L * 2 * 60 * 30) throw invalidResponse()
                        emit(buffer.copyOf(evenSize))
                    }
                    pending = size - evenSize
                    if (pending != 0) buffer[0] = buffer[evenSize]
                }
                if (total == 0L || pending != 0) throw invalidResponse()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun requireKey(): String = key()
        ?: throw SpeechProviderException("configuration", "请先配置 DeepSeek API Key")

    private fun request(tool: String, credential: String, body: RequestBody): Request =
        Request.Builder()
            .url("${DeepSeekProvider.OFFICIAL_BASE_URL}/v1/tools/$tool")
            .header("Authorization", "Bearer $credential")
            .post(object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                override fun isOneShot() = true
            })
            .build()

    private suspend fun <T> readResponse(
        request: Request,
        synthesis: Boolean = false,
        read: suspend (Response) -> T,
    ): T = coroutineScope {
        val call = createCall(request)
        val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw httpError(response.code, synthesis)
                read(response)
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (error is SocketTimeoutException) {
                throw SpeechProviderException("timeout", "DeepSeek 语音服务超时，请重试")
            }
            throw SpeechProviderException("network", "DeepSeek 语音连接中断，请重试")
        } finally {
            cancelCall.cancel()
        }
    }

    private fun invalidResponse() =
        SpeechProviderException("invalid_response", "DeepSeek 返回的语音结果不完整或格式无效")

    private fun httpError(status: Int, synthesis: Boolean): SpeechProviderException = when (status) {
        400 -> if (synthesis) {
            SpeechProviderException("invalid_input", "DeepSeek 无法朗读这段文本")
        } else {
            SpeechProviderException("audio_format", "DeepSeek 无法识别此录音格式")
        }
        401, 403 -> SpeechProviderException("authentication", "请检查 DeepSeek API Key 和服务权限")
        402 -> SpeechProviderException("balance", "DeepSeek 账户余额不足")
        408, 504 -> SpeechProviderException("timeout", "DeepSeek 语音服务超时，请重试")
        413 -> SpeechProviderException("audio_format", "录音超过 DeepSeek 的文件大小限制")
        429 -> SpeechProviderException("rate_limit", "DeepSeek 请求过多，请稍后重试")
        503 -> SpeechProviderException("unavailable", "DeepSeek 语音服务繁忙，请稍后重试")
        else -> SpeechProviderException("service", "DeepSeek 语音请求失败（HTTP $status）")
    }

    private companion object {
        val wavType = "audio/wav".toMediaType()
        val jsonType = "application/json; charset=utf-8".toMediaType()
        val http = OkHttpManager.sensitiveContentClient(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build(),
        )
    }
}

internal fun encodeSpeechWav(pcm: ByteArray, sampleRate: Int = SPEECH_INPUT_SAMPLE_RATE): ByteArray =
    ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(sampleRate)
        putInt(sampleRate * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(pcm.size)
        put(pcm)
    }.array()

internal fun splitDeepSeekSpeechText(text: String): List<String> = buildList {
    val input = text.trim()
    var start = 0
    while (start < input.length) {
        var end = start
        var boundary = start
        var billingChars = 0
        while (end < input.length) {
            val codePoint = input.codePointAt(end)
            val cost = if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) 2 else 1
            if (billingChars + cost > 10_000) break
            billingChars += cost
            end += Character.charCount(codePoint)
            if (billingChars >= 8_000 && (Character.isWhitespace(codePoint) || codePoint in speechPunctuation)) {
                boundary = end
            }
        }
        if (end < input.length && boundary > start) end = boundary
        input.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(::add)
        start = end
    }
}

private val speechPunctuation = "。！？；.!?;".map(Char::code).toSet()
