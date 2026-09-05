package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentTerminalDistributionText
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.ArtifactAction
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class SharedHelper(
    val context: Context,
    val json: Json,
    val terminalDistribution: TerminalDistribution.Spec = TerminalDistribution.alpine
) {
    companion object {
        const val DIRECT_TERMINAL_WORKSPACE_ID = "__direct_terminal__"
        const val PRIVILEGED_SESSION_WORKSPACE_PREFIX = "__privileged__:"
        const val PRIVILEGED_SESSION_START_ACTION = "shell.session_start"
        const val PRIVILEGED_SESSION_EXEC_ACTION = "shell.session_exec"
    }

    val isEnglishLocale: Boolean
        get() = AppLocaleManager.isEnglish(context)

    private val englishTextMap: Map<String, String> = mapOf(
        "应用列表读取权限" to "Installed Apps Access",
        "悬浮窗权限" to "Overlay",
        "Shizuku 权限" to "Shizuku Permission",
        "精确闹钟权限(SCHEDULE_EXACT_ALARM)" to "Exact alarm permission (SCHEDULE_EXACT_ALARM)",
        "通知权限(POST_NOTIFICATIONS)" to "Notification permission (POST_NOTIFICATIONS)",
        "日历权限(READ/WRITE_CALENDAR)" to "Calendar permission (READ/WRITE_CALENDAR)",
        "正在查询已安装应用" to "Querying installed apps",
        "未找到匹配的已安装应用。" to "No matching installed apps found.",
        "查询已安装应用失败" to "Failed to query installed apps",
        "浏览器操作失败" to "Browser action failed",
        "请提供继续执行所需的信息。" to "Please provide the information required to continue.",
        "任务已取消" to "Task cancelled",
        "正在调用内嵌终端环境执行命令" to "Running a command in the embedded terminal environment",
        "正在调用内嵌 Alpine 终端执行命令" to "Running a command in the embedded terminal environment",
        "终端输出更新中" to "Terminal output is updating",
        "终端命令执行失败" to "Terminal command failed",
        "正在启动内嵌终端会话" to "Starting embedded terminal session",
        "打开工作区" to "Open Workspace",
        "终端会话启动失败" to "Failed to start terminal session",
        "正在向终端会话发送命令" to "Sending command to terminal session",
        "会话命令仍在运行，请先读取输出确认状态" to
            "The session command is still running. Read the output first to confirm its state.",
        "会话命令执行完成" to "Session command completed",
        "会话命令执行失败" to "Session command failed",
        "终端会话命令执行失败" to "Failed to execute terminal session command",
        "终端会话暂无输出" to "Terminal session has no output yet",
        "已读取终端会话输出" to "Read terminal session output",
        "读取终端会话失败" to "Failed to read terminal session output",
        "正在结束终端会话" to "Stopping terminal session",
        "缺少 sessionId" to "Missing sessionId",
        "结束终端会话失败" to "Failed to stop terminal session",
        "读取文件失败" to "Failed to read file",
        "正在写入文件" to "Writing file",
        "缺少 content" to "Missing content",
        "写入文件失败" to "Failed to write file",
        "正在编辑文件" to "Editing file",
        "缺少 oldText" to "Missing oldText",
        "文件中未找到 oldText" to "oldText was not found in the file",
        "编辑文件失败" to "Failed to edit file",
        "打开目录" to "Open Directory",
        "列目录失败" to "Failed to list directory",
        "缺少 query" to "Missing query",
        "未找到匹配结果" to "No matching results found",
        "搜索文件失败" to "Failed to search files",
        "查看文件信息失败" to "Failed to inspect file info",
        "正在移动文件" to "Moving file",
        "移动文件失败" to "Failed to move file",
        "当前没有匹配的 skills" to "No matching skills found",
        "列出 skills 失败" to "Failed to list skills",
        "缺少 skillId" to "Missing skillId",
        "当前环境不可用" to "Current environment unavailable",
        "读取 skill 失败" to "Failed to read skill",
        "正在创建定时任务" to "Creating scheduled task",
        "targetKind 仅支持 subagent" to "`targetKind` only supports `subagent`",
        "subagent 定时任务缺少 subagentPrompt" to
            "Missing `subagentPrompt` for the subagent scheduled task",
        "定时任务已创建" to "Scheduled task created",
        "正在读取定时任务列表" to "Loading scheduled tasks",
        "当前没有定时任务。" to "There are no scheduled tasks.",
        "正在更新定时任务" to "Updating scheduled task",
        "定时任务已更新" to "Scheduled task updated",
        "正在删除定时任务" to "Deleting scheduled task",
        "定时任务已删除" to "Scheduled task deleted",
        "正在创建提醒闹钟" to "Creating reminder alarm",
        "title 不能为空" to "`title` cannot be empty",
        "triggerAt 不能为空" to "`triggerAt` cannot be empty",
        "提醒闹钟已创建" to "Reminder alarm created",
        "正在读取提醒闹钟列表" to "Loading reminder alarms",
        "当前没有提醒闹钟。" to "There are no reminder alarms.",
        "正在删除提醒闹钟" to "Deleting reminder alarm",
        "alarmId 不能为空" to "`alarmId` cannot be empty",
        "提醒闹钟已删除" to "Reminder alarm deleted",
        "正在请求日历权限" to "Requesting calendar permission",
        "正在读取日历列表" to "Loading calendars",
        "未找到符合条件的日历。" to "No calendars matched the criteria.",
        "正在创建日程" to "Creating calendar event",
        "startAt 不能为空" to "`startAt` cannot be empty",
        "endAt 不能为空" to "`endAt` cannot be empty",
        "日程已创建" to "Calendar event created",
        "正在查询日程" to "Querying calendar events",
        "正在修改日程" to "Updating calendar event",
        "eventId 不能为空" to "`eventId` cannot be empty",
        "日程已更新" to "Calendar event updated",
        "正在删除日程" to "Deleting calendar event",
        "日程已删除" to "Calendar event deleted",
        "action 不能为空" to "`action` cannot be empty",
        "正在发送系统播放命令" to "Sending system play command",
        "正在准备播放音频" to "Preparing audio playback",
        "正在暂停播放" to "Pausing playback",
        "正在恢复播放" to "Resuming playback",
        "正在停止播放" to "Stopping playback",
        "正在调整播放进度" to "Seeking playback",
        "正在读取播放状态" to "Reading playback status",
        "正在切换到下一首" to "Skipping to the next track",
        "正在切换到上一首" to "Going back to the previous track",
        "正在执行音乐播放控制" to "Running music playback control",
        "seek 动作需要提供 positionSeconds" to "`seek` requires `positionSeconds`",
        "音乐播放控制已执行" to "Music playback control executed",
        "正在检索 workspace 记忆" to "Searching workspace memory",
        "query 不能为空" to "`query` cannot be empty",
        "未命中相关记忆。" to "No relevant memory hits found.",
        "正在写入当日记忆" to "Writing daily memory",
        "text 不能为空" to "`text` cannot be empty",
        "已写入当日记忆" to "Daily memory written",
        "已写入当日短期记忆。" to "Short-term memory for today has been written.",
        "正在沉淀长期记忆" to "Writing long-term memory",
        "已写入长期记忆" to "Long-term memory written",
        "已沉淀一条长期记忆。" to "One long-term memory entry has been stored.",
        "正在整理当日记忆" to "Rolling up daily memory",
        "记忆整理完成" to "Memory rollup completed",
        "tasks 不能为空" to "`tasks` cannot be empty",
        "当前会话仍有命令在执行，请先读取输出或停止会话。" to
            "A command is still running in the current session. Read its output or stop the session first.",
        "终端会话初始化超时，可能仍在后台继续运行。" to
            "Terminal session initialization timed out and may still be running in the background.",
        "终端命令等待超时，可能仍在后台继续运行。" to
            "Terminal command timed out and may still be running in the background.",
        "正在执行 Shizuku 高级动作" to "Executing privileged Shizuku action",
        "正在启动高权限 Shizuku 会话" to "Starting privileged Shizuku session",
        "正在执行高权限 Shizuku 命令" to "Running privileged Shizuku command",
        "高权限会话不存在或不属于当前 workspace：" to
            "Privileged session does not exist or does not belong to the current workspace: ",
        "高权限会话已启动：" to "Privileged session started: ",
        "高权限会话已结束：" to "Privileged session stopped: ",
        "高权限会话暂无输出" to "Privileged session has no output yet",
        "已读取高权限会话输出" to "Read privileged session output",
        "高权限会话启动失败" to "Failed to start privileged session",
        "高权限命令执行完成" to "Privileged command completed",
        "高权限会话命令执行失败" to "Failed to execute privileged session command",
        "读取高权限会话失败" to "Failed to read privileged session output",
        "正在结束高权限会话" to "Stopping privileged session",
        "结束高权限会话失败" to "Failed to stop privileged session",
        "Shizuku 动作执行失败" to "Shizuku action failed",
        "command 不能为空" to "`command` cannot be empty",
        "terminal_execute 缺少 command" to "`terminal_execute` is missing `command`",
        "executionMode 仅支持 termux 或 proot" to
            "`executionMode` only supports `termux` or `proot`",
        "缺少 command" to "Missing command"
    )

    suspend fun ensureRunActive() {
        currentCoroutineContext().ensureActive()
    }

    fun localized(text: String?): String {
        if (text == null) {
            return ""
        }
        if (!isEnglishLocale) {
            return AgentTerminalDistributionText.makeDistributionExplicit(
                text,
                terminalDistribution,
                english = false
            )
        }
        englishTextMap[text]?.let {
            return AgentTerminalDistributionText.makeDistributionExplicit(
                it,
                terminalDistribution,
                english = true
            )
        }

        when {
            text.startsWith("终端会话已启动：") ->
                return terminalText(
                    zhCN = "${terminalDistribution.displayName} 会话已启动：${text.removePrefix("终端会话已启动：")}",
                    enUS = "${terminalDistribution.displayName} session started: ${text.removePrefix("终端会话已启动：")}"
                )
            text.startsWith("终端会话不存在或不属于当前 workspace：") ->
                return "${terminalDistribution.displayName} session does not exist or does not belong to the current workspace: ${text.removePrefix("终端会话不存在或不属于当前 workspace：")}"
            text.startsWith("终端会话不存在或已结束：") ->
                return "${terminalDistribution.displayName} session does not exist or has already ended: ${text.removePrefix("终端会话不存在或已结束：")}"
            text.startsWith("文件不存在：") ->
                return "File does not exist: ${text.removePrefix("文件不存在：")}"
            text.startsWith("目标不是文件：") ->
                return "Target is not a file: ${text.removePrefix("目标不是文件：")}"
            text.startsWith("已读取文件：") ->
                return "Read file: ${text.removePrefix("已读取文件：")}"
            text.startsWith("已追加写入文件：") ->
                return "Appended to file: ${text.removePrefix("已追加写入文件：")}"
            text.startsWith("已写入文件：") ->
                return "Wrote file: ${text.removePrefix("已写入文件：")}"
            text.startsWith("目标文件不存在：") ->
                return "Target file does not exist: ${text.removePrefix("目标文件不存在：")}"
            text.startsWith("目录不存在：") ->
                return "Directory does not exist: ${text.removePrefix("目录不存在：")}"
            text.startsWith("路径不存在：") ->
                return "Path does not exist: ${text.removePrefix("路径不存在：")}"
            text.startsWith("已读取路径信息：") ->
                return "Read path info: ${text.removePrefix("已读取路径信息：")}"
            text.startsWith("源文件不存在：") ->
                return "Source file does not exist: ${text.removePrefix("源文件不存在：")}"
            text.startsWith("目标已存在：") ->
                return "Target already exists: ${text.removePrefix("目标已存在：")}"
            text.startsWith("已移动到：") ->
                return "Moved to: ${text.removePrefix("已移动到：")}"
            text.startsWith("未找到 skill：") ->
                return "Skill not found: ${text.removePrefix("未找到 skill：")}"
            text.startsWith("读取 SKILL.md 失败：") ->
                return "Failed to read SKILL.md: ${text.removePrefix("读取 SKILL.md 失败：")}"
            text.startsWith("已读取 skill：") ->
                return "Read skill: ${text.removePrefix("已读取 skill：")}"
            text.startsWith("终端会话不存在或不属于当前 agent：") ->
                return "${terminalDistribution.displayName} session does not exist or does not belong to the current agent: ${text.removePrefix("终端会话不存在或不属于当前 agent：")}"
            text.startsWith("终端会话不存在：") ->
                return "${terminalDistribution.displayName} session does not exist: ${text.removePrefix("终端会话不存在：")}"
            text.startsWith("不支持的 action：") ->
                return "Unsupported action: ${text.removePrefix("不支持的 action：")}"
            text.startsWith("已执行 Shizuku 动作：") ->
                return "Executed Shizuku action: ${text.removePrefix("已执行 Shizuku 动作：")}"
            text.startsWith("Shizuku 动作执行失败：") ->
                return "Shizuku action failed: ${text.removePrefix("Shizuku 动作执行失败：")}"
            text.startsWith("当前 Shizuku 后端不支持该动作：") ->
                return "This Shizuku backend does not support the action: ${text.removePrefix("当前 Shizuku 后端不支持该动作：")}"
            text.startsWith("高权限会话已启动：") ->
                return "Privileged session started: ${text.removePrefix("高权限会话已启动：")}"
            text.startsWith("高权限会话不存在或不属于当前 workspace：") ->
                return "Privileged session does not exist or does not belong to the current workspace: ${text.removePrefix("高权限会话不存在或不属于当前 workspace：")}"
            text.startsWith("高权限会话已结束：") ->
                return "Privileged session stopped: ${text.removePrefix("高权限会话已结束：")}"
            text.startsWith("正在调用 ") && text.contains(" 的 ") -> {
                val remainder = text.removePrefix("正在调用 ")
                val parts = remainder.split(" 的 ", limit = 2)
                if (parts.size == 2) {
                    return "Calling ${parts[0]} / ${parts[1]}"
                }
            }
        }

        Regex("^(\\d+) 个已安装应用。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} installed apps."
        }
        Regex("^共找到 (\\d+) 项$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} items."
        }
        Regex("^找到 (\\d+) 个匹配结果$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} matching results."
        }
        Regex("^共找到 (\\d+) 个 skill$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} skills."
        }
        Regex("^当前共有 (\\d+) 个定时任务。$").matchEntire(text)?.let {
            return "There are currently ${it.groupValues[1]} scheduled tasks."
        }
        Regex("^当前共有 (\\d+) 个提醒闹钟。$").matchEntire(text)?.let {
            return "There are currently ${it.groupValues[1]} reminder alarms."
        }
        Regex("^找到 (\\d+) 个日历。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} calendars."
        }
        Regex("^找到 (\\d+) 条日程。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} calendar events."
        }
        Regex("^命中 (\\d+) 条记忆（词法检索）。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} memory hits (lexical fallback)."
        }
        Regex("^命中 (\\d+) 条记忆。$").matchEntire(text)?.let {
            return "Found ${it.groupValues[1]} memory hits."
        }
        Regex("^正在分派 (\\d+) 个子任务（并发 (\\d+)）$").matchEntire(text)?.let {
            return "Dispatching ${it.groupValues[1]} subtasks (concurrency ${it.groupValues[2]})"
        }
        Regex("^SubAgent #(\\d+) 开始：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} started: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 开始思考$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} started thinking"
        }
        Regex("^SubAgent #(\\d+) 思考：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} thinking: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 调用工具：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} called tool: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 工具进度：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} tool progress: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 工具完成：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} completed tool: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 输出：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} output: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 得到结果：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} result: ${it.groupValues[2]}"
        }
        Regex("^SubAgent #(\\d+) 失败：(.*)$").matchEntire(text)?.let {
            return "SubAgent #${it.groupValues[1]} failed: ${it.groupValues[2]}"
        }
        Regex("^已完成子任务：(.*)$").matchEntire(text)?.let {
            return "Completed subtask: ${it.groupValues[1]}"
        }
        Regex("^已完成 (\\d+) 个 subagent 子任务。$").matchEntire(text)?.let {
            return "Completed ${it.groupValues[1]} subagent subtasks."
        }
        return AgentTerminalDistributionText.makeDistributionExplicit(
            text,
            terminalDistribution,
            english = true
        )
    }

    fun terminalText(zhCN: String, enUS: String): String {
        val text = if (isEnglishLocale) enUS else zhCN
        return AgentTerminalDistributionText.resolve(text, terminalDistribution)
    }

    private fun localizePayloadValue(value: Any?, key: String? = null): Any? {
        return when (value) {
            is String -> if (
                key in setOf(
                    "summary",
                    "message",
                    "error",
                    "errorMessage",
                    "result",
                    "lastProgress",
                    "subagentStatusText"
                )
            ) {
                localized(value)
            } else {
                value
            }
            is Map<*, *> -> value.entries.associate { (entryKey, item) ->
                entryKey.toString() to localizePayloadValue(item, entryKey.toString())
            }
            is Iterable<*> -> value.map { localizePayloadValue(it, key) }
            else -> value
        }
    }

    fun localizeExtras(extras: Map<String, Any?>): Map<String, Any?> {
        return extras.mapValues { (key, value) -> localizePayloadValue(value, key) }
    }

    fun encodeLocalizedPayload(payload: Any?): String {
        return json.encodeToString(mapToJsonElement(localizePayloadValue(payload)))
    }

    private fun localizeMissingPermissions(missing: List<String>): List<String> {
        return if (isEnglishLocale) missing.map(::localized) else missing
    }

    suspend fun permissionRequiredResult(
        callback: AgentCallback,
        missing: List<String>
    ): ToolExecutionResult.PermissionRequired {
        val localizedMissing = localizeMissingPermissions(missing)
        callback.onPermissionRequired(localizedMissing)
        return ToolExecutionResult.PermissionRequired(localizedMissing)
    }

    fun errorResult(
        toolName: String,
        message: String?,
        fallbackMessage: String
    ): ToolExecutionResult.Error {
        return ToolExecutionResult.Error(toolName, localized(message ?: fallbackMessage))
    }

    suspend fun reportToolProgress(
        callback: AgentCallback,
        toolName: String,
        progress: String,
        extras: Map<String, Any?> = emptyMap(),
        toolHandle: AgentToolExecutionHandle? = null
    ) {
        val localizedProgress = localized(progress)
        val localizedExtras = localizeExtras(extras)
        toolHandle?.throwIfStopRequested()
        toolHandle?.recordProgress(localizedProgress, localizedExtras)
        callback.onToolCallProgress(toolName, localizedProgress, localizedExtras)
        toolHandle?.throwIfStopRequested()
        ensureRunActive()
    }

    fun firstUsefulLine(text: String): String? {
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.let { if (it.length <= 120) it else it.take(120) + "..." }
    }

    fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
        return jsonObject.entries.associate { (key, value) ->
            key to jsonElementToAny(value)
        }
    }

    fun jsonObjectToStringMap(
        jsonObject: JsonObject,
        excludedKeys: Set<String> = emptySet()
    ): Map<String, String> {
        return jsonObject.entries
            .filterNot { (key, _) -> excludedKeys.contains(key) }
            .associate { (key, value) ->
                key to (jsonElementToAny(value)?.toString() ?: "")
            }
    }

    fun jsonElementToAny(element: JsonElement): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonObject -> jsonObjectToMap(element)
            is JsonArray -> element.map { jsonElementToAny(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" || element.content == "false" -> element.content.toBooleanStrict()
                element.longOrNull != null -> element.longOrNull
                element.doubleOrNull != null -> element.doubleOrNull
                else -> element.content
            }
        }
    }

    fun mapToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Map<*, *> -> JsonObject(
                value.entries.associate { (key, item) ->
                    key.toString() to mapToJsonElement(item)
                }
            )
            is List<*> -> JsonArray(value.map { mapToJsonElement(it) })
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    fun parseIntegerArray(raw: JsonArray?): List<Int> {
        if (raw == null) return emptyList()
        return raw.mapNotNull { item ->
            (item as? JsonPrimitive)?.intOrNull
        }
    }

    fun parseEnvironmentMap(raw: JsonObject?): Map<String, String> {
        return raw?.let { jsonObjectToStringMap(it) } ?: emptyMap()
    }

    fun parseConfirmedFlag(raw: JsonElement?): Boolean {
        val primitive = raw as? JsonPrimitive ?: return false
        return primitive.booleanOrNull == true ||
            primitive.contentOrNull.isTruthyFlag()
    }

    fun String?.isTruthyFlag(): Boolean {
        return this?.trim()?.lowercase() in setOf("1", "true", "yes", "confirm", "confirmed", "on")
    }

    suspend fun requireWorkspaceStorageAccess(
        callback: AgentCallback
    ): ToolExecutionResult.PermissionRequired? {
        if (WorkspaceStorageAccess.isGranted(context)) {
            return null
        }
        val missing = WorkspaceStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    suspend fun requirePublicStorageAccessIfNeeded(
        callback: AgentCallback,
        vararg inputPaths: String?
    ): ToolExecutionResult.PermissionRequired? {
        val needsPublicStorage = inputPaths.any { PublicStorageAccess.isPublicStorageInput(it) }
        if (!needsPublicStorage || PublicStorageAccess.isGranted()) {
            return null
        }
        val missing = PublicStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    suspend fun workspacePermissionResult(
        error: Exception,
        callback: AgentCallback
    ): ToolExecutionResult.PermissionRequired? {
        if (!WorkspaceStorageAccess.looksLikePermissionError(error)) {
            return null
        }
        val missing = WorkspaceStorageAccess.requiredPermissionNames()
        return permissionRequiredResult(callback, missing)
    }

    fun buildOpenDirectoryAction(
        workspaceManager: AgentWorkspaceManager,
        workspace: AgentWorkspaceDescriptor,
        directory: java.io.File,
        label: String = "打开目录"
    ): ArtifactAction {
        val target = workspaceManager.uriForFile(directory) ?: directory.absolutePath
        return ArtifactAction(
            type = "workspace",
            label = localized(label),
            target = target,
            payload = mapOf(
                "workspaceId" to workspace.id,
                "workspacePath" to directory.absolutePath,
                "workspaceShellPath" to (workspaceManager.shellPathForAndroid(directory)
                    ?: directory.absolutePath)
            )
        )
    }
}
