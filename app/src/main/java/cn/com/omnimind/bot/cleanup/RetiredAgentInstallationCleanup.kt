package cn.com.omnimind.bot.cleanup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.ManagedAgentUninstallReport
import cn.com.omnimind.bot.agent.runtime.ManagedAgentUninstallStrategy
import cn.com.omnimind.bot.agent.runtime.ManagedAcpPreparationGate
import cn.com.omnimind.bot.agent.runtime.managedAgentUninstallCommand
import cn.com.omnimind.bot.agent.runtime.parseManagedAgentUninstallReport
import com.ai.assistance.operit.terminal.TerminalManager
import com.rk.terminal.runtime.EmbeddedRuntimeInstaller
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Package retirement only. ACP owners, history, credentials and user plugins are preserved. */
internal object RetiredAgentInstallationCleanup {
    private const val WORK_NAME = "retired_agent_installations_v1"
    private const val PREFS_NAME = "retired_agent_installations"
    private const val COMPLETE = "deepseek_v1_complete"

    fun start(context: Context) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getBoolean(COMPLETE, false)) return
        if (TerminalDistribution.supported.none { hasInstallation(context, it) }) {
            preferences.edit().putBoolean(COMPLETE, true).apply()
            return
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RetiredAgentInstallationCleanupWorker>().build(),
        )
    }

    internal fun hasInstallation(context: Context, distribution: TerminalDistribution.Spec): Boolean {
        val root = File(context.applicationInfo.dataDir, "local/${distribution.rootfsDirectoryName}")
        return listOf(
            "root/.npm-global/lib/node_modules/@deepseek-ai/dsh",
            "root/.npm-global/lib/node_modules/@deepseek-ai/dsh-acp-demo",
            "root/.npm-global/lib/node_modules/@openma/deepseek-harness-acp",
            "root/.npm-global/bin/dsh", "root/.npm-global/bin/dsh-acp",
            "root/.npm-global/bin/dsh-acp-android", "root/.npm-global/bin/dsh-acp-demo",
            "usr/local/bin/dsh", "usr/local/bin/dsh-acp",
            "usr/local/bin/dsh-acp-android", "usr/local/bin/dsh-acp-demo",
        ).any { Files.exists(File(root, it).toPath(), NOFOLLOW_LINKS) }
    }

    internal suspend fun cleanup(
        context: Context,
        installed: (TerminalDistribution.Spec) -> Boolean = {
            EmbeddedRuntimeInstaller.isDistributionInstalled(context, it)
        },
        execute: suspend (TerminalDistribution.Spec) -> ManagedAgentUninstallReport,
    ): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var complete = true
        for (distribution in TerminalDistribution.supported) {
            if (!hasInstallation(context, distribution)) continue
            val removed = if (!installed(distribution)) false else try {
                val report = execute(distribution)
                report.ok && report.removed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            preferences.edit().putBoolean("deepseek_v1_${distribution.id}_removed", removed).apply()
            complete = complete && removed
        }
        return complete && preferences.edit().putBoolean(COMPLETE, true).commit()
    }
}

class RetiredAgentInstallationCleanupWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val source = applicationContext.assets.open("acp/uninstall-agent.cjs")
            .bufferedReader().use { it.readText() }
        val complete = RetiredAgentInstallationCleanup.cleanup(applicationContext) { distribution ->
            ManagedAcpPreparationGate.shared.run("deepseek-harness-acp") {
                val result = TerminalManager.getInstance(applicationContext).executeHiddenCommand(
                    command = managedAgentUninstallCommand(source, ManagedAgentUninstallStrategy.DEEPSEEK_HARNESS),
                    executorKey = "retired-agent-cleanup-${distribution.id}",
                    distribution = distribution,
                    timeoutMs = 180_000L,
                    installIfMissing = false,
                )
                parseManagedAgentUninstallReport(result.output)
            }
        }
        if (complete) return Result.success()
        OmniLog.w("RetiredAgentCleanup", "Retired package cleanup incomplete; unrecognized installations were preserved")
        // A bounded package-maintenance retry is unrelated to ACP prompt retries.
        return if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
