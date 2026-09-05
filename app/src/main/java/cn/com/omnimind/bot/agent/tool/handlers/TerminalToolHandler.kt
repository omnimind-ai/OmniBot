package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.terminal.EmbeddedTerminalSessionRegistry
import cn.com.omnimind.bot.termux.TermuxCommandResult
import cn.com.omnimind.bot.termux.TermuxCommandSpec
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

class TerminalToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
    private val scope: kotlinx.coroutines.CoroutineScope
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "terminal_execute", "bash", "terminal_session_start", "terminal_session_exec",
        "terminal_session_read", "terminal_session_stop"
    )

    private val terminalSessionRegistry = EmbeddedTerminalSessionRegistry(helper.context)
    private val terminalEnvKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    data class TerminalExecuteArgs(
        val command: String,
        val executionMode: String,
        val prootDistro: String?,
        val workingDirectory: String?,
        val timeoutSeconds: Int?
    )

    data class TerminalSessionStartArgs(
        val sessionName: String?,
        val workingDirectory: String?
    )

    data class TerminalSessionExecArgs(
        val sessionId: String,
        val command: String,
        val workingDirectory: String?
    )

    data class TerminalSessionReadArgs(
        val sessionId: String
    )

    data class DirectTerminalSessionSnapshot(
        val sessionId: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean
    )

    data class DirectTerminalCommandResult(
        val sessionId: String,
        val completed: Boolean,
        val timedOut: Boolean,
        val output: String,
        val transcript: String,
        val currentDirectory: String,
        val commandRunning: Boolean,
        val errorMessage: String? = null
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "terminal_execute", "bash" -> executeTerminalTool(
                args,
                env.workspaceDescriptor,
                env.terminalEnvironment,
                callback,
                toolHandle,
            )
            "terminal_session_start" -> executeTerminalSessionStart(args, env.workspaceDescriptor, env.terminalEnvironment, callback)
            "terminal_session_exec" -> executeTerminalSessionExec(args, env.workspaceDescriptor, env.terminalEnvironment, callback, toolHandle)
            "terminal_session_read" -> executeTerminalSessionRead(args, env.workspaceDescriptor, callback)
            "terminal_session_stop" -> executeTerminalSessionStop(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(
                toolCall.function.name,
                "Unknown ${helper.terminalDistribution.displayName} tool"
            )
        }
    }

    override suspend fun dispose() {
        closeOwnedDirectTerminalSessions()
    }

    private suspend fun executeTerminalTool(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "terminal_execute"
        return try {
            var runningProcess: Process? = null
            toolHandle.bindStopAction {
                runCatching { runningProcess?.destroyForcibly() }
            }
            helper.reportToolProgress(
                callback,
                toolName,
                "正在调用内嵌终端环境执行命令",
                mapOf(
                    "summary" to "正在调用内嵌终端环境执行命令",
                    "terminalStreamState" to "starting"
                ),
                toolHandle = toolHandle
            )
            val rawArgs = parseTerminalExecuteArgs(args)
            val parsedArgs = rawArgs.copy(
                workingDirectory = rawArgs.workingDirectory
                    ?.let { workspaceManager.resolveShellPath(it, workspace, allowRootDirectories = true) }
                    ?: workspace.currentCwd
            )
            val commandResult = TermuxCommandRunner.execute(
                context = helper.context,
                spec = TermuxCommandSpec(
                    command = parsedArgs.command,
                    executionMode = parsedArgs.executionMode,
                    prootDistro = parsedArgs.prootDistro,
                    workingDirectory = parsedArgs.workingDirectory,
                    timeoutSeconds = parsedArgs.timeoutSeconds,
                    environment = terminalEnvironment
                ),
                onProcessStarted = { process ->
                    runningProcess = process
                    if (toolHandle.isManualStopRequested()) {
                        runCatching { process.destroyForcibly() }
                    }
                },
                onLiveUpdate = { update ->
                    helper.reportToolProgress(
                        callback,
                        toolName,
                        if (update.outputDelta.isBlank()) {
                            "正在调用内嵌终端环境执行命令"
                        } else {
                            "终端输出更新中"
                        },
                        mapOf<String, Any?>(
                            "summary" to if (update.outputDelta.isBlank()) {
                                "正在调用内嵌终端环境执行命令"
                            } else {
                                "终端输出更新中"
                            },
                            "terminalSessionId" to update.sessionId,
                            "terminalOutputDelta" to update.outputDelta,
                            "terminalStreamState" to update.streamState
                        ),
                        toolHandle = toolHandle
                    )
                }
            )
            buildTerminalToolResult(
                toolName = toolName,
                args = parsedArgs,
                result = commandResult,
                workspace = workspace,
                sourceTool = toolName
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errorMessage = helper.localized(e.message ?: "终端命令执行失败")
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = errorMessage,
                previewJson = helper.encodeLocalizedPayload(mapOf("error" to errorMessage)),
                rawResultJson = helper.encodeLocalizedPayload(mapOf("error" to errorMessage)),
                success = false,
                timedOut = false,
                terminalOutput = errorMessage,
                terminalStreamState = "error",
                workspaceId = workspace.id
            )
        }
    }

    private suspend fun executeTerminalSessionStart(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_start"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在启动内嵌终端会话")
            val parsedArgs = parseTerminalSessionStartArgs(args)
            val workingDirectory = resolveShellWorkingDirectory(parsedArgs.workingDirectory, workspace)
            val result = EmbeddedTerminalRuntime.startSession(
                context = helper.context,
                requestedSessionId = null,
                sessionTitle = parsedArgs.sessionName,
                workingDirectory = workingDirectory,
                environment = terminalEnvironment
            )
            val sessionId = result.sessionId
            rememberOwnedTerminalSession(
                workspaceId = workspace.id,
                sessionId = sessionId,
                sessionName = parsedArgs.sessionName
            )
            terminalSessionDirectory(workspace, sessionId).mkdirs()
            val logArtifact = persistTerminalSessionTranscript(workspace, sessionId, result.transcript, toolName)
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "workingDirectory" to workingDirectory,
                "currentDirectory" to result.currentDirectory,
                "success" to true,
                "logPath" to logArtifact.androidPath,
                "logUri" to logArtifact.uri
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = helper.localized("终端会话已启动：$sessionId"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                timedOut = false,
                terminalOutput = "",
                terminalSessionId = sessionId,
                terminalStreamState = "ready",
                artifacts = listOf(logArtifact),
                workspaceId = workspace.id,
                actions = listOf(
                    ArtifactAction(
                        type = "workspace",
                        label = helper.localized("打开工作区"),
                        target = workspace.uriRoot,
                        payload = mapOf(
                            "workspaceId" to workspace.id,
                            "workspacePath" to workspace.androidRootPath,
                            "workspaceShellPath" to workspace.rootPath
                        )
                    )
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "终端会话启动失败")
        }
    }

    private suspend fun executeTerminalSessionExec(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        terminalEnvironment: Map<String, String>,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = "terminal_session_exec"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val parsedArgs = parseTerminalSessionExecArgs(args)
            val sessionId = parsedArgs.sessionId.trim()
            require(isOwnedTerminalSession(workspace.id, sessionId)) { "终端会话不存在或不属于当前 workspace：$sessionId" }
            require(EmbeddedTerminalRuntime.hasSession(helper.context, sessionId)) {
                forgetOwnedTerminalSession(sessionId)
                "终端会话不存在或已结束：$sessionId"
            }
            val shellWorkingDirectory = parsedArgs.workingDirectory?.let {
                resolveShellWorkingDirectory(it, workspace)
            }
            helper.reportToolProgress(
                callback,
                toolName,
                "正在向终端会话发送命令",
                mapOf(
                    "summary" to "正在向终端会话发送命令",
                    "terminalSessionId" to sessionId,
                    "terminalStreamState" to "starting"
                ),
                toolHandle = toolHandle
            )
            toolHandle.bindStopAction {
                EmbeddedTerminalRuntime.stopSession(helper.context, sessionId)
            }
            val result = EmbeddedTerminalRuntime.executeSessionCommand(
                context = helper.context,
                sessionId = sessionId,
                command = parsedArgs.command,
                workingDirectory = shellWorkingDirectory,
                environment = terminalEnvironment,
                onLiveUpdate = { update ->
                    val summary = update.summary.ifBlank { "终端输出更新中" }
                    helper.reportToolProgress(
                        callback,
                        toolName,
                        summary,
                        mapOf<String, Any?>(
                            "summary" to summary,
                            "terminalSessionId" to update.sessionId,
                            "terminalOutputDelta" to update.outputDelta,
                            "terminalStreamState" to update.streamState
                        ),
                        toolHandle = toolHandle
                    )
                }
            )
            val terminalStreamState = when {
                !result.completed -> "running"
                result.errorMessage != null -> "error"
                else -> "completed"
            }
            val logArtifact = persistTerminalSessionTranscript(workspace, sessionId, result.transcript, toolName)
            val rawResult = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "workingDirectory" to shellWorkingDirectory,
                "currentDirectory" to result.currentDirectory,
                "command" to parsedArgs.command,
                "exitCode" to result.exitCode,
                "completed" to result.completed,
                "timedOut" to result.timedOut,
                "logPath" to logArtifact.workspacePath,
                "androidLogPath" to logArtifact.androidPath,
                "logUri" to logArtifact.uri,
                "stdout" to result.output,
                "terminalOutput" to if (result.completed) result.output else result.transcript,
                "success" to (result.completed && result.success && result.errorMessage == null),
                "errorMessage" to result.errorMessage,
                "terminalStreamState" to terminalStreamState
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = helper.localized(if (!result.completed) {
                    result.errorMessage ?: "会话命令仍在运行，请先读取输出确认状态"
                } else if (result.errorMessage == null && result.success) {
                    "会话命令执行完成"
                } else {
                    result.errorMessage ?: "会话命令执行失败"
                }),
                previewJson = helper.encodeLocalizedPayload(rawResult),
                rawResultJson = helper.encodeLocalizedPayload(rawResult),
                success = result.completed && result.success && result.errorMessage == null,
                timedOut = result.timedOut,
                terminalOutput = if (result.completed) result.output else result.transcript,
                terminalSessionId = sessionId,
                terminalStreamState = terminalStreamState,
                artifacts = listOf(logArtifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "终端会话命令执行失败")
        }
    }

    private suspend fun executeTerminalSessionRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val parsedArgs = parseTerminalSessionReadArgs(args)
            val sessionId = parsedArgs.sessionId.trim()
            require(isOwnedTerminalSession(workspace.id, sessionId)) { "终端会话不存在或不属于当前 workspace：$sessionId" }
            require(EmbeddedTerminalRuntime.hasSession(helper.context, sessionId)) {
                forgetOwnedTerminalSession(sessionId)
                "终端会话不存在或已结束：$sessionId"
            }
            val readResult = EmbeddedTerminalRuntime.readSession(helper.context, sessionId)
            val artifact = persistTerminalSessionTranscript(workspace, sessionId, readResult.transcript, toolName)
            val content = EmbeddedTerminalRuntime.trimTerminalOutput(
                EmbeddedTerminalRuntime.sanitizeTerminalNoise(readResult.transcript)
            )
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "content" to content,
                "contentLength" to content.length,
                "currentDirectory" to readResult.currentDirectory,
                "commandRunning" to readResult.commandRunning,
                "logPath" to artifact.workspacePath,
                "androidLogPath" to artifact.androidPath,
                "logUri" to artifact.uri
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = helper.localized(if (content.isBlank()) "终端会话暂无输出" else "已读取终端会话输出"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                timedOut = false,
                terminalOutput = content,
                terminalSessionId = sessionId,
                terminalStreamState = if (readResult.commandRunning) "running" else "completed",
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取终端会话失败")
        }
    }

    private suspend fun executeTerminalSessionStop(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "terminal_session_stop"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在结束终端会话")
            val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(sessionId.isNotEmpty()) { "缺少 sessionId" }
            val owned = isOwnedTerminalSession(workspace.id, sessionId)
            val result = if (owned) {
                EmbeddedTerminalRuntime.stopSession(helper.context, sessionId)
            } else {
                false
            }
            if (owned) {
                forgetOwnedTerminalSession(sessionId)
            }
            val payload = linkedMapOf<String, Any?>(
                "sessionId" to sessionId,
                "success" to result
            )
            ToolExecutionResult.TerminalResult(
                toolName = toolName,
                summaryText = helper.localized(if (result) "终端会话已结束：$sessionId" else "终端会话不存在或已结束：$sessionId"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result,
                timedOut = false,
                terminalOutput = if (result) "session_stopped:$sessionId" else "session_not_found:$sessionId",
                terminalSessionId = sessionId,
                terminalStreamState = if (result) "stopped" else "error",
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "结束终端会话失败")
        }
    }

    fun rememberOwnedTerminalSession(workspaceId: String, sessionId: String, sessionName: String?) {
        terminalSessionRegistry.rememberSession(workspaceId = workspaceId, sessionId = sessionId, sessionName = sessionName)
    }

    fun isOwnedTerminalSession(workspaceId: String, sessionId: String): Boolean {
        return terminalSessionRegistry.ownsSession(workspaceId = workspaceId, sessionId = sessionId)
    }

    private fun rememberOwnedTerminalSession(sessionId: String) {
        rememberOwnedTerminalSession(SharedHelper.DIRECT_TERMINAL_WORKSPACE_ID, sessionId, null)
    }

    private fun isOwnedTerminalSession(sessionId: String): Boolean {
        return isOwnedTerminalSession(SharedHelper.DIRECT_TERMINAL_WORKSPACE_ID, sessionId)
    }

    fun forgetOwnedTerminalSession(sessionId: String) {
        terminalSessionRegistry.forgetSession(sessionId)
    }

    private suspend fun closeOwnedDirectTerminalSessions() {
        terminalSessionRegistry.listSessionIds(SharedHelper.DIRECT_TERMINAL_WORKSPACE_ID).forEach { sessionId ->
            runCatching { stopDirectTerminalSession(sessionId) }
            forgetOwnedTerminalSession(sessionId)
        }
    }

    suspend fun <T> withLocalTerminalManager(block: suspend (TerminalManager) -> T): T {
        val manager = TerminalManager.getInstance(helper.context)
        val previousType = manager.getPreferredTerminalType()
        manager.setPreferredTerminalType(TerminalType.LOCAL)
        return try {
            block(manager)
        } finally {
            manager.setPreferredTerminalType(previousType)
        }
    }

    private suspend fun executeDirectTerminalCommand(
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int?,
        environment: Map<String, String>,
        onLiveUpdate: suspend (sessionId: String, outputDelta: String, streamState: String) -> Unit = { _, _, _ -> }
    ): TermuxCommandResult {
        val createdSession = withLocalTerminalManager { manager ->
            createLocalTerminalSession(manager, "Agent ${helper.terminalDistribution.displayName}")
        }
        rememberOwnedTerminalSession(createdSession.id)
        onLiveUpdate(createdSession.id, "", "running")

        return try {
            val execution = withLocalTerminalManager { manager ->
                executeDirectCommandInSession(
                    manager = manager,
                    sessionId = createdSession.id,
                    command = buildDirectShellCommand(command, workingDirectory, environment),
                    timeoutSeconds = timeoutSeconds,
                    onLiveOutput = { outputDelta ->
                        onLiveUpdate(createdSession.id, outputDelta, "running")
                    }
                )
            }
            if (!execution.timedOut) {
                stopDirectTerminalSession(createdSession.id)
            }

            val terminalOutput = execution.transcript.ifBlank { execution.output }
            val completedSuccessfully =
                execution.completed && !execution.timedOut && execution.errorMessage.isNullOrBlank()
            TermuxCommandResult(
                success = completedSuccessfully,
                timedOut = execution.timedOut,
                resultCode = null,
                errorCode = null,
                errorMessage = execution.errorMessage,
                stdout = if (completedSuccessfully) execution.output else "",
                stderr = if (completedSuccessfully) "" else execution.output.ifBlank { terminalOutput },
                rawExtras = mapOf(
                    "executionPath" to "terminal_manager_session",
                    "currentDirectory" to execution.currentDirectory
                ),
                terminalOutput = terminalOutput,
                liveSessionId = createdSession.id,
                liveStreamState = when {
                    execution.timedOut || execution.commandRunning -> "running"
                    execution.errorMessage != null -> "error"
                    else -> "completed"
                },
                liveFallbackReason = null
            )
        } catch (error: Exception) {
            val fallbackSnapshot = runCatching {
                withLocalTerminalManager { manager ->
                    captureDirectTerminalSessionSnapshot(manager, createdSession.id)
                }
            }.getOrNull()
            runCatching { stopDirectTerminalSession(createdSession.id) }
            TermuxCommandResult(
                success = false,
                timedOut = false,
                resultCode = null,
                errorCode = null,
                errorMessage = helper.localized(error.message ?: "终端命令执行失败"),
                stdout = "",
                stderr = fallbackSnapshot?.transcript.orEmpty(),
                rawExtras = mapOf("executionPath" to "terminal_manager_session"),
                terminalOutput = fallbackSnapshot?.transcript.orEmpty(),
                liveSessionId = createdSession.id,
                liveStreamState = "error",
                liveFallbackReason = null
            )
        }
    }

    private suspend fun startDirectTerminalSession(
        sessionTitle: String?,
        workingDirectory: String?,
        environment: Map<String, String>
    ): DirectTerminalSessionSnapshot {
        val safeTitle = sanitizeTerminalSessionId(sessionTitle)
        val session = withLocalTerminalManager { manager ->
            createLocalTerminalSession(manager, safeTitle)
        }
        rememberOwnedTerminalSession(session.id)

        return try {
            val setupCommand = buildSessionSetupCommand(workingDirectory = workingDirectory, environment = environment)
            if (setupCommand.isNotBlank()) {
                val setupResult = withLocalTerminalManager { manager ->
                    executeDirectCommandInSession(
                        manager = manager,
                        sessionId = session.id,
                        command = setupCommand,
                        timeoutSeconds = null
                    )
                }
                if (!setupResult.completed || setupResult.timedOut || !setupResult.errorMessage.isNullOrBlank()) {
                    stopDirectTerminalSession(session.id)
                    throw IllegalStateException(
                        helper.localized(setupResult.errorMessage ?: "终端会话初始化超时，可能仍在后台继续运行。")
                    )
                }
            }

            withLocalTerminalManager { manager ->
                captureDirectTerminalSessionSnapshot(manager, session.id)
            }
        } catch (error: Exception) {
            runCatching { stopDirectTerminalSession(session.id) }
            throw error
        }
    }

    private suspend fun executeDirectTerminalSessionCommand(
        sessionId: String,
        command: String,
        workingDirectory: String?,
        timeoutSeconds: Int?,
        environment: Map<String, String>,
        onLiveUpdate: suspend (String) -> Unit = {}
    ): DirectTerminalCommandResult {
        require(sessionId.isNotBlank()) { "缺少 sessionId" }
        require(isOwnedTerminalSession(sessionId)) { "终端会话不存在或不属于当前 agent：$sessionId" }
        return withLocalTerminalManager { manager ->
            val preSnapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
            if (preSnapshot.commandRunning) {
                return@withLocalTerminalManager DirectTerminalCommandResult(
                    sessionId = sessionId,
                    completed = false,
                    timedOut = false,
                    output = "",
                    transcript = preSnapshot.transcript,
                    currentDirectory = preSnapshot.currentDirectory,
                    commandRunning = true,
                    errorMessage = helper.localized("当前会话仍有命令在执行，请先读取输出或停止会话。")
                )
            }
            executeDirectCommandInSession(
                manager = manager,
                sessionId = sessionId,
                command = buildDirectShellCommand(command, workingDirectory, environment),
                timeoutSeconds = timeoutSeconds,
                onLiveOutput = onLiveUpdate
            )
        }
    }

    private suspend fun readDirectTerminalSession(sessionId: String): DirectTerminalSessionSnapshot {
        require(sessionId.isNotBlank()) { "缺少 sessionId" }
        require(isOwnedTerminalSession(sessionId)) { "终端会话不存在或不属于当前 agent：$sessionId" }
        return withLocalTerminalManager { manager ->
            captureDirectTerminalSessionSnapshot(manager, sessionId)
        }
    }

    private suspend fun stopDirectTerminalSession(sessionId: String): Boolean {
        if (sessionId.isBlank() || !isOwnedTerminalSession(sessionId)) {
            return false
        }
        return withLocalTerminalManager { manager ->
            val exists = findTerminalSession(manager, sessionId) != null
            if (exists) {
                manager.closeSession(sessionId)
            }
            forgetOwnedTerminalSession(sessionId)
            exists
        }
    }

    private suspend fun createLocalTerminalSession(manager: TerminalManager, title: String): TerminalSessionData {
        val previousSessionId = manager.terminalState.value.currentSessionId
        val session = manager.createNewSession(title, TerminalType.LOCAL)
        if (!previousSessionId.isNullOrBlank() && previousSessionId != session.id) {
            runCatching { manager.switchToSession(previousSessionId) }
        }
        return session
    }

    private suspend fun executeDirectCommandInSession(
        manager: TerminalManager,
        sessionId: String,
        command: String,
        timeoutSeconds: Int?,
        onLiveOutput: suspend (String) -> Unit = {}
    ): DirectTerminalCommandResult = coroutineScope {
        val session = findTerminalSession(manager, sessionId)
            ?: throw IllegalStateException("终端会话不存在：$sessionId")
        if (session.currentExecutingCommand?.isExecuting == true) {
            val snapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
            return@coroutineScope DirectTerminalCommandResult(
                sessionId = sessionId,
                completed = false,
                timedOut = false,
                output = "",
                transcript = snapshot.transcript,
                currentDirectory = snapshot.currentDirectory,
                commandRunning = true,
                errorMessage = "当前会话仍有命令在执行，请先读取输出或停止会话。"
            )
        }

        val commandId = UUID.randomUUID().toString()
        val completionOutput = CompletableDeferred<String?>()
        val collectorReady = CompletableDeferred<Unit>()
        val collectorJob = launch {
            manager.commandExecutionEvents
                .filter { event ->
                    event.sessionId == sessionId && event.commandId == commandId
                }
                .onStart { collectorReady.complete(Unit) }
                .collect { event ->
                    if (event.isCompleted) {
                        if (!completionOutput.isCompleted) {
                            completionOutput.complete(event.outputChunk)
                        }
                        return@collect
                    }
                    val normalizedDelta = normalizeTerminalOutputDelta(event.outputChunk)
                    if (normalizedDelta.isNotBlank()) {
                        onLiveOutput(normalizedDelta)
                    }
                }
        }

        collectorReady.await()
        manager.sendCommandToSession(sessionId = sessionId, command = command, commandId = commandId)

        val completedOutput = if (timeoutSeconds != null) {
            withTimeoutOrNull(timeoutSeconds * 1000L) { completionOutput.await() }
        } else {
            completionOutput.await()
        }
        collectorJob.cancelAndJoin()

        val snapshot = captureDirectTerminalSessionSnapshot(manager, sessionId)
        val normalizedOutput = EmbeddedTerminalRuntime.trimTerminalOutput(
            EmbeddedTerminalRuntime.sanitizeTerminalNoise(completedOutput.orEmpty())
        )
        if (completedOutput == null) {
            return@coroutineScope DirectTerminalCommandResult(
                sessionId = sessionId,
                completed = false,
                timedOut = true,
                output = normalizedOutput.ifBlank { snapshot.transcript },
                transcript = snapshot.transcript,
                currentDirectory = snapshot.currentDirectory,
                commandRunning = snapshot.commandRunning,
                errorMessage = helper.localized("终端命令等待超时，可能仍在后台继续运行。")
            )
        }

        DirectTerminalCommandResult(
            sessionId = sessionId,
            completed = true,
            timedOut = false,
            output = normalizedOutput,
            transcript = snapshot.transcript,
            currentDirectory = snapshot.currentDirectory,
            commandRunning = snapshot.commandRunning,
            errorMessage = null
        )
    }

    private fun buildDirectShellCommand(command: String, workingDirectory: String?, environment: Map<String, String>): String {
        val normalizedCommand = command.trim()
        require(normalizedCommand.isNotEmpty()) { "command 不能为空" }
        val segments = buildSessionSetupSegments(workingDirectory, environment).toMutableList()
        segments += normalizedCommand
        return segments.joinToString(separator = " && ")
    }

    private fun buildSessionSetupCommand(workingDirectory: String?, environment: Map<String, String>): String {
        return buildSessionSetupSegments(workingDirectory, environment).joinToString(separator = " && ")
    }

    private fun buildSessionSetupSegments(workingDirectory: String?, environment: Map<String, String>): List<String> {
        val segments = mutableListOf<String>()
        environment.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim()
            if (key.isEmpty() || !terminalEnvKeyPattern.matches(key)) {
                return@forEach
            }
            segments += "export $key=${cn.com.omnimind.bot.termux.TermuxCommandBuilder.quoteForShell(rawValue)}"
        }
        if (!workingDirectory.isNullOrBlank()) {
            segments += "cd ${cn.com.omnimind.bot.termux.TermuxCommandBuilder.quoteForShell(workingDirectory)}"
        }
        return segments
    }

    private fun normalizeTerminalOutputDelta(outputChunk: String): String {
        val cleaned = EmbeddedTerminalRuntime.sanitizeTerminalNoise(outputChunk)
        if (cleaned.isBlank()) return ""
        return if (cleaned.endsWith("\n")) cleaned else "$cleaned\n"
    }

    private fun captureDirectTerminalSessionSnapshot(manager: TerminalManager, sessionId: String): DirectTerminalSessionSnapshot {
        val session = findTerminalSession(manager, sessionId)
            ?: throw IllegalStateException("终端会话不存在：$sessionId")
        return DirectTerminalSessionSnapshot(
            sessionId = sessionId,
            transcript = buildDirectTerminalTranscript(session),
            currentDirectory = normalizeTerminalCurrentDirectory(session.currentDirectory),
            commandRunning = session.currentExecutingCommand?.isExecuting == true
        )
    }

    private fun findTerminalSession(manager: TerminalManager, sessionId: String): TerminalSessionData? {
        return manager.terminalState.value.sessions.find { session -> session.id == sessionId }
    }

    private fun buildDirectTerminalTranscript(session: TerminalSessionData): String {
        return EmbeddedTerminalRuntime.trimTerminalOutput(
            EmbeddedTerminalRuntime.sanitizeTerminalNoise(session.transcript.trim('\n'))
        )
    }

    private fun normalizeTerminalCurrentDirectory(prompt: String): String {
        val cleaned = prompt.trim().replace(Regex("""\s+[#$]\s*$"""), "")
        return if (cleaned.isBlank() || cleaned == "$") "~" else cleaned
    }

    private fun resolveShellWorkingDirectory(requestedPath: String?, workspace: AgentWorkspaceDescriptor): String {
        return if (requestedPath.isNullOrBlank()) {
            workspace.currentCwd
        } else {
            workspaceManager.resolveShellPath(requestedPath, workspace, allowRootDirectories = true)
        }
    }

    private fun sanitizeTerminalSessionId(raw: String?): String {
        val normalized = raw.orEmpty().trim()
        val base = normalized.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return if (base.isBlank()) {
            "session_${UUID.randomUUID().toString().take(8)}"
        } else {
            base.take(48)
        }
    }

    private fun terminalSessionDirectory(workspace: AgentWorkspaceDescriptor, sessionId: String): File {
        return File(File(workspaceManager.offloadsDirectory(workspace.id), "terminal_sessions"), sessionId)
    }

    private fun persistTerminalSessionTranscript(workspace: AgentWorkspaceDescriptor, sessionId: String, transcript: String, sourceTool: String): ArtifactRef {
        val logFile = File(terminalSessionDirectory(workspace, sessionId), "latest.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText(transcript)
        return workspaceManager.buildArtifactForFile(logFile, sourceTool)
    }

    private fun buildTerminalToolResult(
        toolName: String,
        args: TerminalExecuteArgs,
        result: TermuxCommandResult,
        workspace: AgentWorkspaceDescriptor,
        sourceTool: String
    ): ToolExecutionResult.TerminalResult {
        val resultMap = buildTerminalResultMap(args, result)
        val artifacts = buildTerminalArtifacts(
            workspace = workspace,
            sourceTool = sourceTool,
            terminalOutput = result.terminalOutput.ifBlank { result.stdout + result.stderr }
        )
        return ToolExecutionResult.TerminalResult(
            toolName = toolName,
            summaryText = buildTerminalSummary(result),
            previewJson = helper.encodeLocalizedPayload(resultMap),
            rawResultJson = helper.encodeLocalizedPayload(resultMap),
            success = result.success,
            timedOut = result.timedOut,
            terminalOutput = result.terminalOutput,
            terminalSessionId = result.liveSessionId,
            terminalStreamState = result.liveStreamState,
            artifacts = artifacts,
            workspaceId = workspace.id
        )
    }

    private fun buildTerminalResultMap(args: TerminalExecuteArgs, result: TermuxCommandResult): Map<String, Any?> {
        return linkedMapOf(
            "executionMode" to args.executionMode,
            "prootDistro" to args.prootDistro,
            "workingDirectory" to args.workingDirectory,
            "timeoutSeconds" to args.timeoutSeconds,
            "command" to args.command,
            "success" to result.success,
            "timedOut" to result.timedOut,
            "resultCode" to result.resultCode,
            "errorCode" to result.errorCode,
            "errorMessage" to result.errorMessage,
            "stdout" to result.stdout,
            "stderr" to result.stderr,
            "stdoutLength" to result.stdout.length,
            "stderrLength" to result.stderr.length,
            "terminalOutput" to result.terminalOutput,
            "terminalOutputLength" to result.terminalOutput.length,
            "liveSessionId" to result.liveSessionId,
            "liveStreamState" to result.liveStreamState,
            "liveFallbackReason" to result.liveFallbackReason,
            "rawExtras" to sanitizeTerminalRawExtras(result.rawExtras)
        )
    }

    private fun buildTerminalSummary(result: TermuxCommandResult): String {
        val liveNote = if (result.liveFallbackReason.isNullOrBlank()) {
            ""
        } else if (helper.isEnglishLocale) {
            ", falling back to showing the result after completion"
        } else {
            "，已回退为结束后展示结果"
        }
        if (result.timedOut) {
            val timedOutText = if (helper.isEnglishLocale) {
                "Terminal command timed out and may still be running in the background$liveNote"
            } else {
                "终端命令等待超时，可能仍在后台继续运行$liveNote"
            }
            return helper.localized(timedOutText)
        }
        val headline = helper.firstUsefulLine(if (result.success) result.stdout else result.stderr.ifBlank { result.stdout })
        val suffix = headline?.let { if (helper.isEnglishLocale) ": $it" else "：$it" }.orEmpty()
        val summary = when {
            result.success && result.resultCode == 0 ->
                if (helper.isEnglishLocale) "Terminal command succeeded (exit=0)$suffix$liveNote"
                else "终端命令执行成功（exit=0）$suffix$liveNote"
            result.success ->
                if (helper.isEnglishLocale) "Terminal command completed$suffix$liveNote"
                else "终端命令执行完成$suffix$liveNote"
            result.resultCode != null ->
                if (helper.isEnglishLocale) "Terminal command failed (exit=${result.resultCode})$suffix$liveNote"
                else "终端命令执行失败（exit=${result.resultCode}）$suffix$liveNote"
            !result.errorMessage.isNullOrBlank() -> helper.localized(result.errorMessage) + liveNote
            else -> if (helper.isEnglishLocale) "Terminal command failed$liveNote" else "终端命令执行失败$liveNote"
        }
        return helper.localized(summary)
    }

    private fun buildTerminalArtifacts(workspace: AgentWorkspaceDescriptor, sourceTool: String, terminalOutput: String): List<ArtifactRef> {
        if (terminalOutput.isBlank()) return emptyList()
        return try {
            listOf(workspaceManager.writeOffload(agentRunId = workspace.id, extension = "log", content = terminalOutput).copy(sourceTool = sourceTool))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun sanitizeTerminalRawExtras(rawExtras: Map<String, Any?>): Map<String, Any?> {
        if (rawExtras.isEmpty()) return emptyMap()
        return rawExtras.entries.associate { (key, value) ->
            key to when (value) {
                is String -> EmbeddedTerminalRuntime.sanitizeTerminalNoise(value)
                is List<*> -> value.map { item ->
                    if (item is String) EmbeddedTerminalRuntime.sanitizeTerminalNoise(item) else item
                }
                else -> value
            }
        }
    }

    private fun parseTerminalExecuteArgs(
        args: JsonObject,
    ): TerminalExecuteArgs {
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(command.isNotEmpty()) { "terminal_execute 缺少 command" }
        val requestedMode = args["executionMode"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (requestedMode != null) {
            require(requestedMode == TermuxCommandSpec.EXECUTION_MODE_TERMUX || requestedMode == TermuxCommandSpec.EXECUTION_MODE_PROOT) {
                "executionMode 仅支持 termux 或 proot"
            }
        }
        val executionMode = requestedMode ?: TermuxCommandSpec.EXECUTION_MODE_TERMUX
        val prootDistro = if (executionMode == TermuxCommandSpec.EXECUTION_MODE_PROOT) {
            helper.terminalDistribution.id
        } else {
            null
        }
        val workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        return TerminalExecuteArgs(command = command, executionMode = executionMode, prootDistro = prootDistro, workingDirectory = workingDirectory, timeoutSeconds = timeoutSeconds)
    }

    private fun parseTerminalSessionStartArgs(args: JsonObject): TerminalSessionStartArgs {
        return TerminalSessionStartArgs(
            sessionName = args["sessionName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parseTerminalSessionExecArgs(args: JsonObject): TerminalSessionExecArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        require(command.isNotEmpty()) { "缺少 command" }
        return TerminalSessionExecArgs(
            sessionId = sessionId,
            command = command,
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun parseTerminalSessionReadArgs(
        args: JsonObject,
    ): TerminalSessionReadArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return TerminalSessionReadArgs(
            sessionId = sessionId
        )
    }
}
