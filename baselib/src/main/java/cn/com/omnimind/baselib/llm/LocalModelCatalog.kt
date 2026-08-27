package cn.com.omnimind.baselib.llm

/**
 * Curated, user-consented catalog of verified GGUF models.
 * URLs are HTTPS-only and checksums are pinned to the published model files.
 */
data class LocalModelCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val format: String,
    val quantization: String,
    val sizeBytes: Long,
    val contextLength: Int,
    val capabilities: List<String>,
    val downloadUrl: String,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val minRamBytes: Long,
    val recommendedRamBytes: Long,
    val parameterCount: Long,
)

object LocalModelCatalog {
    private const val QWEN_15B_Q4_K_M_URL =
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true"

    /**
     * Qwen's official GGUF release. Apache-2.0 license, SHA-256 verified.
     * The file is approximately 1.12 GB and is suitable for many 4 GB+ Android devices.
     */
    val entries: List<LocalModelCatalogEntry> = listOf(
        LocalModelCatalogEntry(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 1.5B Instruct",
            description = "Small instruction-tuned GGUF model for private on-device chat.",
            format = LocalModelProvider.SupportedFormats.GGUF,
            quantization = LocalModelProvider.QuantizationLevels.Q4_K_M,
            sizeBytes = 1_117_320_736L,
            contextLength = 4096,
            capabilities = listOf("chat", "streaming", "text"),
            downloadUrl = QWEN_15B_Q4_K_M_URL,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            license = "Apache-2.0",
            sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            minRamBytes = 3L * 1024 * 1024 * 1024,
            recommendedRamBytes = 4L * 1024 * 1024 * 1024,
            parameterCount = 1_540_000_000L,
        ),
    )

    fun find(id: String): LocalModelCatalogEntry? = entries.firstOrNull { it.id == id }
}
