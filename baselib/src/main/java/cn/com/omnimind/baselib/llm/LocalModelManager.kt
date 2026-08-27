package cn.com.omnimind.baselib.llm

import android.app.ActivityManager
import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.io.File
import java.security.MessageDigest

/**
 * Persistent lifecycle manager for downloaded local GGUF models.
 */
object LocalModelManager {
    private const val TAG = "LocalModelManager"
    private const val KEY_LOCAL_MODELS = "local_models_v2"
    private const val KEY_DOWNLOAD_TASKS = "local_model_downloads_v2"
    private const val KEY_SELECTED_MODEL = "local_model_selected_v1"
    private const val LOCAL_MODELS_DIR = "models"

    private val gson = Gson()
    private var context: Context? = null

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        ensureLocalModelsDirectory()
        OmniLog.d(TAG, "LocalModelManager initialized")
    }

    fun getLocalModelsDirectory(): File {
        val ctx = requireContext()
        return File(ctx.filesDir, LOCAL_MODELS_DIR).apply { mkdirs() }
    }

    fun getModelDirectory(modelId: String): File {
        val safeId = sanitizeModelId(modelId)
        return File(getLocalModelsDirectory(), safeId).apply { mkdirs() }
    }

    fun getModelPath(modelId: String): File =
        File(getModelDirectory(modelId), "model.gguf")

    fun listLocalModels(): List<LocalModel> {
        val mmkv = MMKV.defaultMMKV() ?: return emptyList()
        val json = mmkv.decodeString(KEY_LOCAL_MODELS) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LocalModel>>() {}.type
            val models: List<LocalModel> = gson.fromJson(json, type) ?: emptyList()
            models.filter { model ->
                val file = File(model.modelPath)
                if (!file.exists() || !file.isFile || !file.canRead()) {
                    OmniLog.w(TAG, "Local model file missing: ${model.modelPath}")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error loading local models: ${e.message}")
            emptyList()
        }
    }

    fun getLocalModel(modelId: String): LocalModel? =
        listLocalModels().firstOrNull { it.id == modelId }

    fun registerCatalogModel(entry: LocalModelCatalogEntry): LocalModel {
        val file = getModelPath(entry.id)
        require(file.exists() && file.isFile && file.canRead()) {
            "Downloaded model file does not exist: ${file.absolutePath}"
        }
        require(file.length() == entry.sizeBytes) {
            "Downloaded model size does not match catalog metadata"
        }
        val checksum = calculateFileSha256(file)
        require(checksum.equals(entry.sha256, ignoreCase = true)) {
            "Downloaded model checksum does not match catalog metadata"
        }

        val model = LocalModel(
            id = entry.id,
            displayName = entry.name,
            modelPath = file.absolutePath,
            fileSize = file.length(),
            format = entry.format,
            quantization = entry.quantization,
            contextWindow = entry.contextLength,
            downloadedAt = System.currentTimeMillis(),
            checksumSha256 = checksum,
            isValid = true,
        )
        saveLocalModels(listLocalModels() + model)
        return model
    }

    fun registerLocalModel(
        modelId: String,
        displayName: String,
        modelPath: String,
        format: String = LocalModelProvider.SupportedFormats.GGUF,
        quantization: String? = null,
        contextWindow: Int? = null,
    ): LocalModel {
        val file = File(modelPath)
        require(file.exists() && file.isFile && file.canRead()) {
            "Model file is not readable: $modelPath"
        }
        require(format.equals(LocalModelProvider.SupportedFormats.GGUF, ignoreCase = true)) {
            "Only GGUF models are supported by the offline runtime"
        }

        val model = LocalModel(
            id = sanitizeModelId(modelId),
            displayName = displayName.trim(),
            modelPath = file.absolutePath,
            fileSize = file.length(),
            format = LocalModelProvider.SupportedFormats.GGUF,
            quantization = quantization,
            contextWindow = contextWindow,
            downloadedAt = System.currentTimeMillis(),
            checksumSha256 = calculateFileSha256(file),
            isValid = true,
        )
        saveLocalModels(listLocalModels().filterNot { it.id == model.id } + model)
        return model
    }

    fun deleteLocalModel(modelId: String, deleteFile: Boolean = true): Boolean {
        val model = getLocalModel(modelId) ?: return false
        if (deleteFile) {
            val modelDir = getModelDirectory(modelId)
            modelDir.deleteRecursively()
        }
        val remaining = listLocalModels().filterNot { it.id == modelId }
        saveLocalModels(remaining)
        if (getSelectedModelId() == modelId) setSelectedModelId(null)
        return true
    }

    fun getSelectedModelId(): String? =
        MMKV.defaultMMKV()?.decodeString(KEY_SELECTED_MODEL)?.takeIf { it.isNotBlank() }

    fun setSelectedModelId(modelId: String?) {
        val mmkv = MMKV.defaultMMKV() ?: return
        if (modelId == null) mmkv.removeValueForKey(KEY_SELECTED_MODEL)
        else mmkv.encode(KEY_SELECTED_MODEL, modelId)
    }

    fun getDownloadTasks(): List<LocalModelDownloadTask> {
        val json = MMKV.defaultMMKV()?.decodeString(KEY_DOWNLOAD_TASKS) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LocalModelDownloadTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun updateDownloadTask(task: LocalModelDownloadTask) {
        val tasks = getDownloadTasks().toMutableList()
        val index = tasks.indexOfFirst { it.modelId == task.modelId }
        if (index >= 0) tasks[index] = task else tasks.add(task)
        saveDownloadTasks(tasks)
    }

    fun removeDownloadTask(modelId: String) {
        saveDownloadTasks(getDownloadTasks().filterNot { it.modelId == modelId })
    }

    fun verifyModelIntegrity(modelId: String, expectedChecksum: String? = null): Boolean {
        val model = getLocalModel(modelId) ?: return false
        val file = File(model.modelPath)
        if (!file.exists()) return false
        val expected = expectedChecksum ?: model.checksumSha256 ?: return false
        return calculateFileSha256(file).equals(expected, ignoreCase = true)
    }

    fun canLoadModel(modelId: String): Boolean {
        val entry = LocalModelCatalog.find(modelId) ?: return false
        val model = getLocalModel(modelId) ?: return false
        if (!verifyModelIntegrity(modelId, entry.sha256)) return false
        val memoryInfo = ActivityManager.MemoryInfo()
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem >= entry.minRamBytes
    }

    fun getStorageStats(): Map<String, Any> {
        val models = listLocalModels()
        val totalSize = models.sumOf { it.fileSize }
        return mapOf(
            "totalModels" to models.size,
            "totalSize" to totalSize,
            "availableSpace" to getLocalModelsDirectory().usableSpace,
            "models" to models.map {
                mapOf(
                    "id" to it.id,
                    "name" to it.displayName,
                    "size" to it.fileSize,
                    "format" to it.format,
                    "quantization" to it.quantization,
                )
            },
        )
    }

    private fun ensureLocalModelsDirectory() {
        getLocalModelsDirectory().mkdirs()
    }

    private fun saveLocalModels(models: List<LocalModel>) {
        MMKV.defaultMMKV()?.encode(KEY_LOCAL_MODELS, gson.toJson(models))
    }

    private fun saveDownloadTasks(tasks: List<LocalModelDownloadTask>) {
        MMKV.defaultMMKV()?.encode(KEY_DOWNLOAD_TASKS, gson.toJson(tasks))
    }

    private fun calculateFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeModelId(modelId: String): String {
        val value = modelId.trim()
        require(value.isNotEmpty()) { "Model ID cannot be empty" }
        require(value.length <= 96) { "Model ID is too long" }
        require(value.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Model ID contains unsafe filesystem characters"
        }
        return value
    }

    private fun requireContext(): Context = checkNotNull(context) {
        "LocalModelManager.initialize(context) must be called before use"
    }
}
