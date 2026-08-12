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
import kotlinx.serialization.json.JsonObject
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
        val environment: Map<String, String>
    )

    data class PrivilegedSessionExecArgs(
        val sessionId: String,
        val command: String,
        val timeoutSeconds: Int
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
            "android_privileged_action" -> executeAndroidPrivilegedAction(args, callback)
            "android_privileged_session_start" -> executeAndroidPrivilegedSessionStart(args, env.workspaceDescriptor, callback)
            "android_privileged_session_exec" -> executeAndroidPrivilegedSessionExec(args, env.workspaceDescriptor, callback, toolHandle)
            "android_privileged_session_read" -> executeAndroidPrivilegedSessionRead(args, env.workspaceDescriptor, callback)
            "android_privileged_session_stop" -> executeAndroidPrivilegedSessionStop(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown privileged tool")
        }
    }

    private fun privilegedSessionWorkspaceId(workspaceId: String): String {
        return "${SharedHelper.PRIVILEGED_SESSION_WORKSPACE_PREFIX}$workspaceId"
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

    private suspend fun executeAndroidPrivilegedAction(args: JsonObject, callback: AgentCallback): ToolExecutionResult {
        val toolName = "android_privileged_action"
        return try {
            val parsed = parseAndroidPrivilegedArgs(args)
            if (PrivilegedActionPolicy.requiresConfirmation(parsed.action)) {
                return securePrivilegedConfirmationUnavailable(toolName)
            }
            val shizukuManager = ShizukuCapabilityManager.get(helper.context)
            val status = shizukuManager.getStatus()
            if (!status.isGranted()) {
                return helper.permissionRequiredResult(callback, listOf("Shizuku 权限"))
            }
            if (!PrivilegedActionPolicy.isSupported(parsed.action, status.backend, arguments = parsed.arguments)) {
                return ToolExecutionResult.Error(toolName, helper.localized("当前 Shizuku 后端不支持该动作"))
            }
            helper.reportToolProgress(
                callback, toolName, "正在执行 Shizuku 高级动作",
                mapOf("action" to parsed.action, "backend" to status.backend.name, "command" to parsed.command, "availableActions" to status.availableActions)
            )
            val result = if (parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
                shizukuManager.executeRawShell(
                    command = parsed.command.orEmpty(),
                    timeoutSeconds = parsed.timeoutSeconds,
                    workingDirectory = parsed.workingDirectory,
                    environment = parsed.environment
                )
            } else {
                shizukuManager.executeAgentAction(
                    action = parsed.action,
                    arguments = parsed.arguments,
                    requiresConfirmation = PrivilegedActionPolicy.requiresConfirmation(parsed.action)
                )
            }
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                return securePrivilegedConfirmationUnavailable(toolName)
            }
            val payload = result.toMap().toMutableMap().apply { this["message"] = localizedPrivilegedMessage(result) }
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (result.success) "已执行 Shizuku 动作：${result.action}" else "Shizuku 动作执行失败：${result.action}"),
                previewJson = payloadJson, rawResultJson = payloadJson, success = result.success
            )
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ToolExecutionResult.Error(toolName, helper.localized("Shizuku 动作执行失败")) }
    }

    private suspend fun executeAndroidPrivilegedSessionStart(args: JsonObject, _workspace: AgentWorkspaceDescriptor, _callback: AgentCallback): ToolExecutionResult {
        val toolName = "android_privileged_session_start"
        return try {
            parsePrivilegedSessionStartArgs(args)
            securePrivilegedConfirmationUnavailable(toolName)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { securePrivilegedConfirmationUnavailable(toolName) }
    }

    private suspend fun executeAndroidPrivilegedSessionExec(args: JsonObject, _workspace: AgentWorkspaceDescriptor, _callback: AgentCallback, _toolHandle: AgentToolExecutionHandle): ToolExecutionResult {
        val toolName = "android_privileged_session_exec"
        return try {
            parsePrivilegedSessionExecArgs(args)
            securePrivilegedConfirmationUnavailable(toolName)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { securePrivilegedConfirmationUnavailable(toolName) }
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
            arguments = helper.jsonObjectToStringMap(
                rawArguments,
                excludedKeys = setOf("environment", "confirmed", "confirmationToken", "locallyApproved"),
            ),
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
            environment = helper.parseEnvironmentMap(args["environment"] as? JsonObject)
        )
    }

    private fun parsePrivilegedSessionExecArgs(args: JsonObject): PrivilegedSessionExecArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        require(command.isNotEmpty()) { "缺少 command" }
        return PrivilegedSessionExecArgs(
            sessionId = sessionId, command = command,
            timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.coerceIn(5, 600) ?: 120
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

    private fun securePrivilegedConfirmationUnavailable(toolName: String): ToolExecutionResult.Error =
        ToolExecutionResult.Error(
            toolName,
            if (helper.isEnglishLocale) {
                "This privileged operation is disabled because the Shizuku shell/root boundary cannot yet verify a one-time local user approval. Model-provided confirmation is never accepted."
            } else {
                "此高权限操作已禁用：Shizuku shell/root 边界目前无法验证一次性本地用户批准，且绝不接受模型提供的确认值。"
            },
        )

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

}
