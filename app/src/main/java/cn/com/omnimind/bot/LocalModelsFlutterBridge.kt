package cn.com.omnimind.bot

import cn.com.omnimind.baselib.llm.LocalInferenceAdapter
import cn.com.omnimind.baselib.llm.LocalInferenceEngine
import cn.com.omnimind.baselib.llm.LocalInferenceMode
import cn.com.omnimind.baselib.llm.LocalModelCatalog
import cn.com.omnimind.baselib.llm.LocalModelDownloadTask
import cn.com.omnimind.baselib.llm.LocalModelDownloader
import cn.com.omnimind.baselib.llm.LocalModelManager
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Bridges the Flutter Offline AI settings page to the real local-model runtime. */
object LocalModelsFlutterBridge {
    private const val CHANNEL = "omnibot/local_models"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val jobsLock = Any()
    private val httpClient = OkHttpClient.Builder().build()

    fun attach(engine: FlutterEngine) {
        MethodChannel(engine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "catalog" -> result.success(LocalModelCatalog.entries.map { entryToMap(it) })
                "installed" -> result.success(LocalModelManager.listLocalModels().map { modelToMap(it) })
                "downloads" -> result.success(LocalModelManager.getDownloadTasks().map { taskToMap(it) })
                "storage" -> result.success(LocalModelManager.getStorageStats())
                "selected" -> result.success(LocalModelManager.getSelectedModelId())
                "mode" -> result.success(LocalInferenceMode.get().name.lowercase())
                "setMode" -> {
                    val mode = call.argument<String>("mode")?.lowercase()
                    val parsed = when (mode) {
                        "online" -> LocalInferenceMode.ONLINE
                        "offline" -> LocalInferenceMode.OFFLINE
                        "automatic" -> LocalInferenceMode.AUTOMATIC
                        else -> null
                    }
                    if (parsed == null) {
                        result.error("INVALID_MODE", "Unsupported inference mode.", null)
                    } else {
                        LocalInferenceMode.set(parsed)
                        result.success(null)
                    }
                }
                "download" -> {
                    val id = call.argument<String>("modelId")
                    val entry = id?.let(LocalModelCatalog::find)
                    if (entry == null) {
                        result.error("UNKNOWN_MODEL", "Unknown offline model.", null)
                    } else {
                        startDownload(entry.id)
                        result.success(null)
                    }
                }
                "pause" -> {
                    val id = call.argument<String>("modelId")
                    if (id.isNullOrBlank()) result.error("INVALID_MODEL", "Model ID is required.", null)
                    else {
                        cancelJob(id)
                        updateTaskState(id, "paused", null)
                        result.success(null)
                    }
                }
                "cancel" -> {
                    val id = call.argument<String>("modelId")
                    if (id.isNullOrBlank()) result.error("INVALID_MODEL", "Model ID is required.", null)
                    else {
                        cancelJob(id)
                        LocalModelManager.getModelPath(id).let { path ->
                            path.resolveSibling(path.name + ".part").delete()
                            path.delete()
                        }
                        LocalModelManager.removeDownloadTask(id)
                        result.success(null)
                    }
                }
                "delete" -> {
                    val id = call.argument<String>("modelId")
                    if (id.isNullOrBlank()) result.error("INVALID_MODEL", "Model ID is required.", null)
                    else result.success(LocalModelManager.deleteLocalModel(id))
                }
                "select" -> {
                    val id = call.argument<String>("modelId")
                    if (id.isNullOrBlank() || LocalModelManager.getLocalModel(id) == null) {
                        result.error("MODEL_NOT_INSTALLED", "Offline model is not installed.", null)
                    } else {
                        LocalModelManager.setSelectedModelId(id)
                        result.success(null)
                    }
                }
                "load" -> {
                    val id = call.argument<String>("modelId")
                    scope.launch {
                        try {
                            if (!id.isNullOrBlank()) LocalModelManager.setSelectedModelId(id)
                            val loaded = LocalInferenceAdapter.loadSelectedModel()
                            result.success(loaded)
                        } catch (e: Exception) {
                            result.error("MODEL_LOAD_FAILED", e.message, null)
                        }
                    }
                }
                "unload" -> {
                    scope.launch {
                        LocalInferenceEngine.unloadModel()
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun startDownload(modelId: String) {
        val entry = LocalModelCatalog.find(modelId) ?: return
        synchronized(jobsLock) {
            if (jobs[modelId]?.isActive == true) return
            val destination = LocalModelManager.getModelPath(modelId).absolutePath
            val existing = LocalModelManager.getDownloadTasks().firstOrNull { it.modelId == modelId }
            updateTaskState(modelId, "downloading", null, existing?.retryCount ?: 0)
            jobs[modelId] = scope.launch {
                val downloader = LocalModelDownloader(httpClient) { id, downloaded, total ->
                    val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                    val current = LocalModelManager.getDownloadTasks().firstOrNull { it.modelId == id }
                    LocalModelManager.updateDownloadTask(
                        LocalModelDownloadTask(
                            modelId = id,
                            displayName = entry.name,
                            sourceUrl = entry.downloadUrl,
                            destinationPath = destination,
                            state = "downloading",
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            progressPercent = percent,
                            startedAt = current?.startedAt ?: System.currentTimeMillis(),
                            retryCount = current?.retryCount ?: 0,
                        )
                    )
                }
                val ok = downloader.downloadCatalogModel(entry, destination)
                if (ok) {
                    runCatching { LocalModelManager.registerCatalogModel(entry) }
                        .onSuccess {
                            LocalModelManager.removeDownloadTask(modelId)
                        }
                        .onFailure { error ->
                            updateTaskState(modelId, "error", error.message)
                        }
                } else if (!kotlin.coroutines.coroutineContext[Job]!!.isActive) {
                    updateTaskState(modelId, "paused", null)
                } else {
                    updateTaskState(modelId, "error", "Model download failed.")
                }
                synchronized(jobsLock) { jobs.remove(modelId) }
            }
        }
    }

    private fun cancelJob(modelId: String) {
        synchronized(jobsLock) {
            jobs.remove(modelId)?.cancel()
        }
    }

    private fun updateTaskState(modelId: String, state: String, error: String?, retryCount: Int? = null) {
        val entry = LocalModelCatalog.find(modelId) ?: return
        val destination = LocalModelManager.getModelPath(modelId).absolutePath
        val previous = LocalModelManager.getDownloadTasks().firstOrNull { it.modelId == modelId }
        LocalModelManager.updateDownloadTask(
            LocalModelDownloadTask(
                modelId = modelId,
                displayName = entry.name,
                sourceUrl = entry.downloadUrl,
                destinationPath = destination,
                state = state,
                downloadedBytes = previous?.downloadedBytes ?: 0L,
                totalBytes = previous?.totalBytes ?: entry.sizeBytes,
                progressPercent = previous?.progressPercent ?: 0,
                startedAt = previous?.startedAt ?: System.currentTimeMillis(),
                errorMessage = error,
                retryCount = retryCount ?: previous?.retryCount ?: 0,
            )
        )
    }

    private fun entryToMap(entry: cn.com.omnimind.baselib.llm.LocalModelCatalogEntry): Map<String, Any?> = mapOf(
        "id" to entry.id,
        "name" to entry.name,
        "description" to entry.description,
        "format" to entry.format,
        "quantization" to entry.quantization,
        "sizeBytes" to entry.sizeBytes,
        "contextLength" to entry.contextLength,
        "capabilities" to entry.capabilities,
        "downloadUrl" to entry.downloadUrl,
        "sha256" to entry.sha256,
        "license" to entry.license,
        "sourceUrl" to entry.sourceUrl,
        "minRamBytes" to entry.minRamBytes,
        "recommendedRamBytes" to entry.recommendedRamBytes,
        "parameterCount" to entry.parameterCount,
    )

    private fun modelToMap(model: cn.com.omnimind.baselib.llm.LocalModel): Map<String, Any?> = mapOf(
        "id" to model.id,
        "displayName" to model.displayName,
        "fileSize" to model.fileSize,
        "format" to model.format,
        "quantization" to model.quantization,
        "contextWindow" to model.contextWindow,
        "downloadedAt" to model.downloadedAt,
        "checksumSha256" to model.checksumSha256,
        "isValid" to model.isValid,
    )

    private fun taskToMap(task: LocalModelDownloadTask): Map<String, Any?> = mapOf(
        "modelId" to task.modelId,
        "displayName" to task.displayName,
        "state" to task.state,
        "downloadedBytes" to task.downloadedBytes,
        "totalBytes" to task.totalBytes,
        "progressPercent" to task.progressPercent,
        "startedAt" to task.startedAt,
        "completedAt" to task.completedAt,
        "errorMessage" to task.errorMessage,
        "retryCount" to task.retryCount,
    )
}
