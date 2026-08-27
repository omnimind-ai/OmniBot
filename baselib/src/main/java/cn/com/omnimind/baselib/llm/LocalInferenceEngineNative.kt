package cn.com.omnimind.baselib.llm

/**
 * JNI boundary for the pinned llama.cpp runtime.
 * The native engine is CPU-first and keeps model weights out of the JVM heap.
 */
internal object LocalInferenceEngineNative {
    init {
        System.loadLibrary("omnibot_llama_jni")
    }

    interface Callback {
        fun onToken(token: String)
        fun onError(message: String)
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int): Boolean
    external fun nativeUnloadModel()
    external fun nativeIsLoaded(): Boolean
    external fun nativeModelInfo(): String
    external fun nativeCancel()
    /** 0 = error, 1 = complete, 2 = cancelled. */
    external fun nativeGenerate(
        callback: Callback,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
    ): Int
    external fun nativeShutdown()
}
