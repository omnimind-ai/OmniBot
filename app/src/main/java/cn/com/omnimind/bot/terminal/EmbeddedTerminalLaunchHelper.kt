package cn.com.omnimind.bot.terminal

import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import com.ai.assistance.operit.terminal.setup.EnvironmentSetupLogic
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.rk.libcommons.OMNIBOT_SETUP_SESSION_ID
import com.rk.libcommons.ShellArgv
import com.rk.libcommons.TerminalCommand
import com.rk.libcommons.pendingCommand
import com.rk.settings.Settings
import com.rk.terminal.runtime.TerminalDistribution
import com.rk.terminal.ui.activities.terminal.MainActivity as ReTerminalMainActivity
import java.io.File

object EmbeddedTerminalLaunchHelper {
    private const val TAG = "EmbeddedTerminalLaunch"

    fun launch(
        context: Context,
        openSetup: Boolean = false,
        setupPackageIds: List<String> = emptyList()
    ) {
        preparePendingCommand(
            context = context,
            openSetup = openSetup,
            setupPackageIds = setupPackageIds
        )
        context.startActivity(
            Intent(context, ReTerminalMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun preparePendingCommand(
        context: Context,
        openSetup: Boolean = false,
        setupPackageIds: List<String> = emptyList()
    ) {
        pendingCommand = null
        val workingMode = Settings.terminal_distribution
        Settings.working_Mode = workingMode
        if (!openSetup) {
            prepareTerminalSession(context, workingMode)
            return
        }

        val selectedPackageIds = setupPackageIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (selectedPackageIds.isEmpty()) {
            return
        }

        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = selectedPackageIds,
            sourceManager = SourceManager(context)
        )
        if (commands.isEmpty()) {
            return
        }

        val installScriptPath = prepareSetupScript(
            context = context,
            commands = commands,
            selectedPackageIds = selectedPackageIds,
            workingMode = workingMode
        )
        val initHostPath = File(context.filesDir.parentFile, "local/bin/init-host").absolutePath

        pendingCommand = TerminalCommand(
            shell = ShellArgv.SYSTEM_SH,
            args = ShellArgv.buildShellScriptArgv(
                initHostPath,
                "/bin/sh",
                installScriptPath
            ),
            id = OMNIBOT_SETUP_SESSION_ID,
            workingMode = workingMode,
            terminatePreviousSession = true,
            workingDir = "/"
        )
        OmniLog.d(TAG, "Prepared terminal setup session")
    }

    private fun prepareTerminalSession(context: Context, workingMode: Int) {
        val distribution = TerminalDistribution.fromWorkingMode(workingMode)
        val initHostPath = File(context.filesDir.parentFile, "local/bin/init-host").absolutePath
        pendingCommand = TerminalCommand(
            shell = ShellArgv.SYSTEM_SH,
            args = ShellArgv.buildShellScriptArgv(initHostPath),
            id = "main-${distribution.id}",
            workingMode = distribution.workingMode,
            terminatePreviousSession = false,
            workingDir = "/"
        )
    }

    private fun prepareSetupScript(
        context: Context,
        commands: List<String>,
        selectedPackageIds: List<String>,
        workingMode: Int
    ): String {
        val scriptFile = File(context.filesDir.parentFile, "local/bin/omni-setup.sh").apply {
            parentFile?.mkdirs()
        }
        val content = EnvironmentSetupLogic.buildSetupScript(
            commands = commands,
            selectedPackageIds = selectedPackageIds,
            workingMode = workingMode
        )
        scriptFile.writeText(content)
        scriptFile.setExecutable(true, false)
        return scriptFile.absolutePath
    }
}
