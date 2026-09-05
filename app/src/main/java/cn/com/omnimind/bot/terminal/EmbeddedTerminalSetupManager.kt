package cn.com.omnimind.bot.terminal

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import com.ai.assistance.operit.terminal.utils.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EmbeddedTerminalSetupManager(
    private val context: Context
) {
    data class PackageDefinition(
        val id: String,
        val command: String,
        val categoryId: String
    )

    data class InstallResult(
        val success: Boolean,
        val message: String,
        val output: String = ""
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "success" to success,
            "message" to message,
            "output" to output
        )
    }

    data class PackageInventoryItem(
        val ready: Boolean,
        val version: String? = null
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "ready" to ready,
            "version" to version
        )
    }

    data class InstallSessionSnapshot(
        val sessionId: String? = null,
        val running: Boolean = false,
        val completed: Boolean = false,
        val success: Boolean? = null,
        val message: String = "",
        val selectedPackageIds: List<String> = emptyList()
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "sessionId" to sessionId,
            "running" to running,
            "completed" to completed,
            "success" to success,
            "message" to message,
            "selectedPackageIds" to selectedPackageIds
        )
    }

    private val sourceManager = SourceManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installSessionMutex = Mutex()
    @Volatile
    private var installSessionSnapshot = InstallSessionSnapshot()

    private val packageDefinitions: List<PackageDefinition> =
        EnvironmentSetupLogic.packageDefinitions.map { pkg ->
            PackageDefinition(id = pkg.id, command = pkg.command, categoryId = pkg.categoryId)
        }

    suspend fun getPackageInstallStatus(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        getPackageInventory().mapValues { it.value.ready }
    }

    suspend fun getPackageInventory(): Map<String, PackageInventoryItem> = withContext(Dispatchers.IO) {
        withLocalTerminalManager { manager ->
            val packageIds = packageDefinitions.map { it.id }
            val result = manager.executeHiddenCommand(
                command = EnvironmentSetupLogic.buildInventoryProbeCommand(packageIds),
                executorKey = "embedded-terminal-setup-inventory",
                timeoutMs = 30_000L
            )
            val cleanedOutput = EmbeddedTerminalRuntime.trimTerminalOutput(
                EmbeddedTerminalRuntime.sanitizeTerminalNoise(
                    result.output.ifBlank { result.rawOutputPreview }
                )
            )
            if (!result.isOk || result.exitCode != 0) {
                val details = cleanedOutput
                    .ifBlank { result.rawOutputPreview.trim() }
                    .ifBlank { result.error.trim() }
                    .takeLast(1000)
                throw IllegalStateException(
                    if (details.isNotBlank()) {
                        "终端环境启动失败，无法读取环境配置：$details"
                    } else {
                        "终端环境启动失败，无法读取环境配置。"
                    }
                )
            }
            val parsed = EnvironmentSetupLogic.parseInventoryProbeOutput(cleanedOutput)
            packageDefinitions.associate { pkg ->
                val probe = parsed[pkg.id]
                pkg.id to PackageInventoryItem(
                    ready = probe?.ready == true,
                    version = probe?.version
                )
            }
        }
    }

    suspend fun installPackages(
        selectedPackageIds: List<String>,
        onProgress: suspend (kind: String, message: String) -> Unit = { _, _ -> }
    ): InstallResult = withContext(Dispatchers.IO) {
        val requestedIds = selectedPackageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (requestedIds.isEmpty()) {
            return@withContext InstallResult(
                success = false,
                message = "请选择至少一个需要安装的组件。"
            )
        }

        try {
            onProgress("status", "正在检查所选开发工具")
            val currentStatus = getPackageInstallStatus()
            val installIds = requestedIds.filter { currentStatus[it] != true }
            if (installIds.isEmpty()) {
                onProgress("status", "所选开发工具已就绪")
                return@withContext InstallResult(
                    success = true,
                    message = "所选组件均已安装。"
                )
            }

            val commands = buildInstallCommands(installIds)
            if (commands.isEmpty()) {
                return@withContext InstallResult(
                    success = false,
                    message = "未生成任何安装命令，请检查所选组件。"
                )
            }

            onProgress("status", "正在安装所选开发工具：${installIds.joinToString(", ")}")
            val commandSequence = commands + EnvironmentSetupLogic.buildValidationCommands(installIds)
            val output = StringBuilder()
            val hiddenResult = withLocalTerminalManager { manager ->
                manager.executeHiddenCommand(
                    command = commandSequence.joinToString(separator = " && "),
                    executorKey = "embedded-terminal-setup",
                    timeoutMs = 15 * 60 * 1000L,
                    onOutputChunk = { chunk ->
                        val normalized = chunk.replace("\r\n", "\n").replace('\r', '\n')
                        if (normalized.isNotBlank()) {
                            output.append(normalized)
                            if (!normalized.endsWith("\n")) {
                                output.append('\n')
                            }
                            onProgress("output", normalized)
                        }
                    }
                )
            }

            if (!hiddenResult.isOk || hiddenResult.exitCode != 0) {
                val details = hiddenResult.output.trim()
                    .ifBlank { hiddenResult.rawOutputPreview.trim() }
                    .ifBlank { hiddenResult.error.trim() }
                return@withContext InstallResult(
                    success = false,
                    message = if (details.isNotBlank()) {
                        "环境配置失败：$details"
                    } else {
                        "环境配置失败，请稍后重试。"
                    },
                    output = output.toString().trim()
                )
            }

            onProgress("status", "正在验证所选开发工具")
            val refreshedStatus = getPackageInstallStatus()
            val remaining = installIds.filter { refreshedStatus[it] != true }
            if (remaining.isNotEmpty()) {
                val diagnostics = buildPostInstallDiagnostics(remaining)
                return@withContext InstallResult(
                    success = false,
                    message = buildInstallValidationFailureMessage(
                        remaining = remaining,
                        diagnostics = diagnostics
                    ),
                    output = output.toString().trim()
                )
            }

            onProgress("status", "开发环境配置完成")
            InstallResult(
                success = true,
                message = "环境配置完成：${installIds.joinToString(", ")}",
                output = output.toString().trim()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onProgress("error", error.message ?: "环境配置失败，请稍后重试。")
            InstallResult(
                success = false,
                message = error.message ?: "环境配置失败，请稍后重试。"
            )
        }
    }

    fun getInstallSessionSnapshot(): InstallSessionSnapshot = installSessionSnapshot

    suspend fun startInstallSession(selectedPackageIds: List<String>): InstallSessionSnapshot =
        withContext(Dispatchers.IO) {
            val requestedIds = selectedPackageIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (requestedIds.isEmpty()) {
                val snapshot = InstallSessionSnapshot(
                    running = false,
                    completed = true,
                    success = false,
                    message = "请选择至少一个需要安装的组件。",
                    selectedPackageIds = emptyList()
                )
                updateInstallSessionSnapshot(snapshot)
                return@withContext snapshot
            }

            installSessionMutex.withLock {
                val currentSnapshot = installSessionSnapshot
                if (currentSnapshot.running && !currentSnapshot.sessionId.isNullOrBlank()) {
                    return@withLock currentSnapshot
                }

                val currentStatus = getPackageInstallStatus()
                val installIds = requestedIds.filter { currentStatus[it] != true }
                if (installIds.isEmpty()) {
                    val snapshot = InstallSessionSnapshot(
                        running = false,
                        completed = true,
                        success = true,
                        message = "所选组件均已安装。",
                        selectedPackageIds = requestedIds
                    )
                    updateInstallSessionSnapshot(snapshot)
                    return@withLock snapshot
                }

                val commands = buildInstallCommands(installIds)
                if (commands.isEmpty()) {
                    val snapshot = InstallSessionSnapshot(
                        running = false,
                        completed = true,
                        success = false,
                        message = "未生成任何安装命令，请检查所选组件。",
                        selectedPackageIds = installIds
                    )
                    updateInstallSessionSnapshot(snapshot)
                    return@withLock snapshot
                }

                withLocalTerminalManager { manager ->
                    val previousSessionId = currentSnapshot.sessionId
                    if (!currentSnapshot.running && !previousSessionId.isNullOrBlank()) {
                        runCatching { manager.closeSession(previousSessionId) }
                    }

                    val session = manager.createNewSession("环境配置", TerminalType.LOCAL)
                    manager.switchToSession(session.id)

                    val startedSnapshot = InstallSessionSnapshot(
                        sessionId = session.id,
                        running = true,
                        completed = false,
                        success = null,
                        message = "环境配置进行中，请在下方终端查看安装输出。",
                        selectedPackageIds = installIds
                    )
                    updateInstallSessionSnapshot(startedSnapshot)

                    val commandSequence = commands + EnvironmentSetupLogic.buildValidationCommands(installIds)
                    scope.launch {
                        withLocalTerminalManager { sessionManager ->
                            sessionManager.sendCommandToSession(
                                sessionId = session.id,
                                command = commandSequence.joinToString(separator = " && ")
                            )
                        }
                    }

                    startedSnapshot
                }
            }
        }

    suspend fun dismissInstallSession() = withContext(Dispatchers.IO) {
        installSessionMutex.withLock {
            val snapshot = installSessionSnapshot
            val sessionId = snapshot.sessionId
            if (!sessionId.isNullOrBlank()) {
                withLocalTerminalManager { manager ->
                    runCatching { manager.closeSession(sessionId) }
                }
            }
            updateInstallSessionSnapshot(InstallSessionSnapshot())
        }
    }

    private suspend fun <T> withLocalTerminalManager(
        block: suspend (TerminalManager) -> T
    ): T {
        val manager = TerminalManager.getInstance(context)
        val previousType = manager.getPreferredTerminalType()
        manager.setPreferredTerminalType(TerminalType.LOCAL)
        return try {
            block(manager)
        } finally {
            manager.setPreferredTerminalType(previousType)
        }
    }

    private suspend fun updateInstallSessionSnapshot(snapshot: InstallSessionSnapshot) {
        installSessionSnapshot = snapshot
    }

    private fun buildInstallCommands(selectedPackageIds: List<String>): List<String> {
        return EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = selectedPackageIds,
            sourceManager = sourceManager,
            context = context,
        )
    }

    private suspend fun buildPostInstallDiagnostics(remainingIds: List<String>): String {
        if (!remainingIds.contains("nodejs")) {
            return ""
        }
        return withLocalTerminalManager { manager ->
            val result = manager.executeHiddenCommand(
                command = """
                    echo "node_path=${'$'}(command -v node 2>/dev/null || echo missing)"
                    echo "node_realpath=${'$'}(readlink -f "${'$'}(command -v node 2>/dev/null)" 2>/dev/null || echo missing)"
                    echo "node_version=${'$'}(node -v 2>&1 || echo missing)"
                    echo "npm_path=${'$'}(command -v npm 2>/dev/null || echo missing)"
                    echo "npm_version=${'$'}(npm -v 2>&1 || echo missing)"
                """.trimIndent(),
                executorKey = "embedded-terminal-setup-nodejs-diagnostics",
                timeoutMs = 20_000L
            )
            EmbeddedTerminalRuntime.trimTerminalOutput(
                EmbeddedTerminalRuntime.sanitizeTerminalNoise(
                    result.output.ifBlank { result.rawOutputPreview }
                )
            ).trim()
        }
    }

    private fun buildInstallValidationFailureMessage(
        remaining: List<String>,
        diagnostics: String,
        tailOutput: String = ""
    ): String {
        val message = StringBuilder("以下组件安装后仍未通过校验：${remaining.joinToString(", ")}")
        if (diagnostics.isNotBlank()) {
            message.append("\n")
            message.append(diagnostics)
        }
        if (tailOutput.isNotBlank()) {
            message.append("\n")
            message.append(tailOutput)
        }
        return message.toString()
    }

    private suspend fun checkPackageInstalled(
        manager: TerminalManager,
        pkg: PackageDefinition
    ): Boolean {
        val command = EnvironmentSetupLogic.buildCheckCommand(pkg.id, pkg.command)
        val result = manager.executeHiddenCommand(
            command = command,
            executorKey = "embedded-terminal-setup-check-${pkg.id}",
            timeoutMs = 20_000L
        )
        val output = result.output.trim()
        if (!result.isOk && output.isBlank()) {
            return false
        }
        return EnvironmentSetupLogic.isPackageInstalled(pkg.id, output)
    }
}
