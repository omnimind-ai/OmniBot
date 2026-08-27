package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Adapter for local model inference requests.
 * Converts standard chat completion requests to local inference format.
 * Manages routing between local models and remote providers.
 */
object LocalInferenceAdapter {
    private const val TAG = "LocalInferenceAdapter"
    
    /**
     * Check if local models are available for the requested capability.
     */
    fun isLocalInferenceAvailable(capability: String? = null): Boolean {
        val models = LocalModelManager.listLocalModels()
        if (models.isEmpty()) {
            return false
        }
        
        // For now, all local models support text generation
        return when (capability?.trim()?.lowercase()) {
            null, "", "text", "chat" -> true
            else -> false
        }
    }
    
    /**
     * Get available local models for a given capability.
     */
    fun getAvailableLocalModels(capability: String? = null): List<ProviderModelOption> {
        val models = LocalModelManager.listLocalModels()
        
        return models.map { model ->
            ProviderModelOption(
                id = model.id,
                displayName = model.displayName,
                ownedBy = "local",
                contextLimit = model.contextWindow ?: 4096,
                inputModalities = listOf("text"),
                outputModalities = listOf("text"),
                family = model.format,
                group = "local_models"
            )
        }
    }
    
    /**
     * Validate that a requested model is available locally.
     */
    fun validateLocalModel(modelId: String): Boolean {
        val model = LocalModelManager.listLocalModels().find { it.id == modelId }
        if (model == null) {
            OmniLog.w(TAG, "Local model not found: $modelId")
            return false
        }
        
        if (!model.isValid) {
            OmniLog.w(TAG, "Local model marked as invalid: $modelId")
            return false
        }
        
        return true
    }
    
    /**
     * Get the file path for a local model.
     * Returns null if model not found or invalid.
     */
    fun getLocalModelPath(modelId: String): String? {
        val model = LocalModelManager.listLocalModels().find { it.id == modelId }
        return if (model?.isValid == true) model.modelPath else null
    }
    
    /**
     * Convert a standard chat completion request to local inference format.
     * This prepares the request parameters for llama.cpp or similar engine.
     */
    fun adaptChatCompletionRequest(
        modelId: String,
        messages: List<Map<String, String>>,
        parameters: Map<String, Any?>
    ): Map<String, Any?> {
        return mapOf(
            "model" to modelId,
            "messages" to messages,
            "temperature" to (parameters["temperature"] as? Number)?.toDouble() ?: 0.7,
            "top_p" to (parameters["top_p"] as? Number)?.toDouble() ?: 0.9,
            "max_tokens" to (parameters["max_tokens"] as? Number)?.toInt() ?: 2048,
            "stop" to (parameters["stop"] as? List<*>) ?: emptyList<String>(),
            "stream" to (parameters["stream"] as? Boolean) ?: false
        )
    }
    
    /**
     * Check if a model should prefer local inference or remote.
     * Returns "local" if model is available locally, "remote" otherwise.
     */
    fun determineInferenceRoute(modelId: String): String {
        return if (validateLocalModel(modelId)) {
            "local"
        } else {
            "remote"
        }
    }
    
    /**
     * Get inference capabilities for a local model.
     */
    fun getLocalModelCapabilities(modelId: String): Map<String, Boolean> {
        val model = LocalModelManager.listLocalModels().find { it.id == modelId }
        
        return if (model != null && model.isValid) {
            mapOf(
                "text_generation" to true,
                "streaming" to true,
                "vision" to false,
                "function_calling" to false,
                "structured_output" to false
            )
        } else {
            emptyMap()
        }
    }
    
    /**
     * Estimate inference performance for a local model.
     * Returns estimated tokens per second (conservative estimate).
     */
    fun estimateLocalInferenceSpeed(modelId: String): Double {
        val model = LocalModelManager.listLocalModels().find { it.id == modelId } ?: return 0.0
        
        // Conservative estimates based on model size and quantization
        return when {
            model.format != LocalModelProvider.SupportedFormats.GGUF -> 0.0
            model.quantization == LocalModelProvider.QuantizationLevels.Q4_K_M -> 5.0  // tokens/sec
            model.quantization == LocalModelProvider.QuantizationLevels.Q5_K_M -> 3.5
            model.quantization == LocalModelProvider.QuantizationLevels.Q6_K -> 2.5
            model.quantization == LocalModelProvider.QuantizationLevels.FP16 -> 1.0
            else -> 2.0  // Default estimate
        }
    }
    
    /**
     * Prepare system context for local inference.
     * Includes memory constraints and available features.
     */
    fun buildLocalInferenceContext(modelId: String): Map<String, Any?> {
        val model = LocalModelManager.listLocalModels().find { it.id == modelId }
        val stats = LocalModelManager.getStorageStats()
        
        return if (model != null) {
            mapOf(
                "model_id" to modelId,
                "model_name" to model.displayName,
                "model_path" to model.modelPath,
                "format" to model.format,
                "quantization" to model.quantization,
                "context_window" to (model.contextWindow ?: 4096),
                "file_size" to model.fileSize,
                "downloaded_at" to model.downloadedAt,
                "storage_stats" to stats,
                "local_only" to true
            )
        } else {
            emptyMap()
        }
    }
    
    /**
     * Log inference metrics for monitoring.
     */
    fun logInferenceMetrics(
        modelId: String,
        inputTokens: Int,
        outputTokens: Int,
        durationMs: Long
    ) {
        val throughput = if (durationMs > 0) {
            (outputTokens * 1000.0) / durationMs
        } else {
            0.0
        }
        
        OmniLog.d(
            TAG,
            "Local inference: model=$modelId, " +
            "input=$inputTokens, output=$outputTokens, " +
            "duration=${durationMs}ms, throughput=${"%.2f".format(throughput)} tok/s"
        )
    }
}
