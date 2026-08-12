package com.ai.assistance.operit.terminal.setup

import com.ai.assistance.operit.terminal.utils.SourceManager
import com.rk.terminal.runtime.UbuntuRepositoryManager
import com.rk.terminal.ui.screens.settings.WorkingMode

object EnvironmentSetupLogic {
    data class PackageDefinition(
        val id: String,
        val command: String,
        val categoryId: String
    )

    val packageDefinitions: List<PackageDefinition> = listOf(
        PackageDefinition("nodejs", "node --version", "dev"),
        PackageDefinition("npm", "npm --version", "dev"),
        PackageDefinition("git", "git --version", "dev"),
        PackageDefinition("python", "python3 --version", "dev"),
        PackageDefinition("uv", "uv --version", "dev"),
        PackageDefinition("pip", "pip3 --version", "dev"),
        PackageDefinition("codex", "codex --version", "ai"),
        PackageDefinition("claude_code", "claude --version", "ai"),
        PackageDefinition("opencode", "opencode --version", "ai"),
        PackageDefinition("ssh_client", "ssh -V 2>&1", "ssh"),
        PackageDefinition("sshpass", "sshpass -V 2>&1", "ssh"),
        PackageDefinition("openssh_server", "sshd -V 2>&1", "ssh")
    )

    data class PackageProbeResult(
        val ready: Boolean,
        val version: String?
    )

    private data class ValidationCheck(
        val label: String,
        val command: String
    )

    private val alpineInstallPackageMap = linkedMapOf(
        "bash" to listOf("bash"),
        "curl" to listOf("curl"),
        "ripgrep" to listOf("ripgrep"),
        "tmux" to listOf("tmux"),
        "xz" to listOf("xz"),
        "nodejs" to listOf("nodejs", "npm"),
        "npm" to listOf("npm"),
        "git" to listOf("git"),
        "codex" to listOf("nodejs", "npm", "git", "bash", "curl", "ripgrep"),
        "claude_code" to listOf("nodejs", "npm", "git", "bash", "curl", "ripgrep"),
        "opencode" to listOf("nodejs", "npm", "git", "bash", "curl", "ripgrep"),
        "python" to listOf("python3"),
        "pip" to listOf("py3-pip"),
        "uv" to listOf("python3", "py3-pip"),
        "ssh_client" to listOf("openssh-client-default"),
        "sshpass" to listOf("sshpass"),
        "openssh_server" to listOf("openssh-server")
    )

    private val ubuntuInstallPackageMap = linkedMapOf(
        "bash" to listOf("bash"),
        "curl" to listOf("curl"),
        "ripgrep" to listOf("ripgrep"),
        "tmux" to listOf("tmux"),
        "xz" to listOf("xz-utils"),
        "nodejs" to listOf("nodejs"),
        "npm" to listOf("nodejs"),
        "git" to listOf("git"),
        "codex" to listOf("nodejs", "git", "bash", "curl", "ripgrep"),
        "claude_code" to listOf("nodejs", "git", "bash", "curl", "ripgrep"),
        "opencode" to listOf("nodejs", "git", "bash", "curl", "ripgrep"),
        "python" to listOf("python3"),
        "pip" to listOf("python3-pip"),
        "uv" to listOf("python3", "python3-pip"),
        "ssh_client" to listOf("openssh-client"),
        "sshpass" to listOf("sshpass"),
        "openssh_server" to listOf("openssh-server")
    )

    fun buildInstallCommands(
        selectedPackageIds: List<String>,
        sourceManager: SourceManager
    ): List<String> {
        return buildInstallCommands(
            selectedPackageIds = selectedPackageIds,
            repositorySetupCommand = sourceManager.buildRepositorySetupCommand(),
            workingMode = sourceManager.distributionWorkingMode
        )
    }

