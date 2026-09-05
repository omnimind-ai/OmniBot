package com.ai.assistance.operit.terminal.setup

import com.rk.settings.UbuntuPackageMirror
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.terminal.ui.screens.settings.WorkingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvironmentSetupLogicTest {

    @Test
    fun buildInstallCommands_usesAlpinePackagesAndUvBootstrap() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.contains("omnibot_apk_add") }
        assertTrue(apkAdd.contains("python3"))
        assertTrue(apkAdd.contains("py3-pip"))
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("openssh-client-default"))

        assertTrue(commands.contains("ln -sf /usr/bin/python3 /usr/local/bin/python || true"))
        assertTrue(commands.contains("ln -sf /usr/bin/pip3 /usr/local/bin/pip || true"))
        assertTrue(
            commands.contains(
                "if ! apk add --no-cache uv; then python3 -m pip install --break-system-packages --upgrade uv; fi"
            )
        )
    }

    @Test
    fun buildInstallCommands_prependsRepositorySetupWhenProvided() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("curl"),
            repositorySetupCommand = "echo mirror-ready"
        )

        assertEquals("echo mirror-ready", commands.first())
        assertTrue(commands.any { it.contains("omnibot_apk_add 'curl'") })
    }

    @Test
    fun buildInstallCommands_usesUbuntuPackagesAndApt() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("python", "pip", "uv", "nodejs", "ssh_client", "xz"),
            repositorySetupCommand = UbuntuRepositoryManager.buildRepositorySetupCommand(
                UbuntuPackageMirror.TSINGHUA
            ),
            workingMode = WorkingMode.UBUNTU
        )

        val ubuntuRepositorySetup = commands.first()
        assertTrue(ubuntuRepositorySetup.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"))
        assertTrue(ubuntuRepositorySetup.contains("ports.ubuntu.com/ubuntu-ports"))
        assertTrue(ubuntuRepositorySetup.contains("ubuntu.sources"))

        val nodeRepositorySetup = commands.first { it.contains("deb.nodesource.com/node_22.x") }
        assertTrue(nodeRepositorySetup.contains("nodesource-repo.gpg.key"))
        assertTrue(nodeRepositorySetup.contains("Architectures: %s"))

        val aptInstall = commands.last { it.startsWith("apt-get update") }
        assertTrue(aptInstall.contains("python3"))
        assertTrue(aptInstall.contains("python3-pip"))
        assertTrue(aptInstall.contains("nodejs"))
        assertTrue(!aptInstall.split(Regex("\\s+")).contains("npm"))
        assertTrue(aptInstall.contains("openssh-client"))
        assertTrue(aptInstall.contains("xz-utils"))
        assertTrue(commands.contains("python3 -m pip install --break-system-packages --upgrade uv"))
    }

    @Test
    fun buildInstallCommands_codexInstallsOfficialCliAndRuntimeDependencies() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("codex"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.contains("omnibot_apk_add") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("git"))
        assertTrue(commands.contains("npm config set prefix /root/.npm-global"))
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund @openai/codex@latest"
            )
        )
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/codex /usr/local/bin/codex || true"
            )
        )
    }

    @Test
    fun buildInventoryProbeCommand_codexChecksCliFromManagedNpmPath() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("codex"))

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v codex"))
        assertTrue(command.contains("codex --version"))
    }

    @Test
    fun buildInstallCommands_installsClaudeCodeAndOpenCodeInManagedNpmPath() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("claude_code", "opencode"),
            repositorySetupCommand = ""
        )

        val apkAdd = commands.first { it.contains("omnibot_apk_add") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(commands.count { it == "npm config set prefix /root/.npm-global" } == 1)
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund @agentclientprotocol/claude-agent-acp@latest"
            )
        )
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/claude-agent-acp /usr/local/bin/claude-agent-acp || true"
            )
        )
        assertTrue(
            commands.contains(
                "npm install -g --no-audit --no-fund opencode-ai@latest"
            )
        )
        assertTrue(commands.any { it.contains("opencode-linux-arm64-musl@latest") })
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/opencode /usr/local/bin/opencode || true"
            )
        )
        assertTrue(commands.any { it.contains("test -x /root/.npm-global/bin/opencode") })
    }

    @Test
    fun buildInventoryProbeCommand_detectsClaudeCodeAndOpenCode() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(
            listOf("claude_code", "opencode")
        )

        assertTrue(command.contains("/root/.npm-global/bin"))
        assertTrue(command.contains("command -v claude-agent-acp"))
        assertTrue(!command.contains("claude-agent-acp --version"))
        assertTrue(command.contains("command -v opencode"))
        assertTrue(command.contains("opencode --version"))
    }

    @Test
    fun buildInstallCommands_installsLatestDeepSeekHarnessRuntime() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("deepseek_harness"),
            repositorySetupCommand = "",
            harnessInstallCommands = mapOf(
                "deepseek_harness" to
                    File("src/main/assets/acp/install/deepseek-harness.sh").readText(),
            ),
        )

        val apkAdd = commands.first { it.contains("omnibot_apk_add") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("build-base"))
        assertTrue(apkAdd.contains("python3"))
        assertTrue(apkAdd.contains("linux-headers"))
        assertTrue(apkAdd.contains("util-linux-dev"))
        val npmInstall = commands.first { it.contains("dsh plugin --profile acp add") }
        assertTrue(npmInstall.contains("@deepseek-ai/dsh@next"))
        assertTrue(npmInstall.contains("@openma/deepseek-harness-acp@latest"))
        assertTrue(npmInstall.contains("DSH_PACKAGE_ROOT/package.json"))
        assertTrue(npmInstall.contains("npm cache clean --force"))
        assertTrue(npmInstall.contains("@deepseek-ai/.dsh-*"))
        assertTrue(npmInstall.contains("if ! command -v pnpm"))
        assertTrue(!npmInstall.contains("@deepseek-ai/dsh-llm-deepseek@next"))
        assertTrue(!npmInstall.contains("0.1.0-rc.6"))
        assertTrue(npmInstall.contains("DSH_HOME=\"/root/.dsh/omnibot-acp\""))
        assertTrue(npmInstall.contains("registry.npmmirror.com"))
        assertTrue(npmInstall.contains("registry.npmjs.org"))
        assertTrue(npmInstall.contains("fetch-retries=5"))
        assertTrue(npmInstall.contains("fetch-timeout=120000"))
        assertTrue(
            npmInstall.contains("/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        )
        assertTrue(npmInstall.contains("test -x /root/.npm-global/bin/dsh"))
        assertTrue(npmInstall.contains("dsh-acp-android"))
        assertTrue(npmInstall.contains("--expose-internals"))
        assertTrue(npmInstall.contains("omnibot-acp-headless.patch.yml"))
        assertTrue(npmInstall.contains("dsh-plugin-mgr"))
        assertTrue(npmInstall.contains("dsh-plugin-studio"))
        assertTrue(npmInstall.contains("- id: uisfx"))
        assertTrue(npmInstall.contains("node-pty"))
        assertTrue(npmInstall.contains("npm_config_build_from_source=true"))
        assertTrue(npmInstall.contains("npm rebuild --prefix"))
        assertTrue(npmInstall.contains("command -v apk"))
        assertTrue(npmInstall.contains("command -v apt-get"))
        assertTrue(npmInstall.contains("build-essential python3"))
        assertTrue(npmInstall.contains("PNPM_CONFIG_NODE_LINKER=hoisted"))
        assertTrue(npmInstall.contains("PNPM_CONFIG_PACKAGE_IMPORT_METHOD=copy"))
        assertTrue(npmInstall.contains("pnpm config set --location=project nodeLinker hoisted"))
        assertTrue(npmInstall.contains("pnpm config set --location=project packageImportMethod copy"))
        assertTrue(npmInstall.contains("dsh plugin --profile acp add -w"))
        assertTrue(npmInstall.contains("dsh_acp_profile_is_healthy"))
        assertTrue(npmInstall.contains("PROFILE_LAYOUT_MARKER"))
        assertTrue(npmInstall.contains("rm -rf \"${'$'}DSH_HOME/profiles\""))
        assertTrue(npmInstall.contains("timeout 30 dsh-acp-android --profile acp --dump-config"))
        assertTrue(npmInstall.contains("plugin command exited"))
        assertTrue(!npmInstall.contains("npm_config_node_linker"))
        assertTrue(!npmInstall.contains("npm_config_package_import_method"))
        assertTrue(!npmInstall.contains("materialize_pnpm_link"))
        assertTrue(!npmInstall.contains("pnpm install --force"))
        assertTrue(npmInstall.contains("${'$'}DSH_PACKAGE_ROOT/node_modules"))
        // Preparation may repair the official adapter, but it must never
        // delete user-installed plugins from the persistent ACP profile.
        assertTrue(!npmInstall.contains("prune_acp_profile_plugins"))
        assertTrue(!npmInstall.contains("dsh plugin --profile acp remove"))
        assertTrue(npmInstall.contains("@openma/deepseek-harness-acp"))
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/dsh /usr/local/bin/dsh || true"
            )
        )
        assertTrue(commands.none { it.contains("dsh-acp\n") })
    }

    @Test
    fun buildInventoryProbeCommand_detectsCompleteDeepSeekHarnessRuntime() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(
            listOf("deepseek_harness")
        )

        assertTrue(command.contains("command -v dsh"))
        assertTrue(command.contains("command -v dsh-acp-android"))
        assertTrue(!command.contains("await import('@openma/deepseek-harness-acp/plugin')"))
        assertTrue(!command.contains("await import('@openma/deepseek-harness-acp/stdio')"))
    }

    @Test
    fun buildInstallCommands_installsOfficialKimiCodeRuntime() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("kimi"),
            repositorySetupCommand = "",
        )

        val apkAdd = commands.first { it.contains("omnibot_apk_add") }
        assertTrue(apkAdd.contains("nodejs"))
        assertTrue(apkAdd.contains("npm"))
        assertTrue(apkAdd.contains("git"))
        val npmInstall = commands.first { it.contains("@moonshot-ai/kimi-code@latest") }
        assertTrue(npmInstall.contains("--no-audit"))
        assertTrue(npmInstall.contains("registry.npmmirror.com"))
        assertTrue(
            commands.contains(
                "ln -sf /root/.npm-global/bin/kimi /usr/local/bin/kimi || true",
            ),
        )
    }

    @Test
    fun buildInventoryProbeCommand_requiresKimiCodeNodeVersion() {
        val command = EnvironmentSetupLogic.buildInventoryProbeCommand(listOf("kimi"))

        assertTrue(command.contains("command -v kimi"))
        assertTrue(command.contains("major === 22 && minor < 19"))
        assertTrue(command.contains("kimi --version"))
    }

    @Test
    fun buildAlpinePackageInstallCommand_repairsAndRetriesOneInterruptedTransaction() {
        val tempDir = Files.createTempDirectory("omnibot-apk-retry-test").toFile()
        try {
            val invocationLog = File(tempDir, "apk-invocations.log")
            val fakeApk = File(tempDir, "apk").apply {
                writeText(
                    """
                        #!/bin/sh
                        printf '%s\n' "${'$'}*" >> "${'$'}OMNIBOT_TEST_APK_LOG"
                        if [ "${'$'}1" = "fix" ]; then
                          if [ "${'$'}3" = "--upgrade" ]; then
                            exit 0
                          fi
                          exit 1
                        fi
                        add_attempts="${'$'}(grep -c '^add ' "${'$'}OMNIBOT_TEST_APK_LOG" 2>/dev/null || true)"
                        if [ "${'$'}add_attempts" -eq 1 ]; then
                          exit 5
                        fi
                        exit 0
                    """.trimIndent()
                )
                setExecutable(true)
            }
            assertTrue(fakeApk.canExecute())

            val command = buildAlpinePackageInstallCommand(
                listOf("build-base", "python3")
            )
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .apply {
                    environment()["OMNIBOT_TEST_APK_LOG"] = invocationLog.absolutePath
                    environment()["PATH"] =
                        tempDir.absolutePath + File.pathSeparator + environment()["PATH"]
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            assertEquals(output, 0, exitCode)
            assertEquals(
                listOf(
                    "add --no-cache build-base python3",
                    "fix --no-cache",
                    "fix --no-cache --upgrade",
                    "add --no-cache build-base python3"
                ),
                invocationLog.readLines()
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun buildInstallCommands_installsUbuntuDeepSeekHarnessNativeBuildTools() {
        val commands = EnvironmentSetupLogic.buildInstallCommands(
            selectedPackageIds = listOf("deepseek_harness"),
            repositorySetupCommand = "",
            workingMode = WorkingMode.UBUNTU,
            harnessInstallCommands = mapOf(
                "deepseek_harness" to
                    File("src/main/assets/acp/install/deepseek-harness.sh").readText(),
            ),
        )

        val aptInstall = commands.last { it.startsWith("apt-get update") }
        assertTrue(aptInstall.contains("build-essential"))
        assertTrue(aptInstall.contains("python3"))
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
        assertTrue(script.contains("setup_status=${'$'}?"))
        assertTrue(script.contains("|| return \"${'$'}setup_status\""))
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
        val packageIds = EnvironmentSetupLogic.packageDefinitions.map { it.id }
        val workingModes = listOf(WorkingMode.ALPINE, WorkingMode.UBUNTU)
        val processes = workingModes.associateWith { workingMode ->
            ProcessBuilder("/bin/sh", "-n")
                .redirectErrorStream(true)
                .start()
        }
        val writers = processes.mapValues { (_, process) ->
            process.outputStream.bufferedWriter()
        }

        try {
            val total = 1 shl packageIds.size
            for (mask in 1 until total) {
                val selectedPackageIds = packageIds.filterIndexed { index, _ ->
                    mask and (1 shl index) != 0
                }
                workingModes.forEach { workingMode ->
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
                        workingMode = workingMode,
                        harnessInstallCommands = if ("deepseek_harness" in selectedPackageIds) {
                            mapOf(
                                "deepseek_harness" to
                                    File("src/main/assets/acp/install/deepseek-harness.sh").readText(),
                            )
                        } else {
                            emptyMap()
                        },
                    )
                    val script = EnvironmentSetupLogic.buildSetupScript(
                        commands = distroCommands,
                        selectedPackageIds = selectedPackageIds,
                        workingMode = workingMode
                    )
                    val writer = writers.getValue(workingMode)
                    writer.write("# combination mask=$mask\n")
                    writer.write(script)
                    writer.write("\n")
                }
            }
        } finally {
            writers.values.forEach { writer ->
                runCatching { writer.close() }
            }
        }

        processes.forEach { (workingMode, process) ->
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()

            assertEquals(
                "Shell syntax check failed for mode=$workingMode: $output",
                0,
                exitCode
            )
        }
    }
}
