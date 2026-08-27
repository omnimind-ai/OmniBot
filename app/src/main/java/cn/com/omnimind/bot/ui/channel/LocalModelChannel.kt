package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.llm.LocalInferenceEngine
import cn.com.omnimind.baselib.llm.LocalInferenceMode
import cn.com.omnimind.baselib.llm.LocalModelCatalog
import cn.com.omnimind.baselib.llm.LocalModelDownloader
import cn.com.omnimind.baselib.llm.LocalModelManager
import cn.com.omnimind.baselib.llm.LocalModelDownloadTask
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Flutter bridge for the curated offline GGUF model system. */
class LocalModelChannel {
    companion object { private const val CHANNEL = "omnibot/local_models" }

    private var context: Context? = null
    private var methodChannel: MethodChannel? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val downloader by lazy {
        LocalModelDownloader(OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()) { modelId, downloaded, total ->
            LocalModelManager.updateDownloadTask(
                LocalModelDownloadTask(
                    modelId = modelId,
                    displayName = LocalModelCatalog.find(modelId)?.name ?: modelId,
                    sourceUrl = LocalModelCatalog.find(modelId)?.downloadUrl ?: "",
                    destinationPath = LocalModelManager.getModelPath(modelId).absolutePath,
                    state = if (total > 0 && downloaded >= total) "verifying" else "downloading",
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    progressPercent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0,
                )
            )
        }
    }

    fun onCreate(context: Context) {
        this.context = context.applicationContext
        LocalModelManager.initialize(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "catalog" -> result.success(LocalModelCatalog.entries.map { it.toMap() })
            "installed" -> result.success(LocalModelManager.listLocalModels().map { it.toMap() })
            "downloads" -> result.success(LocalModelManager.getDownloadTasks().map { it.toMap() })
            "storage" -> result.success(LocalModelManager.getStorageStats())
            "selected" -> result.success(LocalModelManager.getSelectedModelId())
            "mode" -> result.success(LocalInferenceMode.get().name.lowercase())
            "setMode" -> setMode(call, result)
            "download" -> startDownload(call, result)
            "pause" -> pauseDownload(call, result)
            "cancel" -> cancelDownload(call, result)
            "delete" -> deleteModel(call, result)
            "select" -> selectModel(call, result)
            "load" -> loadModel(call, result)
            "unload" -> unloadModel(result)
            "generate" -> generate(call, result)
            "cancelGeneration" -> { LocalInferenceEngine.cancel(); result.success(true) }
            "isLoaded" -> result.success(LocalInferenceEngine.isLoaded())
            "modelInfo" -> result.success(LocalInferenceEngine.modelInfo())
            else -> result.notImplemented()
        }
    }

    private fun setMode(call: MethodCall, result: MethodChannel.Result) {
        when (call.argument<String>("mode")?.lowercase()) {
            "automatic" -> LocalInferenceMode.set(LocalInferenceMode.AUTOMATIC)
            "online" -> LocalInferenceMode.set(LocalInferenceMode.ONLINE)
            "offline" -> LocalInferenceMode.set(LocalInferenceMode.OFFLINE)
            else -> {
                result.error("INVALID_MODE", "Mode must be automatic, online, or offline", null)
                return
            }
        }
        result.success(LocalInferenceMode.get().name.lowercase())
    }

    private fun startDownload(call: MethodCall, result: MethodChannel.Result) {
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
        val entry = LocalModelCatalog.find(modelId)
        if (entry == null) { result.error("UNKNOWN_MODEL", "Model is not in the approved catalog", null); return }
        if (jobs[modelId]?.isActive == true) { result.success(true); return }

        val destination = LocalModelManager.getModelPath(modelId)
        if ((destination.parentFile?.usableSpace ?: 0L) < entry.sizeBytes) {
            result.error("INSUFFICIENT_STORAGE", "Not enough storage for this model", null); return
        }
        LocalModelManager.updateDownloadTask(LocalModelDownloadTask(
            modelId = entry.id, displayName = entry.name, sourceUrl = entry.downloadUrl,
            destinationPath = destination.absolutePath, state = "pending", totalBytes = entry.sizeBytes,
        ))

        jobs[modelId] = scope.launch {
            val success = downloader.downloadCatalogModel(entry, destination.absolutePath)
            if (success) {
                runCatching { LocalModelManager.registerCatalogModel(entry) }
                    .onSuccess {
                        LocalModelManager.updateDownloadTask(LocalModelDownloadTask(
                            modelId = entry.id, displayName = entry.name, sourceUrl = entry.downloadUrl,
                            destinationPath = destination.absolutePath, state = "completed",
                            downloadedBytes = entry.sizeBytes, totalBytes = entry.sizeBytes,
                            progressPercent = 100, completedAt = System.currentTimeMillis(),
                        ))
                    }
                    .onFailure {
                        LocalModelManager.updateDownloadTask(LocalModelDownloadTask(
                            modelId = entry.id, displayName = entry.name, sourceUrl = entry.downloadUrl,
                            destinationPath = destination.absolutePath, state = "failed", errorMessage = it.message,
                        ))
                    }
            } else if (kotlin.coroutines.coroutineContext[Job]?.isCancelled == true) {
                LocalModelManager.updateDownloadTask(LocalModelDownloadTask(
                    modelId = entry.id, displayName = entry.name, sourceUrl = entry.downloadUrl,
                    destinationPath = destination.absolutePath, state = "paused",
                    downloadedBytes = destination.resolveSibling(destination.name + ".part").length(),
                    totalBytes = entry.sizeBytes,
                ))
            } else {
                LocalModelManager.updateDownloadTask(LocalModelDownloadTask(
                    modelId = entry.id, displayName = entry.name, sourceUrl = entry.downloadUrl,
                    destinationPath = destination.absolutePath, state = "failed",
                    errorMessage = "Download failed or checksum verification failed",
                ))
            }
        }
        result.success(true)
    }