    internal fun buildInstallCommands(
        selectedPackageIds: List<String>,
        repositorySetupCommand: String,
        workingMode: Int = WorkingMode.ALPINE
    ): List<String> {
        val requested = selectedPackageIds
            .map(::canonicalPackageId)
            .toSet()
        if (requested.isEmpty()) {
            return emptyList()
        }
        if (requested.any { it in NPM_AGENT_PACKAGE_IDS }) {
            // Do not partially mutate the environment before reporting that the
            // standalone CLI dependency graph is not yet audited.
            return listOf(
                "printf '%s\\n' 'AGENT_RUNTIME_MANAGED_CLI_LOCK_REQUIRED' >&2; exit 73"
            )
        }
        if ("uv" in requested) {
            // The automatic base runtime no longer downloads uv. User-selected
            // installation remains closed until both supported Linux wheel
            // hashes are shipped in a reviewed manifest.
            return listOf("printf '%s\\n' 'AGENT_RUNTIME_UV_LOCK_REQUIRED' >&2; exit 74")
        }
        val repoSetup = repositorySetupCommand.trim()
        val installPackageMap = if (workingMode == WorkingMode.UBUNTU) {
            ubuntuInstallPackageMap
        } else {
            alpineInstallPackageMap
        }
        val systemPackages = requested
            .flatMap { installPackageMap[it].orEmpty() }
            .distinct()

        val commands = mutableListOf<String>()
        if (repoSetup.isNotBlank()) {
            commands += repoSetup
        }
        if (
            workingMode == WorkingMode.UBUNTU &&
            requested.any { it == "nodejs" || it == "npm" || it in NPM_AGENT_PACKAGE_IDS }
        ) {
            commands += UbuntuRepositoryManager.buildNodeRepositorySetupCommand()
        }
        if (systemPackages.isNotEmpty()) {
            commands += if (workingMode == WorkingMode.UBUNTU) {
                "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${systemPackages.joinToString(" ")}"
            } else {
                "apk add --no-cache ${systemPackages.joinToString(" ")}"
            }
        }

        if ("python" in requested || "pip" in requested || "uv" in requested) {
            commands += "ln -sf /usr/bin/python3 /usr/local/bin/python || true"
        }
        if ("pip" in requested || "uv" in requested) {
            commands += "ln -sf /usr/bin/pip3 /usr/local/bin/pip || true"
        }
        if ("openssh_server" in requested) {
            commands += "mkdir -p /var/run/sshd /etc/ssh"
            commands += "ssh-keygen -A || true"
        }

        return commands
    }

    internal fun buildSetupScript(
        commands: List<String>,
        selectedPackageIds: List<String> = emptyList(),
        workingMode: Int = WorkingMode.ALPINE
    ): String {
        val validationChecks = buildValidationChecks(selectedPackageIds)
        val distributionName = if (workingMode == WorkingMode.UBUNTU) "Ubuntu" else "Alpine"
        return buildString {
            appendLine("#!/bin/sh")
            appendLine("""printf '\033[34;1m[*]\033[0m 开始配置 $distributionName 开发环境\n'""")
            appendLine("run_setup() {")
            appendLine("  set -e")
            commands.forEach { command ->
                appendLine("  $command")
            }
            appendLine("}")
            if (validationChecks.isNotEmpty()) {
                appendLine("run_validate() {")
                appendLine("  set -e")
                validationChecks.forEach { check ->
                    appendLine("""  printf '\033[34;1m[*]\033[0m 校验${check.label}\n'""")
                    appendLine("  if ! (${check.command}); then")
                    appendLine("""    printf '\033[31;1m[!]\033[0m 校验失败：${check.label}\n'""")
                    appendLine("    return 1")
                    appendLine("  fi")
                }
                appendLine("}")
            }
            val setupCondition = if (validationChecks.isEmpty()) {
                "run_setup"
            } else {
                "run_setup && run_validate"
            }
            appendLine("if $setupCondition; then")
            appendLine("""  printf '\033[32;1m[+]\033[0m 选中的环境已准备完成\n'""")
            appendLine("else")
            appendLine("  status=\$?")
            appendLine(
                """  printf '\033[31;1m[!]\033[0m 环境配置失败，退出码: %s\n' "${'$'}status" """,
            )
            appendLine("fi")
            appendLine("echo")
            appendLine("if [ -x /bin/bash ]; then exec /bin/bash -l; else exec /bin/sh -l; fi")
        }.trimEnd()
    }

    internal fun buildValidationCommands(selectedPackageIds: List<String>): List<String> {
        return buildValidationChecks(selectedPackageIds).map { check ->
            "{ printf '\\033[34;1m[*]\\033[0m 校验${check.label}\\n'; " +
                "if ! (${check.command}); then " +
                "printf '\\033[31;1m[!]\\033[0m 校验失败：${check.label}\\n'; exit 1; " +
                "fi; }"
        }
    }

