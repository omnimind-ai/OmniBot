package cn.com.omnimind.baselib.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level local inference engine backed by llama.cpp.
 * Native inference never runs on the Android main thread.
 */
object LocalInferenceEngine {
    data class Message(val role: String, val content: String)

    sealed interface Event {
        data class Token(val value: String) : Event
        data class Error(val message: String) : Event
        data object Complete : Event
        data object Cancelled : Event
    }

    private val loaded = AtomicBoolean(false)
    private val generating = AtomicBoolean(false)

    suspend fun loadModel(modelPath: String, contextSize: Int = 4096): Boolean =
        withContext(Dispatchers.Default) {
            require(modelPath.isNotBlank()) { "Model path cannot be empty" }
            val success = LocalInferenceEngineNative.nativeLoadModel(modelPath, contextSize)
            loaded.set(success)
            success
        }

    suspend fun unloadModel() = withContext(Dispatchers.Default) {
        cancel()
        LocalInferenceEngineNative.nativeUnloadModel()
        loaded.set(false)
    }

    fun isLoaded(): Boolean = loaded.get() && LocalInferenceEngineNative.nativeIsLoaded()

    fun modelInfo(): String = LocalInferenceEngineNative.nativeModelInfo()

    fun cancel() {
        if (generating.get()) {
            LocalInferenceEngineNative.nativeCancel()
        }
    }

    fun generate(
        messages: List<Message>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
    ): Flow<Event> = channelFlow {
        if (!isLoaded()) {
            trySend(Event.Error("Offline model is not loaded."))
            close()
            return@channelFlow
        }
        if (messages.isEmpty()) {
            trySend(Event.Error("At least one chat message is required."))
            close()
            return@channelFlow
        }
        if (!generating.compareAndSet(false, true)) {
            trySend(Event.Error("Another local inference request is already running."))
            close()
            return@channelFlow
        }

        val callback = object : LocalInferenceEngineNative.Callback {
            override fun onToken(token: String) {
                trySend(Event.Token(token))
            }

            override fun onError(message: String) {
                trySend(Event.Error(message))
            }
        }

        val generationJob = launch(Dispatchers.Default) {
            try {
                when (
                    LocalInferenceEngineNative.nativeGenerate(
                        callback = callback,
                        roles = messages.map { it.role }.toTypedArray(),
                        contents = messages.map { it.content }.toTypedArray(),
                        maxTokens = maxTokens.coerceIn(1, 8192),
                        temperature = temperature.coerceIn(0.05f, 2.0f),
                        topP = topP.coerceIn(0.05f, 1.0f),
                    )
                ) {
                    1 -> trySend(Event.Complete)
                    2 -> trySend(Event.Cancelled)
                    else -> Unit
                }
            } finally {
                generating.set(false)
                close()
            }
        }

        awaitClose {
            if (generating.get()) {
                LocalInferenceEngineNative.nativeCancel()
            }
            generationJob.cancel()
        }
    }

    fun shutdown() {
        cancel()
        LocalInferenceEngineNative.nativeShutdown()
        loaded.set(false)
        generating.set(false)
    }
}