    private fun pauseDownload(call: MethodCall, result: MethodChannel.Result) {
        jobs.remove(call.argument<String>("modelId")?.trim().orEmpty())?.cancel()
        result.success(true)
    }

    private fun cancelDownload(call: MethodCall, result: MethodChannel.Result) {
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
        jobs.remove(modelId)?.cancel()
        if (LocalModelCatalog.find(modelId) != null) downloader.cancelDownload(LocalModelManager.getModelPath(modelId).absolutePath)
        LocalModelManager.removeDownloadTask(modelId)
        result.success(true)
    }

    private fun deleteModel(call: MethodCall, result: MethodChannel.Result) {
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
        scope.launch { runCatching { LocalInferenceEngine.unloadModel() } }
        result.success(LocalModelManager.deleteLocalModel(modelId))
    }

    private fun selectModel(call: MethodCall, result: MethodChannel.Result) {
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
        if (LocalModelManager.getLocalModel(modelId) == null) {
            result.error("MODEL_NOT_INSTALLED", "Model is not installed", null); return
        }
        LocalModelManager.setSelectedModelId(modelId)
        result.success(modelId)
    }

    private fun loadModel(call: MethodCall, result: MethodChannel.Result) {
        val modelId = call.argument<String>("modelId")?.trim().orEmpty()
        val entry = LocalModelCatalog.find(modelId)
        val model = LocalModelManager.getLocalModel(modelId)
        if (entry == null || model == null || !LocalModelManager.canLoadModel(modelId)) {
            result.error("MODEL_NOT_COMPATIBLE", "The model is missing, invalid, or this device has insufficient available memory.", null); return
        }
        scope.launch { result.success(LocalInferenceEngine.loadModel(model.modelPath, entry.contextLength)) }
    }

    private fun unloadModel(result: MethodChannel.Result) {
        scope.launch { LocalInferenceEngine.unloadModel(); result.success(true) }
    }

    private fun generate(call: MethodCall, result: MethodChannel.Result) {
        val rawMessages = call.argument<List<Map<String, Any?>>>("messages").orEmpty()
        val messages = rawMessages.map { LocalInferenceEngine.Message(it["role"]?.toString() ?: "user", it["content"]?.toString() ?: "") }
        scope.launch {
            val output = StringBuilder(); var error: String? = null
            LocalInferenceEngine.generate(
                messages = messages,
                maxTokens = call.argument<Int>("maxTokens") ?: 512,
                temperature = (call.argument<Number>("temperature") ?: 0.7).toFloat(),
                topP = (call.argument<Number>("topP") ?: 0.9).toFloat(),
            ).collect { event ->
                when (event) {
                    is LocalInferenceEngine.Event.Token -> output.append(event.value)
                    is LocalInferenceEngine.Event.Error -> error = event.message
                    else -> Unit
                }
            }
            if (error != null) result.error("INFERENCE_ERROR", error, null) else result.success(output.toString())
        }
    }

    private fun LocalModelCatalogEntry.toMap(): Map<String, Any> = mapOf(
        "id" to id, "name" to name, "description" to description, "format" to format,
        "quantization" to quantization, "sizeBytes" to sizeBytes, "contextLength" to contextLength,
        "capabilities" to capabilities, "downloadUrl" to downloadUrl, "sha256" to sha256,
        "license" to license, "sourceUrl" to sourceUrl, "minRamBytes" to minRamBytes,
        "recommendedRamBytes" to recommendedRamBytes, "parameterCount" to parameterCount,
    )

    private fun cn.com.omnimind.baselib.llm.LocalModel.toMap(): Map<String, Any?> = mapOf(
        "id" to id, "displayName" to displayName, "modelPath" to modelPath, "fileSize" to fileSize,
        "format" to format, "quantization" to quantization, "contextWindow" to contextWindow,
        "downloadedAt" to downloadedAt, "checksumSha256" to checksumSha256, "isValid" to isValid,
    )

    private fun LocalModelDownloadTask.toMap(): Map<String, Any?> = mapOf(
        "modelId" to modelId, "displayName" to displayName, "state" to state,
        "downloadedBytes" to downloadedBytes, "totalBytes" to totalBytes,
        "progressPercent" to progressPercent, "startedAt" to startedAt,
        "completedAt" to completedAt, "errorMessage" to errorMessage,
    )

    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}
