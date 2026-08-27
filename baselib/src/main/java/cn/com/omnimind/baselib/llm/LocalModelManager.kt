package cn.com.omnimind.baselib.llm

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Manages local models lifecycle: discovery, download, storage, validation.
 * Persists local model metadata in MMKV with models stored in app cache.
 */
object LocalModelManager {
    private const val TAG = "LocalModelManager"
    private const val KEY_LOCAL_MODELS = "local_models_v1"
    private const val KEY_DOWNLOAD_TASKS = "local_model_downloads_v1"
    private const val LOCAL_MODELS_DIR = "local_models"
    
    private val gson = Gson()
    private var context: Context? = null
    
    /**
     * Initialize the local model manager with app context.
     * Must be called once during app startup.
     */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        ensureLocalModelsDirectory()
        OmniLog.d(TAG, "LocalModelManager initialized")
    }
    
    /**
     * Get the directory where local models are stored.
     * Uses app cache directory to allow cleanup if needed.
     */
    fun getLocalModelsDirectory(): File {
        val ctx = requireContext()
        val dir = File(ctx.cacheDir, LOCAL_MODELS_DIR)
        dir.mkdirs()
        return dir
    }
    
    /**
     * List all downloaded and valid local models.
     */
    fun listLocalModels(): List<LocalModel> {
        val mmkv = MMKV.defaultMMKV() ?: return emptyList()
        val json = mmkv.decodeString(KEY_LOCAL_MODELS) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<LocalModel>>() {}.type
            val models: List<LocalModel> = gson.fromJson(json, type) ?: emptyList()
            
            // Validate that model files still exist
            models.filter { model ->
                val file = File(model.modelPath)
                if (!file.exists()) {
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
    
    /**
     * Register a newly downloaded model or manually added model.
     */
    fun registerLocalModel(
        modelId: String,
        displayName: String,
        modelPath: String,
        format: String = LocalModelProvider.SupportedFormats.GGUF,
        quantization: String? = null,
        contextWindow: Int? = null
    ): LocalModel {
        val file = File(modelPath)
        
        // Validate file exists
        require(file.exists()) { "Model file does not exist: $modelPath" }
        require(file.isFile) { "Model path is not a file: $modelPath" }
        require(file.canRead()) { "Cannot read model file: $modelPath" }
        
        // Calculate SHA256 for integrity checking
        val checksumSha256 = calculateFileSha256(file)
        
        val model = LocalModel(
            id = modelId.trim(),
            displayName = displayName.trim(),
            modelPath = modelPath,
            fileSize = file.length(),
            format = format,
            quantization = quantization,
            contextWindow = contextWindow,
            downloadedAt = System.currentTimeMillis(),
            checksumSha256 = checksumSha256,
            isValid = true
        )
        
        // Save to persistent storage
        saveLocalModels(listLocalModels() + model)
        
        OmniLog.i(TAG, "Registered local model: $modelId")
        return model
    }
    
    /**
     * Remove a local model and optionally delete its file.
     */
    fun deleteLocalModel(modelId: String, deleteFile: Boolean = true): Boolean {
        val models = listLocalModels().toMutableList()
        val model = models.find { it.id == modelId } ?: return false
        
        if (deleteFile) {
            try {
                val file = File(model.modelPath)
                if (file.exists()) {
                    file.delete()
                    OmniLog.i(TAG, "Deleted model file: ${model.modelPath}")
                }
            } catch (e: Exception) {
                OmniLog.w(TAG, "Failed to delete model file: ${e.message}")
            }
        }
        
        models.removeAll { it.id == modelId }
        saveLocalModels(models)
        
        OmniLog.i(TAG, "Deleted local model: $modelId")
        return true
    }
    
    /**
     * Get download tasks for tracking ongoing downloads.
     */
    fun getDownloadTasks(): List<LocalModelDownloadTask> {
        val mmkv = MMKV.defaultMMKV() ?: return emptyList()
        val json = mmkv.decodeString(KEY_DOWNLOAD_TASKS) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<LocalModelDownloadTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            OmniLog.e(TAG, "Error loading download tasks: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Create or update a download task.
     */
    fun updateDownloadTask(task: LocalModelDownloadTask) {
        val tasks = getDownloadTasks().toMutableList()
        val index = tasks.indexOfFirst { it.modelId == task.modelId }
        
        if (index >= 0) {
            tasks[index] = task
        } else {
            tasks.add(task)
        }
        
        saveDownloadTasks(tasks)
    }
    
    /**
     * Remove a completed download task.
     */
    fun removeDownloadTask(modelId: String) {
        val tasks = getDownloadTasks().toMutableList()
        tasks.removeAll { it.modelId == modelId }
        saveDownloadTasks(tasks)
    }
    
    /**
     * Verify model integrity using SHA256 checksum.
     */
    fun verifyModelIntegrity(modelId: String, expectedChecksum: String? = null): Boolean {
        val model = listLocalModels().find { it.id == modelId } ?: return false
        val file = File(model.modelPath)
        
        if (!file.exists()) return false
        
        val actualChecksum = calculateFileSha256(file)
        val expected = expectedChecksum ?: model.checksumSha256
        
        return if (expected != null) {
            actualChecksum == expected
        } else {
            true  // No checksum to verify
        }
    }
    
    /**
     * Get storage stats for local models.
     */
    fun getStorageStats(): Map<String, Any> {
        val models = listLocalModels()
        val totalSize = models.sumOf { it.fileSize }
        val modelCount = models.size
        val dirSpace = getLocalModelsDirectory().freeSpace
        
        return mapOf(
            "totalModels" to modelCount,
            "totalSize" to totalSize,
            "availableSpace" to dirSpace,
            "models" to models.map { 
                mapOf(
                    "id" to it.id,
                    "name" to it.displayName,
                    "size" to it.fileSize,
                    "format" to it.format
                )
            }
        )
    }
    
    // Private helper methods
    
    private fun ensureLocalModelsDirectory() {
        val dir = getLocalModelsDirectory()
        if (!dir.exists()) {
            dir.mkdirs()
            OmniLog.d(TAG, "Created local models directory: ${dir.absolutePath}")
        }
    }
    
    private fun saveLocalModels(models: List<LocalModel>) {
        val mmkv = MMKV.defaultMMKV() ?: return
        val json = gson.toJson(models)
        mmkv.encode(KEY_LOCAL_MODELS, json)
    }
    
    private fun saveDownloadTasks(tasks: List<LocalModelDownloadTask>) {
        val mmkv = MMKV.defaultMMKV() ?: return
        val json = gson.toJson(tasks)
        mmkv.encode(KEY_DOWNLOAD_TASKS, json)
    }
    
    private fun calculateFileSha256(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } > 0) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            OmniLog.w(TAG, "Failed to calculate SHA256: ${e.message}")
            ""
        }
    }
    
    private fun requireContext(): Context {
        return checkNotNull(context) {
            "LocalModelManager.initialize(context) must be called before use"
        }
    }
}
