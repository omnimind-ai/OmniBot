package com.ai.assistance.operit.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.rk.libcommons.ContainerBackends
import com.rk.libcommons.OmnibotTerminalEnvironment
import com.rk.libcommons.ShellArgv
import com.rk.libcommons.ShellAssetWriter
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localLibDir
import com.rk.settings.Settings
import com.rk.terminal.App
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller.RuntimeInstallProgress
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.RootProbe
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TerminalManager private constructor(
    private val context: Context
) {
    private data class PendingCommand(
        val commandId: String,
        val transcriptStart: Int
    )

    private data class ManagedSession(
        val sessionId: String,
        val terminalSession: TerminalSession,
        val mutex: Mutex = Mutex(),
        var data: TerminalSessionData,
        var pendingCommand: PendingCommand? = null
    )

    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sessionsById = ConcurrentHashMap<String, ManagedSession>()
    private val _terminalState = MutableStateFlow(TerminalState())
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>(extraBufferCapacity = 64)
    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>(extraBufferCapacity = 32)
    private var preferredTerminalTypeOverride: TerminalType? = null

    // hidden exec 外层进程 → executorKey：手动停止（bindStopAction 只拿到 Process）时
    // 据此找回 executorKey 做 chroot 进程组回收；进程结束即注销，身份比较用 IdentityHashMap
    private val hiddenExecKeysByProcess =
        Collections.synchronizedMap(IdentityHashMap<Process, String>())

    val terminalState: StateFlow<TerminalState> = _terminalState.asStateFlow()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "/root" }

    companion object {
        private const val TAG = "EmbeddedTerminalManager"
        private const val DEFAULT_COLUMNS = 120
        private const val DEFAULT_ROWS = 40
        private const val DEFAULT_CELL_WIDTH = 10
        private const val DEFAULT_CELL_HEIGHT = 20
        private const val SESSION_DONE_PREFIX = "__OMNIBOT_SESSION_DONE__:"

        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    suspend fun initializeEnvironment(
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
        onProgress: suspend (RuntimeInstallProgress) -> Unit = {}
    ): Boolean {
        val status = EmbeddedRuntimeInstaller.ensureRuntimeInstalled(
            context = context,
            distribution = distribution,
            onProgress = onProgress
        )
        if (!status.success) {
            return false
        }
        return runCatching {
            ensureShellScripts()
            true
        }.getOrElse {
            Log.e(TAG, "Failed to prepare shell scripts", it)
            false
        }
    }

    fun prepareForMaintenance() {
        coroutineScope.launch {
            initializeEnvironment()
        }
    }

    fun setPreferredTerminalType(terminalType: TerminalType?) {
        preferredTerminalTypeOverride = terminalType
    }

    fun getPreferredTerminalType(): TerminalType? = preferredTerminalTypeOverride

    suspend fun createNewSession(title: String? = null): TerminalSessionData {
        return createNewSession(title = title, terminalType = TerminalType.LOCAL)
    }

    suspend fun createNewSession(
        title: String? = null,
        terminalType: TerminalType
    ): TerminalSessionData {
        require(terminalType == TerminalType.LOCAL) { "Only local terminal environment sessions are supported." }
        check(initializeEnvironment()) { "Terminal environment is not ready." }

        val sessionId = UUID.randomUUID().toString()
        val sessionTitle = title?.trim().orEmpty().ifBlank { "Session" }
        val client = ManagedSessionClient(context.applicationContext) { handle ->
            handleSessionChanged(handle)
        }

        val terminalSession = withContext(Dispatchers.Main) {
            val shell = ShellArgv.SYSTEM_SH
            val args = ShellArgv.buildShellScriptArgv(ensureShellScripts().absolutePath)
            Log.d(TAG, "Creating local session ${ShellArgv.formatExecSpec(shell, args, "/")}")
            TerminalSession(
                shell,
                "/",
                args,
                buildSessionEnvironment(sessionId),
                TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
                client
            ).also { session ->
                session.mSessionName = sessionTitle
                session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT)
            }
        }

        val data = TerminalSessionData(
            id = sessionId,
            title = sessionTitle,
            terminalType = TerminalType.LOCAL,
            terminalSession = terminalSession,
            currentDirectory = "/root",
            initState = SessionInitState.READY,
            transcript = ""
        )
        val handle = ManagedSession(
            sessionId = sessionId,
            terminalSession = terminalSession,
            data = data
        )
        client.attachHandle(handle)
        sessionsById[sessionId] = handle
        publishState(
            sessions = _terminalState.value.sessions + data,
            currentSessionId = _terminalState.value.currentSessionId ?: sessionId
        )
        return data
    }

    fun switchToSession(sessionId: String) {
        if (!sessionsById.containsKey(sessionId)) return
        publishState(currentSessionId = sessionId)
    }

    fun closeSession(sessionId: String) {
        val removed = sessionsById.remove(sessionId) ?: return
        removed.terminalSession.finishIfRunning()
        val remaining = _terminalState.value.sessions.filterNot { it.id == sessionId }
        val nextCurrent = when {
            _terminalState.value.currentSessionId != sessionId -> _terminalState.value.currentSessionId
            remaining.isEmpty() -> null
            else -> remaining.first().id
        }
        publishState(sessions = remaining, currentSessionId = nextCurrent)
    }

    fun closeAllSessions() {
        sessionsById.keys.toList().forEach(::closeSession)
    }

    suspend fun sendCommandToSession(
        sessionId: String,
        command: String,
        commandId: String? = null
    ): String {
        val handle = sessionsById[sessionId] ?: error("Terminal session not found: $sessionId")
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        handle.mutex.withLock {
            handle.pendingCommand = PendingCommand(
                commandId = actualCommandId,
                transcriptStart = handle.data.transcript.length
            )
            val currentItem = CommandHistoryItem(
                id = actualCommandId,
                prompt = handle.data.currentDirectory,
                command = command,
                output = "",
                isExecuting = true
            )
            handle.data = handle.data.copy(currentExecutingCommand = currentItem)
            publishManagedSession(handle)
            withContext(Dispatchers.Main) {
                val payload = if (command.endsWith("\n")) command else "$command\n"
                handle.terminalSession.write(payload.toByteArray(StandardCharsets.UTF_8), 0, payload.toByteArray(StandardCharsets.UTF_8).size)
            }
        }
        return actualCommandId
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected(),
        onProcessStarted: ((Process) -> Unit)? = null,
        onOutputChunk: suspend (String) -> Unit = {}
    ): HiddenExecResult {
        if (!initializeEnvironment(distribution = distribution)) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_NOT_READY,
                error = "Terminal environment is not ready."
            )
        }

        return withContext(Dispatchers.IO) {
            val uniqueKey = uniqueExecutorKey(executorKey, System.nanoTime())
            val process = runCatching {
                buildHiddenExecProcess(uniqueKey, command, distribution).start()
            }.getOrElse { error ->
                return@withContext HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_START_FAILED,
                    error = error.message ?: "Failed to start terminal environment shell."
                )
            }
            // 先注册再回调：onProcessStarted 内可能立即处理手动停止，
            // destroyHiddenExecCompletely 依赖这里的 executorKey 注册
            hiddenExecKeysByProcess[process] = uniqueKey
            try {
                onProcessStarted?.invoke(process)

                withCancellableHiddenExecProcess(
                    process,
                    onCancellationKill = { killChrootProcessGroup(uniqueKey) }
                ) {
                    coroutineScope {
                        val outputChannel = Channel<String>(Channel.UNLIMITED)
                        val outputBuffer = StringBuilder()
                        val reader = launch {
                            try {
                                process.inputStream.bufferedReader().useLines { lines ->
                                    lines.forEach { line ->
                                        val chunk = "$line\n"
                                        outputBuffer.append(chunk)
                                        outputChannel.trySend(chunk)
                                    }
                                }
                            } catch (error: Throwable) {
                                if (error is CancellationException) {
                                    throw error
                                }
                                if (!isExpectedHiddenExecReaderTermination(error)) {
                                    Log.w(TAG, "Hidden exec reader terminated unexpectedly", error)
                                }
                            } finally {
                                outputChannel.close()
                            }
                        }

                        val forwarder = launch {
                            for (chunk in outputChannel) {
                                onOutputChunk(chunk)
                            }
                        }

                        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                        if (!finished) {
                            // 先整组回收 root 侧进程（chroot 模式），再走统一终止逻辑（开发者审查 P1；融合上游 terminateHiddenExecProcess）
                            killChrootProcessGroup(uniqueKey)
                            terminateHiddenExecProcess(process)
                            reader.join()
                            forwarder.join()
                            return@coroutineScope HiddenExecResult(
                                output = outputBuffer.toString(),
                                exitCode = -1,
                                state = HiddenExecResult.State.TIMEOUT,
                                error = "Command timed out after ${timeoutMs}ms",
                                rawOutputPreview = outputBuffer.toString().takeLast(4000)
                            )
                        }

                        reader.join()
                        forwarder.join()
                        HiddenExecResult(
                            output = outputBuffer.toString(),
                            exitCode = process.exitValue(),
                            state = HiddenExecResult.State.OK,
                            rawOutputPreview = outputBuffer.toString().takeLast(4000)
                        )
                    }
                }
            } finally {
                hiddenExecKeysByProcess.remove(process)
            }
        }
    }

    suspend fun startLongLivedProcess(
        command: String,
        executorKey: String,
        extraEnvironment: Map<String, String> = emptyMap(),
        redirectErrorStream: Boolean = false
    ): Process {
        check(initializeEnvironment()) { "Terminal environment is not ready." }
        return withContext(Dispatchers.IO) {
            buildDistributionProcess(
                executorKey = executorKey,
                command = command,
                redirectErrorStream = redirectErrorStream,
                // 长驻进程（ACP）无交互 tty 需求：强制 headless 让 root 段 setsid + 写 pid 文件，
                // 否则 killChrootProcessGroup 读不到 pgid 空转，close 后 root 进程残留（开发者审查 P1#2）。
                // 放最后覆盖：即使调用方误传 OMNIBOT_HEADLESS 也不许关掉监督
                extraEnvironment = extraEnvironment + mapOf("OMNIBOT_HEADLESS" to "1")
            ).start()
        }
    }

    suspend fun startLongLivedAlpineProcess(
        command: String,
        executorKey: String,
        extraEnvironment: Map<String, String> = emptyMap(),
        redirectErrorStream: Boolean = false,
    ): Process = startLongLivedProcess(
        command = command,
        executorKey = executorKey,
        extraEnvironment = extraEnvironment,
        redirectErrorStream = redirectErrorStream,
    )

    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        val handle = sessionsById[sessionId] ?: return
        handle.data = handle.data.copy(scrollOffsetY = scrollOffset)
        publishManagedSession(handle)
    }

    fun getScrollOffset(sessionId: String): Float {
        return sessionsById[sessionId]?.data?.scrollOffsetY ?: 0f
    }

    fun getTerminalSession(sessionId: String): TerminalSession? {
        return sessionsById[sessionId]?.terminalSession
    }

    private fun buildHiddenExecProcess(
        executorKey: String,
        command: String,
        distribution: TerminalDistribution.Spec
    ): ProcessBuilder {
        return buildDistributionProcess(
            executorKey = executorKey,
            command = command,
            redirectErrorStream = true,
            extraEnvironment = mapOf("OMNIBOT_HEADLESS" to "1"),
            distribution = distribution
        )
    }

    private fun buildDistributionProcess(
        executorKey: String,
        command: String,
        redirectErrorStream: Boolean,
        extraEnvironment: Map<String, String> = emptyMap(),
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected()
    ): ProcessBuilder {
        val initHost = ensureShellScripts()
        val processBuilder = ProcessBuilder(
            "/system/bin/sh",
            initHost.absolutePath,
            "/bin/sh",
            "-lc",
            command
        )
        processBuilder.redirectErrorStream(redirectErrorStream)
        val env = processBuilder.environment()
        buildEnvironmentMap(sessionId = executorKey, distribution = distribution).forEach { (key, value) ->
            env[key] = value
        }
        // 会话标识透传 root 段：进程组回收时据此定位 pid 文件（开发者审查 P1）。
        // 做文件名安全化，避免 executorKey 含 '/' 等破坏 launcher 内的 pid 文件路径
        env["OMNIBOT_EXECUTOR_KEY"] = executorKey.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        extraEnvironment.forEach { (key, value) ->
            if (key.isNotBlank()) {
                env[key] = value
            }
        }
        return processBuilder
    }

    private fun buildSessionEnvironment(sessionId: String): Array<String> {
        return buildEnvironmentMap(sessionId).map { "${it.key}=${it.value}" }.toTypedArray()
    }

    private fun buildEnvironmentMap(
        sessionId: String,
        distribution: TerminalDistribution.Spec = TerminalDistribution.selected()
    ): Map<String, String> {
        val filesParent = context.filesDir.parentFile ?: context.filesDir
        val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
        val hostWorkspaceDir = AgentWorkspaceManager.rootDirectory(context).apply { mkdirs() }
        val env = linkedMapOf(
            "PATH" to "${System.getenv("PATH") ?: ""}:/sbin:${localBinDir().absolutePath}",
            "HOME" to "/root",
            "COLORTERM" to "truecolor",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "BIN" to localBinDir().absolutePath,
            "PREFIX" to filesParent.absolutePath,
            "LD_LIBRARY_PATH" to localLibDir().absolutePath,
            "LINKER" to linker,
            "NATIVE_LIB_DIR" to context.applicationInfo.nativeLibraryDir,
            "PKG" to context.packageName,
            "PKG_PATH" to context.applicationInfo.sourceDir,
            "OMNIBOT_HOST_WORKSPACE" to hostWorkspaceDir.absolutePath,
            "OMNIBOT_TERMINAL_DISTRIBUTION" to distribution.id,
            "OMNIBOT_ALPINE_APK_REPOSITORY_BASE" to AlpineRepositoryManager.selectedBaseUrl(),
            "OMNIBOT_UBUNTU_APT_REPOSITORY_BASE" to UbuntuRepositoryManager.selectedBaseUrl(),
            "PROOT_TMP_DIR" to App.getTempDir().resolve(sessionId).apply { mkdirs() }.absolutePath,
            "TMPDIR" to App.getTempDir().absolutePath
        )
        if (File(context.applicationInfo.nativeLibraryDir).resolve("libproot-loader32.so").exists()) {
            env["PROOT_LOADER32"] = "${context.applicationInfo.nativeLibraryDir}/libproot-loader32.so"
            env["PROOT_LOADER_32"] = "${context.applicationInfo.nativeLibraryDir}/libproot-loader32.so"
        }
        if (File(context.applicationInfo.nativeLibraryDir).resolve("libproot-loader.so").exists()) {
            env["PROOT_LOADER"] = "${context.applicationInfo.nativeLibraryDir}/libproot-loader.so"
        }
        env.putAll(OmnibotTerminalEnvironment.buildTerminalEnvironment(context))
        env.remove("PROOT_NO_SECCOMP")
        env.remove("SECCOMP")
        if (Settings.seccomp) {
            env["SECCOMP"] = "1"
        }
        return env
    }

    private fun ensureShellScripts(): File {
        // Agent 链路读独立开关：终端 UI 开 chroot 不自动放行 Agent 提权（开发者审查 P1#1）。
        // 且每次启动经 RootProbe 重检实际 uid/能力，授权撤销则回退 proot 并改写持久化设置（P2#6）
        val backend = RootProbe.resolveAgentBackendBlocking()
        val initHost = localBinDir().resolve(ContainerBackends.initHostFileName(backend))
        ShellAssetWriter.writeExecutableShellAsset(context, ContainerBackends.initHostAsset(backend), initHost)
        if (ContainerBackends.isChroot(backend)) {
            ShellAssetWriter.writeExecutableShellAsset(
                context,
                ContainerBackends.INIT_HOST_CHROOT_ROOT_ASSET,
                localBinDir().resolve(ContainerBackends.INIT_HOST_CHROOT_ROOT_FILE)
            )
            // 同时落盘 proot 版启动脚本：chroot 脚本在 su 不可用时会自动回退 proot
            ShellAssetWriter.writeExecutableShellAsset(
                context,
                ContainerBackends.INIT_HOST_ASSET,
                localBinDir().resolve(ContainerBackends.initHostFileName(ContainerBackends.PROOT))
            )
        }

        val init = localBinDir().resolve("init")
        ShellAssetWriter.writeExecutableShellAsset(context, "init.sh", init)
        return initHost
    }

    /**
     * 手动停止入口（terminal_execute 的 bindStopAction 只拿得到外层 Process）：
     * 先按注册表找回 executorKey 整组回收 root 侧子孙，再强杀外层进程。
     * proot 模式下 killChrootProcessGroup 首行即返回，零开销。
     */
    fun destroyHiddenExecCompletely(process: Process) {
        val executorKey = hiddenExecKeysByProcess[process]
        if (executorKey != null) {
            killChrootProcessGroup(executorKey)
        } else {
            Log.d(TAG, "destroyHiddenExecCompletely: no executorKey registered for process")
        }
        runCatching { process.destroyForcibly() }
    }

    /**
     * 进程组回收：chroot 模式下 root 段 setsid 并把 pgid 写入 pid 文件，
     * 停止/超时/关闭时经 su 显式整组 kill，避免 root 命令子进程在工具报告停止后残留
     * （开发者审查 P1：destroyForcibly 只杀外层 sh/su 客户端，daemon 侧 root 命令会成孤儿）。
     */
    fun killChrootProcessGroup(executorKey: String) {
        if (Settings.agent_container_backend != ContainerBackends.CHROOT) return
        val safeKey = ChrootProcessReaper.sanitizeExecutorKey(executorKey)
        val pidFile = File(context.filesDir.parentFile, "local/run/chroot-$safeKey.pid")
        val pgid = ChrootProcessReaper.readPgidFromFile(pidFile)
        if (pgid == null) {
            Log.w(TAG, "chroot pid file missing or unread: ${pidFile.absolutePath}")
            return
        }
        val finished = runCatching {
            ProcessBuilder("su", "-c", "kill -KILL -- -$pgid")
                .start()
                .waitFor(3, TimeUnit.SECONDS)
        }.onFailure { error ->
            Log.w(TAG, "killpg -$pgid failed", error)
        }.getOrDefault(false)
        if (!finished) {
            Log.w(TAG, "killpg -$pgid timed out; outer destroyForcibly will still run")
        }
    }

    private fun handleSessionChanged(handle: ManagedSession) {
        val currentTranscript = handle.terminalSession.getTranscriptText()
        val currentDirectory = handle.terminalSession.getCwd() ?: handle.data.currentDirectory
        val currentCommand = handle.data.currentExecutingCommand
        val previousTranscript = handle.data.transcript

        if (currentCommand != null) {
            val outputSinceCommand = currentTranscript.safeSubstring(handle.pendingCommand?.transcriptStart ?: 0)
            currentCommand.setOutput(outputSinceCommand)
            if (outputSinceCommand.contains(SESSION_DONE_PREFIX)) {
                currentCommand.setExecuting(false)
                handle.pendingCommand?.let { pending ->
                    coroutineScope.launch {
                        _commandExecutionEvents.emit(
                            CommandExecutionEvent(
                                commandId = pending.commandId,
                                sessionId = handle.sessionId,
                                outputChunk = outputSinceCommand,
                                isCompleted = true
                            )
                        )
                    }
                }
                handle.pendingCommand = null
            }
        }

        handle.data = handle.data.copy(
            currentDirectory = currentDirectory,
            transcript = currentTranscript,
            currentExecutingCommand = currentCommand
        )
        publishManagedSession(handle)
        if (currentDirectory != handle.data.currentDirectory || currentTranscript != previousTranscript) {
            coroutineScope.launch {
                _directoryChangeEvents.emit(
                    SessionDirectoryEvent(
                        sessionId = handle.sessionId,
                        currentDirectory = currentDirectory
                    )
                )
            }
        }
    }

    private fun publishManagedSession(handle: ManagedSession) {
        val sessions = _terminalState.value.sessions.map { session ->
            if (session.id == handle.sessionId) handle.data else session
        }
        publishState(sessions = sessions)
    }

    private fun publishState(
        sessions: List<TerminalSessionData> = _terminalState.value.sessions,
        currentSessionId: String? = _terminalState.value.currentSessionId
    ) {
        _terminalState.value = _terminalState.value.copy(
            sessions = sessions,
            currentSessionId = currentSessionId
        )
    }

    private fun String.safeSubstring(startIndex: Int): String {
        if (startIndex <= 0) return this
        if (startIndex >= length) return ""
        return substring(startIndex)
    }

    private class ManagedSessionClient(
        private val context: Context,
        private val onSessionChanged: (ManagedSession) -> Unit
    ) : TerminalSessionClient {
        private var handle: ManagedSession? = null

        fun attachHandle(value: ManagedSession) {
            handle = value
        }

        override fun onTextChanged(changedSession: TerminalSession) {
            handle?.let(onSessionChanged)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            handle?.let(onSessionChanged)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            handle?.let(onSessionChanged)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) = Unit

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit

        override fun getTerminalCursorStyle(): Int {
            return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE
        }

        override fun logError(tag: String, message: String) {
            Log.e(tag, message)
        }

        override fun logWarn(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun logInfo(tag: String, message: String) {
            Log.i(tag, message)
        }

        override fun logDebug(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun logVerbose(tag: String, message: String) {
            Log.v(tag, message)
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }

        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, e.message, e)
        }
    }
}

