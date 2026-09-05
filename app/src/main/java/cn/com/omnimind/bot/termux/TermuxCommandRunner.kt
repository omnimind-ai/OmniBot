package cn.com.omnimind.bot.termux

import android.content.Context
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import com.rk.terminal.runtime.TerminalDistribution

data class TermuxCommandSpec(
    val command: String,
    val executionMode: String = EXECUTION_MODE_TERMUX,
    val prootDistro: String? = null,
    val workingDirectory: String? = null,
    val timeoutSeconds: Int? = null,
    val environment: Map<String, String> = emptyMap()
) {
    companion object {
        const val EXECUTION_MODE_TERMUX = "termux"
        const val EXECUTION_MODE_PROOT = "proot"
    }
}

data class TermuxCommandResult(
    val success: Boolean,
    val timedOut: Boolean,
    val resultCode: Int?,
    val errorCode: Int?,
    val errorMessage: String?,
    val stdout: String,
    val stderr: String,
    val rawExtras: Map<String, Any?>,
    val terminalOutput: String = "",
    val liveSessionId: String? = null,
    val liveStreamState: String = "completed",
    val liveFallbackReason: String? = null
)

data class TermuxLiveUpdate(
    val sessionId: String,
    val summary: String,
    val outputDelta: String = "",
    val streamState: String = "running"
)

data class TermuxLiveEnvironmentResult(
    val success: Boolean,
    val wrapperReady: Boolean,
    val sharedStorageReady: Boolean,
    val message: String
)

object TermuxCommandBuilder {
    internal const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    internal const val TERMUX_BASH_PATH = "$TERMUX_PREFIX/bin/bash"
    internal const val TERMUX_PROOT_DISTRO_PATH = "$TERMUX_PREFIX/bin/proot-distro"

    fun buildWrappedCommand(spec: TermuxCommandSpec): String {
        val normalizedCommand = spec.command.trim()
        require(normalizedCommand.isNotEmpty()) { "command 不能为空" }
        val workingDirectory = spec.workingDirectory?.trim().orEmpty()
        return if (workingDirectory.isBlank()) {
            normalizedCommand
        } else {
            "cd ${quoteForShell(workingDirectory)} && $normalizedCommand"
        }
    }

    internal fun buildWorkspaceBindArguments(): List<String> {
        return listOf(
            "--bind",
            "${AgentWorkspaceManager.androidRootPath(App.instance)}:${AgentWorkspaceManager.SHELL_ROOT_PATH}"
        )
    }

    internal fun quoteForShell(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

object TermuxCommandRunner {
    const val LIVE_STREAM_STATE_RUNNING = "running"
    const val LIVE_STREAM_STATE_COMPLETED = "completed"
    const val LIVE_STREAM_STATE_FALLBACK = "fallback"

    suspend fun execute(
        context: Context,
        spec: TermuxCommandSpec,
        onProcessStarted: ((Process) -> Unit)? = null,
        onLiveUpdate: suspend (TermuxLiveUpdate) -> Unit = {}
    ): TermuxCommandResult {
        val normalizedSpec = normalizeSpec(spec)
        val result = EmbeddedTerminalRuntime.executeCommand(
            context = context,
            command = normalizedSpec.command,
            workingDirectory = normalizedSpec.workingDirectory,
            timeoutSeconds = normalizedSpec.timeoutSeconds,
            environment = normalizedSpec.environment,
            onProcessStarted = onProcessStarted,
            onLiveUpdate = onLiveUpdate
        )

        val sanitizedOutput = sanitizeTerminalNoise(result.output)
        val trimmedOutput = trimTerminalOutput(sanitizedOutput)
        val stdout = if (result.success) trimmedOutput else ""
        val stderr = if (result.success) "" else trimmedOutput

        return TermuxCommandResult(
            success = result.success,
            timedOut = result.timedOut,
            resultCode = result.exitCode,
            errorCode = if (result.success || result.exitCode == null) null else result.exitCode,
            errorMessage = result.errorMessage,
            stdout = stdout,
            stderr = stderr,
            rawExtras = result.rawExtras,
            terminalOutput = trimmedOutput,
            liveSessionId = result.sessionId,
            liveStreamState = if (result.timedOut) LIVE_STREAM_STATE_RUNNING else LIVE_STREAM_STATE_COMPLETED,
            liveFallbackReason = null
        )
    }

    suspend fun prepareLiveEnvironment(context: Context): TermuxLiveEnvironmentResult {
        return prepareLiveEnvironment(context) {}
    }

    suspend fun prepareLiveEnvironment(
        context: Context,
        onProgress: suspend (EmbeddedTerminalRuntime.EnvironmentProgress) -> Unit
    ): TermuxLiveEnvironmentResult {
        return prepareLiveEnvironment(
            context = context,
            installBasePackages = true,
            onProgress = onProgress
        )
    }

    suspend fun prepareLiveEnvironment(
        context: Context,
        installBasePackages: Boolean,
        onProgress: suspend (EmbeddedTerminalRuntime.EnvironmentProgress) -> Unit
    ): TermuxLiveEnvironmentResult {
        val status = EmbeddedTerminalRuntime.prepareEnvironment(
            context = context,
            installBasePackages = installBasePackages,
            onProgress = onProgress
        )
        return TermuxLiveEnvironmentResult(
            success = status.success,
            wrapperReady = status.initialized,
            sharedStorageReady = true,
            message = status.message
        )
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return EmbeddedTerminalRuntime.isSupportedDevice()
    }

    fun hasRunCommandPermission(context: Context): Boolean {
        return EmbeddedTerminalRuntime.isSupportedDevice()
    }

    fun trimTerminalOutput(text: String): String =
        EmbeddedTerminalRuntime.trimTerminalOutput(text)

    fun sanitizeTerminalNoise(text: String): String {
        return EmbeddedTerminalRuntime.sanitizeTerminalNoise(text)
    }

    private fun normalizeSpec(spec: TermuxCommandSpec): TermuxCommandSpec {
        val executionMode = spec.executionMode.trim().lowercase()
        val prootDistro = spec.prootDistro
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { TerminalDistribution.fromId(it).id }
            ?: TerminalDistribution.selected().id
        return spec.copy(
            executionMode = executionMode,
            prootDistro = prootDistro
        )
    }
}
