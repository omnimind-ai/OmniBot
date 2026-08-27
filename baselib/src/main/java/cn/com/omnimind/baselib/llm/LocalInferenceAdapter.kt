package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.flow.Flow

/**
 * Adapter between OmniBot's provider abstraction and the on-device llama.cpp engine.
 */
object LocalInferenceAdapter {
    private const val TAG = "LocalInferenceAdapter"

    fun isLocalInferenceAvailable(capability: String? = null): Boolean {
        val models = LocalModelManager.listLocalModels()
        if (models.isEmpty()) return false
        return when (capability?.trim()?.lowercase()) {
            null, "", "text", "chat", "streaming" -> true
            else -> false
        }
    }

    fun getAvailableLocalModels(capability: String? = null): List<ProviderModelOption> {
        return LocalModelManager.listLocalModels().filter { model ->
            capability == null || modelCapabilities(model.id).contains(capability.lowercase())
        }.map { model ->
            ProviderModelOption(
                id = model.id,
                displayName = model.displayName,
                ownedBy = "local",
                contextLimit = model.contextWindow ?: 4096,
                inputModalities = listOf("text"),
                outputModalities = listOf("text"),
                family = model.format,
                group = "local_models",
            )
        }
    }

    fun validateLocalModel(modelId: String): Boolean {
        val model = LocalModelManager.getLocalModel(modelId)
        if (model == null || !model.isValid) {
            OmniLog.w(TAG, "Local model not found or invalid: $modelId")
            return false
        }
        return LocalModelManager.verifyModelIntegrity(modelId)
    }

    fun getLocalModelPath(modelId: String): String? =
        LocalModelManager.getLocalModel(modelId)?.takeIf { validateLocalModel(modelId) }?.modelPath

    fun selectedModelId(): String? = LocalModelManager.getSelectedModelId()

    fun currentMode(): LocalInferenceMode = LocalInferenceMode.get()

    fun setMode(mode: LocalInferenceMode) = LocalInferenceMode.set(mode)

    /**
     * Returns local/remote routing without ever silently converting explicit Offline mode to remote.
     */
    fun determineInferenceRoute(modelId: String? = null): String {
        val selected = modelId?.takeIf { it.isNotBlank() } ?: selectedModelId()
        return when (LocalInferenceMode.get()) {
            LocalInferenceMode.ONLINE -> "remote"
            LocalInferenceMode.OFFLINE -> if (selected != null && validateLocalModel(selected)) "local" else "offline_unavailable"
            LocalInferenceMode.AUTOMATIC -> if (selected != null && validateLocalModel(selected)) "local" else "remote"
        }
    }

    fun requireOfflineModel(modelId: String? = null): String {
        val selected = modelId?.takeIf { it.isNotBlank() } ?: selectedModelId()
        require(!selected.isNullOrBlank()) {
            "Offline model is not installed or cannot be loaded."
        }
        require(validateLocalModel(selected)) {
            "Offline model is not installed or cannot be loaded."
        }
        return selected
    }

    suspend fun loadSelectedModel(): Boolean {
        val modelId = requireOfflineModel()
        val entry = LocalModelCatalog.find(modelId)
            ?: throw IllegalStateException("Offline model metadata is unavailable.")
        val model = LocalModelManager.getLocalModel(modelId)
            ?: throw IllegalStateException("Offline model is not installed or cannot be loaded.")
        require(LocalModelManager.canLoadModel(modelId)) {
            "This model requires more available memory than the device currently has."
        }
        return LocalInferenceEngine.loadModel(model.modelPath, entry.contextLength)
    }

    fun generate(
        messages: List<LocalInferenceEngine.Message>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
    ): Flow<LocalInferenceEngine.Event> {
        requireOfflineModel()
        return LocalInferenceEngine.generate(messages, maxTokens, temperature, topP)
    }

    fun adaptChatCompletionRequest(
        modelId: String,
        messages: List<Map<String, String>>,
        parameters: Map<String, Any?>,
    ): Map<String, Any?> = mapOf(
        "model" to modelId,
        "messages" to messages,
        "temperature" to (parameters["temperature"] as? Number)?.toDouble() ?: 0.7,
        "top_p" to (parameters["top_p"] as? Number)?.toDouble() ?: 0.9,
        "max_tokens" to (parameters["max_tokens"] as? Number)?.toInt() ?: 2048,
        "stop" to (parameters["stop"] as? List<*>) ?: emptyList<String>(),
        "stream" to true,
    )

    fun getLocalModelCapabilities(modelId: String): Map<String, Boolean> {
        val valid = validateLocalModel(modelId)
        return if (valid) {
            mapOf(
                "text_generation" to true,
                "streaming" to true,
                "vision" to false,
                "function_calling" to false,
                "structured_output" to false,
                "network_inference" to false,
            )
        } else {
            emptyMap()
        }
    }

    fun modelCapabilities(modelId: String): Set<String> =
        LocalModelCatalog.find(modelId)?.capabilities?.map { it.lowercase() }?.toSet()
            ?: emptySet()

    fun buildLocalInferenceContext(modelId: String): Map<String, Any?> {
        val model = LocalModelManager.getLocalModel(modelId) ?: return emptyMap()
        return mapOf(
            "model_id" to modelId,
            "model_name" to model.displayName,
            "model_path" to model.modelPath,
            "format" to model.format,
            "quantization" to model.quantization,
            "context_window" to (model.contextWindow ?: 4096),
            "file_size" to model.fileSize,
            "local_only" to true,
            "network_inference" to false,
            "mode" to LocalInferenceMode.get().name.lowercase(),
        )
    }

    fun logInferenceMetrics(modelId: String, inputTokens: Int, outputTokens: Int, durationMs: Long) {
        val throughput = if (durationMs > 0) outputTokens * 1000.0 / durationMs else 0.0
        OmniLog.d(
            TAG,
            "Local inference: model=$modelId, input=$inputTokens, output=$outputTokens, duration=${durationMs}ms, throughput=${"%.2f".format(throughput)} tok/s",
        )
    }
}
