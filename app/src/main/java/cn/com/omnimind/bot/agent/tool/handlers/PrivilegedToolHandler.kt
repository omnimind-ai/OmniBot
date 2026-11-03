package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.bot.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PrivilegedToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "android_privileged_action", "android_privileged_session_start", "android_privileged_session_exec",
        "android_privileged_session_read", "android_privileged_session_stop",
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val name = toolCall.function.name
        return try {
            val parsed = parse(name, args)
            val access = PrivilegedCapabilityManager.get(helper.context)
                .reserve(env.workspaceDescriptor.id, parsed.sessionId, env.acpSessionId)
            val status = access.status
            if (!status.isGranted()) return ToolExecutionResult.Error(name, helper.localized(
                if (parsed.sessionId == null) "高权限能力不可用，请在权限设置中启用 Root 或授权 Shizuku。"
                else "高权限 shell 会话已失效或不属于当前对话。"
            ))
            if (name == "android_privileged_action" &&
                !PrivilegedActionPolicy.isSupported(parsed.action, status.backend, arguments = parsed.arguments)) {
                return ToolExecutionResult.Error(name, helper.localized("当前后端不支持该动作：${parsed.action}"))
            }
            if (parsed.command != null) {
                PrivilegedActionPolicy.blockedCommandReason(parsed.command)?.let {
                    return ToolExecutionResult.Error(name, helper.localized(it))
                }
            }
            val approved = PrivilegedToolConfirmation.request(
                action = parsed.action,
                toolCallId = toolHandle.toolCallId,
                requester = env.permissionRequester,
                valid = access::isValid,
                title = if (helper.isEnglishLocale) "${status.label}: privileged operation" else "${status.label}：高权限操作",
                detail = buildString {
                    append(if (helper.isEnglishLocale) "Backend: " else "执行后端：")
                    append(status.label)
                    append("\n${parsed.action}")
                    parsed.sessionId?.let { append("\nsessionId: $it") }
                    parsed.command?.let { append("\n$it") }
                    if (parsed.arguments.isNotEmpty()) append("\n${helper.encodeLocalizedPayload(parsed.arguments)}")
                    parsed.workingDirectory?.let { append("\nworkingDirectory: $it") }
                    if (parsed.environment.isNotEmpty()) append("\nenvironment: ${helper.encodeLocalizedPayload(parsed.environment)}")
                    append(if (helper.isEnglishLocale) "\nThis operation has not run yet." else "\n当前尚未执行。")
                },
            )
            if (!approved) return ToolExecutionResult.Error(name, helper.localized(
                if (access.isValid()) "高权限操作未获得用户授权，命令未执行。" else "权限或后端已变化，操作未执行。"
            ))
            toolHandle.throwIfStopRequested()
            helper.reportToolProgress(callback, name, "正在执行高权限操作", mapOf(
                "action" to parsed.action, "backend" to status.backend.name, "provider" to status.provider.name,
                "sessionId" to parsed.sessionId, "command" to parsed.command,
            ), toolHandle = toolHandle)
            val result = when (name) {
                "android_privileged_action" -> if (parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
                    access.executeRawShell(parsed.command.orEmpty(), parsed.timeoutSeconds, parsed.workingDirectory, parsed.environment, confirmed = true)
                } else access.executeAgentAction(
                    parsed.action, parsed.arguments + ("confirmed" to "true"),
                    PrivilegedActionPolicy.requiresConfirmation(parsed.action),
                )
                "android_privileged_session_start" -> access.startPrivilegedSession(
                    parsed.sessionName, parsed.workingDirectory, parsed.environment, confirmed = true,
                )
                "android_privileged_session_exec" -> {
                    toolHandle.bindStopAction { access.stopPrivilegedSession(parsed.sessionId!!) }
                    access.execPrivilegedSession(parsed.sessionId!!, parsed.command!!, parsed.timeoutSeconds, confirmed = true)
                }
                "android_privileged_session_read" -> access.readPrivilegedSession(parsed.sessionId!!)
                "android_privileged_session_stop" -> access.stopPrivilegedSession(parsed.sessionId!!)
                else -> error("Unknown privileged tool")
            }
            projectResult(name, parsed, result, env.workspaceDescriptor)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { helper.errorResult(name, error.message, "高权限操作执行失败") }
    }

    private fun projectResult(name: String, parsed: Operation, result: PrivilegedResult, workspace: AgentWorkspaceDescriptor): ToolExecutionResult {
        if (result.requiresConfirmation || result.code == "confirmation_required") {
            return ToolExecutionResult.Error(name, helper.localized("高级动作确认状态不一致，命令未执行。"))
        }
        val artifacts = mutableListOf<ArtifactRef>()
        val sessionId = result.sessionId
        val transcript = result.transcript.ifBlank { result.output }
        if (sessionId != null && transcript.isNotBlank()) {
            val directory = File(File(workspaceManager.offloadsDirectory(workspace.id), "privileged_sessions"), sessionId)
            val logFile = File(directory, "latest.log")
            directory.mkdirs()
            logFile.writeText(transcript)
            artifacts += workspaceManager.buildArtifactForFile(logFile, name)
        }
        val message = localizedMessage(result)
        val payload = result.toMap().toMutableMap().apply {
            this["message"] = message
            parsed.sessionName?.let { this["sessionName"] = it }
            if (name == "android_privileged_session_read") {
                this["content"] = transcript
                this["contentLength"] = transcript.length
            }
            artifacts.firstOrNull()?.let {
                this["logPath"] = it.workspacePath; this["androidLogPath"] = it.androidPath; this["logUri"] = it.uri
            }
        }
        val json = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = name, summaryText = message, previewJson = json, rawResultJson = json,
            success = result.success, artifacts = artifacts, workspaceId = workspace.id,
        )
    }

    private fun localizedMessage(result: PrivilegedResult): String {
        if (helper.isEnglishLocale) return result.message
        return when (result.code) {
            "ok" -> if (result.observation?.containsKey("verified") == true) "设备状态修改已验证。" else "高权限操作已完成。"
            "state_change_unconfirmed" -> "修改请求已提交，但尚未确认目标状态；请先查询状态，不要直接重复操作。"
            "state_unavailable" -> "无法读取当前设备状态。"
            "privileged_access_changed" -> "权限或后端已变化，本次操作已停止。"
            "root_denied", "permission_denied" -> "系统拒绝了此操作，请检查 Root 或 Shizuku 授权。"
            "root_unavailable" -> "设备上的直接 Root 不可用。"
            "unsupported_action" -> "当前设备、系统版本或执行后端不支持该操作。"
            "capability_probe_failed" -> "系统命令能力检查失败，尚无法确认是否支持；请查看具体错误。"
            "capability_probe_incomplete" -> "系统命令帮助输出不完整，尚无法确认是否支持该操作。"
            "timeout", "service_timeout" -> "高权限操作超时，执行已停止；请先检查设备状态。"
            "session_not_found" -> "高权限 shell 会话不存在或已失效。"
            "session_busy" -> "高权限 shell 会话仍有命令在执行。"
            "service_call_failed" -> "高权限服务连接失败；本次操作未自动重试，请先检查设备状态。"
            else -> helper.localized(result.message)
        }
    }

    private data class Operation(
        val action: String, val arguments: Map<String, String> = emptyMap(), val command: String? = null,
        val timeoutSeconds: Int? = null, val workingDirectory: String? = null,
        val environment: Map<String, String> = emptyMap(), val sessionId: String? = null, val sessionName: String? = null,
    )

    private fun parse(name: String, args: JsonObject): Operation {
        val body = if (name == "android_privileged_action") args["arguments"] as? JsonObject ?: JsonObject(emptyMap()) else args
        fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val action = when (name) {
            "android_privileged_action" -> requireNotNull(args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) { "action is required" }
            "android_privileged_session_start" -> SharedHelper.PRIVILEGED_SESSION_START_ACTION
            "android_privileged_session_exec" -> SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION
            "android_privileged_session_read" -> "shell.session_read"
            "android_privileged_session_stop" -> "shell.session_stop"
            else -> error("Unknown privileged tool")
        }
        val sessionId = if (name in setOf("android_privileged_session_exec", "android_privileged_session_read", "android_privileged_session_stop")) {
            requireNotNull(text("sessionId")) { "sessionId is required" }
        } else null
        val command = text("command")
        if (action == PrivilegedActionPolicy.ACTION_SHELL_EXEC || name == "android_privileged_session_exec") require(!command.isNullOrEmpty()) { "command is required" }
        return Operation(
            action = action,
            arguments = if (name == "android_privileged_action") helper.jsonObjectToStringMap(body, excludedKeys = setOf("environment", "confirmed")) else emptyMap(),
            command = command,
            timeoutSeconds = body["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.also { require(it > 0) { "timeoutSeconds must be positive" } },
            workingDirectory = text("workingDirectory"), environment = helper.parseEnvironmentMap(body["environment"] as? JsonObject),
            sessionId = sessionId, sessionName = text("sessionName"),
        )
    }
}

internal object PrivilegedToolConfirmation {
    suspend fun request(
        action: String, toolCallId: String, requester: AgentPermissionRequester?, valid: () -> Boolean,
        title: String, detail: String,
    ): Boolean {
        if (!valid()) return false
        if (!PrivilegedActionPolicy.requiresConfirmation(action)) return true
        // 授权只来自 ACP 客户端，不接受模型参数或历史工具结果中的 confirmed。
        val allowed = requester?.requestPermission(toolCallId, title, detail) == true
        return allowed && valid()
    }
}
