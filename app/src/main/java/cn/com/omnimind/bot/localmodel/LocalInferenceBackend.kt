package cn.com.omnimind.bot.localmodel

/** Startup-only selection. Never retries an inference request or Agent turn. */
internal object LocalInferenceBackend {
    const val CPU = "llama.cpp/cpu"
    const val HTP = "llama.cpp/htp"

    const val LITERT_CPU = "litert/cpu"
    const val LITERT_GPU = "litert/gpu"

    fun select(
        supportsHtp: Boolean,
        previous: String?,
        load: (String) -> Boolean,
        reset: () -> Unit,
        lastError: () -> String,
        remember: (String) -> Unit,
        liteRt: Boolean = false,
    ): String {
        val candidates = when {
            liteRt && previous == LITERT_CPU -> listOf(LITERT_CPU)
            liteRt -> listOf(LITERT_GPU, LITERT_CPU)
            supportsHtp && previous != CPU -> listOf(HTP, CPU)
            else -> listOf(CPU)
        }
        val errors = mutableListOf<String>()
        for (backend in candidates) {
            if (load(backend)) {
                remember(backend)
                return backend
            }
            errors += "$backend: ${lastError()}"
            reset()
        }
        error("本地模型启动失败：${errors.joinToString("; ")}")
    }
}
