package cn.com.omnimind.bot.terminal

import android.content.Context
import android.os.Build
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.termux.TermuxLiveUpdate
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.App
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EmbeddedTerminalRuntime {
    data class EnvironmentProgress(
        val kind: Kind,
        val message: String,
        val phase: String? = null,
        val distribution: String? = null,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val progress: Double? = null,
        val error: String? = null
    ) {
        enum class Kind {
            STATUS,
            OUTPUT,
            ERROR
        }
    }

    data class EnvironmentStatus(
        val success: Boolean,
        val initialized: Boolean,
        val basePackagesReady: Boolean,
        val message: String
    )

    data class RuntimeReadinessStatus(
        val supported: Boolean,
        val runtimeReady: Boolean,
        val basePackagesReady: Boolean,
        val missingCommands: List<String>,
        val message: String,
        val nodeReady: Boolean,
        val nodeVersion: String?,
        val nodeMinMajor: Int,
        val pnpmReady: Boolean,
        val pnpmVersion: String?
    )

    data class CommandResult(
        val success: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
        val output: String,
        val errorMessage: String?,
        val sessionId: String,
        val rawExtras: Map<String, Any?> = emptyMap()
    )

    data class SessionStartResult(
        val sessionId: String,
        val currentDirectory: String,
        val transcript: String
    )

    data class SessionCommandResult(
        val sessionId: String,
        val completed: Boolean,
        val success: Boolean,
        val exitCode: Int?,
        val timedOut: Boolean = false,
        val output: String,
        val transcript: String,
        val currentDirectory: String,
        val errorMessage: String? = null
    )

    data class SessionReadResult(
        val sessionId: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean
    )

    data class BackgroundServiceLaunchResult(
        val sessionId: String,
        val started: Boolean,
        val alreadyRunning: Boolean,
        val currentDirectory: String,
        val transcript: String,
        val message: String
    )

    internal data class SessionLiveOutputUpdate(
        val visibleOutput: String,
        val outputDelta: String,
        val exitCode: Int?
    )

    private data class SessionHandle(
        val externalSessionId: String,
        val mutex: Mutex = Mutex(),
        @Volatile var activeCommandToken: String? = null
    )

    private data class BasePackageProbeResult(
        val missingCommands: List<String> = emptyList(),
        val errorMessage: String? = null,
        val nodeReady: Boolean = false,
        val nodeVersion: String? = null,
        val nodeMajor: Int? = null,
        val pnpmReady: Boolean = false,
        val pnpmVersion: String? = null
    )

    private const val PREFS_NAME = "embedded_terminal_runtime"
    private const val KEY_BASE_PACKAGE_VERSION = "base_package_version"
    private const val BASE_PACKAGE_VERSION = 3
    private const val SESSION_DONE_PREFIX = "__OMNIBOT_SESSION_DONE__"
    private const val DEFAULT_CURRENT_DIRECTORY = AgentWorkspaceManager.SHELL_ROOT_PATH
    private const val BASE_PACKAGE_READY_MARKER = "__OMNIBOT_BASE_PACKAGES_READY__"
    private const val BASE_PACKAGE_MISSING_MARKER = "__OMNIBOT_BASE_PACKAGES_MISSING__"
    private const val BASE_PACKAGE_NODE_VERSION_MARKER = "__OMNIBOT_NODE_VERSION__"
    private const val BASE_PACKAGE_PNPM_VERSION_MARKER = "__OMNIBOT_PNPM_VERSION__"
    private const val NODE_MIN_MAJOR = 22
    private val terminalEnvKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private const val SESSION_HELPER_SCRIPT_NAME = "omnibot-session-lib.sh"

    private val sessionHandles = ConcurrentHashMap<String, SessionHandle>()
    private val packageInstallMutex = Mutex()
    private val environmentWarmupMutex = Mutex()
    @Volatile
    private var environmentWarmupReady = false
    private val requiredCliCommands = listOf(
        "bash",
        "curl",
        "fuser",
        "git",
        "node",
        "npm",
        "pkill",
        "python",
        "python3",
        "pip3",
        "rg",
        "tmux",
        "xz"
    )

    private val ansiEscapeRegex = Regex("""\u001B(?:\[[0-?]*[ -/]*[@-~]|\([A-Za-z0-9])""")
    private val knownNoiseRegexes = listOf(
        Regex("""^Warning: CPU doesn't support 32-bit instructions, some software may not work\.$"""),
        Regex("""^proot warning: can't sanitize binding "/proc/self/fd/\d+": No such file or directory$""")
    )
    private val shellPromptRegex = Regex("""^[^\r\n]*[#$] ?$""")

    private fun buildBasePackageBootstrapCommand(): String {
        val packageBootstrap = if (TerminalDistribution.selected().workingMode == WorkingMode.UBUNTU) {
            """
                ${UbuntuRepositoryManager.buildSelectedRepositorySetupCommand()}
                ${UbuntuRepositoryManager.buildNodeRepositorySetupCommand()}
                apt-get update &&
                DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
                  bash \
                  ca-certificates \
                  curl \
                  git \
                  nodejs \
                  procps \
                  psmisc \
                  python3 \
                  python3-numpy \
                  python3-pip \
                  python3-venv \
                  ripgrep \
                  tmux \
                  xz-utils
            """.trimIndent()
        } else {
            """
                ${AlpineRepositoryManager.buildSelectedRepositorySetupCommand()}
                apk update &&
                apk add --no-cache \
                  bash \
                  ca-certificates \
                  curl \
                  gcompat \
                  git \
                  glib \
                  nodejs \
                  npm \
                  procps \
                  psmisc \
                  python3 \
                  py3-numpy \
                  py3-pip \
                  py3-virtualenv \
                  ripgrep \
                  tmux \
                  xz
            """.trimIndent()
        }
        return """
            set -e
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            $packageBootstrap
            ln -sf /usr/bin/python3 /usr/local/bin/python || true
            python3 -m pip install --break-system-packages --upgrade pip >/dev/null 2>&1 || true
            npm install -g pnpm --no-audit --no-fund >/dev/null 2>&1 || true
        """.trimIndent()
    }

    fun isSupportedDevice(): Boolean {
        return Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    }

    suspend fun warmup(
        context: Context,
        onProgress: suspend (EnvironmentProgress) -> Unit = {}
    ): EnvironmentStatus {
        if (environmentWarmupReady) {
            return EnvironmentStatus(
                success = true,
                initialized = true,
                basePackagesReady = isBasePackagesReady(context),
                message = "内嵌终端环境已就绪。"
            )
        }
        return environmentWarmupMutex.withLock {
            if (environmentWarmupReady) {
                return@withLock EnvironmentStatus(
                    success = true,
                    initialized = true,
                    basePackagesReady = isBasePackagesReady(context),
                    message = "内嵌终端环境已就绪。"
                )
            }
            prepareEnvironment(
                context = context,
                installBasePackages = false,
                onProgress = onProgress
            ).also { status ->
                if (status.success && status.initialized) {
                    environmentWarmupReady = true
                }
            }
        }
    }

    suspend fun inspectRuntimeReadiness(
        context: Context,
        onProgress: suspend (EnvironmentProgress) -> Unit = {}
    ): RuntimeReadinessStatus {
        if (!isSupportedDevice()) {
            return RuntimeReadinessStatus(
                supported = false,
                runtimeReady = false,
                basePackagesReady = false,
                missingCommands = emptyList(),
                message = "当前设备 ABI 不受支持，内嵌终端环境仅支持 arm64-v8a。",
                nodeReady = false,
                nodeVersion = null,
                nodeMinMajor = NODE_MIN_MAJOR,
                pnpmReady = false,
                pnpmVersion = null
            )
        }

        val environmentStatus = prepareEnvironment(
            context = context,
            installBasePackages = false,
            onProgress = onProgress
        )
        if (!environmentStatus.success || !environmentStatus.initialized) {
            return RuntimeReadinessStatus(
                supported = true,
                runtimeReady = false,
                basePackagesReady = false,
                missingCommands = emptyList(),
                message = environmentStatus.message.ifBlank { "内嵌终端环境初始化失败。" },
                nodeReady = false,
                nodeVersion = null,
                nodeMinMajor = NODE_MIN_MAJOR,
                pnpmReady = false,
                pnpmVersion = null
            )
        }

        val probeResult = probeBasePackageCommands(context)
        val probeError = probeResult.errorMessage
        if (probeError != null) {
            return RuntimeReadinessStatus(
                supported = true,
                runtimeReady = true,
                basePackagesReady = false,
                missingCommands = emptyList(),
                message = probeError,
                nodeReady = probeResult.nodeReady,
                nodeVersion = probeResult.nodeVersion,
                nodeMinMajor = NODE_MIN_MAJOR,
                pnpmReady = probeResult.pnpmReady,
                pnpmVersion = probeResult.pnpmVersion
            )
        }

        val missingCommands = probeResult.missingCommands
        if (missingCommands.isEmpty()) {
            markBasePackagesReady(context)
            val readyMessage =
                if (probeResult.nodeReady && probeResult.pnpmReady) {
                    "内嵌终端环境和基础 Agent CLI 包均已就绪。"
                } else {
                    "内嵌终端环境和基础 Agent CLI 包已就绪；Node.js/PNPM 状态可在环境页查看。"
                }
            return RuntimeReadinessStatus(
                supported = true,
                runtimeReady = true,
                basePackagesReady = true,
                missingCommands = emptyList(),
                message = readyMessage,
                nodeReady = probeResult.nodeReady,
                nodeVersion = probeResult.nodeVersion,
                nodeMinMajor = NODE_MIN_MAJOR,
                pnpmReady = probeResult.pnpmReady,
                pnpmVersion = probeResult.pnpmVersion
            )
        }

        return RuntimeReadinessStatus(
            supported = true,
            runtimeReady = true,
            basePackagesReady = false,
            missingCommands = missingCommands,
            message = "检测到基础 Agent CLI 包缺失：${missingCommands.joinToString(", ")}",
            nodeReady = probeResult.nodeReady,
            nodeVersion = probeResult.nodeVersion,
            nodeMinMajor = NODE_MIN_MAJOR,
            pnpmReady = probeResult.pnpmReady,
            pnpmVersion = probeResult.pnpmVersion
        )
    }

    suspend fun prepareEnvironment(
        context: Context,
        installBasePackages: Boolean,
        onProgress: suspend (EnvironmentProgress) -> Unit = {}
    ): EnvironmentStatus {
        if (!isSupportedDevice()) {
            emitEnvironmentProgress(
                onProgress,
                EnvironmentProgress(
                    kind = EnvironmentProgress.Kind.ERROR,
                    message = "当前设备 ABI 不受支持，内嵌终端环境仅支持 arm64-v8a。"
                )
            )
            return EnvironmentStatus(
                success = false,
                initialized = false,
                basePackagesReady = false,
                message = "当前设备 ABI 不受支持，内嵌终端环境仅支持 arm64-v8a。"
            )
        }

        emitEnvironmentProgress(
            onProgress,
            EnvironmentProgress(
                kind = EnvironmentProgress.Kind.STATUS,
                message = "正在准备 workspace 和运行目录"
            )
        )
        withContext(Dispatchers.IO) {
            AgentWorkspaceManager(context).ensureRuntimeDirectories()
        }

        val manager = terminalManager(context)
        emitEnvironmentProgress(
            onProgress,
            EnvironmentProgress(
                kind = EnvironmentProgress.Kind.STATUS,
                message = "正在初始化宿主终端运行时"
            )
        )
        var runtimeFailureMessage: String? = null
        val initialized =
            manager.initializeEnvironment { runtimeProgress ->
                runtimeProgress.error?.takeIf { it.isNotBlank() }?.let { error ->
                    runtimeFailureMessage = error
                }
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = if (runtimeProgress.error == null) {
                            EnvironmentProgress.Kind.STATUS
                        } else {
                            EnvironmentProgress.Kind.ERROR
                        },
                        message = runtimeProgress.message,
                        phase = runtimeProgress.phase,
                        distribution = runtimeProgress.distribution,
                        downloadedBytes = runtimeProgress.downloadedBytes,
                        totalBytes = runtimeProgress.totalBytes,
                        progress = runtimeProgress.progress,
                        error = runtimeProgress.error
                    )
                )
            }
        if (!initialized) {
            val failureMessage = runtimeFailureMessage ?: "内嵌终端环境初始化失败。"
            emitEnvironmentProgress(
                onProgress,
                EnvironmentProgress(
                    kind = EnvironmentProgress.Kind.ERROR,
                    message = failureMessage,
                    phase = "error",
                    distribution = TerminalDistribution.selected().id,
                    error = failureMessage
                )
            )
            return EnvironmentStatus(
                success = false,
                initialized = false,
                basePackagesReady = isBasePackagesReady(context),
                message = failureMessage
            )
        }

        if (!installBasePackages) {
            val basePackagesReady = isBasePackagesReady(context)
            emitEnvironmentProgress(
                onProgress,
                EnvironmentProgress(
                    kind = EnvironmentProgress.Kind.STATUS,
                    message = if (basePackagesReady) {
                        "内嵌终端环境已就绪。"
                    } else {
                        "内嵌终端环境已就绪，基础 Agent CLI 包尚未完成预装。"
                    }
                )
            )
            return EnvironmentStatus(
                success = true,
                initialized = true,
                basePackagesReady = basePackagesReady,
                message = if (basePackagesReady) {
                    "内嵌终端环境已就绪。"
                } else {
                    "内嵌终端环境已就绪，基础 Agent CLI 包尚未完成预装。"
                }
            )
        }

        return packageInstallMutex.withLock {
            emitEnvironmentProgress(
                onProgress,
                EnvironmentProgress(
                    kind = EnvironmentProgress.Kind.STATUS,
                    message = "正在检查基础 Agent CLI 包"
                )
            )
            val preflightProbe = probeBasePackageCommands(context)
            if (preflightProbe.errorMessage == null && preflightProbe.missingCommands.isEmpty()) {
                markBasePackagesReady(context)
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = EnvironmentProgress.Kind.STATUS,
                        message = "基础 Agent CLI 包已就绪。"
                    )
                )
                return@withLock EnvironmentStatus(
                    success = true,
                    initialized = true,
                    basePackagesReady = true,
                    message = "内嵌终端环境和基础 Agent CLI 包均已就绪。"
                )
            }

            if (preflightProbe.missingCommands.isNotEmpty()) {
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = EnvironmentProgress.Kind.STATUS,
                        message = "检测到基础 Agent CLI 包缺失：${preflightProbe.missingCommands.joinToString(", ")}"
                    )
                )
            } else if (preflightProbe.errorMessage != null && isBasePackagesReady(context)) {
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = EnvironmentProgress.Kind.STATUS,
                        message = "基础 Agent CLI 包检查异常，准备重新安装。"
                    )
                )
            }

            emitEnvironmentProgress(
                onProgress,
                EnvironmentProgress(
                    kind = EnvironmentProgress.Kind.STATUS,
                    message = "正在安装基础 Agent CLI 包（git、python、node 等）"
                )
            )
            val installResult = manager.executeHiddenCommand(
                command = buildBasePackageBootstrapCommand(),
                executorKey = "embedded-bootstrap",
                timeoutMs = 15 * 60 * 1000L,
                onOutputChunk = { chunk ->
                    val normalizedChunk = chunk.replace("\r\n", "\n").replace('\r', '\n')
                    if (normalizedChunk.isNotBlank()) {
                        emitEnvironmentProgress(
                            onProgress,
                            EnvironmentProgress(
                                kind = EnvironmentProgress.Kind.OUTPUT,
                                message = normalizedChunk
                            )
                        )
                    }
                }
            )
            if (installResult.isOk && installResult.exitCode == 0) {
                val postInstallProbe = probeBasePackageCommands(context)
                if (postInstallProbe.errorMessage == null && postInstallProbe.missingCommands.isEmpty()) {
                    markBasePackagesReady(context)
                    emitEnvironmentProgress(
                        onProgress,
                        EnvironmentProgress(
                            kind = EnvironmentProgress.Kind.STATUS,
                            message = "基础 Agent CLI 包安装完成。"
                        )
                    )
                    return@withLock EnvironmentStatus(
                        success = true,
                        initialized = true,
                        basePackagesReady = true,
                        message = "内嵌终端环境和基础 Agent CLI 包均已就绪。"
                    )
                }
                val failureMessage = postInstallProbe.errorMessage
                    ?: buildMissingBasePackageFailureMessage(postInstallProbe.missingCommands)
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = EnvironmentProgress.Kind.ERROR,
                        message = failureMessage
                    )
                )
                EnvironmentStatus(
                    success = false,
                    initialized = true,
                    basePackagesReady = false,
                    message = failureMessage
                )
            } else {
                val failureMessage = buildInstallFailureMessage(installResult)
                emitEnvironmentProgress(
                    onProgress,
                    EnvironmentProgress(
                        kind = EnvironmentProgress.Kind.ERROR,
                        message = failureMessage
                    )
                )
                EnvironmentStatus(
                    success = false,
                    initialized = true,
                    basePackagesReady = false,
                    message = failureMessage
                )
            }
        }
    }

    suspend fun executeCommand(
        context: Context,
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int?,
        environment: Map<String, String> = emptyMap(),
        onProcessStarted: ((Process) -> Unit)? = null,
        onLiveUpdate: suspend (TermuxLiveUpdate) -> Unit = {}
    ): CommandResult {
        // A shell command must not implicitly install the optional Agent CLI
        // bundle.  The bundle contains node/pnpm and other tools used by the
        // environment screen, but it is unrelated to executing `bash`,
        // `pwd`, or another already-available shell command.  Installing it
        // here serialized package-manager/network latency into the ACP turn.
        val status = ensureRuntimeEnvironmentReady(context) { progress ->
            if (progress.message.isBlank()) return@ensureRuntimeEnvironmentReady
            onLiveUpdate(
                TermuxLiveUpdate(
                    sessionId = "env-bootstrap",
                    summary = progress.message,
                    outputDelta = if (progress.kind == EnvironmentProgress.Kind.OUTPUT) progress.message else "",
                    streamState = when (progress.kind) {
                        EnvironmentProgress.Kind.ERROR -> "error"
                        else -> "running"
                    }
                )
            )
        }
        val liveSessionId = UUID.randomUUID().toString()
        if (!status.success) {
            return CommandResult(
                success = false,
                timedOut = false,
                exitCode = null,
                output = "",
                errorMessage = status.message,
                sessionId = liveSessionId
            )
        }

        onLiveUpdate(
            TermuxLiveUpdate(
                sessionId = liveSessionId,
                summary = "正在执行内嵌终端环境命令",
                streamState = "running"
            )
        )

        val wrappedCommand = wrapOneShotCommand(
            command = command,
            workingDirectory = workingDirectory,
            environment = environment
        )
        var streamedVisibleOutput = false
        val hiddenResult = terminalManager(context).executeHiddenCommand(
            command = wrappedCommand,
            executorKey = buildExecutorKey(workingDirectory),
            timeoutMs = timeoutSeconds?.toLong()?.times(1000L),
            onProcessStarted = onProcessStarted,
            onOutputChunk = { chunk ->
                val cleanedChunk = sanitizeTerminalNoise(chunk)
                if (cleanedChunk.isBlank()) {
                    return@executeHiddenCommand
                }
                streamedVisibleOutput = true
                val normalizedChunk = if (cleanedChunk.endsWith("\n")) {
                    cleanedChunk
                } else {
                    "$cleanedChunk\n"
                }
                onLiveUpdate(
                    TermuxLiveUpdate(
                        sessionId = liveSessionId,
                        summary = summarizeLiveTerminalChunk(normalizedChunk),
                        outputDelta = normalizedChunk,
                        streamState = "running"
                    )
                )
            }
        )
        val cleanedOutput = sanitizeTerminalNoise(hiddenResult.output)
        val trimmedOutput = trimTerminalOutput(cleanedOutput)
        val timedOut = hiddenResult.state == HiddenExecResult.State.TIMEOUT
        val success = hiddenResult.isOk && hiddenResult.exitCode == 0
        val errorMessage = when {
            timedOut -> "终端命令等待超时，可能仍在后台继续运行。"
            hiddenResult.error.isNotBlank() -> hiddenResult.error
            !success && trimmedOutput.isBlank() && hiddenResult.rawOutputPreview.isNotBlank() -> hiddenResult.rawOutputPreview
            else -> null
        }

        if (trimmedOutput.isNotBlank() && !streamedVisibleOutput) {
            onLiveUpdate(
                TermuxLiveUpdate(
                    sessionId = liveSessionId,
                    summary = if (timedOut) "终端命令超时，已返回当前可见输出" else "终端命令执行完成",
                    outputDelta = trimmedOutput.takeLast(4000),
                    streamState = if (timedOut) "running" else "completed"
                )
            )
        }

        return CommandResult(
            success = success,
            timedOut = timedOut,
            exitCode = hiddenResult.exitCode.takeIf { hiddenResult.state == HiddenExecResult.State.OK },
            output = trimmedOutput,
            errorMessage = errorMessage,
            sessionId = liveSessionId,
            rawExtras = buildMap {
                put("hiddenExecState", hiddenResult.state.name)
                if (hiddenResult.rawOutputPreview.isNotBlank()) {
                    put("rawOutputPreview", hiddenResult.rawOutputPreview)
                }
                put("basePackagesReady", status.basePackagesReady)
            }
        )
    }

    private fun summarizeLiveTerminalChunk(chunk: String): String {
        return chunk.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { line ->
                if (line.length <= 120) {
                    line
                } else {
                    line.take(119).trimEnd() + "…"
                }
            }
            ?: "终端输出更新中"
    }

    suspend fun startSession(
        context: Context,
        requestedSessionId: String? = null,
        sessionTitle: String? = null,
        workingDirectory: String?,
        environment: Map<String, String> = emptyMap()
    ): SessionStartResult {
        val status = ensureRuntimeEnvironmentReady(context)
        require(status.success) { status.message }

        val sessionAccess = ReTerminalSessionBridge.ensureHeadlessSession(
            context = context,
            sessionId = requestedSessionId,
            sessionTitle = sessionTitle
        )
        val actualSessionId = sessionAccess.sessionId
        val handle = sessionHandles.computeIfAbsent(actualSessionId) {
            SessionHandle(externalSessionId = actualSessionId)
        }
        if (sessionAccess.created) {
            handle.activeCommandToken = null
        }

        try {
            val targetWorkingDirectory = workingDirectory?.trim().takeUnless { it.isNullOrBlank() }
                ?: AgentWorkspaceManager.SHELL_ROOT_PATH
            if (sessionAccess.created) {
                val cwdResult = executeSessionCommand(
                    context = context,
                    sessionId = actualSessionId,
                    command = "cd ${TermuxCommandBuilder.quoteForShell(targetWorkingDirectory)}",
                    workingDirectory = null,
                    environment = environment
                )
                require(cwdResult.completed && cwdResult.success) {
                    cwdResult.errorMessage ?: "无法切换到工作目录：$targetWorkingDirectory"
                }
            }

            val snapshot = readSession(context, actualSessionId)
            return SessionStartResult(
                sessionId = actualSessionId,
                currentDirectory = snapshot.currentDirectory,
                transcript = snapshot.transcript
            )
        } catch (error: Throwable) {
            if (sessionAccess.created) {
                sessionHandles.remove(actualSessionId)
                runCatching {
                    ReTerminalSessionBridge.stopSession(context, actualSessionId)
                }
            }
            throw error
        }
    }

    suspend fun executeSessionCommand(
        context: Context,
        sessionId: String,
        command: String,
        workingDirectory: String?,
        environment: Map<String, String> = emptyMap(),
        onLiveUpdate: suspend (TermuxLiveUpdate) -> Unit = {}
    ): SessionCommandResult {
        val handle = sessionHandles[sessionId]
            ?: return SessionCommandResult(
                sessionId = sessionId,
                completed = false,
                success = false,
                exitCode = null,
                output = "",
                transcript = "",
                currentDirectory = DEFAULT_CURRENT_DIRECTORY,
                errorMessage = "终端会话不存在：$sessionId"
            )

        val status = ensureRuntimeEnvironmentReady(context)
        if (!status.success) {
            return SessionCommandResult(
                sessionId = sessionId,
                completed = false,
                success = false,
                exitCode = null,
                output = "",
                transcript = "",
                currentDirectory = DEFAULT_CURRENT_DIRECTORY,
                errorMessage = status.message
            )
        }

        return handle.mutex.withLock {
            val preSnapshot = readSession(context, sessionId)
            if (preSnapshot.commandRunning) {
                return@withLock SessionCommandResult(
                    sessionId = sessionId,
                    completed = false,
                    success = false,
                    exitCode = null,
                    output = "",
                    transcript = preSnapshot.transcript,
                    currentDirectory = preSnapshot.currentDirectory,
                    errorMessage = "当前会话仍有命令在执行，请先读取输出或停止会话。"
                )
            }

            if (!workingDirectory.isNullOrBlank()) {
                val cwdResult = sendSessionCommandAndAwait(
                    context = context,
                    handle = handle,
                    command = "cd ${TermuxCommandBuilder.quoteForShell(workingDirectory)}",
                    environment = environment
                )
                if (!cwdResult.completed || !cwdResult.success) {
                    return@withLock cwdResult.copy(sessionId = sessionId)
                }
            }

            sendSessionCommandAndAwait(
                context = context,
                handle = handle,
                command = command,
                environment = environment,
                onLiveUpdate = onLiveUpdate
            ).copy(sessionId = sessionId)
        }
    }

    suspend fun readSession(
        context: Context,
        sessionId: String
    ): SessionReadResult {
        val handle = sessionHandles[sessionId]
            ?: return SessionReadResult(
                sessionId = sessionId,
                transcript = "",
                currentDirectory = DEFAULT_CURRENT_DIRECTORY,
                commandRunning = false
            )

        val session = ReTerminalSessionBridge.getSession(context, sessionId) ?: run {
            sessionHandles.remove(sessionId)
            return SessionReadResult(
                sessionId = sessionId,
                transcript = "",
                currentDirectory = DEFAULT_CURRENT_DIRECTORY,
                commandRunning = false
            )
        }
        val rawTranscript = session.getTranscriptText().trim('\n')
        val activeToken = handle.activeCommandToken
        val commandRunning = if (activeToken.isNullOrBlank()) {
            false
        } else {
            val parsed = parsePersistentCommandOutput(rawTranscript, activeToken)
            if (parsed.exitCode != null) {
                handle.activeCommandToken = null
                false
            } else {
                session.isRunning
            }
        }

        return SessionReadResult(
            sessionId = sessionId,
            transcript = buildTranscript(rawTranscript),
            currentDirectory = normalizeCurrentDirectory(session.getCwd().orEmpty()),
            commandRunning = commandRunning
        )
    }

    suspend fun stopSession(context: Context, sessionId: String): Boolean {
        sessionHandles.remove(sessionId)
        return runCatching {
            ReTerminalSessionBridge.stopSession(context, sessionId)
        }.getOrDefault(false)
    }

    suspend fun hasSession(context: Context, sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isEmpty()) {
            return false
        }
        return ReTerminalSessionBridge.getSession(context, normalizedSessionId) != null
    }

    suspend fun launchBackgroundServiceSession(
        context: Context,
        sessionId: String,
        command: String,
        workingDirectory: String?,
        environment: Map<String, String> = emptyMap()
    ): BackgroundServiceLaunchResult {
        val status = ensureRuntimeEnvironmentReady(context)
        if (!status.success) {
            return BackgroundServiceLaunchResult(
                sessionId = sessionId,
                started = false,
                alreadyRunning = false,
                currentDirectory = DEFAULT_CURRENT_DIRECTORY,
                transcript = "",
                message = status.message
            )
        }

        val sessionAccess = ReTerminalSessionBridge.ensureHeadlessSession(
            context = context,
            sessionId = sessionId
        )
        if (!sessionAccess.created && sessionAccess.session.isRunning) {
            return BackgroundServiceLaunchResult(
                sessionId = sessionId,
                started = false,
                alreadyRunning = true,
                currentDirectory = normalizeCurrentDirectory(sessionAccess.session.getCwd().orEmpty()),
                transcript = buildTranscript(sessionAccess.session.getTranscriptText().trim('\n')),
                message = "后台服务已在运行。"
            )
        }
        sessionHandles.remove(sessionId)
        ReTerminalSessionBridge.sendCommand(
            context = context,
            sessionId = sessionId,
            command = buildServiceSessionCommand(
                context = context,
                command = command,
                workingDirectory = workingDirectory,
                environment = environment
            )
        )
        delay(150)
        val session = ReTerminalSessionBridge.getSession(context, sessionId)
        return BackgroundServiceLaunchResult(
            sessionId = sessionId,
            started = true,
            alreadyRunning = false,
            currentDirectory = normalizeCurrentDirectory(session?.getCwd().orEmpty()),
            transcript = buildTranscript(session?.getTranscriptText()?.trim('\n').orEmpty()),
            message = "后台服务启动命令已发送。"
        )
    }

    private suspend fun sendSessionCommandAndAwait(
        context: Context,
        handle: SessionHandle,
        command: String,
        environment: Map<String, String> = emptyMap(),
        onLiveUpdate: suspend (TermuxLiveUpdate) -> Unit = {}
    ): SessionCommandResult {
        val token = UUID.randomUUID().toString()
        val sessionAccess = ReTerminalSessionBridge.ensureHeadlessSession(
            context = context,
            sessionId = handle.externalSessionId
        )
        if (sessionAccess.created) {
            handle.activeCommandToken = null
        }
        val transcriptStart = sessionAccess.session.getTranscriptText().length
        handle.activeCommandToken = token
        ReTerminalSessionBridge.sendCommand(
            context = context,
            sessionId = handle.externalSessionId,
            command = buildPersistentSessionCommand(
                context = context,
                command = command,
                token = token,
                environment = environment
            )
        )

        var completionTranscript: String? = null
        var previousVisibleOutput = ""
        while (completionTranscript == null) {
            val currentTranscript = ReTerminalSessionBridge.getSession(
                context = context,
                sessionId = handle.externalSessionId
            )?.getTranscriptText().orEmpty()
            val liveOutput = buildSessionLiveOutputUpdate(
                previousVisibleOutput = previousVisibleOutput,
                rawOutput = currentTranscript.safeSubstring(transcriptStart),
                token = token
            )
            if (liveOutput.visibleOutput != previousVisibleOutput) {
                previousVisibleOutput = liveOutput.visibleOutput
            }
            if (liveOutput.outputDelta.isNotBlank()) {
                onLiveUpdate(
                    TermuxLiveUpdate(
                        sessionId = handle.externalSessionId,
                        summary = summarizeLiveTerminalChunk(liveOutput.outputDelta),
                        outputDelta = liveOutput.outputDelta,
                        streamState = "running"
                    )
                )
            }
            if (liveOutput.exitCode != null) {
                completionTranscript = currentTranscript
                continue
            }
            delay(150)
        }
        val snapshot = readSession(context, handle.externalSessionId)
        val completedTranscript = requireNotNull(completionTranscript)

        val completionOutput = if (completedTranscript.length <= transcriptStart) {
            ""
        } else {
            completedTranscript.substring(transcriptStart)
        }
        val parsed = parsePersistentCommandOutput(
            rawOutput = completionOutput,
            token = token
        )
        handle.activeCommandToken = null
        val cleanedOutput = trimTerminalOutput(sanitizeTerminalNoise(parsed.output))
        return SessionCommandResult(
            sessionId = handle.externalSessionId,
            completed = true,
            success = parsed.exitCode == 0,
            exitCode = parsed.exitCode,
            timedOut = false,
            output = cleanedOutput,
            transcript = snapshot.transcript,
            currentDirectory = snapshot.currentDirectory,
            errorMessage = if (parsed.exitCode == 0) null else "终端会话命令执行失败（exit=${parsed.exitCode})"
        )
    }

    internal fun buildSessionLiveOutputUpdate(
        previousVisibleOutput: String,
        rawOutput: String,
        token: String
    ): SessionLiveOutputUpdate {
        val parsed = parsePersistentCommandOutput(
            rawOutput = rawOutput,
            token = token
        )
        val visibleOutput = sanitizeTerminalNoise(parsed.output)
        return SessionLiveOutputUpdate(
            visibleOutput = visibleOutput,
            outputDelta = extractTerminalOutputDelta(
                previousVisibleOutput = previousVisibleOutput,
                currentVisibleOutput = visibleOutput
            ),
            exitCode = parsed.exitCode
        )
    }

    private fun wrapOneShotCommand(
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>
    ): String {
        val trimmedCommand = command.trim()
        val normalizedWorkingDirectory = workingDirectory?.trim().orEmpty()
        val environmentExports = buildCommandEnvironmentExports(environment)
        return buildString {
            appendLine(buildPythonEnvironmentPrelude())
            if (normalizedWorkingDirectory.isNotBlank()) {
                append("cd ")
                append(TermuxCommandBuilder.quoteForShell(normalizedWorkingDirectory))
                appendLine(" || exit $?")
            }
            appendLine("__omni_prepare_python_env 0 || exit $?")
            if (environmentExports.isNotBlank()) {
                appendLine(environmentExports)
            }
            append(trimmedCommand)
        }
    }

    private suspend fun buildPersistentSessionCommand(
        context: Context,
        command: String,
        token: String,
        environment: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n").trimEnd()
        val tokenSuffix = token.replace("-", "")
        val sessionScript = writeSessionTempScript(
            context = context,
            fileName = "omni_session_$tokenSuffix.sh",
            content = buildString {
                appendLine(". ${TermuxCommandBuilder.quoteForShell(ensureSessionHelperScript(context).absolutePath)} || return $?")
                appendLine("__omni_prepare_python_env 0 || return $?")
                val environmentExports = buildCommandEnvironmentExports(environment)
                if (environmentExports.isNotBlank()) {
                    appendLine(environmentExports)
                }
                append(normalizedCommand)
                if (!normalizedCommand.endsWith("\n")) {
                    append('\n')
                }
            }
        )
        val quotedScriptPath = TermuxCommandBuilder.quoteForShell(sessionScript.absolutePath)
        ". $quotedScriptPath; __omnibot_session_rc=$?; rm -f $quotedScriptPath; printf '\\n$SESSION_DONE_PREFIX:$token:%s\\n' \"\$__omnibot_session_rc\""
    }

    private suspend fun buildServiceSessionCommand(
        context: Context,
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>
    ): String = withContext(Dispatchers.IO) {
        val normalizedCommand = command.replace("\r\n", "\n").replace("\r", "\n").trim()
        require(normalizedCommand.isNotEmpty()) { "command 不能为空" }
        val scriptId = UUID.randomUUID().toString().replace("-", "")
        val normalizedWorkingDirectory = workingDirectory?.trim().orEmpty()
        val serviceScript = writeSessionTempScript(
            context = context,
            fileName = "omni_service_$scriptId.sh",
            content = buildString {
                appendLine("trap 'rm -f \"\$0\"' EXIT")
                appendLine(". ${TermuxCommandBuilder.quoteForShell(ensureSessionHelperScript(context).absolutePath)} || exit $?")
                if (normalizedWorkingDirectory.isNotBlank()) {
                    append("cd ")
                    append(TermuxCommandBuilder.quoteForShell(normalizedWorkingDirectory))
                    appendLine(" || exit $?")
                }
                appendLine("__omni_prepare_python_env 0 || exit $?")
                val environmentExports = buildCommandEnvironmentExports(environment)
                if (environmentExports.isNotBlank()) {
                    appendLine(environmentExports)
                }
                append(normalizedCommand)
                if (!normalizedCommand.endsWith("\n")) {
                    append('\n')
                }
            }
        )
        "exec /bin/sh ${TermuxCommandBuilder.quoteForShell(serviceScript.absolutePath)}"
    }

    internal fun buildCommandEnvironmentExports(environment: Map<String, String>): String {
        if (environment.isEmpty()) {
            return ""
        }
        return buildString {
            environment.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                if (key.isEmpty() || !terminalEnvKeyPattern.matches(key)) {
                    return@forEach
                }
                append("export ")
                append(key)
                append("=")
                append(TermuxCommandBuilder.quoteForShell(rawValue))
                append('\n')
            }
        }.trimEnd()
    }

    private fun ensureSessionHelperScript(context: Context): File {
        val scriptFile = File(context.filesDir.parentFile, "local/bin/$SESSION_HELPER_SCRIPT_NAME")
        scriptFile.parentFile?.mkdirs()
        val content = buildPythonEnvironmentPrelude().trimEnd() + "\n"
        if (!scriptFile.exists() || scriptFile.readText() != content) {
            scriptFile.writeText(content)
        }
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(false, false)
        return scriptFile
    }

    private fun writeSessionTempScript(
        context: Context,
        fileName: String,
        content: String
    ): File {
        val directory = App.getTempDir().resolve("agent-session-scripts").apply { mkdirs() }
        return directory.resolve(fileName).apply {
            parentFile?.mkdirs()
            writeText(content)
            setReadable(true, false)
            setExecutable(true, false)
        }
    }

    internal fun buildPythonEnvironmentPrelude(): String = """
        export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
        export UV_LINK_MODE=copy
        __omni_workspace_root=${TermuxCommandBuilder.quoteForShell(AgentWorkspaceManager.SHELL_ROOT_PATH)}
        __omni_uv_env_root="${'$'}HOME/.cache/omnibot/uv-project-envs"

        __omni_locate_python_project_root() {
          __omni_current_dir="${'$'}PWD"
          case "${'$'}__omni_current_dir" in
            "${'$'}__omni_workspace_root"|${'$'}__omni_workspace_root/*) ;;
            *) return 1 ;;
          esac
          while true; do
            if [ -f "${'$'}__omni_current_dir/.venv/bin/activate" ] || \
               [ -f "${'$'}__omni_current_dir/pyproject.toml" ] || \
               [ -f "${'$'}__omni_current_dir/requirements.txt" ] || \
               [ -f "${'$'}__omni_current_dir/requirements-dev.txt" ] || \
               [ -f "${'$'}__omni_current_dir/setup.py" ] || \
               [ -f "${'$'}__omni_current_dir/setup.cfg" ] || \
               [ -f "${'$'}__omni_current_dir/Pipfile" ] || \
               [ -f "${'$'}__omni_current_dir/poetry.lock" ] || \
               [ -f "${'$'}__omni_current_dir/pytest.ini" ] || \
               [ -f "${'$'}__omni_current_dir/tox.ini" ] || \
               [ -f "${'$'}__omni_current_dir/manage.py" ]; then
              printf '%s\n' "${'$'}__omni_current_dir"
              return 0
            fi
            if [ "${'$'}__omni_current_dir" = "${'$'}__omni_workspace_root" ]; then
              break
            fi
            __omni_parent_dir=$(dirname "${'$'}__omni_current_dir")
            if [ "${'$'}__omni_parent_dir" = "${'$'}__omni_current_dir" ]; then
              break
            fi
            __omni_current_dir="${'$'}__omni_parent_dir"
          done
          return 1
        }

        __omni_find_python_project_root() {
          __omni_locate_python_project_root
          __omni_locate_rc="${'$'}?"
          if [ "${'$'}__omni_locate_rc" -eq 0 ]; then
            return 0
          fi
          printf '%s\n' "${'$'}PWD"
        }

        __omni_activate_virtualenv() {
          __omni_target_venv="${'$'}1"
          if [ ! -f "${'$'}__omni_target_venv/bin/activate" ]; then
            return 1
          fi
          if [ "${'$'}VIRTUAL_ENV" != "${'$'}__omni_target_venv" ]; then
            if [ -n "${'$'}VIRTUAL_ENV" ] && command -v deactivate >/dev/null 2>&1; then
              deactivate >/dev/null 2>&1 || true
            fi
            . "${'$'}__omni_target_venv/bin/activate" || return ${'$'}?
          fi
          return 0
        }

        __omni_cleanup_invalid_virtualenv() {
          __omni_candidate_venv="${'$'}1"
          if [ ! -d "${'$'}__omni_candidate_venv" ]; then
            return 0
          fi
          if [ -x "${'$'}__omni_candidate_venv/bin/python" ] || [ -x "${'$'}__omni_candidate_venv/bin/python3" ]; then
            return 0
          fi
          if [ -f "${'$'}__omni_candidate_venv/bin/activate" ] || [ -f "${'$'}__omni_candidate_venv/pyvenv.cfg" ] || [ -d "${'$'}__omni_candidate_venv/bin" ]; then
            printf '[omnibot] Removing invalid virtual environment at %s\n' "${'$'}__omni_candidate_venv" >&2
            rm -rf "${'$'}__omni_candidate_venv" || return ${'$'}?
          fi
          return 0
        }

        __omni_prepare_python_env() {
          __omni_create_if_missing="${'$'}1"
          __omni_project_root=$(__omni_locate_python_project_root 2>/dev/null)
          __omni_project_root_rc="${'$'}?"
          if [ "${'$'}__omni_project_root_rc" -ne 0 ]; then
            if [ -n "${'$'}VIRTUAL_ENV" ] && [ -f "${'$'}VIRTUAL_ENV/bin/activate" ]; then
              return 0
            fi
            if [ "${'$'}__omni_create_if_missing" != "1" ]; then
              return 0
            fi
            __omni_project_root="${'$'}PWD"
          fi
          __omni_venv_dir="${'$'}__omni_project_root/.venv"
          __omni_cleanup_invalid_virtualenv "${'$'}__omni_venv_dir" || return ${'$'}?
          if [ ! -f "${'$'}__omni_venv_dir/bin/activate" ] && [ "${'$'}__omni_create_if_missing" = "1" ]; then
            if [ -d "${'$'}__omni_venv_dir" ]; then
              rm -rf "${'$'}__omni_venv_dir" || return ${'$'}?
            fi
            printf '[omnibot] Creating Python virtual environment at %s\n' "${'$'}__omni_venv_dir" >&2
            command python3 -m venv --copies "${'$'}__omni_venv_dir" || return ${'$'}?
          fi
          if [ -f "${'$'}__omni_venv_dir/bin/activate" ]; then
            __omni_activate_virtualenv "${'$'}__omni_venv_dir" || return ${'$'}?
          fi
          return 0
        }

        __omni_find_uv_workspace_root() {
          __omni_uv_root=$(__omni_locate_python_project_root 2>/dev/null)
          if [ "${'$'}?" -eq 0 ] && [ -n "${'$'}__omni_uv_root" ]; then
            printf '%s\n' "${'$'}__omni_uv_root"
            return 0
          fi
          case "${'$'}PWD" in
            "${'$'}__omni_workspace_root"|${'$'}__omni_workspace_root/*)
              printf '%s\n' "${'$'}PWD"
              return 0
              ;;
            *)
              return 1
              ;;
          esac
        }

        __omni_uv_env_dir_for_root() {
          __omni_uv_root="${'$'}1"
          mkdir -p "${'$'}__omni_uv_env_root" || return ${'$'}?
          __omni_uv_key=$(command python3 -c 'import hashlib, sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())' "${'$'}__omni_uv_root") || return ${'$'}?
          printf '%s/%s\n' "${'$'}__omni_uv_env_root" "${'$'}__omni_uv_key"
        }

        __omni_uv_resolve_target_path() {
          __omni_uv_target="${'$'}1"
          case "${'$'}__omni_uv_target" in
            /*)
              printf '%s\n' "${'$'}__omni_uv_target"
              ;;
            *)
              printf '%s/%s\n' "${'$'}PWD" "${'$'}__omni_uv_target"
              ;;
          esac
        }

        __omni_path_is_under_workspace() {
          __omni_candidate="${'$'}1"
          case "${'$'}__omni_candidate" in
            "${'$'}__omni_workspace_root"|${'$'}__omni_workspace_root/*) return 0 ;;
            *) return 1 ;;
          esac
        }

        uv() {
          __omni_uv_root=$(__omni_find_uv_workspace_root 2>/dev/null)
          if [ "${'$'}?" -ne 0 ] || [ -z "${'$'}__omni_uv_root" ]; then
            command uv "${'$'}@"
            return ${'$'}?
          fi

          __omni_cleanup_invalid_virtualenv "${'$'}__omni_uv_root/.venv" || return ${'$'}?
          __omni_uv_env_dir=$(__omni_uv_env_dir_for_root "${'$'}__omni_uv_root") || return ${'$'}?
          __omni_uv_rc=0

          if [ "${'$'}1" = "venv" ]; then
            shift
            __omni_uv_has_target=0
            __omni_uv_target_path=""
            for __omni_uv_arg in "${'$'}@"; do
              case "${'$'}__omni_uv_arg" in
                -*) ;;
                *)
                  __omni_uv_has_target=1
                  __omni_uv_target_path="${'$'}__omni_uv_arg"
                  break
                  ;;
              esac
            done
            if [ "${'$'}__omni_uv_has_target" = "1" ]; then
              __omni_uv_target_abs=$(__omni_uv_resolve_target_path "${'$'}__omni_uv_target_path")
              if __omni_path_is_under_workspace "${'$'}__omni_uv_target_abs"; then
                command uv venv --link-mode copy "${'$'}@"
              else
                command uv venv "${'$'}@"
              fi
              __omni_uv_rc="${'$'}?"
              if [ "${'$'}__omni_uv_rc" -eq 0 ] && [ -f "${'$'}__omni_uv_target_abs/bin/activate" ]; then
                __omni_activate_virtualenv "${'$'}__omni_uv_target_abs" || return ${'$'}?
              fi
            else
              command uv venv --link-mode copy "${'$'}@" "${'$'}__omni_uv_env_dir"
              __omni_uv_rc="${'$'}?"
            fi
          else
            UV_PROJECT_ENVIRONMENT="${'$'}__omni_uv_env_dir" command uv "${'$'}@"
            __omni_uv_rc="${'$'}?"
          fi

          if [ "${'$'}__omni_uv_rc" -eq 0 ] && [ -f "${'$'}__omni_uv_env_dir/bin/activate" ]; then
            __omni_activate_virtualenv "${'$'}__omni_uv_env_dir" || return ${'$'}?
          fi
          return "${'$'}__omni_uv_rc"
        }

        python() {
          if [ "${'$'}1" = "-m" ] && [ "${'$'}2" = "venv" ]; then
            command python "${'$'}@"
            return ${'$'}?
          fi
          __omni_prepare_python_env 1 || return ${'$'}?
          command python "${'$'}@"
        }

        python3() {
          if [ "${'$'}1" = "-m" ] && [ "${'$'}2" = "venv" ]; then
            command python3 "${'$'}@"
            return ${'$'}?
          fi
          __omni_prepare_python_env 1 || return ${'$'}?
          command python3 "${'$'}@"
        }

        pip() {
          __omni_prepare_python_env 1 || return ${'$'}?
          command python -m pip "${'$'}@"
        }

        pip3() {
          __omni_prepare_python_env 1 || return ${'$'}?
          command python -m pip "${'$'}@"
        }

        pytest() {
          __omni_prepare_python_env 1 || return ${'$'}?
          command python -m pytest "${'$'}@"
        }
    """.trimIndent()

    private fun parsePersistentCommandOutput(
        rawOutput: String,
        token: String
    ): ParsedSessionCommandOutput {
        val regex = Regex("""(?:^|\n)${Regex.escape(SESSION_DONE_PREFIX)}:${Regex.escape(token)}:(-?\d+)(?=\r?\n|$)""")
        val match = regex.find(rawOutput)
        val exitCode = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleaned = if (match != null) {
            val beforeMarker = rawOutput.substring(0, match.range.first).trimEnd('\n', '\r')
            val afterMarker = rawOutput
                .substring(match.range.last + 1)
                .trimStart('\n', '\r')
                .removeLeadingPromptLine()
            listOf(beforeMarker, afterMarker)
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim('\n', '\r')
        } else {
            rawOutput.trim('\n', '\r')
        }
        return ParsedSessionCommandOutput(
            output = cleaned,
            exitCode = exitCode
        )
    }

    private fun extractTerminalOutputDelta(
        previousVisibleOutput: String,
        currentVisibleOutput: String
    ): String {
        if (currentVisibleOutput.isEmpty() || currentVisibleOutput == previousVisibleOutput) {
            return ""
        }
        if (previousVisibleOutput.isEmpty()) {
            return currentVisibleOutput
        }
        if (currentVisibleOutput.startsWith(previousVisibleOutput)) {
            return currentVisibleOutput.substring(previousVisibleOutput.length)
        }
        val commonPrefixLength = previousVisibleOutput
            .commonPrefixWith(currentVisibleOutput)
            .length
        return currentVisibleOutput.safeSubstring(commonPrefixLength)
    }

    private fun buildExecutorKey(workingDirectory: String?): String {
        val normalized = workingDirectory?.trim().orEmpty()
        return if (normalized.isBlank()) {
            "embedded-default"
        } else {
            "embedded-${normalized.hashCode()}"
        }
    }

    private fun terminalManager(context: Context): TerminalManager {
        return TerminalManager.getInstance(context.applicationContext)
    }

    private fun buildTranscript(sessionTranscript: String): String {
        return trimTerminalOutput(
            sanitizeTerminalNoise(sessionTranscript)
        )
    }

    private fun normalizeCurrentDirectory(prompt: String): String {
        val cleaned = prompt.trim().replace(Regex("""\s+[#$]\s*$"""), "")
        return if (cleaned.isBlank() || cleaned == "$") {
            DEFAULT_CURRENT_DIRECTORY
        } else {
            cleaned
        }
    }

    private fun isBasePackagesReady(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(basePackageVersionKey(), 0) >= BASE_PACKAGE_VERSION
    }

    private fun markBasePackagesReady(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(basePackageVersionKey(), BASE_PACKAGE_VERSION)
            .apply()
    }

    private fun basePackageVersionKey(): String {
        return "${KEY_BASE_PACKAGE_VERSION}_${TerminalDistribution.selected().id}"
    }

    private suspend fun ensureRuntimeEnvironmentReady(
        context: Context,
        onProgress: suspend (EnvironmentProgress) -> Unit = {}
    ): EnvironmentStatus {
        return prepareEnvironment(
            context = context,
            installBasePackages = false,
            onProgress = onProgress
        )
    }

    private suspend fun probeBasePackageCommands(context: Context): BasePackageProbeResult {
        val result = terminalManager(context).executeHiddenCommand(
            command = buildBasePackageProbeCommand(),
            executorKey = "embedded-healthcheck",
            timeoutMs = 60 * 1000L
        )
        if (!result.isOk || result.exitCode != 0) {
            val output = sanitizeTerminalNoise(result.output).takeLast(1200).trim()
            val fallback = result.rawOutputPreview.takeLast(1200).trim()
            val details = when {
                output.isNotBlank() -> output
                fallback.isNotBlank() -> fallback
                result.error.isNotBlank() -> result.error
                else -> "未知错误"
            }
            return BasePackageProbeResult(
                errorMessage = "基础 Agent CLI 包检查失败：$details"
            )
        }

        val outputLines = sanitizeTerminalNoise(result.output)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val missingLine = outputLines.lastOrNull { line ->
            line.startsWith(BASE_PACKAGE_MISSING_MARKER)
        }
        val nodeVersionLine = outputLines.lastOrNull { line ->
            line.startsWith(BASE_PACKAGE_NODE_VERSION_MARKER)
        }
        val pnpmVersionLine = outputLines.lastOrNull { line ->
            line.startsWith(BASE_PACKAGE_PNPM_VERSION_MARKER)
        }
        val nodeVersion = nodeVersionLine
            ?.removePrefix(BASE_PACKAGE_NODE_VERSION_MARKER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "missing" }
            ?.removePrefix("v")
        val nodeMajor = nodeVersion
            ?.substringBefore('.')
            ?.toIntOrNull()
        val nodeReady = nodeMajor != null && nodeMajor >= NODE_MIN_MAJOR
        val pnpmVersion = pnpmVersionLine
            ?.removePrefix(BASE_PACKAGE_PNPM_VERSION_MARKER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "missing" }
        val pnpmReady = !pnpmVersion.isNullOrBlank()

        if (missingLine != null) {
            val missing = missingLine
                .removePrefix(BASE_PACKAGE_MISSING_MARKER)
                .trim()
                .split(Regex("""\s+"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            return BasePackageProbeResult(
                missingCommands = missing,
                nodeReady = nodeReady,
                nodeVersion = nodeVersion,
                nodeMajor = nodeMajor,
                pnpmReady = pnpmReady,
                pnpmVersion = pnpmVersion
            )
        }
        if (outputLines.any { line -> line == BASE_PACKAGE_READY_MARKER }) {
            return BasePackageProbeResult(
                missingCommands = emptyList(),
                nodeReady = nodeReady,
                nodeVersion = nodeVersion,
                nodeMajor = nodeMajor,
                pnpmReady = pnpmReady,
                pnpmVersion = pnpmVersion
            )
        }
        return BasePackageProbeResult(
            errorMessage = "基础 Agent CLI 包检查失败：探测结果无法解析。",
            nodeReady = nodeReady,
            nodeVersion = nodeVersion,
            nodeMajor = nodeMajor,
            pnpmReady = pnpmReady,
            pnpmVersion = pnpmVersion
        )
    }

    private fun buildBasePackageProbeCommand(): String {
        val commands = requiredCliCommands.joinToString(" ")
        return """
            export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
            missing=""
            for cmd in $commands; do
              if ! command -v "${'$'}cmd" >/dev/null 2>&1; then
                missing="${'$'}missing ${'$'}cmd"
              fi
            done
            if [ -n "${'$'}missing" ]; then
              echo "$BASE_PACKAGE_MISSING_MARKER ${'$'}{missing# }"
            else
              echo "$BASE_PACKAGE_READY_MARKER"
            fi
            node_version=""
            if command -v node >/dev/null 2>&1; then
              node_version=$(node -v 2>/dev/null | head -n 1 | tr -d '\r')
            fi
            if [ -z "${'$'}node_version" ]; then
              node_version="missing"
            fi
            echo "$BASE_PACKAGE_NODE_VERSION_MARKER ${'$'}node_version"

            pnpm_version=""
            if command -v pnpm >/dev/null 2>&1; then
              pnpm_version=$(pnpm -v 2>/dev/null | head -n 1 | tr -d '\r')
            fi
            if [ -z "${'$'}pnpm_version" ] && command -v corepack >/dev/null 2>&1; then
              pnpm_version=$(corepack pnpm -v 2>/dev/null | head -n 1 | tr -d '\r')
            fi
            if [ -z "${'$'}pnpm_version" ]; then
              pnpm_version="missing"
            fi
            echo "$BASE_PACKAGE_PNPM_VERSION_MARKER ${'$'}pnpm_version"
        """.trimIndent()
    }

    private fun buildInstallFailureMessage(result: HiddenExecResult): String {
        val output = sanitizeTerminalNoise(result.output).takeLast(1200).trim()
        val details = when {
            output.isNotBlank() -> output
            result.rawOutputPreview.isNotBlank() -> result.rawOutputPreview.takeLast(1200)
            result.error.isNotBlank() -> result.error
            else -> "未知错误"
        }
        return "内嵌终端环境已初始化，但基础 Agent CLI 包安装失败：$details"
    }

    private fun buildMissingBasePackageFailureMessage(missingCommands: List<String>): String {
        if (missingCommands.isEmpty()) {
            return "内嵌终端环境已初始化，但基础 Agent CLI 包安装后仍未通过校验。"
        }
        return "内嵌终端环境已初始化，但基础 Agent CLI 包仍缺失：${missingCommands.joinToString(", ")}"
    }

    private fun shouldSuppressTerminalLine(line: String): Boolean {
        val normalized = ansiEscapeRegex.replace(line, "").trim()
        if (normalized.isEmpty()) {
            return false
        }
        return knownNoiseRegexes.any { regex -> regex.matches(normalized) }
    }

    fun sanitizeTerminalNoise(text: String): String {
        if (text.isBlank()) {
            return text
        }
        val filtered = text.lineSequence()
            .filterNot { line -> shouldSuppressTerminalLine(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
        return filtered.trim('\n')
    }

    /**
     * Terminal output is already bounded by the underlying process/transport.
     * Do not apply a second application-defined character or line quota here:
     * tool results and persisted transcripts must retain the actual output.
     */
    fun trimTerminalOutput(text: String): String = text

    private suspend fun emitEnvironmentProgress(
        onProgress: suspend (EnvironmentProgress) -> Unit,
        progress: EnvironmentProgress
    ) {
        try {
            onProgress(progress)
        } catch (_: Exception) {
        }
    }

    private data class ParsedSessionCommandOutput(
        val output: String,
        val exitCode: Int?
    )

    private fun String.safeSubstring(startIndex: Int): String {
        if (startIndex <= 0) return this
        if (startIndex >= length) return ""
        return substring(startIndex)
    }

    private fun String.removeLeadingPromptLine(): String {
        if (isBlank()) {
            return trim('\n', '\r')
        }
        val firstLine = lineSequence().firstOrNull()?.trimEnd().orEmpty()
        if (!shellPromptRegex.matches(firstLine)) {
            return trim('\n', '\r')
        }
        val dropped = lines().drop(1).joinToString("\n")
        return dropped.trim('\n', '\r')
    }
}
