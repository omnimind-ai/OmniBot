package com.ai.assistance.operit.terminal.setup

import com.rk.terminal.ui.screens.settings.WorkingMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnvironmentInstallExecutionTest {
    private fun execute(script: String): Pair<Int, String> {
        val shell = if (File("/bin/dash").canExecute()) "/bin/dash" else "/bin/sh"
        val process = ProcessBuilder(shell, "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }

    private fun interactive(commands: List<String>): String =
        EnvironmentSetupLogic.buildSetupScript(commands)
            .substringBefore("if [ -x /bin/bash ]; then exec")

    @Test fun interactiveInstallStopsAtFailureInsideMultilineCommand() {
        val (_, output) = execute(interactive(listOf("false\nprintf 'BAD_SIDE_EFFECT\\n'")))
        assertFalse(output, output.contains("BAD_SIDE_EFFECT"))
        assertFalse(output, output.contains("选中的环境已准备完成"))
        assertTrue(output, output.contains("环境配置失败"))
    }

    @Test fun interactiveInstallStopsAtFailureInsideRepositorySubshell() {
        val (_, output) = execute(interactive(listOf("( set -e; false; printf 'BAD_REPOSITORY\\n' )")))
        assertFalse(output, output.contains("BAD_REPOSITORY"))
        assertTrue(output, output.contains("环境配置失败"))
    }

    @Test fun bothDistributionsReuseCatalogOpenCodeInstaller() {
        for (mode in listOf(WorkingMode.UBUNTU, WorkingMode.ALPINE)) {
            val commands = EnvironmentSetupLogic.buildInstallCommands(
                listOf("opencode"), "", mode,
                harnessInstallCommands = mapOf("opencode" to "catalog-opencode-command"),
            )
            assertEquals(1, commands.count { it == "catalog-opencode-command" })
            assertFalse(commands.any { it.contains("opencode-linux-arm64") })
        }
    }

    @Test fun hiddenInstallPreservesExportsAndLiteralQuotes() {
        val command = EnvironmentSetupLogic.buildInstallExecutionCommand(listOf(
            "export INSTALL_TEST_VALUE=\"space ' quote\"",
            "printf '%s' \"${'$'}INSTALL_TEST_VALUE\"",
        ))
        val (status, output) = execute(command)
        assertEquals(output, 0, status)
        assertEquals("space ' quote", output)
    }

    @Test fun hiddenInstallDoesNotLetLaterOrTrueSwallowEarlierFailure() {
        val command = EnvironmentSetupLogic.buildInstallExecutionCommand(listOf(
            "false", "false || true", "printf 'BAD_SIDE_EFFECT'",
        ))
        val (status, output) = execute(command)
        assertEquals(output, 1, status)
        assertFalse(output, output.contains("BAD_SIDE_EFFECT"))
    }

    @Test fun explicitFallbackStillWorksButFailedFallbackStopsInstallation() {
        for (fallback in listOf("true", "false")) {
            val command = EnvironmentSetupLogic.buildInstallExecutionCommand(listOf(
                "false || $fallback", "printf 'INSTALL_COMPLETE'",
            ))
            val (status, output) = execute(command)
            assertEquals(output, fallback == "true", status == 0)
            assertEquals(output, fallback == "true", output.contains("INSTALL_COMPLETE"))
        }
    }

    @Test fun deepSeekMissingAdapterFilesCannotBeReportedInstalled() {
        val command = "command() { return 0; }; test() { return 1; }; " +
            EnvironmentSetupLogic.buildCheckCommand("deepseek_harness", "")
        val (_, output) = execute(command)
        assertEquals(output, "MISSING", output.trim())
    }
}
