package cn.com.omnimind.baselib.llm

/** Curated, user-consented catalog of verified GGUF models. */
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
    private const val QWEN_15B_Q4_0_URL =
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_0.gguf?download=true"

    val entries: List<LocalModelCatalogEntry> = listOf(
        LocalModelCatalogEntry(
            id = "qwen2.5-1.5b-instruct-q4_k_m",
            name = "Qwen2.5 1.5B Instruct — Q4_K_M",
            description = "Balanced small instruction model for private on-device chat.",
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
        LocalModelCatalogEntry(
            id = "qwen2.5-1.5b-instruct-q4_0",
            name = "Qwen2.5 1.5B Instruct — Q4_0",
            description = "Smaller Q4_0 variant for devices where storage is more constrained.",
            format = LocalModelProvider.SupportedFormats.GGUF,
            quantization = "Q4_0",
            sizeBytes = 1_066_227_232L,
            contextLength = 4096,
            capabilities = listOf("chat", "streaming", "text"),
            downloadUrl = QWEN_15B_Q4_0_URL,
            sha256 = "dcd819ff094852c38faba6873d8ff0c9d51eadb2844539e52042ae5d647bbfdb",
            license = "Apache-2.0",
            sourceUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            minRamBytes = 3L * 1024 * 1024 * 1024,
            recommendedRamBytes = 4L * 1024 * 1024 * 1024,
            parameterCount = 1_540_000_000L,
        ),
    )

    fun find(id: String): LocalModelCatalogEntry? = entries.firstOrNull { it.id == id }
}
