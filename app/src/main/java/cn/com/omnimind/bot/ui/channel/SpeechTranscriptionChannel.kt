package cn.com.omnimind.bot.ui.channel

import android.Manifest
import android.content.Context
import cn.com.omnimind.baselib.permission.PermissionRequest
import cn.com.omnimind.bot.voice.SpeechRecorder
import cn.com.omnimind.bot.voice.SpeechTranscriptionClient
import cn.com.omnimind.bot.voice.SpeechTranscriptionErrorCode
import cn.com.omnimind.bot.voice.SpeechTranscriptionException
import cn.com.omnimind.bot.voice.SpeechTranscriptionProtocol
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SpeechTranscriptionChannel {
    private var channel: MethodChannel? = null
    private var appContext: Context? = null
    private var recorder: SpeechRecorder? = null
    private var client: SpeechTranscriptionClient? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recordingLimitJob: Job? = null
    private var transcriptionJob: Job? = null
    private var permissionJob: Job? = null
    private var recordingLanguage: String? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
        recorder = SpeechRecorder(context)
        client = SpeechTranscriptionClient()
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME,
        ).also { current ->
            current.setMethodCallHandler(::handleMethodCall)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "startRecording" -> startRecording(call, result)
            "stopAndTranscribe" -> stopAndTranscribe(call, result)
            "transcribeFile" -> transcribeFile(call, result)
            "cancel" -> cancel(result)
            else -> result.notImplemented()
        }
    }

    private fun startRecording(call: MethodCall, result: MethodChannel.Result) {
        val currentContext = appContext
        val currentRecorder = recorder
        if (currentContext == null || currentRecorder == null) {
            result.error(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "语音输入尚未初始化",
                null,
            )
            return
        }
        if (isBusy() || currentRecorder.isRecording()) {
            result.error(SpeechTranscriptionErrorCode.BUSY, "语音输入正在处理中", null)
            return
        }
        permissionJob = scope.launch {
            val runningJob = coroutineContext[Job]
            try {
                val granted = requestMicrophonePermission(currentContext)
                if (!granted) {
                    throw SpeechTranscriptionException(
                        SpeechTranscriptionErrorCode.PERMISSION_DENIED,
                        "需要麦克风权限才能使用语音输入",
                    )
                }
                val session = currentRecorder.start()
                recordingLanguage = call.argument<String>("language")
                scheduleRecordingLimit()
                result.success(
                    mapOf(
                        "state" to "recording",
                        "startedAtElapsedMs" to session.startedAtElapsedMs,
                        "maxDurationMs" to session.maxDurationMs,
                    )
                )
            } catch (error: Throwable) {
                if (permissionJob === runningJob) {
                    recordingLanguage = null
                }
                deliverError(result, error)
            } finally {
                if (permissionJob === runningJob) {
                    permissionJob = null
                }
            }
        }
    }

    private fun stopAndTranscribe(call: MethodCall, result: MethodChannel.Result) {
        if (transcriptionJob?.isActive == true) {
            result.error(SpeechTranscriptionErrorCode.BUSY, "语音转写正在进行", null)
            return
        }
        val recorded = try {
            recordingLimitJob?.cancel()
            recordingLimitJob = null
            recorder?.stop() ?: throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "语音输入尚未初始化",
            )
        } catch (error: Throwable) {
            recordingLanguage = null
            deliverError(result, error)
            return
        }
        val language = call.argument<String>("language") ?: recordingLanguage
        recordingLanguage = null
        startTranscription(
            file = recorded.file,
            durationMs = recorded.durationMs,
            deleteAfter = true,
            requestedModel = call.argument<String>("model"),
            language = language,
            result = result,
            autoStopped = false,
        )
    }

    private fun transcribeFile(call: MethodCall, result: MethodChannel.Result) {
        if (isBusy() || recorder?.isRecording() == true) {
            result.error(SpeechTranscriptionErrorCode.BUSY, "语音输入正在处理中", null)
            return
        }
        val path = call.argument<String>("path")?.trim().orEmpty()
        if (path.isEmpty()) {
            result.error(
                SpeechTranscriptionErrorCode.FILE_NOT_FOUND,
                "未选择音频文件",
                null,
            )
            return
        }
        val file = File(path)
        transcriptionJob = scope.launch {
            val runningJob = coroutineContext[Job]
            try {
                val duration = withContext(Dispatchers.IO) {
                    SpeechTranscriptionProtocol.validateAudioFileBasics(file)
                    recorder?.readDurationMs(file)
                }
                val validated = withContext(Dispatchers.IO) {
                    SpeechTranscriptionProtocol.validateAudioFile(
                        file = file,
                        durationMs = duration,
                        mimeTypeHint = call.argument<String>("mimeType"),
                    )
                }
                val response = withContext(Dispatchers.IO) {
                    requireNotNull(client).transcribe(
                        audio = validated,
                        requestedModel = call.argument<String>("model"),
                        language = call.argument<String>("language"),
                    )
                }
                result.success(response.toMap(validated.durationMs))
            } catch (error: Throwable) {
                deliverError(result, error)
            } finally {
                if (transcriptionJob === runningJob) {
                    transcriptionJob = null
                }
            }
        }
    }

    private fun startTranscription(
        file: File,
        durationMs: Long,
        deleteAfter: Boolean,
        requestedModel: String?,
        language: String?,
        result: MethodChannel.Result?,
        autoStopped: Boolean,
    ) {
        if (autoStopped) {
            emitEvent(mapOf("state" to "transcribing", "autoStopped" to true))
        }
        transcriptionJob = scope.launch {
            val runningJob = coroutineContext[Job]
            try {
                val validated = withContext(Dispatchers.IO) {
                    SpeechTranscriptionProtocol.validateAudioFile(
                        file = file,
                        durationMs = durationMs,
                        mimeTypeHint = "audio/mp4",
                    )
                }
                val response = withContext(Dispatchers.IO) {
                    requireNotNull(client).transcribe(
                        audio = validated,
                        requestedModel = requestedModel,
                        language = language,
                    )
                }
                val payload = response.toMap(validated.durationMs)
                if (result != null) {
                    result.success(payload)
                } else {
                    emitEvent(payload + ("state" to "completed"))
                }
            } catch (error: Throwable) {
                if (result != null) {
                    deliverError(result, error)
                } else {
                    emitErrorEvent(error)
                }
            } finally {
                if (deleteAfter) file.delete()
                if (transcriptionJob === runningJob) {
                    transcriptionJob = null
                }
            }
        }
    }

    private fun scheduleRecordingLimit() {
        recordingLimitJob?.cancel()
        recordingLimitJob = scope.launch {
            delay(SpeechTranscriptionProtocol.MAX_RECORDING_DURATION_MS)
            recordingLimitJob = null
            val recorded = runCatching { recorder?.stop() }.getOrNull()
            if (recorded == null) {
                recordingLanguage = null
                emitErrorEvent(
                    SpeechTranscriptionException(
                        SpeechTranscriptionErrorCode.RECORDING_FAILED,
                        "录音已停止，但未能生成可转写文件",
                    )
                )
                return@launch
            }
            val language = recordingLanguage
            recordingLanguage = null
            startTranscription(
                file = recorded.file,
                durationMs = recorded.durationMs,
                deleteAfter = true,
                requestedModel = null,
                language = language,
                result = null,
                autoStopped = true,
            )
        }
    }

    private fun cancel(result: MethodChannel.Result) {
        recordingLimitJob?.cancel()
        recordingLimitJob = null
        permissionJob?.cancel()
        permissionJob = null
        recordingLanguage = null
        recorder?.cancel()
        transcriptionJob?.cancel(CancellationException("speech transcription cancelled"))
        transcriptionJob = null
        result.success(mapOf("state" to "cancelled"))
    }

    private fun isBusy(): Boolean =
        permissionJob?.isActive == true || transcriptionJob?.isActive == true

    private suspend fun requestMicrophonePermission(context: Context): Boolean {
        if (PermissionRequest.isPermissionGranted(context, Manifest.permission.RECORD_AUDIO)) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            PermissionRequest.requestPermissions(
                context,
                arrayOf(Manifest.permission.RECORD_AUDIO),
            ) { result ->
                if (continuation.isActive) {
                    continuation.resume(result[Manifest.permission.RECORD_AUDIO] == true)
                }
            }
        }
    }

    private fun cn.com.omnimind.bot.voice.SpeechTranscriptionResult.toMap(
        durationMs: Long,
    ): Map<String, Any?> = mapOf(
        "text" to text,
        "model" to modelId,
        "route" to if (platform) "platform" else "byok",
        "durationMs" to durationMs,
    )

    private fun deliverError(result: MethodChannel.Result, raw: Throwable) {
        val error = normalizeError(raw)
        result.error(error.stableCode, error.message, null)
    }

    private fun emitErrorEvent(raw: Throwable) {
        val error = normalizeError(raw)
        emitEvent(
            mapOf(
                "state" to if (error.stableCode == SpeechTranscriptionErrorCode.CANCELLED) {
                    "cancelled"
                } else {
                    "error"
                },
                "code" to error.stableCode,
                "message" to error.message,
            )
        )
    }

    private fun normalizeError(raw: Throwable): SpeechTranscriptionException {
        if (raw is SpeechTranscriptionException) return raw
        if (raw is CancellationException) {
            return SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.CANCELLED,
                "语音转写已取消",
                raw,
            )
        }
        return SpeechTranscriptionException(
            SpeechTranscriptionErrorCode.REQUEST_FAILED,
            "语音转写失败，请稍后重试",
            raw,
        )
    }

    private fun emitEvent(payload: Map<String, Any?>) {
        channel?.invokeMethod("onSpeechTranscriptionEvent", payload)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        recordingLimitJob?.cancel()
        permissionJob?.cancel()
        transcriptionJob?.cancel()
        recorder?.cancel()
        recordingLanguage = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        appContext = null
        recorder = null
        client = null
    }

    companion object {
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/SpeechTranscription"
    }
}
