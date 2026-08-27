package cn.com.omnimind.baselib.llm

import com.google.gson.annotations.SerializedName
import java.io.File

data class LocalModel(
    val id: String,
    val displayName: String,
    val modelPath: String,
    val fileSize: Long,
    val format: String,
    val quantization: String? = null,
    val contextWindow: Int? = null,
    val downloadedAt: Long = System.currentTimeMillis(),
    val checksumSha256: String? = null,
    val isValid: Boolean = true,
    val errorMessage: String? = null,
)

data class LocalModelDownloadTask(
    val modelId: String,
    val displayName: String,
    val sourceUrl: String,
    val destinationPath: String,
    @SerializedName("state")
    val state: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
)

/** Provider metadata for the built-in on-device GGUF runtime. */
object LocalModelProvider {
    const val SOURCE_TYPE = "local"
    const val OFFICIAL_PROFILE_ID = "local-models"
    const val OFFICIAL_PROFILE_NAME = "Local Models"

    object SupportedFormats {
        const val GGUF = "gguf"
    }

    object QuantizationLevels {
        const val Q4_K_M = "Q4_K_M"
        const val Q5_K_M = "Q5_K_M"
        const val Q6_K = "Q6_K"
        const val Q8_0 = "Q8_0"
        const val FP16 = "FP16"
    }

    fun createLocalProfile(): ModelProviderProfile {
        return ModelProviderProfile(
            id = OFFICIAL_PROFILE_ID,
            name = OFFICIAL_PROFILE_NAME,
            baseUrl = "local://inference",
            sourceType = SOURCE_TYPE,
            readOnly = true,
            ready = true,
            statusText = "On-device GGUF inference",
            protocolType = "local_inference",
            wireApi = "local_completion",
        )
    }

    fun isValidModelFormat(filePath: String): Boolean =
        filePath.substringAfterLast('.', "").equals(GGUF_EXTENSION, ignoreCase = true)

    fun validateModelFile(filePath: String): Boolean = try {
        val file = File(filePath)
        file.exists() && file.isFile && file.canRead() && isValidModelFormat(filePath)
    } catch (_: Exception) {
        false
    }

    fun recommendedQuantization(modelSize: String): String = when (modelSize.lowercase()) {
        "7b", "7b-chat" -> QuantizationLevels.Q4_K_M
        "13b", "13b-chat" -> QuantizationLevels.Q5_K_M
        "70b", "70b-chat" -> QuantizationLevels.Q6_K
        else -> QuantizationLevels.Q4_K_M
    }

    private const val GGUF_EXTENSION = "gguf"
}
