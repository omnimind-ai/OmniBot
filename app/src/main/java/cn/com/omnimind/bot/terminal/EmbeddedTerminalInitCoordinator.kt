package cn.com.omnimind.bot.terminal

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.termux.TermuxLiveEnvironmentResult
import com.ai.assistance.operit.terminal.TerminalManager
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object EmbeddedTerminalInitCoordinator {
    private const val TAG = "EmbeddedTerminalInit"
    private const val MAX_INIT_LOG_LINES = 160

    private val BASE_PACKAGE_NAMES = listOf(
        "bash",
        "ca-certificates",
        "curl",
        "git",
        "gcompat",
        "glib",
        "nodejs",
        "npm",
        "python3",
        "py3-pip",
        "py3-virtualenv",
        "ripgrep",
        "tmux",
        "xz"
    )

    private data class EmbeddedTerminalInitState(
        val running: Boolean = false,
        val completed: Boolean = false,
        val success: Boolean? = null,
        val progress: Double = 0.0,
        val stage: String = "",
        val logLines: List<String> = emptyList(),
        val startedAt: Long = 0L,
        val updatedAt: Long = 0L,
        val completedAt: Long? = null,
        val seenBasePackages: Set<String> = emptySet(),
        val phase: String? = null,
        val distribution: String? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null
    )

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val listenerLock = Any()
    private val listeners = linkedSetOf<(Map<String, Any?>) -> Unit>()

    private var embeddedTerminalInitState = EmbeddedTerminalInitState()
    private var activeRun: CompletableDeferred<TermuxLiveEnvironmentResult>? = null
    private var activeJob: Job? = null

    fun addListener(listener: (Map<String, Any?>) -> Unit) {
        synchronized(listenerLock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Map<String, Any?>) -> Unit) {
        synchronized(listenerLock) {
            listeners.remove(listener)
        }
    }

    fun buildSnapshot(): Map<String, Any?> {
        val snapshot = synchronized(stateLock) {
            embeddedTerminalInitState
        }
        return mapOf(
            "running" to snapshot.running,
            "completed" to snapshot.completed,
            "success" to snapshot.success,
            "progress" to snapshot.progress,
            "stage" to snapshot.stage,
            "logLines" to snapshot.logLines,
            "startedAt" to snapshot.startedAt.takeIf { it > 0L },
            "updatedAt" to snapshot.updatedAt.takeIf { it > 0L },
            "completedAt" to snapshot.completedAt,
            "phase" to snapshot.phase,
            "distribution" to snapshot.distribution,
            "downloadedBytes" to snapshot.downloadedBytes,
            "totalBytes" to snapshot.totalBytes,
            "error" to snapshot.error
        )
    }

    fun startInBackground(context: Context): Boolean {
        val deferred = synchronized(stateLock) {
            // Activity recreation, cached engines and repeated display callbacks
            // must not restart setup or erase a failure before it can be shown.
            // Explicit retries continue to use prepare(), the same run owner.
            if (embeddedTerminalInitState.startedAt != 0L || activeRun?.isCompleted == false) {
                return false
            }
            CompletableDeferred<TermuxLiveEnvironmentResult>().also {
                activeRun = it
                resetEmbeddedTerminalInitStateLocked()
            }
        }
        val appContext = context.applicationContext
        val job = workerScope.launch {
            runPreparation(appContext, deferred)
        }
        synchronized(stateLock) { activeJob = job }
        return true
    }

    suspend fun prepare(
        context: Context,
        selectedPackageIds: List<String>? = null
    ): TermuxLiveEnvironmentResult {
        val appContext = context.applicationContext
        val deferred: CompletableDeferred<TermuxLiveEnvironmentResult>
        val shouldStartNow: Boolean
        synchronized(stateLock) {
            val current = activeRun?.takeIf { !it.isCompleted }
            if (current != null) {
                deferred = current
                shouldStartNow = false
            } else {
                deferred = CompletableDeferred()
                activeRun = deferred
                resetEmbeddedTerminalInitStateLocked()
                shouldStartNow = true
            }
        }
        if (shouldStartNow) {
            val job = workerScope.launch {
                runPreparation(appContext, deferred, selectedPackageIds)
            }
            synchronized(stateLock) { activeJob = job }
        }
        val status = deferred.await()
        return if (!shouldStartNow && selectedPackageIds != null && status.success) {
            prepare(appContext, selectedPackageIds)
        } else {
            status
        }
    }

    suspend fun prepareDistribution(
        context: Context,
        distribution: TerminalDistribution.Spec
    ): TermuxLiveEnvironmentResult {
        val appContext = context.applicationContext
        val deferred = synchronized(stateLock) {
            if (activeRun?.isCompleted == false) {
                return TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = "另一个终端环境准备任务正在运行，请稍后再试。"
                )
            }
            CompletableDeferred<TermuxLiveEnvironmentResult>().also {
                activeRun = it
                resetEmbeddedTerminalInitStateLocked()
            }
        }
        val job = workerScope.launch {
            runDistributionPreparation(appContext, distribution, deferred)
        }
        synchronized(stateLock) { activeJob = job }
        return deferred.await()
    }

    fun cancelCurrent(): Boolean {
        val job = synchronized(stateLock) { activeJob?.takeIf { it.isActive } }
        job?.cancel(CancellationException("用户取消终端环境准备"))
        return job != null
    }

    private suspend fun runPreparation(
        context: Context,
        deferred: CompletableDeferred<TermuxLiveEnvironmentResult>,
        selectedPackageIds: List<String>? = null
    ) {
        try {
            emitEmbeddedTerminalInitProgress(
                kind = "status",
                message = "开始准备内嵌终端环境"
            )
            val runtimeStatus = TermuxCommandRunner.prepareLiveEnvironment(
                context = context,
                installBasePackages = selectedPackageIds == null
            ) { progress ->
                emitEmbeddedTerminalInitProgress(
                    kind = progress.kind.name.lowercase(),
                    message = progress.message,
                    phase = progress.phase,
                    distribution = progress.distribution,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    explicitProgress = progress.progress,
                    error = progress.error
                )
            }
            val status =
                if (runtimeStatus.success && selectedPackageIds != null) {
                    val setupResult = EmbeddedTerminalSetupManager(context).installPackages(
                        selectedPackageIds = selectedPackageIds
                    ) { kind, message ->
                        emitEmbeddedTerminalInitProgress(kind = kind, message = message)
                    }
                    TermuxLiveEnvironmentResult(
                        success = setupResult.success,
                        wrapperReady = runtimeStatus.wrapperReady,
                        sharedStorageReady = runtimeStatus.sharedStorageReady,
                        message = setupResult.message
                    )
                } else {
                    runtimeStatus
                }
            emitEmbeddedTerminalInitProgress(
                kind = if (status.success) "status" else "error",
                message = status.message
            )
            markEmbeddedTerminalInitCompleted(
                success = status.success,
                finalMessage = status.message
            )
            deferred.complete(status)
        } catch (error: CancellationException) {
            val message = "终端环境准备已取消，已保留下载进度。"
            emitEmbeddedTerminalInitProgress(kind = "error", message = message, error = message)
            markEmbeddedTerminalInitCompleted(success = false, finalMessage = message)
            deferred.complete(
                TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = message
                )
            )
        } catch (error: Exception) {
            OmniLog.e(TAG, "Failed to prepare embedded terminal runtime", error)
            val failureMessage = error.message ?: "检查内嵌终端环境失败"
            emitEmbeddedTerminalInitProgress(
                kind = "error",
                message = failureMessage
            )
            markEmbeddedTerminalInitCompleted(
                success = false,
                finalMessage = failureMessage
            )
            deferred.complete(
                TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = failureMessage
                )
            )
        } finally {
            synchronized(stateLock) {
                if (activeRun === deferred) {
                    activeRun = null
                    activeJob = null
                }
            }
        }
    }

    private suspend fun runDistributionPreparation(
        context: Context,
        distribution: TerminalDistribution.Spec,
        deferred: CompletableDeferred<TermuxLiveEnvironmentResult>
    ) {
        try {
            emitEmbeddedTerminalInitProgress(
                kind = "status",
                message = "正在准备 ${distribution.displayName} 终端环境",
                phase = "checking",
                distribution = distribution.id
            )
            var runtimeFailureMessage: String? = null
            val manager = TerminalManager.getInstance(context)
            val initialized = manager.initializeEnvironment(distribution = distribution) { progress ->
                progress.error?.takeIf { it.isNotBlank() }?.let { runtimeFailureMessage = it }
                emitEmbeddedTerminalInitProgress(
                    kind = if (progress.error == null) "status" else "error",
                    message = progress.message,
                    phase = progress.phase,
                    distribution = progress.distribution,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                    explicitProgress = progress.progress,
                    error = progress.error
                )
            }
            val status = if (!initialized) {
                TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = runtimeFailureMessage ?: "${distribution.displayName} 终端环境准备失败。"
                )
            } else {
                emitEmbeddedTerminalInitProgress(
                    kind = "status",
                    message = "正在验证 ${distribution.displayName} 终端环境",
                    phase = "verifying",
                    distribution = distribution.id,
                    explicitProgress = 0.98
                )
                val probe = manager.executeHiddenCommand(
                    command = "true",
                    executorKey = "distribution-switch-${distribution.id}",
                    timeoutMs = 60_000L,
                    distribution = distribution
                )
                if (probe.isOk && probe.exitCode == 0) {
                    TermuxLiveEnvironmentResult(
                        success = true,
                        wrapperReady = true,
                        sharedStorageReady = true,
                        message = "${distribution.displayName} 终端环境已就绪。"
                    )
                } else {
                    val details = probe.error.ifBlank {
                        probe.output.trim().takeLast(800).ifBlank { "运行时验证未通过。" }
                    }
                    TermuxLiveEnvironmentResult(
                        success = false,
                        wrapperReady = false,
                        sharedStorageReady = false,
                        message = "${distribution.displayName} 终端环境验证失败：$details"
                    )
                }
            }
            emitEmbeddedTerminalInitProgress(
                kind = if (status.success) "status" else "error",
                message = status.message,
                phase = if (status.success) "ready" else "error",
                distribution = distribution.id,
                explicitProgress = if (status.success) 1.0 else null,
                error = status.message.takeUnless { status.success }
            )
            markEmbeddedTerminalInitCompleted(
                success = status.success,
                finalMessage = status.message
            )
            deferred.complete(status)
        } catch (error: CancellationException) {
            val message = "终端环境准备已取消，已保留下载进度。"
            emitEmbeddedTerminalInitProgress(
                kind = "error",
                message = message,
                phase = "cancelled",
                distribution = distribution.id,
                error = message
            )
            markEmbeddedTerminalInitCompleted(success = false, finalMessage = message)
            deferred.complete(
                TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = message
                )
            )
        } catch (error: Exception) {
            OmniLog.e(TAG, "Failed to prepare ${distribution.id} terminal runtime", error)
            val message = error.message ?: "${distribution.displayName} 终端环境准备失败。"
            emitEmbeddedTerminalInitProgress(
                kind = "error",
                message = message,
                phase = "error",
                distribution = distribution.id,
                error = message
            )
            markEmbeddedTerminalInitCompleted(success = false, finalMessage = message)
            deferred.complete(
                TermuxLiveEnvironmentResult(
                    success = false,
                    wrapperReady = false,
                    sharedStorageReady = false,
                    message = message
                )
            )
        } finally {
            synchronized(stateLock) {
                if (activeRun === deferred) {
                    activeRun = null
                    activeJob = null
                }
            }
        }
    }

    private fun emitEmbeddedTerminalInitProgress(
        kind: String,
        message: String,
        phase: String? = null,
        distribution: String? = null,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        explicitProgress: Double? = null,
        error: String? = null
    ) {
        if (message.isBlank()) {
            return
        }
        updateEmbeddedTerminalInitState(
            kind,
            message,
            phase,
            distribution,
            downloadedBytes,
            totalBytes,
            explicitProgress,
            error
        )
        val payload = mapOf(
            "kind" to kind,
            "message" to message,
            "timestamp" to System.currentTimeMillis(),
            "phase" to phase,
            "distribution" to distribution,
            "downloadedBytes" to downloadedBytes,
            "totalBytes" to totalBytes,
            "progress" to explicitProgress,
            "error" to error
        )
        dispatchProgress(payload)
    }

    private fun dispatchProgress(payload: Map<String, Any?>) {
        val currentListeners = synchronized(listenerLock) {
            listeners.toList()
        }
        if (currentListeners.isEmpty()) {
            return
        }
        mainScope.launch {
            currentListeners.forEach { listener ->
                runCatching {
                    listener(payload)
                }
            }
        }
    }

    private fun resetEmbeddedTerminalInitStateLocked() {
        val now = System.currentTimeMillis()
        embeddedTerminalInitState = EmbeddedTerminalInitState(
            running = true,
            completed = false,
            success = null,
            progress = 0.02,
            stage = "准备开始",
            logLines = listOf("[系统] 正在启动内嵌终端环境初始化..."),
            startedAt = now,
            updatedAt = now
        )
    }

    private fun updateEmbeddedTerminalInitState(
        kind: String,
        message: String,
        phase: String?,
        distribution: String?,
        downloadedBytes: Long,
        totalBytes: Long,
        explicitProgress: Double?,
        error: String?
    ) {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) {
            return
        }

        val normalizedLines = normalizeEmbeddedTerminalInitLines(normalizedMessage)
        if (normalizedLines.isEmpty()) {
            return
        }

        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val current =
                if (embeddedTerminalInitState.startedAt == 0L) {
                    EmbeddedTerminalInitState(
                        running = true,
                        startedAt = now,
                        updatedAt = now
                    )
                } else {
                    embeddedTerminalInitState
                }

            val nextSeenBasePackages =
                if (kind == "output") {
                    current.seenBasePackages + extractSeenBasePackages(normalizedLines)
                } else {
                    current.seenBasePackages
                }

            val derivedProgress = explicitProgress?.let { downloadProgress ->
                // Runtime download occupies the preparation section before package setup.
                0.14 + downloadProgress.coerceIn(0.0, 1.0) * 0.42
            } ?: deriveEmbeddedTerminalInitProgress(
                kind = kind,
                message = normalizedMessage,
                seenBasePackages = nextSeenBasePackages,
                currentProgress = current.progress
            )

            embeddedTerminalInitState = current.copy(
                running = true,
                completed = false,
                success = null,
                progress = maxOf(current.progress, derivedProgress).coerceAtMost(0.99),
                stage = if (kind == "output") current.stage else normalizedMessage,
                logLines = mergeEmbeddedTerminalInitLogLines(
                    current.logLines,
                    formatEmbeddedTerminalInitLogLines(kind, normalizedLines)
                ),
                updatedAt = now,
                seenBasePackages = nextSeenBasePackages,
                phase = phase ?: current.phase,
                distribution = distribution ?: current.distribution,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                error = error ?: current.error
            )
        }
    }

    private fun markEmbeddedTerminalInitCompleted(
        success: Boolean,
        finalMessage: String
    ) {
        val normalizedMessage = finalMessage.trim().ifBlank {
            if (success) {
                "内嵌终端环境和基础 Agent CLI 包均已就绪。"
            } else {
                "检查内嵌终端环境失败"
            }
        }
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val current = embeddedTerminalInitState
            embeddedTerminalInitState = current.copy(
                running = false,
                completed = true,
                success = success,
                progress = if (success) 1.0 else current.progress.coerceAtLeast(0.02),
                stage = normalizedMessage,
                updatedAt = now,
                completedAt = now,
                phase = if (success) "ready" else current.phase.takeIf { it == "cancelled" } ?: "error",
                error = normalizedMessage.takeUnless { success }
            )
        }
        // Publish after committing the terminal snapshot. Consumers may read
        // the snapshot from this callback, including when startup had no waiter.
        dispatchProgress(
            mapOf(
                "kind" to if (success) "status" else "error",
                "message" to normalizedMessage,
                "timestamp" to System.currentTimeMillis(),
                "phase" to if (success) "ready" else "error",
                "error" to normalizedMessage.takeUnless { success }
            )
        )
    }

    private fun normalizeEmbeddedTerminalInitLines(message: String): List<String> {
        return message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
    }

    private fun formatEmbeddedTerminalInitLogLines(
        kind: String,
        lines: List<String>
    ): List<String> {
        val prefix =
            when (kind) {
                "error" -> "[错误] "
                "output" -> ""
                else -> "[阶段] "
            }
        return lines.map { line -> "$prefix$line" }
    }

    private fun mergeEmbeddedTerminalInitLogLines(
        currentLines: List<String>,
        appendedLines: List<String>
    ): List<String> {
        if (appendedLines.isEmpty()) {
            return currentLines
        }
        val merged = currentLines + appendedLines
        return if (merged.size > MAX_INIT_LOG_LINES) {
            merged.takeLast(MAX_INIT_LOG_LINES)
        } else {
            merged
        }
    }

    private fun extractSeenBasePackages(lines: List<String>): Set<String> {
        val lowerCaseLines = lines.map { it.lowercase() }
        return BASE_PACKAGE_NAMES.filter { packageName ->
            val lowerPackageName = packageName.lowercase()
            lowerCaseLines.any { line ->
                line.contains(lowerPackageName) &&
                    (
                        line.contains("fetch ") ||
                            line.contains("installing ") ||
                            line.contains("upgrading ") ||
                            line.contains("get:") ||
                            line.contains("selecting previously") ||
                            line.contains("unpacking") ||
                            line.contains("setting up") ||
                            line.contains("preparing to unpack")
                        )
            }
        }.toSet()
    }

    private fun deriveEmbeddedTerminalInitProgress(
        kind: String,
        message: String,
        seenBasePackages: Set<String>,
        currentProgress: Double
    ): Double {
        val normalizedMessage = message.trim()
        val stageProgress =
            when {
                normalizedMessage.contains("开始准备内嵌终端环境") -> 0.04
                normalizedMessage.contains("正在准备 workspace 和运行目录") -> 0.10
                normalizedMessage.contains("正在初始化宿主终端运行时") -> 0.14
                normalizedMessage.contains("正在校验终端环境运行资源") -> 0.24
                normalizedMessage.contains("正在安装终端环境运行资源") -> 0.42
                normalizedMessage.contains("宿主终端环境校验完成") -> 0.60
                normalizedMessage.contains("正在检查基础 Agent CLI 包") -> 0.68
                normalizedMessage.contains("基础 Agent CLI 包已就绪") -> 0.96
                normalizedMessage.contains("正在安装基础 Agent CLI 包") -> 0.72
                normalizedMessage.contains("基础 Agent CLI 包安装完成") -> 0.98
                normalizedMessage.contains("正在检查所选开发工具") -> 0.64
                normalizedMessage.contains("正在安装所选开发工具") -> 0.72
                normalizedMessage.contains("正在验证所选开发工具") -> 0.96
                normalizedMessage.contains("开发环境配置完成") -> 0.99
                normalizedMessage.contains("所选开发工具已就绪") -> 0.99
                normalizedMessage.contains("均已就绪") -> 1.0
                else -> null
            }
        if (stageProgress != null) {
            return stageProgress
        }

        if (kind == "output" && seenBasePackages.isNotEmpty()) {
            val packageRatio = seenBasePackages.size.toDouble() / BASE_PACKAGE_NAMES.size.toDouble()
            val outputProgress = 0.72 + packageRatio * 0.22
            return maxOf(currentProgress, outputProgress)
        }

        if (kind == "output" && currentProgress >= 0.72) {
            return (currentProgress + 0.004).coerceAtMost(0.94)
        }

        return currentProgress
    }
}
