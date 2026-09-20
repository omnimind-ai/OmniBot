package cn.com.omnimind.bot.terminal

import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbeddedTerminalRuntimeTest {
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
    fun ordinaryPythonRunsWithoutEnsurepipButPackageToolsStillRequireAnEnvironment() {
        val root = java.nio.file.Files.createTempDirectory("omni-python-prelude").toFile().canonicalFile
        try {
            val bin = File(root, "bin").apply { mkdirs() }
            val executable = "#!/bin/sh\nif [ \"\$1\" = -m ] && [ \"\$2\" = venv ]; then exit 73; fi\nprintf 'PYTHON_OK:%s\\n' \"\$*\"\n"
            for (name in listOf("python", "python3")) {
                File(bin, name).apply { writeText(executable); setExecutable(true) }
            }
            File(root, "requirements.txt").writeText("")
            val prelude = EmbeddedTerminalRuntime.buildPythonEnvironmentPrelude() +
                "\n__omni_workspace_root='${root.absolutePath}'\n"
            fun run(command: String): Pair<Int, String> {
                val process = ProcessBuilder("/bin/sh", "-c", prelude + command)
                    .directory(root).redirectErrorStream(true).apply {
                        environment()["HOME"] = root.absolutePath
                        environment()["PATH"] = "${bin.absolutePath}:/usr/bin:/bin"
                        environment().remove("VIRTUAL_ENV")
                    }.start()
                val output = process.inputStream.bufferedReader().readText()
                return process.waitFor() to output
            }
            for (command in listOf("python3 -c 'print(1)'", "python -c 'print(1)'")) {
                val (code, output) = run(command)
                assertEquals(output, 0, code)
                assertTrue(output.contains("PYTHON_OK:"))
                assertFalse(File(root, ".venv").exists())
            }
            assertEquals(73, run("python3 -m pip install example").first)
            assertEquals(73, run("pip install example").first)
            val env = File(root, ".venv/bin").apply { mkdirs() }
            File(env, "python3").apply { writeText(executable); setExecutable(true) }
            File(env, "activate").writeText("export OMNI_TEST_ACTIVATED=1\n")
            val activated = run("python3 -c 'print(1)'; test \"\$OMNI_TEST_ACTIVATED\" = 1")
            assertEquals(activated.second, 0, activated.first)
        } finally { root.deleteRecursively() }
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
