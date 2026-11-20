package cn.com.omnimind.bot.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Owned by the activity. All admission, finish and cancellation calls run on its main scope. */
internal class SpeechInputController(
    private val scope: CoroutineScope,
    private val resolveProvider: () -> SpeechRecognitionProvider,
    private val capture: (Deferred<Unit>) -> Flow<ByteArray> = ::recordChatSpeech,
) {
    private class Recording(val id: String) {
        val finish = CompletableDeferred<Unit>()
        lateinit var transcript: Deferred<String>
    }

    private var recording: Recording? = null

    fun start(id: String): Deferred<String> {
        if (recording != null) throw SpeechProviderException("busy", "已有录音正在处理")
        val session = Recording(id)
        recording = session
        session.transcript = scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    resolveProvider().transcribe(capture(session.finish))
                }
            } finally {
                recording = null
            }
        }
        return session.transcript
    }

    fun finish(id: String): Boolean = recording?.takeIf { it.id == id }?.finish?.complete(Unit) == true

    fun cancel(id: String? = null) {
        // Stop admission until the cancelled recorder has released the microphone.
        recording?.takeIf { id == null || it.id == id }?.transcript?.cancel()
    }
}
