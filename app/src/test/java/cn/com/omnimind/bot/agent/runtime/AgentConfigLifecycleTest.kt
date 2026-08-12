package cn.com.omnimind.bot.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigLifecycleTest {
    @Test
    fun replaceOnlyStatusPayloadContainsMetadataAndNeverContent() {
        val secret = "SECRET_ANTHROPIC_TOKEN_SHOULD_NEVER_CROSS_THE_CHANNEL"
        val payload = buildReplaceOnlyAgentConfigPayload(
            agentId = "claude-code-acp",
            format = "json",
            displayPath = "~/.claude/settings.json",
            hasConfig = true,
            byteCount = secret.length.toLong()
        )

        assertEquals("replace-only", payload["kind"])
        assertEquals(true, payload["hasConfig"])
        assertEquals(secret.length.toLong(), payload["byteCount"])
        assertFalse(payload.containsKey("content"))
        assertFalse(payload.toString().contains(secret))
    }

    @Test
    fun blankReplacementFailsBeforeAnyWriteCanBeBuilt() {
        val error = runCatching { validateAgentConfigReplacement(" \n\t ") }
            .exceptionOrNull()

        assertEquals(AGENT_CONFIG_EMPTY_REPLACEMENT, error?.message)
    }

    @Test
    fun explicitReplacementUsesStdinUniqueTempAndAtomicRename() {
        val secret = "SECRET_CONFIG_CONTENT_NOT_ALLOWED_IN_ARGV"
        val target = requireNotNull(agentConfigFileTargetFor("claude-code-acp"))
        val command = buildAgentConfigAtomicReplaceCommand(target)

        assertFalse(command.contains(secret))
        assertTrue(command.contains("cat > \"\$temp\""))
        assertTrue(command.contains("mktemp"))
        assertTrue(command.contains("mv -f \"\$temp\" \"\$target\""))
        assertTrue(command.contains("chmod 700"))
        assertTrue(command.contains("chmod 600"))
        assertFalse(command.contains("chmod 600 \"\$target\""))
        assertFalse(command.contains("> '${target.path}'"))
    }

    @Test
    fun fixedAllowlistRejectsTraversalAndCallerConstructedTarget() {
        assertEquals(null, agentConfigFileTargetFor("../../root/.ssh/authorized_keys"))
        assertTrue(agentConfigClearTargetsFor("../../root/.ssh").isEmpty())

        val injected = AgentConfigFileTarget(
            agentId = "claude-code-acp",
            role = "config",
            path = "/root/.ssh/authorized_keys",
            displayPath = "~/.ssh/authorized_keys",
            format = "json",
            parentDirectories = listOf("/root/.ssh")
        )
        val error = runCatching { buildAgentConfigAtomicReplaceCommand(injected) }
            .exceptionOrNull()
        assertEquals(AGENT_CONFIG_UNSAFE_PATH, error?.message)
    }

    @Test
    fun statusAndReplaceCommandsFailClosedForLinksAndPinPhysicalParent() {
        val target = requireNotNull(agentConfigFileTargetFor("opencode-acp"))
        val statusCommand = buildAgentConfigFileStatusCommand(target)
        val replaceCommand = buildAgentConfigAtomicReplaceCommand(target)

        assertTrue(statusCommand.contains("os.O_NOFOLLOW"))
        assertTrue(statusCommand.contains("os.fstat(fd)"))
        assertTrue(statusCommand.contains("info.st_nlink != 1"))
        assertFalse(statusCommand.contains("chmod"))
        assertTrue(replaceCommand.contains("[ -L"))
        assertTrue(replaceCommand.contains("cd -P"))
        assertTrue(replaceCommand.contains("stat -c '%h'"))
        assertFalse(statusCommand.contains("cat "))
    }

    @Test
    fun clearOnlyUnlinksAllowlistedEntriesAndNeverReadsThem() {
        val targets = agentConfigClearTargetsFor("codex-acp")
        val command = buildAgentConfigClearCommand(targets)

        assertEquals(2, targets.size)
        assertTrue(command.contains("config.toml"))
        assertTrue(command.contains("auth.json"))
        assertTrue(command.contains("[ ! -L \"\$target\" ]"))
        assertTrue(command.contains("rm -f \"\$target\""))
        assertFalse(command.contains("cat "))
    }

    @Test
    fun shellFailuresMapToStableNonDiagnosticCodes() {
        assertEquals(AGENT_CONFIG_UNSAFE_PATH, agentConfigErrorForExitCode(40))
        assertEquals(AGENT_CONFIG_FILE_TOO_LARGE, agentConfigErrorForExitCode(41))
        assertEquals(AGENT_CONFIG_INVALID_STATUS, agentConfigErrorForExitCode(42))
        assertEquals(AGENT_CONFIG_IO_FAILED, agentConfigErrorForExitCode(127))
    }

    @Test
    fun codexStatusUsesNoFollowFdAndTransactionPreparesBothFilesBeforeReplace() {
        val status = buildCodexAgentConfigStatusCommand()
        val transaction = buildCodexAgentConfigTransactionCommand(includeAuth = true)

        assertTrue(status.contains("os.O_NOFOLLOW"))
        assertTrue(status.contains("os.fstat(fd)"))
        assertFalse(status.contains("awk"))
        assertFalse(status.contains("grep"))
        assertFalse(status.contains("chmod"))
        assertTrue(transaction.contains("for name, content in targets:"))
        assertTrue(transaction.contains("os.fsync(fd)"))
        assertTrue(transaction.contains("os.replace(name, backup)"))
        assertTrue(transaction.contains("for name, _ in targets:"))
        assertTrue(transaction.contains("os.replace(temps.pop(name), name)"))
        assertTrue(transaction.contains("os.replace(backup, name)"))
        assertFalse(transaction.contains("chmod 600 \"\$target\""))
    }
}
