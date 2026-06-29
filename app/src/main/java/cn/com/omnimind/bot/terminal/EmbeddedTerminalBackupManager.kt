package cn.com.omnimind.bot.terminal

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.workspace.PublicStorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class EmbeddedTerminalBackupManager(
    private val context: Context
) {
    data class BackupStatusSnapshot(
        val configured: Boolean,
        val enabled: Boolean,
        val toolPath: String,
        val source: String,
        val repository: String,
        val passwordFile: String,
        val publicStorageGranted: Boolean,
        val resticReady: Boolean,
        val schedulerRunning: Boolean,
        val watchdogRunning: Boolean,
        val lastSuccessEpoch: Long?,
        val lastSuccessLocal: String?,
        val rawOutput: String,
        val message: String?
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "configured" to configured,
            "enabled" to enabled,
            "toolPath" to toolPath,
            "source" to source,
            "repository" to repository,
            "passwordFile" to passwordFile,
            "publicStorageGranted" to publicStorageGranted,
            "resticReady" to resticReady,
            "schedulerRunning" to schedulerRunning,
            "watchdogRunning" to watchdogRunning,
            "lastSuccessEpoch" to lastSuccessEpoch,
            "lastSuccessLocal" to lastSuccessLocal,
            "rawOutput" to rawOutput,
            "message" to message
        )
    }

    data class BackupCommandResult(
        val success: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
        val message: String,
        val output: String,
        val status: BackupStatusSnapshot?
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "success" to success,
            "timedOut" to timedOut,
            "exitCode" to exitCode,
            "message" to message,
            "output" to output,
            "status" to status?.toMap()
        )
    }

    companion object {
        private const val TAG = "EmbeddedTerminalBackup"
        private const val ASSET_ROOT = "backup-tool"
        private const val CONFIG_FILE_NAME = "config.env"
        private const val SHELL_TOOL_PATH = "${AgentWorkspaceManager.SHELL_ROOT_PATH}/.omnibot/backup"
        private const val BACKUP_SCHEDULER_SESSION_ID = "omnibot_backup_scheduler"
    }

    suspend fun getStatus(): BackupStatusSnapshot = withContext(Dispatchers.IO) {
        ensureInstalled(createConfig = false)
        readStatus()
    }

    suspend fun setEnabled(enabled: Boolean): BackupStatusSnapshot = withContext(Dispatchers.IO) {
        ensureInstalled(createConfig = true)
        val nextConfig = mergedConfig().toMutableMap()
        nextConfig["BACKUP_ENABLED"] = if (enabled) "1" else "0"
        if (enabled) {
            ensureStorageAccessForRepository(nextConfig["BACKUP_REPOSITORY"].orEmpty())
        }
        writeConfig(nextConfig)

        val script = if (enabled) {
            "$SHELL_TOOL_PATH/install-cron.sh"
        } else {
            "$SHELL_TOOL_PATH/uninstall-cron.sh"
        }
        val result = runScript(script = script, timeoutSeconds = 180)
        if (!result.success) {
            throw IllegalStateException(
                result.errorMessage
                    ?: result.output.takeLast(1200).ifBlank { "Failed to update backup scheduler." }
            )
        }
        readStatus()
    }

    suspend fun runBackupNow(): BackupCommandResult = withContext(Dispatchers.IO) {
        ensureInstalled(createConfig = false)
        val config = mergedConfig()
        if (config["BACKUP_ENABLED"] != "1") {
            return@withContext BackupCommandResult(
                success = false,
                timedOut = false,
                exitCode = null,
                message = "自动备份尚未启用。",
                output = "",
                status = readStatus()
            )
        }
        ensureStorageAccessForRepository(config["BACKUP_REPOSITORY"].orEmpty())
        val result = runScript(script = "$SHELL_TOOL_PATH/bin/backup.sh", timeoutSeconds = 3600)
        BackupCommandResult(
            success = result.success,
            timedOut = result.timedOut,
            exitCode = result.exitCode,
            message = result.errorMessage
                ?: result.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                ?: if (result.success) "备份完成。" else "备份失败。",
            output = result.output,
            status = readStatus()
        )
    }

    suspend fun ensureOnAppOpen() = withContext(Dispatchers.IO) {
        val configFile = configFile()
        if (!configFile.isFile) {
            return@withContext
        }
        if (readConfig(configFile)["BACKUP_ENABLED"] != "1") {
            return@withContext
        }
        runCatching {
            ensureInstalled(createConfig = true)
            EmbeddedTerminalRuntime.launchBackgroundServiceSession(
                context = context,
                sessionId = BACKUP_SCHEDULER_SESSION_ID,
                command = "/bin/sh ${TermuxCommandBuilder.quoteForShell("$SHELL_TOOL_PATH/bin/ensure-scheduler.sh")}",
                workingDirectory = AgentWorkspaceManager.SHELL_ROOT_PATH
            )
        }.onFailure { error ->
            OmniLog.e(TAG, "Failed to ensure backup scheduler on app open", error)
        }
    }

    private suspend fun readStatus(): BackupStatusSnapshot {
        val config = readConfig(configFile())
        if (config.isEmpty()) {
            return buildStatusSnapshot(
                configured = false,
                config = defaultConfig(),
                parsedStatus = emptyMap(),
                rawOutput = "",
                message = null
            )
        }

        val result = runScript(script = "$SHELL_TOOL_PATH/bin/status.sh", timeoutSeconds = 45)
        val rawOutput = result.output.ifBlank { result.errorMessage.orEmpty() }
        val parsedStatus = parseStatusOutput(rawOutput)
        return buildStatusSnapshot(
            configured = true,
            config = mergedConfig(config),
            parsedStatus = parsedStatus,
            rawOutput = rawOutput,
            message = if (result.success) null else result.errorMessage
        )
    }

    private fun buildStatusSnapshot(
        configured: Boolean,
        config: Map<String, String>,
        parsedStatus: Map<String, String>,
        rawOutput: String,
        message: String?
    ): BackupStatusSnapshot {
        val resticStatus = parsedStatus["restic"].orEmpty()
        val crondStatus = parsedStatus["crond"].orEmpty()
        val watchdogStatus = parsedStatus["watchdog"].orEmpty()
        val enabledValue = parsedStatus["enabled"] ?: config["BACKUP_ENABLED"].orEmpty()
        val lastSuccessEpoch = parsedStatus["LAST_SUCCESS_EPOCH"]?.toLongOrNull()
        return BackupStatusSnapshot(
            configured = configured,
            enabled = enabledValue == "1",
            toolPath = SHELL_TOOL_PATH,
            source = parsedStatus["source"] ?: config["BACKUP_SOURCE"].orEmpty(),
            repository = parsedStatus["repository"] ?: config["BACKUP_REPOSITORY"].orEmpty(),
            passwordFile = parsedStatus["password_file"] ?: config["RESTIC_PASSWORD_FILE"].orEmpty(),
            publicStorageGranted = PublicStorageAccess.isGranted(),
            resticReady = resticStatus.startsWith("restic "),
            schedulerRunning = crondStatus.isNotBlank() && !crondStatus.contains("not running", ignoreCase = true),
            watchdogRunning = watchdogStatus.contains("running", ignoreCase = true),
            lastSuccessEpoch = lastSuccessEpoch,
            lastSuccessLocal = parsedStatus["LAST_SUCCESS_LOCAL"],
            rawOutput = rawOutput,
            message = message
        )
    }

    private suspend fun runScript(
        script: String,
        timeoutSeconds: Int
    ): EmbeddedTerminalRuntime.CommandResult {
        return EmbeddedTerminalRuntime.executeCommand(
            context = context,
            command = "/bin/sh ${TermuxCommandBuilder.quoteForShell(script)}",
            workingDirectory = AgentWorkspaceManager.SHELL_ROOT_PATH,
            timeoutSeconds = timeoutSeconds
        )
    }

    private fun ensureInstalled(createConfig: Boolean) {
        AgentWorkspaceManager(context).ensureRuntimeDirectories()
        val toolDirectory = toolDirectory()
        toolDirectory.mkdirs()
        copyAssetTree(ASSET_ROOT, toolDirectory)
        markScriptsExecutable(toolDirectory)
        if (createConfig) {
            writeConfig(mergedConfig())
        }
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            if (target.name == "excludes.txt" && target.isFile) {
                return
            }
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private fun markScriptsExecutable(directory: File) {
        directory.walkTopDown()
            .filter { file -> file.isFile && file.extension == "sh" }
            .forEach { file ->
                file.setReadable(true, false)
                file.setExecutable(true, false)
            }
    }

    private fun mergedConfig(existing: Map<String, String> = readConfig(configFile())): LinkedHashMap<String, String> {
        val merged = LinkedHashMap<String, String>()
        merged.putAll(defaultConfig())
        existing.forEach { (key, value) ->
            if (key.isNotBlank()) {
                merged[key] = value
            }
        }
        return merged
    }

    private fun defaultConfig(): LinkedHashMap<String, String> {
        return linkedMapOf(
            "BACKUP_ENABLED" to "0",
            "BACKUP_SOURCE" to context.applicationInfo.dataDir,
            "BACKUP_REPOSITORY" to "/sdcard/Backups/omnibot-restic-repo",
            "RESTIC_PASSWORD_FILE" to "/sdcard/Backups/omnibot-restic-password",
            "EXCLUDES_FILE" to "$SHELL_TOOL_PATH/excludes.txt",
            "LOG_DIR" to "$SHELL_TOOL_PATH/logs",
            "LOCK_FILE" to "$SHELL_TOOL_PATH/backup.lock",
            "STATE_DIR" to "$SHELL_TOOL_PATH/state",
            "LAST_SUCCESS_FILE" to "$SHELL_TOOL_PATH/state/last-success.env",
            "SCHEDULER_LOCK_FILE" to "$SHELL_TOOL_PATH/scheduler.lock",
            "WATCHDOG_LOCK_FILE" to "$SHELL_TOOL_PATH/watchdog.lock",
            "WATCHDOG_PID_FILE" to "$SHELL_TOOL_PATH/watchdog.pid",
            "SCHEDULER_LOG_FILE" to "$SHELL_TOOL_PATH/logs/scheduler.log",
            "STARTUP_LOCK_FILE" to "$SHELL_TOOL_PATH/startup.lock",
            "STARTUP_LOG_FILE" to "$SHELL_TOOL_PATH/logs/startup.log",
            "BACKUP_TIMEZONE" to "Asia/Shanghai",
            "CRON_SCHEDULE" to "17 19 * * *",
            "CRON_CATCH_UP_SCHEDULE" to "*/30 * * * *",
            "CRON_DISPLAY_TIME" to "03:17 Beijing",
            "BACKUP_DAILY_TIME" to "03:17",
            "CATCH_UP_GRACE_MINUTES" to "10",
            "WATCHDOG_INTERVAL_SECONDS" to "600",
            "START_BACKUP_WATCHDOG" to "1",
            "CHECK_AFTER_BACKUP" to "1",
            "CHECK_READ_DATA_SUBSET" to "",
            "REQUIRE_FREE_PERCENT_OF_SOURCE" to "120",
            "MIN_FREE_KB_AFTER_BACKUP" to "1048576"
        )
    }

    private fun writeConfig(config: Map<String, String>) {
        val file = configFile()
        file.parentFile?.mkdirs()
        val content = buildString {
            appendLine("# Omnibot automatic backup configuration.")
            appendLine("# Password contents are stored only in RESTIC_PASSWORD_FILE.")
            config.forEach { (key, value) ->
                append(key)
                append('=')
                appendLine(quoteShellValue(value))
            }
        }
        file.writeText(content)
        file.setReadable(true, false)
    }

    private fun readConfig(file: File): Map<String, String> {
        if (!file.isFile) {
            return emptyMap()
        }
        return file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    return@mapNotNull null
                }
                val assignment = trimmed.removePrefix("export ").trim()
                val separatorIndex = assignment.indexOf('=')
                if (separatorIndex <= 0) {
                    return@mapNotNull null
                }
                val key = assignment.substring(0, separatorIndex).trim()
                val value = unquoteShellValue(assignment.substring(separatorIndex + 1).trim())
                key to value
            }
            .toMap()
    }

    private fun parseStatusOutput(output: String): Map<String, String> {
        val parsed = linkedMapOf<String, String>()
        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) {
                return@forEach
            }
            if (line.startsWith("LAST_SUCCESS_")) {
                val separatorIndex = line.indexOf('=')
                if (separatorIndex > 0) {
                    parsed[line.substring(0, separatorIndex)] = line.substring(separatorIndex + 1)
                }
                return@forEach
            }
            val separatorIndex = line.indexOf(':')
            if (separatorIndex > 0) {
                parsed[line.substring(0, separatorIndex).trim()] = line.substring(separatorIndex + 1).trim()
            }
        }
        return parsed
    }

    private fun ensureStorageAccessForRepository(repository: String) {
        if (PublicStorageAccess.isPublicStoragePath(repository) && !PublicStorageAccess.isGranted()) {
            throw IllegalStateException("请先授予公共文件访问权限，再启用自动备份。")
        }
    }

    private fun quoteShellValue(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun unquoteShellValue(value: String): String {
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("'\"'\"'", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
        }
        return value
    }

    private fun toolDirectory(): File {
        return File(AgentWorkspaceManager.internalRootDirectory(context), "backup")
    }

    private fun configFile(): File {
        return File(toolDirectory(), CONFIG_FILE_NAME)
    }
}
