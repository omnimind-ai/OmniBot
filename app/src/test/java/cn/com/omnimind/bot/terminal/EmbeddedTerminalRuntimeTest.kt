package cn.com.omnimind.bot.terminal

import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import com.rk.terminal.ui.screens.settings.WorkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedTerminalRuntimeTest {
    @Test
    fun basePackageBootstrapNeverRunsFloatingLanguagePackageInstallers() {
        val commands = listOf(
            EmbeddedTerminalRuntime.buildBasePackageBootstrapCommand(
                workingMode = WorkingMode.ALPINE,
                repositorySetupCommand = "true"
            ),
            EmbeddedTerminalRuntime.buildBasePackageBootstrapCommand(
                workingMode = WorkingMode.UBUNTU,
                repositorySetupCommand = "true",
                nodeRepositorySetupCommand = "true"
            )
        )

        commands.forEach { command ->
            assertFalse(command.contains("pip install"))
            assertFalse(command.contains("npm install"))
            assertFalse(command.contains("--upgrade"))
            assertFalse(command.contains("|| true"))
            assertFalse(command.contains("@latest"))
        }
    }

    @Test
    fun basePackageProbeDoesNotTriggerPackageManagerDownloads() {
        val command = EmbeddedTerminalRuntime.buildBasePackageProbeCommand()

        val executableLines = command.lineSequence()
            .map(String::trim)
            .filterNot { it.startsWith("#") }
            .joinToString("\n")
        assertFalse(executableLines.contains("corepack ", ignoreCase = true))
        assertFalse(command.contains("pnpm -v"))
        assertTrue(command.contains("__OMNIBOT_PNPM_VERSION__ missing"))
    }

    @Test
    fun basePackageBootstrapFailureDoesNotExposeInstallerOutput() {
        assertEquals(
            "AGENT_RUNTIME_BASE_PACKAGE_INSTALL_FAILED",
            EmbeddedTerminalRuntime.buildInstallFailureMessage()
        )
    }

    @Test
    fun ubuntuStartupSuppressesSudoGroupProbeWithoutRemovingAndroidGroups() {
        val initScript = File("../ReTerminal/core/main/src/main/assets/init.sh").readText()

        assertTrue(initScript.contains("suppress_ubuntu_sudo_group_probe"))
        assertTrue(initScript.contains("[ \"\$TERMINAL_DISTRIBUTION\" = \"ubuntu\" ] || return 0"))
        assertTrue(initScript.contains("[ -e \"\$HOME/.hushlogin\" ] || : > \"\$HOME/.hushlogin\""))
        assertFalse(initScript.contains("setgroups"))
    }

    @Test
    fun buildPythonEnvironmentPreludeIncludesWorkspaceVenvBootstrap() {
        val prelude = EmbeddedTerminalRuntime.buildPythonEnvironmentPrelude()

        assertTrue(prelude.contains(AgentWorkspaceManager.SHELL_ROOT_PATH))
        assertTrue(prelude.contains("HOME/.local/bin"))
        assertTrue(prelude.contains("UV_LINK_MODE=copy"))
        assertTrue(prelude.contains("UV_PROJECT_ENVIRONMENT"))
        assertTrue(prelude.contains("uv() {"))
        assertTrue(prelude.contains("command uv venv --link-mode copy"))
        assertTrue(prelude.contains("__omni_uv_resolve_target_path"))
        assertTrue(prelude.contains("__omni_cleanup_invalid_virtualenv"))
        assertTrue(prelude.contains("Removing invalid virtual environment"))
        assertTrue(prelude.contains("__omni_prepare_python_env"))
        assertTrue(prelude.contains("command python3 -m venv --copies"))
        assertTrue(prelude.contains("command python -m pip"))
        assertTrue(prelude.contains("command python -m pytest"))
        assertTrue(prelude.contains(".venv/bin/activate"))
    }

    @Test
    fun buildCommandEnvironmentExportsQuotesValuesAndSkipsInvalidKeys() {
        val exports = EmbeddedTerminalRuntime.buildCommandEnvironmentExports(
            linkedMapOf(
                "OPENAI_API_KEY" to "sk-test'value",
                "1INVALID" to "ignored",
                "PATH" to "/tmp/bin"
            )
        )

        assertTrue(exports.contains("export OPENAI_API_KEY='sk-test'\"'\"'value'"))
        assertTrue(exports.contains("export PATH='/tmp/bin'"))
        assertFalse(exports.contains("1INVALID"))
    }

    @Test
    fun buildSessionLiveOutputUpdateStreamsOnlyNewSessionOutput() {
        val token = "session-token"

        val update = EmbeddedTerminalRuntime.buildSessionLiveOutputUpdate(
            previousVisibleOutput = "line 1",
            rawOutput = "line 1\nline 2",
            token = token
        )

        assertEquals("line 1\nline 2", update.visibleOutput)
        assertEquals("\nline 2", update.outputDelta)
        assertEquals(null, update.exitCode)
    }

    @Test
    fun buildSessionLiveOutputUpdateRemovesCompletionMarkerAndPrompt() {
        val token = "session-token"

        val update = EmbeddedTerminalRuntime.buildSessionLiveOutputUpdate(
            previousVisibleOutput = "",
            rawOutput = "done\n__OMNIBOT_SESSION_DONE__:$token:0\n~ $ ",
            token = token
        )

        assertEquals("done", update.visibleOutput)
        assertEquals("done", update.outputDelta)
        assertEquals(0, update.exitCode)
    }
}
