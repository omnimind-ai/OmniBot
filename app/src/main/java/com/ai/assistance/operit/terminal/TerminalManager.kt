package com.ai.assistance.operit.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.rk.libcommons.OmnibotTerminalEnvironment
import com.rk.libcommons.ShellArgv
import com.rk.libcommons.ShellAssetWriter
import com.rk.libcommons.localBinDir
import com.rk.libcommons.localLibDir
import com.rk.settings.Settings
import com.rk.terminal.App
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.rk.terminal.runtime.AlpineRepositoryManager
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
        onProgress: suspend (String) -> Unit = {}
    ): Boolean {
        val status = EmbeddedRuntimeInstaller.ensureRuntimeInstalled(context, onProgress)
        if (!status.success) {
            return false
        }
        return runCatching {
            ensureShellScripts()
            true
        }.getOrElse {
            if (BuildConfig.DEBUG) {
                OmniLog.e(TAG, "Failed to prepare shell scripts", it)
            } else {
                OmniLog.e(TAG, "Failed to prepare shell scripts type=${it.javaClass.simpleName}")
            }
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
            OmniLog.d(TAG, "Creating local terminal session")
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
        onProcessStarted: ((Process) -> Unit)? = null,
        onOutputChunk: suspend (String) -> Unit = {}
    ): HiddenExecResult {
        if (!initializeEnvironment()) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_NOT_READY,
                error = "Terminal environment is not ready."
            )
        }

        return withContext(Dispatchers.IO) {
            val process = runCatching {
                buildHiddenExecProcess(executorKey, command).start()
            }.getOrElse { error ->
                return@withContext HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.SHELL_START_FAILED,
                    error = error.message ?: "Failed to start terminal environment shell."
                )
            }
            onProcessStarted?.invoke(process)

            kotlinx.coroutines.coroutineScope {
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
                            if (BuildConfig.DEBUG) {
                                OmniLog.w(TAG, "Hidden exec reader terminated unexpectedly", error)
                            } else {
                                OmniLog.w(
                                    TAG,
                                    "Hidden exec reader stopped type=${error.javaClass.simpleName}",
                                )
                            }
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
                    runCatching { process.inputStream.close() }
                    process.destroyForcibly()
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
    }

    suspend fun startLongLivedAlpineProcess(
        command: String,
        executorKey: String,
        extraEnvironment: Map<String, String> = emptyMap(),
        redirectErrorStream: Boolean = false
    ): Process {
        check(initializeEnvironment()) { "Terminal environment is not ready." }
        return withContext(Dispatchers.IO) {
            buildAlpineProcess(
                executorKey = executorKey,
                command = command,
                redirectErrorStream = redirectErrorStream,
                extraEnvironment = extraEnvironment
            ).start()
        }
    }

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

    private fun buildHiddenExecProcess(executorKey: String, command: String): ProcessBuilder {
        return buildAlpineProcess(
            executorKey = executorKey,
            command = command,
            redirectErrorStream = true,
            extraEnvironment = mapOf("OMNIBOT_HEADLESS" to "1")
        )
    }

    private fun buildAlpineProcess(
        executorKey: String,
        command: String,
        redirectErrorStream: Boolean,
        extraEnvironment: Map<String, String> = emptyMap()
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
        buildEnvironmentMap(sessionId = executorKey).forEach { (key, value) ->
            env[key] = value
        }
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

    private fun buildEnvironmentMap(sessionId: String): Map<String, String> {
        val distribution = TerminalDistribution.selected()
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
        val initHost = localBinDir().resolve("init-host")
        ShellAssetWriter.writeExecutableShellAsset(context, "init-host.sh", initHost)

        val init = localBinDir().resolve("init")
        ShellAssetWriter.writeExecutableShellAsset(context, "init.sh", init)
        return initHost
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
            if (BuildConfig.DEBUG) OmniLog.e(tag, message)
            else OmniLog.e(tag, "Terminal component error messageChars=${message.length}")
        }

        override fun logWarn(tag: String, message: String) {
            if (BuildConfig.DEBUG) OmniLog.w(tag, message)
            else OmniLog.w(tag, "Terminal component warning messageChars=${message.length}")
        }

        override fun logInfo(tag: String, message: String) {
            if (BuildConfig.DEBUG) OmniLog.i(tag, message)
        }

        override fun logDebug(tag: String, message: String) {
            if (BuildConfig.DEBUG) OmniLog.d(tag, message)
        }

        override fun logVerbose(tag: String, message: String) {
            if (BuildConfig.DEBUG) OmniLog.v(tag, message)
        }

        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            if (BuildConfig.DEBUG) OmniLog.e(tag, message, e)
            else OmniLog.e(tag, "Terminal exception type=${e.javaClass.simpleName}")
        }

        override fun logStackTrace(tag: String, e: Exception) {
            if (BuildConfig.DEBUG) OmniLog.e(tag, e.message.orEmpty(), e)
            else OmniLog.e(tag, "Terminal exception type=${e.javaClass.simpleName}")
        }
    }
}

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
