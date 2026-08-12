package cn.com.omnimind.bot.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.UUID

internal data class RecordedSpeech(
    val file: File,
    val durationMs: Long,
)

internal class SpeechRecorder(context: Context) {
    private val appContext = context.applicationContext
    private val cacheDirectory = File(appContext.cacheDir, "speech_input")
    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtElapsedMs: Long = 0L

    init {
        cleanupStaleRecordings()
    }

    fun isRecording(): Boolean = synchronized(lock) { recorder != null }

    fun start(): RecordedSpeechSession {
        synchronized(lock) {
            if (recorder != null) {
                throw SpeechTranscriptionException(
                    SpeechTranscriptionErrorCode.BUSY,
                    "正在录音，请先结束当前录音",
                )
            }
            cleanupStaleRecordings()
            val file = File(cacheDirectory, "recording-${UUID.randomUUID()}.m4a")
            val created = createMediaRecorder()
            try {
                created.setAudioSource(MediaRecorder.AudioSource.MIC)
                created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                created.setAudioChannels(1)
                created.setAudioSamplingRate(16_000)
                created.setAudioEncodingBitRate(64_000)
                created.setMaxDuration((SpeechTranscriptionProtocol.MAX_RECORDING_DURATION_MS + 5_000L).toInt())
                created.setMaxFileSize(SpeechTranscriptionProtocol.MAX_AUDIO_BYTES)
                created.setOutputFile(file.absolutePath)
                created.prepare()
                created.start()
            } catch (error: Throwable) {
                runCatching { created.reset() }
                runCatching { created.release() }
                file.delete()
                throw SpeechTranscriptionException(
                    SpeechTranscriptionErrorCode.RECORDING_FAILED,
                    "无法开始录音，请检查麦克风是否被其他应用占用",
                    error,
                )
            }
            recorder = created
            outputFile = file
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            return RecordedSpeechSession(
                startedAtElapsedMs = startedAtElapsedMs,
                maxDurationMs = SpeechTranscriptionProtocol.MAX_RECORDING_DURATION_MS,
            )
        }
    }

    fun stop(): RecordedSpeech {
        val active: MediaRecorder
        val file: File
        val duration: Long
        synchronized(lock) {
            active = recorder ?: throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "当前没有正在进行的录音",
            )
            file = outputFile ?: throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "录音文件尚未准备好",
            )
            duration = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
            recorder = null
            outputFile = null
            startedAtElapsedMs = 0L
        }
        try {
            active.stop()
        } catch (error: Throwable) {
            file.delete()
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "录音时间过短或录音失败，请重试",
                error,
            )
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        if (duration < 1_000L) {
            file.delete()
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.RECORDING_FAILED,
                "录音时间太短，请至少录制 1 秒",
            )
        }
        if (duration > SpeechTranscriptionProtocol.MAX_RECORDING_DURATION_MS + 5_000L) {
            file.delete()
            throw SpeechTranscriptionException(
                SpeechTranscriptionErrorCode.DURATION_EXCEEDED,
                "单次录音不能超过 60 秒",
            )
        }
        return RecordedSpeech(file = file, durationMs = duration)
    }

    fun cancel() {
        val active: MediaRecorder?
        val file: File?
        synchronized(lock) {
            active = recorder
            file = outputFile
            recorder = null
            outputFile = null
            startedAtElapsedMs = 0L
        }
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        file?.delete()
    }

    fun readDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun cleanupStaleRecordings() {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
            return
        }
        cacheDirectory.listFiles()?.forEach { stale ->
            if (stale.isFile && stale.name.startsWith("recording-") && stale.name.endsWith(".m4a")) {
                stale.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            MediaRecorder()
        }
}

internal data class RecordedSpeechSession(
    val startedAtElapsedMs: Long,
    val maxDurationMs: Long,
)