    fun buildInventoryProbeCommand(selectedPackageIds: List<String>): String {
        val requested = selectedPackageIds
            .map(::canonicalPackageId)
            .filter { id -> packageDefinitions.any { it.id == id } }
            .distinct()
        if (requested.isEmpty()) {
            return buildCoreHealthProbeSnippet()
        }
        return buildCoreHealthProbeSnippet() + "\n" + requested.joinToString(separator = "\n") { packageId ->
            when (packageId) {
                "nodejs" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v node >/dev/null 2>&1 && node -e 'process.cwd(); if (Number(process.versions.node.split(\".\")[0]) < 22) process.exit(1)' >/dev/null 2>&1",
                    versionCommand = "node --version"
                )
                "npm" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v npm >/dev/null 2>&1 && npm --version >/dev/null 2>&1",
                    versionCommand = "npm --version"
                )
                "git" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v git >/dev/null 2>&1",
                    versionCommand = "git --version"
                )
                "python" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v python3 >/dev/null 2>&1 && python3 -c 'import os; os.getcwd()' >/dev/null 2>&1",
                    versionCommand = "python3 --version"
                )
                "uv" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v uv >/dev/null 2>&1 && uv --version >/dev/null 2>&1",
                    versionCommand = "uv --version"
                )
                "pip" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v pip3 >/dev/null 2>&1 && pip3 --version >/dev/null 2>&1",
                    versionCommand = "pip3 --version"
                )
                "codex" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v codex >/dev/null 2>&1 && codex --version >/dev/null 2>&1",
                    versionCommand = "codex --version"
                )
                "claude_code" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v claude >/dev/null 2>&1 && claude --version >/dev/null 2>&1",
                    versionCommand = "claude --version"
                )
                "opencode" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v opencode >/dev/null 2>&1 && opencode --version >/dev/null 2>&1",
                    versionCommand = "opencode --version"
                )
                "ssh_client" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v ssh >/dev/null 2>&1",
                    versionCommand = "ssh -V 2>&1"
                )
                "sshpass" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v sshpass >/dev/null 2>&1",
                    versionCommand = "sshpass -V 2>&1"
                )
                "openssh_server" -> buildProbeSnippet(
                    packageId = packageId,
                    commandCheck = "command -v sshd >/dev/null 2>&1",
                    versionCommand = "sshd -V 2>&1"
                )
                else -> buildMissingProbeSnippet(packageId)
            }
        }
    }

    fun parseInventoryProbeOutput(output: String): Map<String, PackageProbeResult> {
        return output
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("__OMNI_ENV__\t") }
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) {
                    return@mapNotNull null
                }
                val packageId = canonicalPackageId(parts[1])
                val ready = parts[2] == "READY"
                val version = parts[3].trim().ifBlank { null }
                packageId to PackageProbeResult(
                    ready = ready,
                    version = version
                )
            }
            .toMap()
    }

    fun buildCheckCommand(pkgId: String, command: String): String {
        val actual = when (canonicalPackageId(pkgId)) {
            "bash" -> "command -v bash"
            "curl" -> "command -v curl"
            "git" -> "command -v git"
            "nodejs" -> "command -v node && node -e 'process.cwd()'"
            "npm" -> "command -v npm && npm --version"
            "python" -> "command -v python3 && python3 -c 'import os; os.getcwd()'"
            "pip" -> "command -v pip3 && pip3 --version"
            "uv" -> "command -v uv && uv --version"
            "codex" -> "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v codex && codex --version"
            "claude_code" -> "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v claude && claude --version"
            "opencode" -> "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; command -v opencode && opencode --version"
            "ripgrep" -> "command -v rg"
            "tmux" -> "command -v tmux"
            "xz" -> "command -v xz"
            "ssh_client" -> "command -v ssh"
            "sshpass" -> "command -v sshpass"
            "openssh_server" -> "command -v sshd"
            else -> command
        }
        return "if { $actual; } >/dev/null 2>&1; then echo INSTALLED; else echo MISSING; fi"
    }

    fun isPackageInstalled(pkgId: String, output: String): Boolean {
        val normalized = output.trim()
        val canonicalId = canonicalPackageId(pkgId)
        return normalized.contains("INSTALLED") || normalized.contains(canonicalId, ignoreCase = true)
    }

    private fun canonicalPackageId(packageId: String): String {
        return when (packageId.trim()) {
            "python3" -> "python"
            "pip3" -> "pip"
            "ssh" -> "ssh_client"
            "openssh_client" -> "ssh_client"
            "ssh_server" -> "openssh_server"
            "claude", "claude-code" -> "claude_code"
            else -> packageId.trim()
        }
    }

    private fun buildValidationChecks(selectedPackageIds: List<String>): List<ValidationCheck> {
        val requested = selectedPackageIds
            .map(::canonicalPackageId)
            .filter { id ->
                alpineInstallPackageMap.containsKey(id) ||
                    ubuntuInstallPackageMap.containsKey(id) ||
                    packageDefinitions.any { it.id == id }
            }
            .distinct()
            .toSet()
        if (requested.isEmpty()) {
            return emptyList()
        }

        val checks = linkedMapOf(
            "基础目录操作" to "cd /root >/dev/null 2>&1 && /bin/pwd >/dev/null 2>&1"
        )
        fun add(label: String, command: String) {
            checks.putIfAbsent(label, command)
        }

        if (
            requested.any {
                it == "nodejs" || it == "npm" || it in NPM_AGENT_PACKAGE_IDS
            }
        ) {
            add(
                "Node.js 22+",
                "node -e 'process.cwd(); if (Number(process.versions.node.split(\".\")[0]) < 22) process.exit(1)' >/dev/null 2>&1"
            )
        }
        if (requested.any { it == "npm" || it in NPM_AGENT_PACKAGE_IDS }) {
            add("npm", "npm --version >/dev/null 2>&1")
        }
        if ("git" in requested || requested.any { it in NPM_AGENT_PACKAGE_IDS }) {
            add("Git", "git --version >/dev/null 2>&1")
        }
        if (requested.any { it == "python" || it == "pip" || it == "uv" }) {
            add("Python cwd", "python3 -c 'import os; os.getcwd()' >/dev/null 2>&1")
        }
        if ("pip" in requested || "uv" in requested) {
            add("pip", "pip3 --version >/dev/null 2>&1")
        }
        if ("uv" in requested) {
            add("uv", "uv --version >/dev/null 2>&1")
        }
        if ("codex" in requested) {
            add(
                "Codex CLI",
                "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; codex --version >/dev/null 2>&1"
            )
        }
        if ("claude_code" in requested) {
            add(
                "Claude Code CLI",
                "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; claude --version >/dev/null 2>&1"
            )
        }
        if ("opencode" in requested) {
            add(
                "OpenCode CLI",
                "PATH=\"/root/.npm-global/bin:${'$'}PATH\"; export PATH; opencode --version >/dev/null 2>&1"
            )
        }
        if ("ssh_client" in requested) {
            add("SSH client", "ssh -V >/dev/null 2>&1")
        }
        if ("sshpass" in requested) {
            add("sshpass", "sshpass -V >/dev/null 2>&1")
        }
        if ("openssh_server" in requested) {
            add("OpenSSH server", "command -v sshd >/dev/null 2>&1")
        }

        return checks.map { (label, command) -> ValidationCheck(label, command) }
    }

    private fun buildCoreHealthProbeSnippet(): String {
        return """
            if ! (cd /root >/dev/null 2>&1 && /bin/pwd >/dev/null 2>&1); then
              printf '__OMNI_ENV__\t%s\tBROKEN\t%s\n' 'core' 'cwd syscall failed'
              exit 38
            fi
        """.trimIndent()
    }

    private fun buildProbeSnippet(
        packageId: String,
        commandCheck: String,
        versionCommand: String
    ): String {
        return """
            if $commandCheck; then
              version="${'$'}($versionCommand | head -n 1 | tr '\r' ' ')"
              printf '__OMNI_ENV__\t%s\tREADY\t%s\n' '$packageId' "${'$'}version"
            else
              printf '__OMNI_ENV__\t%s\tMISSING\t\n' '$packageId'
            fi
        """.trimIndent()
    }

    private fun buildMissingProbeSnippet(packageId: String): String {
        return "printf '__OMNI_ENV__\\t%s\\tMISSING\\t\\n' '$packageId'"
    }

    private val NPM_AGENT_PACKAGE_IDS = setOf("codex", "claude_code", "opencode")
}
