package cn.com.omnimind.bot.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneVoiceConfig
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.media.PlatformMediaGatewayExecutor
import cn.com.omnimind.bot.media.PlatformGatewayException
import cn.com.omnimind.bot.media.PlatformMediaProtocol
import cn.com.omnimind.bot.media.awaitResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.Base64

private enum class VoiceTransport {
    PLATFORM_SPEECH,
    BYOK_CHAT_AUDIO,
    CUSTOM_CURL,
}

private data class VoicePlaybackQueueItem(
    val messageId: String,
    val text: String,
    val binding: SceneVoiceResolvedBinding?,
    val config: SceneVoiceConfig,
    val cacheKey: String,
    val preferStreaming: Boolean,
    val transport: VoiceTransport,
    val customCurlCommand: String? = null
) {
    val isCustomCurl: Boolean get() = transport == VoiceTransport.CUSTOM_CURL
    val isPlatformSpeech: Boolean get() = transport == VoiceTransport.PLATFORM_SPEECH
}

private data class VoiceCacheEntry(
    val key: String,
    val format: String,
    val pcmBytes: ByteArray? = null,
    val wavFile: File? = null
)

class SceneVoicePlaybackManager(
    context: Context
) {
    companion object {
        private const val TAG = "SceneVoicePlayback"
        private const val STREAM_PCM_FORMAT = "pcm16"
        private const val FALLBACK_WAV_FORMAT = "wav"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_CACHE_SIZE = 48
        private const val MAX_BYOK_AUDIO_BYTES = 16L * 1024L * 1024L
        private const val MAX_BYOK_JSON_BYTES = 24L * 1024L * 1024L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }
    private val platformHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val platformExecutor = PlatformMediaGatewayExecutor(
        executeRequest = { request ->
            val call = OkHttpManager.sensitiveContentCall(
                client = platformHttpClient,
                request = request,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
            currentPlatformCall = call
            try {
                call.awaitResponse()
            } finally {
                if (currentPlatformCall === call) {
                    currentPlatformCall = null
                }
            }
        },
    )
    private val lock = Any()
    private val queue = ArrayDeque<VoicePlaybackQueueItem>()
    private val replayableKeysByMessageId = mutableMapOf<String, MutableSet<String>>()
    private val cache = object : LinkedHashMap<String, VoiceCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VoiceCacheEntry>?): Boolean {
            val shouldRemove = size > MAX_CACHE_SIZE
            if (shouldRemove) {
                eldest?.value?.wavFile?.takeIf { it.exists() }?.delete()
                replayableKeysByMessageId.values.forEach { keys ->
                    eldest?.key?.let(keys::remove)
                }
            }
            return shouldRemove
        }
    }

    @Volatile
    private var eventEmitter: ((Map<String, Any?>) -> Unit)? = null

    @Volatile
    private var processorJob: Job? = null

    @Volatile
    private var currentEventSource: EventSource? = null

    @Volatile
    private var currentPlatformCall: Call? = null

    @Volatile
    private var currentAudioTrack: AudioTrack? = null

    @Volatile
    private var currentMediaPlayer: MediaPlayer? = null

    @Volatile
    private var currentMessageId: String? = null

    @Volatile
    private var currentStopRequested = false

    fun setEventEmitter(emitter: ((Map<String, Any?>) -> Unit)?) {
        eventEmitter = emitter
    }

    fun speakText(
        messageId: String,
        text: String,
        enqueue: Boolean,
        preferStreaming: Boolean
    ): Boolean {
        val item = runCatching { buildQueueItem(messageId, text, preferStreaming) }
            .onFailure {
                emitState(
                    messageId = messageId,
                    status = "error",
                    error = "voice scene is not configured (${it.javaClass.simpleName})"
                )
            }
            .getOrNull()
            ?: return false

        synchronized(lock) {
            if (!enqueue) {
                queue.clear()
                stopCurrentPlaybackLocked()
            }
            queue.addLast(item)
            ensureProcessorLocked()
        }
        return true
    }

    fun replayText(
        messageId: String,
        text: String
    ): Boolean {
        return speakText(
            messageId = messageId,
            text = text,
            enqueue = false,
            preferStreaming = true
        )
    }

    fun pause(messageId: String?): Boolean {
        val currentId = currentMessageId ?: return false
        if (!messageId.isNullOrBlank() && messageId.trim() != currentId) {
            return false
        }
        currentAudioTrack?.pause()
        currentMediaPlayer?.takeIf { it.isPlaying }?.pause()
        emitState(currentId, "paused")
        return true
    }

    fun resume(messageId: String?): Boolean {
        val currentId = currentMessageId ?: return false
        if (!messageId.isNullOrBlank() && messageId.trim() != currentId) {
            return false
        }
        currentAudioTrack?.play()
        currentMediaPlayer?.start()
        emitState(currentId, "playing", canReplay = hasReplayableCache(currentId))
        return true
    }

    fun stop(messageId: String?): Boolean {
        val normalizedId = messageId?.trim().orEmpty()
        synchronized(lock) {
            if (normalizedId.isNotEmpty()) {
                queue.removeAll { it.messageId == normalizedId }
            } else {
                queue.clear()
            }
            if (normalizedId.isEmpty() || currentMessageId == normalizedId) {
                stopCurrentPlaybackLocked()
            }
        }
        if (normalizedId.isNotEmpty()) {
            emitState(normalizedId, "idle", canReplay = hasReplayableCache(normalizedId))
        }
        return true
    }

    fun release() {
        synchronized(lock) {
            queue.clear()
            stopCurrentPlaybackLocked()
            processorJob?.cancel()
            processorJob = null
            cache.values.forEach { entry ->
                entry.wavFile?.takeIf { it.exists() }?.delete()
            }
            cache.clear()
            replayableKeysByMessageId.clear()
        }
    }

    private fun buildQueueItem(
        messageId: String,
        text: String,
        preferStreaming: Boolean
    ): VoicePlaybackQueueItem {
        val normalizedMessageId = messageId.trim().ifEmpty {
            throw IllegalArgumentException("messageId is empty")
        }
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) {
            throw IllegalArgumentException("text is empty")
        }
        val config = SceneVoiceConfigStore.getConfig()
        if (config.ttsMode == SceneVoiceConfigStore.TTS_MODE_CUSTOM_CURL) {
            val curl = config.customCurlCommand.trim()
            if (curl.isEmpty()) {
                throw IllegalStateException("未配置自定义 TTS curl 命令")
            }
            if (!curl.contains(SceneVoiceConfigStore.TEXT_PLACEHOLDER)) {
                throw IllegalStateException(
                    "curl 命令缺少 ${SceneVoiceConfigStore.TEXT_PLACEHOLDER} 占位符"
                )
            }
            val cacheKey = SceneVoiceTtsProtocol.buildCacheKey(
                messageId = normalizedMessageId,
                text = normalizedText,
                providerProfileId = "custom_curl",
                modelId = "custom_curl",
                voiceId = "",
                stylePayload = curl
            )
            return VoicePlaybackQueueItem(
                messageId = normalizedMessageId,
                text = normalizedText,
                binding = null,
                config = config,
                cacheKey = cacheKey,
                preferStreaming = false,
                transport = VoiceTransport.CUSTOM_CURL,
                customCurlCommand = curl
            )
        }
        val rawBinding = SceneModelBindingStore.getBinding(SceneVoiceConfigStore.SCENE_ID)
        if (OmniOfficialProvider.isOfficialProfile(rawBinding?.providerProfileId)) {
            val access = OmniAccount.currentAiRequestAccess()
            access.unavailableReason?.let { throw IllegalStateException(it) }
            check(access.usesPlatform) { "官方 AI 账号未登录或服务暂不可用" }
            // Resolve the catalog in processItem on the IO scope. This keeps the
            // synchronous Flutter method call fast while avoiding stale cached
            // TTS model and voice selections.
            return VoicePlaybackQueueItem(
                messageId = normalizedMessageId,
                text = normalizedText,
                binding = null,
                config = config,
                cacheKey = "",
                preferStreaming = false,
                transport = VoiceTransport.PLATFORM_SPEECH,
            )
        }
        val binding = resolveVoiceBinding()
        val stylePayload = SceneVoiceTtsProtocol.composeStylePayload(config)
        val cacheKey = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = normalizedMessageId,
            text = normalizedText,
            providerProfileId = binding.providerProfileId,
            modelId = binding.modelId,
            voiceId = config.voiceId,
            stylePayload = stylePayload
        )
        return VoicePlaybackQueueItem(
            messageId = normalizedMessageId,
            text = normalizedText,
            binding = binding,
            config = config,
            cacheKey = cacheKey,
            preferStreaming = preferStreaming,
            transport = VoiceTransport.BYOK_CHAT_AUDIO,
        )
    }

    private fun resolveVoiceBinding(): SceneVoiceResolvedBinding {
        val binding = SceneModelBindingStore.getBinding(SceneVoiceConfigStore.SCENE_ID)
            ?: throw IllegalStateException("Voice 场景未绑定模型")
        val profile = ModelProviderConfigStore.getProfile(binding.providerProfileId)
            ?: throw IllegalStateException("Voice Provider 不存在")
        if (!profile.isConfigured()) {
            throw IllegalStateException("Voice Provider 未配置")
        }
        if (profile.protocolType.trim().ifEmpty { "openai_compatible" } != "openai_compatible") {
            throw IllegalStateException("Voice 场景仅支持 OpenAI-Compatible Provider")
        }
        return SceneVoiceResolvedBinding(
            providerProfileId = profile.id,
            apiBase = profile.baseUrl,
            apiKey = profile.apiKey,
            modelId = binding.modelId
        )
    }

    private fun ensureProcessorLocked() {
        if (processorJob?.isActive == true) {
            return
        }
        processorJob = scope.launch {
            processLoop()
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val item = synchronized(lock) {
                queue.removeFirstOrNull()?.also {
                    currentMessageId = it.messageId
                    currentStopRequested = false
                }
            } ?: break
            try {
                processItem(item)
            } catch (t: Throwable) {
                OmniLog.e(TAG, "voice playback failed type=${t.javaClass.simpleName}")
                val safeError = if (t is PlatformGatewayException) {
                    t.message
                } else {
                    "voice playback failed (${t.javaClass.simpleName})"
                }
                emitState(item.messageId, "error", error = safeError)
            } finally {
                releaseCurrentPlaybackResources()
                synchronized(lock) {
                    currentMessageId = null
                    currentStopRequested = false
                }
            }
        }
        synchronized(lock) {
            processorJob = null
        }
    }

    private suspend fun processItem(queuedItem: VoicePlaybackQueueItem) {
        val item = if (queuedItem.isPlatformSpeech) {
            resolvePlatformQueueItem(queuedItem)
        } else {
            queuedItem
        }
        synchronized(lock) {
            cache[item.cacheKey]
        }?.let { entry ->
            playCachedEntry(item, entry)
            return
        }

        emitState(item.messageId, "synthesizing")
        if (item.isCustomCurl) {
            val custom = synthesizeCustomCurl(item)
                ?: throw IllegalStateException("自定义 TTS 合成失败")
            synchronized(lock) {
                cache[item.cacheKey] = custom
                replayableKeysByMessageId
                    .getOrPut(item.messageId) { linkedSetOf() }
                    .add(item.cacheKey)
            }
            playCachedEntry(item, custom)
            return
        }
        if (item.isPlatformSpeech) {
            val synthesized = synthesizePlatformSpeech(item)
            synchronized(lock) {
                cache[item.cacheKey] = synthesized
                replayableKeysByMessageId
                    .getOrPut(item.messageId) { linkedSetOf() }
                    .add(item.cacheKey)
            }
            playCachedEntry(item, synthesized)
            return
        }
        if (item.preferStreaming) {
            val streamed = synthesizeStreaming(item)
            if (streamed != null) {
                synchronized(lock) {
                    cache[item.cacheKey] = streamed
                    replayableKeysByMessageId
                        .getOrPut(item.messageId) { linkedSetOf() }
                        .add(item.cacheKey)
                }
                return
            }
        }
        val cached = synthesizeNonStreaming(item)
        if (cached == null) {
            throw IllegalStateException("语音合成失败")
        }
        synchronized(lock) {
            cache[item.cacheKey] = cached
            replayableKeysByMessageId
                .getOrPut(item.messageId) { linkedSetOf() }
                .add(item.cacheKey)
        }
        playCachedEntry(item, cached)
    }

    private suspend fun resolvePlatformQueueItem(
        item: VoicePlaybackQueueItem,
    ): VoicePlaybackQueueItem {
        val status = PlatformAiProvisioner.ensureReadyStatus()
        val modelId = status.defaultTtsModelId
            ?: throw IllegalStateException("官方语音合成能力暂不可用")
        val voiceAlias = item.config.voiceId.trim()
            .takeIf(status.ttsVoiceAliases::contains)
            ?: status.defaultTtsVoiceAlias
                ?.trim()
                ?.takeIf(status.ttsVoiceAliases::contains)
            ?: throw IllegalStateException("官方语音服务当前没有可用的声音")
        val platformConfig = item.config.copy(voiceId = voiceAlias)
        val binding = SceneVoiceResolvedBinding(
            providerProfileId = OmniOfficialProvider.PROFILE_ID,
            apiBase = "",
            apiKey = "",
            modelId = modelId,
        )
        val cacheKey = SceneVoiceTtsProtocol.buildCacheKey(
            messageId = item.messageId,
            text = item.text,
            providerProfileId = binding.providerProfileId,
            modelId = binding.modelId,
            voiceId = platformConfig.voiceId,
            stylePayload = PlatformSpeechProtocol.buildInstructions(platformConfig),
        )
        return item.copy(
            binding = binding,
            config = platformConfig,
            cacheKey = cacheKey,
        )
    }

    private suspend fun synthesizeStreaming(item: VoicePlaybackQueueItem): VoiceCacheEntry? {
        val binding = item.binding ?: return null
        val request = buildRequest(
            binding = binding,
            request = SceneVoiceTtsProtocol.buildChatCompletionRequest(
                text = item.text,
                modelId = item.binding.modelId,
                config = item.config,
                format = STREAM_PCM_FORMAT,
                stream = true
            ),
            stream = true
        )
        val output = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        var parseFailed = false
        var sawAudio = false
        var failureType: String? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data.trim() == "[DONE]") {
                    latch.countDown()
                    return
                }
                val base64Audio = SceneVoiceTtsProtocol.extractStreamAudioBase64(data)
                if (base64Audio.isNullOrBlank()) {
                    return
                }
                val bytes = runCatching { Base64.getDecoder().decode(base64Audio) }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    parseFailed = true
                    failureType = "invalid_audio_payload"
                    latch.countDown()
                    return
                }
                if (output.size().toLong() + bytes.size.toLong() > MAX_BYOK_AUDIO_BYTES) {
                    parseFailed = true
                    failureType = "audio_limit_exceeded"
                    eventSource.cancel()
                    latch.countDown()
                    return
                }
                sawAudio = true
                val track = currentAudioTrack ?: createAudioTrack(minBufferSize).also {
                    currentAudioTrack = it
                    emitState(item.messageId, "playing", canReplay = true)
                    it.play()
                }
                output.write(bytes)
                track.write(bytes, 0, bytes.size)
            }

            override fun onClosed(eventSource: EventSource) {
                latch.countDown()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                failureType = t?.javaClass?.simpleName
                    ?: response?.code?.let { "http_$it" }
                    ?: "transport_failure"
                latch.countDown()
            }
        }

        val source = OkHttpManager.sensitiveContentEventSource(
            client = platformHttpClient,
            request = request,
            listener = listener,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        currentEventSource = source
        withContext(Dispatchers.IO) {
            latch.await(90, TimeUnit.SECONDS)
        }
        currentEventSource = null
        if (currentStopRequested) {
            return null
        }
        if (!sawAudio || parseFailed) {
            OmniLog.w(TAG, "streaming tts fallback type=${failureType ?: "no_audio"}")
            releaseCurrentPlaybackResources()
            return null
        }
        waitForPcmPlayback(output.size())
        emitState(item.messageId, "completed", canReplay = true)
        return VoiceCacheEntry(
            key = item.cacheKey,
            format = STREAM_PCM_FORMAT,
            pcmBytes = output.toByteArray()
        )
    }

    private suspend fun synthesizePlatformSpeech(item: VoicePlaybackQueueItem): VoiceCacheEntry {
        val binding = item.binding
            ?: throw IllegalStateException("官方语音模型尚未就绪")
        val requestBody = PlatformSpeechProtocol.buildRequestBody(
            text = item.text,
            modelId = binding.modelId,
            config = item.config,
        )
        val response = platformExecutor.execute { credentials ->
            Request.Builder()
                .url(PlatformMediaProtocol.endpoint(credentials, "/v1/audio/speech"))
                .header("Authorization", "Bearer ${credentials.bearerToken}")
                .header("Content-Type", "application/json")
                .header("Accept", "audio/wav, audio/mpeg, audio/*")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        return response.use {
            val contentType = it.header("Content-Type")
            val bytes = PlatformMediaProtocol.readBodyLimited(
                response = it,
                maxBytes = PlatformSpeechProtocol.MAX_AUDIO_BYTES,
            )
            PlatformMediaProtocol.requireSuccessfulResponse(it.code, bytes)
            if (currentStopRequested) {
                throw CancellationException("voice playback stopped")
            }
            val format = PlatformSpeechProtocol.detectAudioFormat(bytes, contentType)
                ?: throw IllegalStateException("官方语音服务返回了无效的音频数据")
            val file = cacheFileFor(item.cacheKey, format)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            VoiceCacheEntry(
                key = item.cacheKey,
                format = format,
                wavFile = file,
            )
        }
    }

    private suspend fun synthesizeNonStreaming(item: VoicePlaybackQueueItem): VoiceCacheEntry? {
        val binding = item.binding ?: return null
        val request = buildRequest(
            binding = binding,
            request = SceneVoiceTtsProtocol.buildChatCompletionRequest(
                text = item.text,
                modelId = item.binding.modelId,
                config = item.config,
                format = FALLBACK_WAV_FORMAT,
                stream = false
            ),
            stream = false
        )
        val body = OkHttpManager.sensitiveContentCall(
            client = platformHttpClient,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        ).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("BYOK voice request failed (${response.code})")
            }
            PlatformMediaProtocol.readBodyLimited(response, MAX_BYOK_JSON_BYTES)
                .toString(Charsets.UTF_8)
        }
        val parsed = SceneVoiceTtsProtocol.parseNonStreamingAudio(body) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(parsed.base64Data) }.getOrNull()
            ?: return null
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_BYOK_AUDIO_BYTES) {
            return null
        }
        val file = cacheFileFor(item.cacheKey, parsed.format ?: FALLBACK_WAV_FORMAT)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return VoiceCacheEntry(
            key = item.cacheKey,
            format = parsed.format ?: FALLBACK_WAV_FORMAT,
            wavFile = file
        )
    }

    private suspend fun synthesizeCustomCurl(item: VoicePlaybackQueueItem): VoiceCacheEntry? {
        val command = item.customCurlCommand ?: return null
        val substituted = CustomTtsCurlCommand.substituteText(command, item.text)
        val parsed = CustomTtsCurlCommand.parse(substituted)

        val safeUrl = ContentEndpointSecurity.requireSafe(
            rawUrl = parsed.url,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        val requestBuilder = Request.Builder().url(safeUrl)
        var contentType: String? = null
        parsed.headers.forEach { (name, value) ->
            if (name.equals("Content-Type", ignoreCase = true)) {
                contentType = value
            }
            requestBuilder.addHeader(name, value)
        }
        val body = parsed.body
        if (body != null) {
            val mediaType = (contentType ?: "application/json; charset=utf-8").toMediaType()
            requestBuilder.method(parsed.method, body.toRequestBody(mediaType))
        } else {
            requestBuilder.method(parsed.method, null)
        }

        val request = requestBuilder.build()
        val bytes = OkHttpManager.sensitiveContentCall(
            client = platformHttpClient,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        ).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("custom TTS request failed (${response.code})")
            }
            PlatformMediaProtocol.readBodyLimited(response, MAX_BYOK_AUDIO_BYTES)
        }
        if (currentStopRequested) {
            return null
        }
        if (bytes.isEmpty()) {
            throw IllegalStateException("自定义 TTS 未返回音频数据")
        }
        if (!looksLikeWav(bytes)) {
            throw IllegalStateException("自定义 TTS 返回的不是受支持的 wav 音频")
        }
        val file = audioFileFor(item.cacheKey)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return VoiceCacheEntry(
            key = item.cacheKey,
            format = FALLBACK_WAV_FORMAT,
            wavFile = file
        )
    }

    private fun looksLikeWav(bytes: ByteArray): Boolean {
        if (bytes.size < 12) {
            return false
        }
        return bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
    }

    private suspend fun playCachedEntry(item: VoicePlaybackQueueItem, entry: VoiceCacheEntry) {
        when {
            entry.pcmBytes != null -> playPcmBytes(item.messageId, entry.pcmBytes)
            entry.wavFile != null -> playWavFile(item.messageId, entry.wavFile)
            else -> throw IllegalStateException("voice cache is empty")
        }
    }

    private suspend fun playPcmBytes(messageId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            throw IllegalStateException("pcm bytes are empty")
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val track = createAudioTrack(minBufferSize)
        currentAudioTrack = track
        emitState(messageId, "playing", canReplay = true)
        track.play()
        track.write(bytes, 0, bytes.size)
        waitForPcmPlayback(bytes.size)
        emitState(messageId, "completed", canReplay = true)
    }

    private suspend fun playWavFile(messageId: String, file: File) {
        if (!file.exists()) {
            throw IllegalStateException("wav file does not exist")
        }
        val latch = CountDownLatch(1)
        val mediaPlayer = MediaPlayer()
        currentMediaPlayer = mediaPlayer
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.setOnCompletionListener {
            latch.countDown()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            latch.countDown()
            true
        }
        mediaPlayer.prepare()
        emitState(messageId, "playing", canReplay = true)
        mediaPlayer.start()
        withContext(Dispatchers.IO) {
            latch.await(120, TimeUnit.SECONDS)
        }
        emitState(messageId, "completed", canReplay = true)
    }

    private suspend fun waitForPcmPlayback(totalBytes: Int) {
        val totalFrames = totalBytes / (CHANNEL_COUNT * BYTES_PER_SAMPLE)
        while (true) {
            if (currentStopRequested) {
                return
            }
            val track = currentAudioTrack ?: return
            val playedFrames = track.playbackHeadPosition
            if (playedFrames >= totalFrames - 128) {
                delay(40)
                return
            }
            delay(40)
        }
    }

    private fun createAudioTrack(bufferSize: Int): AudioTrack {
        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    private fun buildRequest(
        binding: SceneVoiceResolvedBinding,
        request: ChatCompletionRequest,
        stream: Boolean
    ): Request {
        val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
        val safeUrl = ContentEndpointSecurity.requireSafe(
            rawUrl = buildChatCompletionsUrl(binding.apiBase),
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
        )
        val requestBuilder = Request.Builder()
            .url(safeUrl)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
        if (stream) {
            requestBuilder.addHeader("Accept", "text/event-stream")
        } else {
            requestBuilder.addHeader("Accept", "application/json")
        }
        if (binding.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${binding.apiKey}")
        }
        return requestBuilder.build()
    }

    private fun buildChatCompletionsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase)
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        return if (ModelProviderConfigStore.hasVersionedBasePath(base)) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    private fun cacheFileFor(cacheKey: String, format: String): File {
        return if (format.lowercase() == STREAM_PCM_FORMAT) {
            File(File(appContext.cacheDir, "scene_voice"), "$cacheKey.pcm")
        } else {
            audioFileFor(cacheKey, format)
        }
    }

    private fun audioFileFor(cacheKey: String, format: String = FALLBACK_WAV_FORMAT): File {
        val safeExtension = format.lowercase().takeIf { it in setOf("wav", "mp3", "flac", "ogg") }
            ?: FALLBACK_WAV_FORMAT
        return File(AgentWorkspaceManager.audioDirectory(appContext), "$cacheKey.$safeExtension")
    }

    private fun hasReplayableCache(messageId: String): Boolean {
        return synchronized(lock) {
            replayableKeysByMessageId[messageId]?.isNotEmpty() == true
        }
    }

    private fun stopCurrentPlaybackLocked() {
        currentStopRequested = true
        currentEventSource?.cancel()
        currentEventSource = null
        currentPlatformCall?.cancel()
        currentPlatformCall = null
        releaseCurrentPlaybackResources()
    }

    private fun releaseCurrentPlaybackResources() {
        currentAudioTrack?.runCatching {
            pause()
            flush()
            stop()
            release()
        }
        currentAudioTrack = null
        currentMediaPlayer?.runCatching {
            if (isPlaying) {
                stop()
            }
            reset()
            release()
        }
        currentMediaPlayer = null
    }

    private fun emitState(
        messageId: String,
        status: String,
        error: String? = null,
        canReplay: Boolean = false
    ) {
        val payload = linkedMapOf<String, Any?>(
            "messageId" to messageId,
            "status" to status,
            "error" to error,
            "canReplay" to canReplay
        )
        mainHandler.post {
            eventEmitter?.invoke(Collections.unmodifiableMap(payload))
        }
    }
}
