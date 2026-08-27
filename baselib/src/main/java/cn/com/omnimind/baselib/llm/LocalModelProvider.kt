package cn.com.omnimind.baselib.llm

import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Represents a locally downloaded model available for offline inference.
 * Supports GGUF format via llama.cpp and other quantized formats.
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val modelPath: String,  // File path or URI to the model file
    val fileSize: Long,     // Size in bytes
    val format: String,     // "gguf", "safetensors", "pytorch", etc.
    val quantization: String? = null,  // "Q4_K_M", "Q5_K_M", "FP16", etc.
    val contextWindow: Int? = null,    // Maximum context length
    val downloadedAt: Long = System.currentTimeMillis(),
    val checksumSha256: String? = null,  // For integrity verification
    val isValid: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Represents the download state of a local model.
 */
data class LocalModelDownloadTask(
    val modelId: String,
    val displayName: String,
    val sourceUrl: String,
    val destinationPath: String,
    @SerializedName("state")
    val state: String,  // "pending", "downloading", "completed", "failed", "paused"
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

/**
 * Local model provider for on-device inference.
 * Manages GGUF models and other quantized formats via llama.cpp.
 */
object LocalModelProvider {
    const val SOURCE_TYPE = "local"
    const val OFFICIAL_PROFILE_ID = "local-models"
    const val OFFICIAL_PROFILE_NAME = "Local Models"
    
    // Supported model formats and their capabilities
    object SupportedFormats {
        const val GGUF = "gguf"
        const val SAFETENSORS = "safetensors"
        const val PYTORCH = "pytorch"
    }
    
    // Common quantization levels
    object QuantizationLevels {
        const val Q4_K_M = "Q4_K_M"   // 4-bit, recommended for 7B models
        const val Q5_K_M = "Q5_K_M"   // 5-bit, recommended for 13B models
        const val Q6_K = "Q6_K"       // 6-bit
        const val FP16 = "FP16"       // Full precision
    }
    
    /**
     * Creates a local model provider profile that will be used for
     * all local inference requests.
     */
    fun createLocalProfile(): ModelProviderProfile {
        return ModelProviderProfile(
            id = OFFICIAL_PROFILE_ID,
            name = OFFICIAL_PROFILE_NAME,
            baseUrl = "local://inference",  // Special protocol for local inference
            sourceType = SOURCE_TYPE,
            readOnly = true,  // User cannot modify local provider settings
            ready = true,
            statusText = "Local models ready",
            protocolType = "local_inference",
            wireApi = "local_completion"
        )
    }
    
    /**
     * Validates whether a model file is in a supported format.
     */
    fun isValidModelFormat(filePath: String): Boolean {
        val extension = filePath.substringAfterLast(".").lowercase()
        return extension in setOf("gguf", "safetensors", "pt", "pth", "bin")
    }
    
    /**
     * Validates whether a model file exists and is readable.
     */
    fun validateModelFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.exists() && file.isFile && file.canRead()
        } catch (_: Exception) {
            false
        }
    }
    
    /**
     * Determines supported quantization for a given model size.
     */
    fun recommendedQuantization(modelSize: String): String {
        return when (modelSize.lowercase()) {
            "7b", "7b-chat" -> QuantizationLevels.Q4_K_M
            "13b", "13b-chat" -> QuantizationLevels.Q5_K_M
            "70b", "70b-chat" -> QuantizationLevels.Q6_K
            else -> QuantizationLevels.Q5_K_M  // Default
        }
    }
}
