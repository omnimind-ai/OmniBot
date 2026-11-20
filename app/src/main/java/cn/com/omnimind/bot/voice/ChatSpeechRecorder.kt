package cn.com.omnimind.bot.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The input flow stays open at the sample limit until the gesture is committed. */
@SuppressLint("MissingPermission")
internal fun recordChatSpeech(finish: Deferred<Unit>): Flow<ByteArray> = flow {
    var recorder: AudioRecord? = null
    try {
        if (!finish.isCompleted) {
            val channels = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(SPEECH_INPUT_SAMPLE_RATE, channels, encoding)
            if (minBuffer <= 0) throw microphoneFailure()
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SPEECH_INPUT_SAMPLE_RATE,
                channels, encoding, maxOf(minBuffer, 4096),
            )
            recorder = audioRecord
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) throw microphoneFailure()
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw microphoneFailure()
            }
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            val buffer = ByteArray(1280)
            var remaining = SPEECH_INPUT_MAX_BYTES
            while (remaining > 0 && !finish.isCompleted && SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                val count = audioRecord.read(
                    buffer, 0, minOf(remaining, buffer.size), AudioRecord.READ_NON_BLOCKING,
                )
                if (count < 0 || count % 2 != 0) throw microphoneFailure()
                if (count == 0) {
                    delay(20)
                } else {
                    remaining -= count
                    emit(buffer.copyOf(count))
                }
            }
        }
    } catch (_: SecurityException) {
        throw SpeechProviderException("permission", "需要麦克风权限才能录音")
    } catch (_: IllegalStateException) {
        throw microphoneFailure()
    } finally {
        recorder?.release()
    }
    finish.await()
}

private fun microphoneFailure() = SpeechProviderException("microphone", "麦克风不可用，请重试")
