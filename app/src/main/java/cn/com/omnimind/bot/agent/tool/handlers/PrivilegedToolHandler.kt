package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.shizuku.PrivilegedActionPolicy
import cn.com.omnimind.baselib.shizuku.PrivilegedResult
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PrivilegedToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: cn.com.omnimind.bot.agent.AgentWorkspaceManager,
    private val terminalToolHandler: TerminalToolHandler
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "android_privileged_action",
        "android_privileged_session_start",
        "android_privileged_session_exec",
        "android_privileged_session_read",
        "android_privileged_session_stop"
    )

    data class AndroidPrivilegedArgs(
        val action: String,
        val arguments: Map<String, String>,
        val command: String?,
        val timeoutSeconds: Int?,
        val workingDirectory: String?,
        val environment: Map<String, String>
    )

    data class PrivilegedSessionStartArgs(
        val sessionName: String?,
        val workingDirectory: String?,
        val environment: Map<String, String>,
        val confirmed: Boolean
    )

    data class PrivilegedSessionExecArgs(
        val sessionId: String,
        val command: String,
        val timeoutSeconds: Int,
        val confirmed: Boolean
    )

    data class PrivilegedSessionReadArgs(
        val sessionId: String,
        val maxChars: Int
    )

    data class PrivilegedSessionStopArgs(
        val sessionId: String
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
            "android_privileged_action" -> executeAndroidPrivilegedAction(args, callback, env, toolHandle.toolCallId)
            "android_privileged_session_start" -> executeAndroidPrivilegedSessionStart(args, env.workspaceDescriptor, callback, env, toolHandle.toolCallId)
            "android_privileged_session_exec" -> executeAndroidPrivilegedSessionExec(args, env.workspaceDescriptor, callback, toolHandle, env)
            "android_privileged_session_read" -> executeAndroidPrivilegedSessionRead(args, env.workspaceDescriptor, callback)
            "android_privileged_session_stop" -> executeAndroidPrivilegedSessionStop(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown privileged tool")
        }
    }

    private fun privilegedSessionWorkspaceId(workspaceId: String): String {
        return "${SharedHelper.PRIVILEGED_SESSION_WORKSPACE_PREFIX}$workspaceId"
    }

    private fun rememberOwnedPrivilegedSession(workspaceId: String, sessionId: String, sessionName: String?) {
        terminalToolHandler.rememberOwnedTerminalSession(privilegedSessionWorkspaceId(workspaceId), sessionId, sessionName)
    }

    private fun isOwnedPrivilegedSession(workspaceId: String, sessionId: String): Boolean {
        return terminalToolHandler.isOwnedTerminalSession(privilegedSessionWorkspaceId(workspaceId), sessionId)
    }

    private fun forgetOwnedPrivilegedSession(sessionId: String) {
        terminalToolHandler.forgetOwnedTerminalSession(sessionId)
    }

    private fun privilegedSessionDirectory(workspace: AgentWorkspaceDescriptor, sessionId: String): File {
        return File(File(workspaceManager.offloadsDirectory(workspace.id), "privileged_sessions"), sessionId)
    }

    private fun persistPrivilegedSessionTranscript(workspace: AgentWorkspaceDescriptor, sessionId: String, transcript: String, sourceTool: String): ArtifactRef {
        val logFile = File(privilegedSessionDirectory(workspace, sessionId), "latest.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText(transcript)
        return workspaceManager.buildArtifactForFile(logFile, sourceTool)
    }

    private suspend fun executeAndroidPrivilegedAction(
        args: JsonObject,
        callback: AgentCallback,
        env: AgentExecutionEnvironment,
        toolCallId: String,
    ): ToolExecutionResult {
        val toolName = "android_privileged_action"
        return try {
            val parsed = parseAndroidPrivilegedArgs(args)
            val shizukuManager = ShizukuCapabilityManager.get(helper.context)
            val status = shizukuManager.getStatus()
            if (!status.isGranted()) {
                return helper.permissionRequiredResult(callback, listOf("Shizuku 权限"))
            }
            if (!PrivilegedActionPolicy.isSupported(parsed.action, status.backend, arguments = parsed.arguments)) {
                return ToolExecutionResult.Error(toolName, helper.localized("当前 Shizuku 后端不支持该动作：${parsed.action}"))
            }
            // Confirmation is a gate, not a tool-progress state. Do not emit
            // "正在执行" or call the privileged backend before the user has
            // confirmed; otherwise the ACP timeline shows an execution card
            // beside a later clarification and users read it as a duplicate
            // success/failure.
            var effectiveArgs = parsed
            if (PrivilegedActionPolicy.requiresConfirmation(parsed.action) &&
                !parsed.arguments["confirmed"].isTruthyFlag()
            ) {
                PrivilegedActionPolicy.blockedCommandReason(parsed.command)?.let { reason ->
                    return ToolExecutionResult.Error(toolName, helper.localized(reason))
                }
                val confirmation = requestPrivilegedConfirmation(
                    callback = callback,
                    action = parsed.action,
                    command = parsed.command,
                    field = "arguments.confirmed",
                    env = env,
                    toolCallId = toolCallId,
                    toolName = toolName,
                )
                if (confirmation != null) return confirmation
                effectiveArgs = parsed.copy(
                    arguments = parsed.arguments + ("confirmed" to "true")
                )
            }
            helper.reportToolProgress(
                callback, toolName, "正在执行 Shizuku 高级动作",
                mapOf("action" to parsed.action, "backend" to status.backend.name, "command" to parsed.command, "availableActions" to status.availableActions)
            )
            val result = if (parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
                shizukuManager.executeRawShell(
                    command = effectiveArgs.command.orEmpty(),
                    timeoutSeconds = effectiveArgs.timeoutSeconds,
                    workingDirectory = effectiveArgs.workingDirectory,
                    environment = effectiveArgs.environment,
                    confirmed = effectiveArgs.arguments["confirmed"].isTruthyFlag()
                )
            } else {
                shizukuManager.executeAgentAction(
                    action = effectiveArgs.action,
                    arguments = effectiveArgs.arguments,
                    requiresConfirmation = PrivilegedActionPolicy.requiresConfirmation(effectiveArgs.action)
                )
            }
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                // Confirmation is owned by the ACP permission request above.
                // A backend asking for it again means the gate and backend
                // disagree; do not create a second Clarify lifecycle.
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高级动作确认状态不一致，命令未执行。"),
                )
            }
            val payload = result.toMap().toMutableMap().apply { this["message"] = localizedPrivilegedMessage(result) }
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (result.success) "已执行 Shizuku 动作：${result.action}" else "Shizuku 动作执行失败：${result.action}"),
                previewJson = payloadJson, rawResultJson = payloadJson, success = result.success
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "Shizuku 动作执行失败")) }
    }

    private suspend fun executeAndroidPrivilegedSessionStart(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
        env: AgentExecutionEnvironment,
        toolCallId: String,
    ): ToolExecutionResult {
        val toolName = "android_privileged_session_start"
        return try {
            var parsed = parsePrivilegedSessionStartArgs(args)
            val shizukuManager = ShizukuCapabilityManager.get(helper.context)
            val status = shizukuManager.getStatus()
            if (!status.isGranted()) { return helper.permissionRequiredResult(callback, listOf("Shizuku 权限")) }
            if (!parsed.confirmed) {
                val confirmation = requestPrivilegedConfirmation(
                    callback = callback,
                    action = SharedHelper.PRIVILEGED_SESSION_START_ACTION,
                    field = "confirmed",
                    env = env,
                    toolCallId = toolCallId,
                    toolName = toolName,
                )
                if (confirmation != null) return confirmation
                parsed = parsed.copy(confirmed = true)
            }
            helper.reportToolProgress(callback, toolName, "正在启动高权限 Shizuku 会话", mapOf("backend" to status.backend.name, "workingDirectory" to parsed.workingDirectory))
            val result = shizukuManager.startPrivilegedSession(sessionName = parsed.sessionName, workingDirectory = parsed.workingDirectory, environment = parsed.environment, confirmed = parsed.confirmed)
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高权限会话确认状态不一致，会话未启动。"),
                )
            }
            val artifacts = mutableListOf<ArtifactRef>()
            if (result.success) {
                result.sessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
                    rememberOwnedPrivilegedSession(workspaceId = workspace.id, sessionId = sessionId, sessionName = parsed.sessionName)
                    artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = sessionId, transcript = result.transcript, sourceTool = toolName)
                }
            }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                this["sessionName"] = parsed.sessionName
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限会话已启动：${result.sessionId.orEmpty()}") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "高权限会话启动失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionExec(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
        env: AgentExecutionEnvironment,
    ): ToolExecutionResult {
        val toolName = "android_privileged_session_exec"
        return try {
            var parsed = parsePrivilegedSessionExecArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val shizukuManager = ShizukuCapabilityManager.get(helper.context)
            val status = shizukuManager.getStatus()
            if (!status.isGranted()) { return helper.permissionRequiredResult(callback, listOf("Shizuku 权限")) }
            if (!parsed.confirmed) {
                PrivilegedActionPolicy.blockedCommandReason(parsed.command)?.let { reason ->
                    return ToolExecutionResult.Error(toolName, helper.localized(reason))
                }
                val confirmation = requestPrivilegedConfirmation(
                    callback = callback,
                    action = SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION,
                    command = parsed.command,
                    field = "confirmed",
                    env = env,
                    toolCallId = toolHandle.toolCallId,
                    toolName = toolName,
                )
                if (confirmation != null) return confirmation
                parsed = parsed.copy(confirmed = true)
            }
            helper.reportToolProgress(callback, toolName, "正在执行高权限 Shizuku 命令", mapOf("backend" to status.backend.name, "sessionId" to parsed.sessionId, "command" to parsed.command), toolHandle = toolHandle)
            toolHandle.bindStopAction { shizukuManager.stopPrivilegedSession(parsed.sessionId) }
            val result = shizukuManager.execPrivilegedSession(sessionId = parsed.sessionId, command = parsed.command, timeoutSeconds = parsed.timeoutSeconds, confirmed = parsed.confirmed)
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高权限会话确认状态不一致，命令未执行。"),
                )
            }
            if (result.code == "session_not_found") { forgetOwnedPrivilegedSession(parsed.sessionId) }
            val transcript = result.transcript.ifBlank { result.output }
            val artifacts = mutableListOf<ArtifactRef>()
            if (transcript.isNotBlank()) { artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = parsed.sessionId, transcript = transcript, sourceTool = toolName) }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限命令执行完成") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "高权限会话命令执行失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionRead(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "android_privileged_session_read"
        return try {
            val parsed = parsePrivilegedSessionReadArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val shizukuManager = ShizukuCapabilityManager.get(helper.context)
            val status = shizukuManager.getStatus()
            if (!status.isGranted()) { return helper.permissionRequiredResult(callback, listOf("Shizuku 权限")) }
            val result = shizukuManager.readPrivilegedSession(sessionId = parsed.sessionId, maxChars = parsed.maxChars)
            if (result.code == "session_not_found") { forgetOwnedPrivilegedSession(parsed.sessionId) }
            val transcript = helper.truncateTerminalTail(result.transcript.ifBlank { result.output }, parsed.maxChars)
            val artifacts = mutableListOf<ArtifactRef>()
            if (transcript.isNotBlank()) { artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = parsed.sessionId, transcript = transcript, sourceTool = toolName) }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                this["content"] = transcript; this["contentLength"] = transcript.length
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (!result.success) localizedPrivilegedMessage(result) else if (transcript.isBlank()) helper.localized("高权限会话暂无输出") else helper.localized("已读取高权限会话输出"),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "读取高权限会话失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionStop(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "android_privileged_session_stop"
        return try {
            helper.reportToolProgress(callback, toolName, "正在结束高权限会话")
            val parsed = parsePrivilegedSessionStopArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val result = ShizukuCapabilityManager.get(helper.context).stopPrivilegedSession(parsed.sessionId)
            forgetOwnedPrivilegedSession(parsed.sessionId)
            val payload = result.toMap().toMutableMap().apply { this["message"] = localizedPrivilegedMessage(result) }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限会话已结束：${parsed.sessionId}") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "结束高权限会话失败") }
    }

    private fun parseAndroidPrivilegedArgs(args: JsonObject): AndroidPrivilegedArgs {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        require(action.isNotEmpty()) { "action 不能为空" }
        val rawArguments = args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return AndroidPrivilegedArgs(
            action = action,
            arguments = helper.jsonObjectToStringMap(rawArguments, excludedKeys = setOf("environment")),
            command = rawArguments["command"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            timeoutSeconds = rawArguments["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.coerceIn(5, 600),
            workingDirectory = rawArguments["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            environment = helper.parseEnvironmentMap(rawArguments["environment"] as? JsonObject)
        )
    }

    private fun parsePrivilegedSessionStartArgs(args: JsonObject): PrivilegedSessionStartArgs {
        return PrivilegedSessionStartArgs(
            sessionName = args["sessionName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            environment = helper.parseEnvironmentMap(args["environment"] as? JsonObject),
            confirmed = helper.parseConfirmedFlag(args["confirmed"])
        )
    }

    private fun parsePrivilegedSessionExecArgs(args: JsonObject): PrivilegedSessionExecArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        require(command.isNotEmpty()) { "缺少 command" }
        return PrivilegedSessionExecArgs(
            sessionId = sessionId, command = command,
            timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.coerceIn(5, 600) ?: 120,
            confirmed = helper.parseConfirmedFlag(args["confirmed"])
        )
    }

    private fun parsePrivilegedSessionReadArgs(args: JsonObject): PrivilegedSessionReadArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return PrivilegedSessionReadArgs(sessionId = sessionId, maxChars = args["maxChars"]?.jsonPrimitive?.intOrNull?.coerceIn(256, 64_000) ?: SharedHelper.DEFAULT_TERMINAL_SESSION_READ_MAX_CHARS)
    }

    private fun parsePrivilegedSessionStopArgs(args: JsonObject): PrivilegedSessionStopArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return PrivilegedSessionStopArgs(sessionId = sessionId)
    }

    private fun privilegedConfirmationQuestion(action: String, command: String? = null): String {
        val preview = command?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length <= 120) it else it.take(120) + "..." }
        val target = preview ?: action
        val suffix = if (helper.isEnglishLocale) {
            " It has not run yet. Reply \"confirm\" to run it once, or \"cancel\" to stop."
        } else {
            "当前尚未执行。请回复“确认”执行一次，或回复“取消”停止。"
        }
        return when (PrivilegedActionPolicy.normalizeAction(action)) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC -> if (helper.isEnglishLocale) "The privileged shell command `$target` requires your confirmation.$suffix" else "高权限 shell 命令“$target”需要你的确认。$suffix"
            SharedHelper.PRIVILEGED_SESSION_START_ACTION -> if (helper.isEnglishLocale) "Starting a persistent privileged shell session requires your confirmation.$suffix" else "启动持久化高权限 shell 会话需要你的确认。$suffix"
            SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION -> if (helper.isEnglishLocale) "The privileged session command `$target` requires your confirmation.$suffix" else "高权限会话命令“$target”需要你的确认。$suffix"
            else -> if (helper.isEnglishLocale) "The privileged action `$target` requires your confirmation.$suffix" else "高权限动作“$target”需要你的确认。$suffix"
        }
    }

    private suspend fun requestPrivilegedConfirmation(
        callback: AgentCallback,
        action: String,
        command: String? = null,
        field: String,
        env: AgentExecutionEnvironment,
        toolCallId: String,
        toolName: String,
    ): ToolExecutionResult? {
        val question = privilegedConfirmationQuestion(action, command)
        val requester = env.permissionRequester
        if (requester != null) {
            val allowed = requester.requestPermission(
                toolCallId = toolCallId,
                title = privilegedPermissionTitle(action),
                detail = question,
            )
            return if (allowed) {
                null
            } else {
                ToolExecutionResult.Error(
                    toolName,
                    helper.localized("用户拒绝了高权限操作，命令未执行。"),
                )
            }
        }
        callback.onClarifyRequired(question, listOf(field))
        return ToolExecutionResult.Clarify(question, listOf(field))
    }

    private fun privilegedPermissionTitle(action: String): String {
        return when (PrivilegedActionPolicy.normalizeAction(action)) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC ->
                if (helper.isEnglishLocale) "Run privileged shell command" else "执行高权限 Shell 命令"
            SharedHelper.PRIVILEGED_SESSION_START_ACTION ->
                if (helper.isEnglishLocale) "Start privileged shell session" else "启动高权限 Shell 会话"
            SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION ->
                if (helper.isEnglishLocale) "Run privileged session command" else "执行高权限会话命令"
            else ->
                if (helper.isEnglishLocale) "Run privileged action" else "执行高权限动作"
        }
    }

    private fun localizedPrivilegedMessage(result: PrivilegedResult): String {
        return when (result.code) {
            "ok" -> if (helper.isEnglishLocale) "Privileged action executed successfully." else "高级动作执行成功。"
            "confirmation_required" -> if (helper.isEnglishLocale) "This privileged action requires explicit confirmation." else "该高级动作需要用户明确确认。"
            "unsupported_action" -> if (helper.isEnglishLocale) "The current Shizuku backend does not support this action." else "当前 Shizuku 后端不支持该动作。"
            "not_installed" -> if (helper.isEnglishLocale) "Shizuku is not installed." else "Shizuku 未安装。"
            "not_running" -> if (helper.isEnglishLocale) "Shizuku is installed but not running." else "Shizuku 已安装但未启动。"
            "permission_denied" -> if (helper.isEnglishLocale) "Shizuku permission is not granted." else "Shizuku 尚未授权。"
            "service_bind_failed" -> if (helper.isEnglishLocale) "Failed to bind the Shizuku user service." else "绑定 Shizuku 用户服务失败。"
            "service_call_failed" -> if (helper.isEnglishLocale) "Failed to call the Shizuku user service." else "调用 Shizuku 用户服务失败。"
            "service_send_failed" -> if (helper.isEnglishLocale) "Failed to send the privileged request to Shizuku." else "向 Shizuku 发送高级请求失败。"
            "service_timeout" -> if (helper.isEnglishLocale) "Timed out waiting for the Shizuku user service result." else "等待 Shizuku 用户服务结果超时。"
            "invalid_arguments" -> if (helper.isEnglishLocale) "The privileged action arguments are invalid." else "高级动作参数不合法。"
            "command_failed" -> if (helper.isEnglishLocale) "The privileged action command failed." else "高级动作执行失败。"
            "timeout" -> if (helper.isEnglishLocale) "The privileged action timed out." else "高级动作执行超时。"
            "blocked_by_policy" -> if (helper.isEnglishLocale) "This privileged command was blocked by the local safety policy." else "该高权限命令已被本地安全策略拦截。"
            "session_not_found" -> if (helper.isEnglishLocale) "The privileged shell session does not exist or is no longer alive." else "高权限 shell 会话不存在或已失效。"
            "session_busy" -> if (helper.isEnglishLocale) "Another command is still running in the privileged shell session." else "高权限 shell 会话中仍有命令在执行。"
            "session_start_failed" -> if (helper.isEnglishLocale) "Failed to start the privileged shell session." else "启动高权限 shell 会话失败。"
            "session_write_failed" -> if (helper.isEnglishLocale) "Failed to write to the privileged shell session." else "向高权限 shell 会话写入命令失败。"
            "session_exists" -> if (helper.isEnglishLocale) "A privileged shell session with the same id already exists." else "同名的高权限 shell 会话已存在。"
            else -> result.message
        }
    }

    private fun String?.isTruthyFlag(): Boolean {
        return this?.trim()?.lowercase() in setOf("1", "true", "yes", "confirm", "confirmed", "on")
    }
}