internal suspend fun <T> withCancellableHiddenExecProcess(
    process: Process,
    onCancellationKill: () -> Unit = {},
    block: suspend () -> T
): T = coroutineScope {
    val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            // 协程取消（手动停止/作用域关闭）也要先整组回收 root 侧子孙：
            // 只 destroy 外层 sh/su 客户端会让 chroot 内命令成孤儿（开发者审查 P1）
            onCancellationKill()
            terminateHiddenExecProcess(process)
        }
    }
    try {
        block()
    } finally {
        cancellationWatcher.cancel()
    }
}

internal fun terminateHiddenExecProcess(process: Process) {
    runCatching { process.outputStream.close() }
    runCatching { process.destroy() }
    val exited = runCatching {
        process.waitFor(HIDDEN_EXEC_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
    if (!exited) {
        runCatching { process.destroyForcibly() }
        runCatching {
            process.waitFor(HIDDEN_EXEC_TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
}

private const val HIDDEN_EXEC_TERMINATION_GRACE_MS = 500L

/**
 * 每次启动追加非零后缀：并发同命令共享同一 executor key 会互相清空 pid 文件
 * （真机 14:56 抓到两路 embedded-462664006 并发），导致停止时 killpg 失联、root 进程残留。
 */
internal fun uniqueExecutorKey(base: String, nonce: Long): String = "$base-$nonce"

internal fun isExpectedHiddenExecReaderTermination(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        when (current) {
            is InterruptedIOException -> return true
            is IOException -> {
                val message = current.message.orEmpty().lowercase()
                if (
                    "interrupted by close" in message ||
                    "stream closed" in message ||
                    "socket closed" in message ||
                    "read interrupted" in message
                ) {
                    return true
                }
            }
        }
        current = current.cause
    }
    return false
}
