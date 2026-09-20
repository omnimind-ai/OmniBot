package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.plugin.runtime.RuntimeSkillBundleManager
import com.ai.assistance.operit.terminal.TerminalManager
import java.util.UUID

internal class OmniFlowAppPlatform(
    private val runtimeSkills: RuntimeSkillBundleManager,
) : OmniFlowPlatform {
    private companion object {
        const val TAG = "[OmniFlowAppPlatform]"
    }

    override suspend fun startProcess(
        context: Context,
        command: String,
        environment: Map<String, String>,
    ): Process = TerminalManager.getInstance(context.applicationContext)
        .startLongLivedProcess(
            command = command,
            executorKey = "omniflow-${UUID.randomUUID()}",
            redirectErrorStream = false,
            extraEnvironment = environment,
        )

    override suspend fun prepareEnvironment(context: Context, command: String) {
        val appContext = context.applicationContext
        val terminal = EmbeddedTerminalRuntime.warmup(appContext)
        require(terminal.success && terminal.initialized) {
            terminal.message.ifBlank { "plugin_terminal_unavailable" }
        }
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = command,
            executorKey = "gui-runtime-prepare",
            timeoutMs = 5 * 60_000L,
            onOutputChunk = { log(it.trim()) },
        )
        require(result.isOk && result.exitCode == 0) {
            buildOmniFlowPythonFailureMessage(result.error, result.output, result.rawOutputPreview)
        }
    }

    private fun log(message: String) {
        runCatching { OmniLog.i(TAG, message) }
    }

    override suspend fun resolveRuntimeSkill(
        context: Context,
        refresh: Boolean,
    ): OmniFlowSkillLocation {
        val location = runtimeSkills.resolve(refresh)
        return OmniFlowSkillLocation(
            androidRoot = location.androidRoot,
            shellRoot = location.shellRoot,
            source = location.source,
        )
    }

    override suspend fun resolvePackagedRuntimeSkill(context: Context): OmniFlowSkillLocation {
        val location = runtimeSkills.resolvePackaged(refresh = true)
        return OmniFlowSkillLocation(
            androidRoot = location.androidRoot,
            shellRoot = location.shellRoot,
            source = location.source,
        )
    }

    override fun allowsPackagedRuntimeFallback(): Boolean =
        runtimeSkills.allowsPackagedFallback()

    override suspend fun bootstrapRuntimeSkill(
        context: Context,
        location: OmniFlowSkillLocation,
    ): OmniFlowSkillLocation {
        val ready = runtimeSkills.bootstrap(
            cn.com.omnimind.bot.plugin.runtime.RuntimeSkillLocation(
                androidRoot = location.androidRoot,
                shellRoot = location.shellRoot,
                source = location.source,
                staged = location.source == "market-pending",
            )
        )
        return OmniFlowSkillLocation(
            androidRoot = ready.androidRoot,
            shellRoot = ready.shellRoot,
            source = ready.source,
        )
    }

    override suspend fun reclaimRuntimeSkill(context: Context) {
        runtimeSkills.reclaim()
    }

    override suspend fun completeJson(request: ChatCompletionRequest): String {
        val response = HttpController.postSceneChatCompletion(request)
        return resolveOmniFlowJsonCompletion(response)
    }

}

internal fun buildOmniFlowPythonFailureMessage(
    error: String,
    output: String,
    rawOutputPreview: String,
): String {
    val details = sequenceOf(error, output, rawOutputPreview)
        .map(EmbeddedTerminalRuntime::sanitizeTerminalNoise)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n")
        .takeLast(1200)
    return details.ifBlank { "omniflow_python_runtime_not_preinstalled" }
}

internal fun resolveOmniFlowJsonCompletion(response: SceneChatCompletionResponse): String {
    check(response.success) { response.message.ifBlank { "model_completion_failed" } }
    return OmniFlowModelHost.jsonCompletionContent(response.content, response.toolCalls)
}
