package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedShellSessionManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sessions(uid: String = "0"): PrivilegedShellSessionManager {
        val bin = temporary.newFolder()
        File(bin, "id").apply { writeText("#!/bin/sh\nprintf '%s\\n' '$uid'\n"); setExecutable(true) }
        // 使用普通本地 shell 模拟已授权的后端，只验证会话协议，不申请本机 Root。
        return PrivilegedShellSessionManager(PrivilegedProcessRunner(startProcess = {
            ProcessBuilder("/bin/sh").apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
            }.start()
        }))
    }

    private fun request(action: String, sessionId: String? = null, command: String? = null) = PrivilegedRequest(
        UUID.randomUUID().toString(), action, sessionId = sessionId, command = command,
    )

    @Test fun cwdAndEnvironmentPersistAcrossCommands() {
        val manager = sessions()
        try {
            val directory = temporary.newFolder()
            val start = manager.startSession(request("shell.session_start").copy(
                workingDirectory = directory.absolutePath, environment = mapOf("OMNI_TEST_VALUE" to "first")), PrivilegedBackend.ROOT)
            assertTrue(start.message, start.success)
            val first = manager.execSession(request("shell.session_exec", start.sessionId, "printf '%s:%s' \"\$PWD\" \"\$OMNI_TEST_VALUE\"; export OMNI_TEST_VALUE=second"), PrivilegedBackend.ROOT)
            assertTrue(first.message, first.success)
            assertEquals(first.stdout, directory.canonicalFile, File(first.stdout.trim().substringBefore(":" )).canonicalFile)
            assertEquals("first", first.stdout.trim().substringAfterLast(":"))
            val second = manager.execSession(request("shell.session_exec", start.sessionId, "printf '%s' \"\$OMNI_TEST_VALUE\""), PrivilegedBackend.ROOT)
            assertEquals("second", second.stdout.trim())
        } finally { manager.shutdown() }
    }

    @Test fun unverifiedShellIdentityIsNotAcceptedAsRoot() {
        val manager = sessions(uid = "2000")
        try {
            val result = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            assertFalse(result.success)
            assertEquals("root_denied", result.code)
        } finally { manager.shutdown() }
    }

    @Test fun outputWithoutNewlinesIsBoundedAndStillFindsTheCompletionMarker() {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            assertTrue(start.success)
            val result = manager.execSession(request("shell.session_exec", start.sessionId,
                "i=0; while [ \"\$i\" -lt 10000 ]; do printf 0123456789; i=\$((i+1)); done"), PrivilegedBackend.ROOT)
            assertTrue(result.message, result.success)
            assertTrue(result.stdout.startsWith("...[earlier output truncated]"))
            assertTrue(result.stdout.length < 66000)
        } finally { manager.shutdown() }
    }

    @Test fun cancellationClosesTheSessionAndDoesNotReplayIt() = runBlocking {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            val marker = File(temporary.root, "started")
            val running = launch(Dispatchers.IO) {
                runInterruptible {
                    manager.execSession(request("shell.session_exec", start.sessionId,
                        "printf started > '${marker.absolutePath}'; while :; do :; done"), PrivilegedBackend.ROOT)
                }
            }
            withTimeout(3000) { while (!marker.exists()) delay(10) }
            withTimeout(3000) { running.cancelAndJoin() }
            val subsequent = manager.execSession(request("shell.session_exec", start.sessionId, "echo should-not-run"), PrivilegedBackend.ROOT)
            assertEquals("session_not_found", subsequent.code)
        } finally { manager.shutdown() }
    }
}
