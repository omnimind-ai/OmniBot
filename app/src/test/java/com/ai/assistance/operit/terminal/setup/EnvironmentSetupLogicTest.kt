package com.ai.assistance.operit.terminal.setup

import com.rk.settings.UbuntuPackageMirror
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.terminal.ui.screens.settings.WorkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files

class EnvironmentSetupLogicTest {

    @Test
    fun buildInstallCommands_uvFailsClosedWithoutAuditedWheelManifest() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client"),
            repositorySetupCommand = ""
        )

        assertEquals(
            listOf("printf '%s\\n' 'AGENT_RUNTIME_UV_LOCK_REQUIRED' >&2; exit 74"),
            commands
        )
        assertTrue(commands.none { it.contains("pip install") })
    }

    @Test
    fun buildInstallCommands_prependsRepositorySetupWhenProvided() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("curl"),
            repositorySetupCommand = "echo mirror-ready"
        )

        assertEquals("echo mirror-ready", commands.first())
        assertTrue(commands.any { it == "apk add --no-cache curl" })
    }

    @Test
    fun buildInstallCommands_uvFailsClosedBeforeUbuntuMutation() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client", "xz"),
            repositorySetupCommand = UbuntuRepositoryManager.buildRepositorySetupCommand(
                UbuntuPackageMirror.TSINGHUA
            ),
            workingMode = WorkingMode.UBUNTU
        )

        assertEquals(
            listOf("printf '%s\\n' 'AGENT_RUNTIME_UV_LOCK_REQUIRED' >&2; exit 74"),
            commands
        )
        assertTrue(commands.none { it.contains("pip install") })
    }

    @Test
    fun buildInstallCommands_codexFailsClosedWithoutAuditedStandaloneCliLock() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("codex"),
            repositorySetupCommand = ""
        )

        assertEquals(
            listOf("printf '%s\\n' 'AGENT_RUNTIME_MANAGED_CLI_LOCK_REQUIRED' >&2; exit 73"),
            commands
        )
        assertTrue(commands.none { it.contains("npm install") || it.contains("@latest") })
    }

    @Test
    fun buildInventoryProbeCommand_codexChecksCliFromManagedNpmPath() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex"))

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v codex"))
        assertTrue(command.contains("codex --version"))
    }

    @Test
    fun buildInstallCommands_claudeAndOpenCodeFailClosedWithoutAuditedLock() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("claude_code", "opencode"),
            repositorySetupCommand = ""
        )

        assertEquals(
            listOf("printf '%s\\n' 'AGENT_RUNTIME_MANAGED_CLI_LOCK_REQUIRED' >&2; exit 73"),
            commands
        )
        assertTrue(commands.none { it.contains("npm install") || it.contains("@latest") })
    }

    @Test
    fun buildInventoryProbeCommand_detectsClaudeCodeAndOpenCode() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(
            listOf("claude_code", "opencode")
        )

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v claude"))
        assertTrue(command.contains("claude --version"))
        assertTrue(command.contains("command -v opencode"))
        assertTrue(command.contains("opencode --version"))
    }

    @Test
    fun buildInventoryProbeCommand_validatesRuntimeCwdForNodeAndPython() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("nodejs", "python", "pip"))

        assertTrue(command.contains("node -e 'process.cwd();"))
        assertTrue(command.contains("process.versions.node"))
        assertTrue(command.contains("python3 -c 'import os; os.getcwd()'"))
        assertTrue(command.contains("pip3 --version"))
    }

    @Test
    fun buildSetupScript_validatesSelectedPackagesBeforeSuccess() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("nodejs", "python", "pip"),
            repositorySetupCommand = ""
        )
        val script = EnvironmentSetupLogic.buildSetupScript(
            commands = commands,
            selectedPackageIds = listOf("nodejs", "python", "pip")
        )

        assertTrue(script.contains("run_validate()"))
        assertTrue(script.contains("校验基础目录操作"))
        assertTrue(script.contains("node -e 'process.cwd();"))
        assertTrue(script.contains("python3 -c 'import os; os.getcwd()'"))
        assertTrue(script.contains("pip3 --version"))
        assertTrue(script.indexOf("run_setup && run_validate") < script.indexOf("选中的环境已准备完成"))
    }

    @Test
    fun buildValidationCommands_wrapsChecksForAndJoinedExecution() {
        val commands = EnvironmentSetupLogic.buildValidationCommands(listOf("nodejs"))

        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.startsWith("{ ") && it.endsWith("; }") })
        assertTrue(commands.any { it.contains("exit 1") })
    }

    @Test
    fun buildSetupScript_isShellSafeForEveryPackageCombination() {
        assumeFalse(
            "POSIX shell syntax validation runs in Linux CI",
            System.getProperty("os.name").orEmpty().startsWith("Windows")
        )
        val packageIds = EnvironmentSetupLogic.packageDefinitions.map { it.id }
        val tempDir = Files.createTempDirectory("omni-setup-script-test").toFile()

        try {
            val total = 1 shl packageIds.size
            for (mask in 1 until total) {
                val selectedPackageIds = packageIds.filterIndexed { index, _ ->
                    mask and (1 shl index) != 0
                }
                listOf(WorkingMode.ALPINE, WorkingMode.UBUNTU).forEach { workingMode ->
                    val repositorySetupCommand = if (workingMode == WorkingMode.UBUNTU) {
                        UbuntuRepositoryManager.buildRepositorySetupCommand(
                            UbuntuPackageMirror.TSINGHUA
                        )
                    } else {
                        ""
                    }
                    val distroCommands = EnvironmentSetupLogic.buildInstallCommands(
                        selectedPackageIds = selectedPackageIds,
                        repositorySetupCommand = repositorySetupCommand,
                        workingMode = workingMode
                    )
                    val scriptFile = File(tempDir, "setup-$workingMode-$mask.sh")
                    scriptFile.writeText(
                        EnvironmentSetupLogic.buildSetupScript(
                            commands = distroCommands,
                            selectedPackageIds = selectedPackageIds,
                            workingMode = workingMode
                        )
                    )

                    val process = ProcessBuilder("/bin/sh", "-n", scriptFile.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    val exitCode = process.waitFor()

                    assertEquals(
                        "Shell syntax check failed for mode=$workingMode $selectedPackageIds: $output",
                        0,
                        exitCode
                    )
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
