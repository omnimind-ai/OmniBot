package cn.com.omnimind.baselib.privileged

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedCommandExecutorTest {
    private fun request(command: String) = PrivilegedRequest(UUID.randomUUID().toString(), "shell.exec",
        mapOf("confirmed" to "true"), command = command)

    @Test fun cancellationBeforeDispatchCannotAdmitALateBinderRequest() {
        val runner = PrivilegedProcessRunner(startProcess = { error("A cancelled request must not start") })
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ADB, PrivilegedProvider.SHIZUKU, runner)
        val request = request("id")
        executor.cancel(request.requestId)
        assertEquals("cancelled", executor.execute(request).code)
    }

    @Test fun staleAuthorizationCannotStartEvenIfTheProviderIsAvailableAgain() {
        val runner = PrivilegedProcessRunner(admission = { true }, startProcess = { error("Stale authorization") })
        val result = runner.execute("request", listOf("id"), 1, requestAdmission = { false })
        assertEquals("privileged_access_changed", result.errorCode)
    }

    @Test fun blockedCommandsAndUnconfirmedWritesDoNotReachTheProcessRunner() {
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { error("Must not execute") }))
        assertEquals("blocked_by_policy", executor.execute(request("reboot")).code)
        assertEquals("confirmation_required", executor.execute(request("id").copy(arguments = emptyMap())).code)
    }

    @Test fun publicOutputLimitIsReportedEvenBelowTheDrainBufferLimit() {
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { args -> ProcessBuilder(listOf("/bin/sh") + args.drop(1)).start() }))
        try {
            val result = executor.execute(request("i=0; while [ \"\$i\" -lt 2000 ]; do printf 0123456789; i=\$((i+1)); done"))
            assertTrue(result.message, result.success)
            assertEquals(16000, result.stdout.length)
            assertEquals("true", result.data["truncated"])
        } finally { executor.shutdown() }
    }

    @Test fun nonzeroHelpOutputRetainsTruncationAndFailureDetails() {
        for (outputSize in listOf(20_000, 80_000)) {
            for (stderr in listOf(false, true)) {
                val command = "cmd uimode help"
                val result = executeFixture(
                    PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DEVICE_COMMAND, command = command),
                    outputSize, stderr, 255,
                )
                assertFalse(result.success)
                assertEquals("command_failed", result.code)
                assertEquals(255, result.exitCode)
                assertEquals(PrivilegedProvider.ROOT, result.provider)
                assertEquals(command, result.command)
                assertEquals("true", result.data["truncated"])
                val help = if (stderr) result.stderr else result.stdout
                assertEquals(16000, help.length)
                assertTrue(help.startsWith("UI mode manager commands:\n"))
                assertTrue((if (stderr) result.stdout else result.stderr).isEmpty())
            }
        }
    }

    @Test fun typedFailuresPreserveTheSameOutputMetadataAsSuccesses() {
        val request = PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DIAGNOSTICS_DUMPSYS,
            mapOf("service" to "power"))
        for (exitCode in listOf(0, 255)) {
            val result = executeFixture(request, 20_000, stderr = true, exitCode = exitCode)
            assertEquals(exitCode == 0, result.success)
            assertEquals(if (exitCode == 0) "ok" else "command_failed", result.code)
            assertEquals(exitCode, result.exitCode)
            assertEquals("true", result.data["truncated"])
            assertEquals(16000, result.stderr.length)
            assertEquals(PrivilegedProvider.ROOT, result.provider)
        }
    }

    @Test fun completeNonzeroHelpIsNotMarkedAsTruncated() {
        val result = executeFixture(
            PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DEVICE_COMMAND, command = "cmd uimode help"),
            outputSize = 10, stderr = false, exitCode = 255,
        )
        assertEquals("command_failed", result.code)
        assertEquals(255, result.exitCode)
        assertEquals("false", result.data["truncated"])
        assertTrue(result.stdout.endsWith("  night [yes|no]\n"))
    }

    private fun executeFixture(request: PrivilegedRequest, outputSize: Int, stderr: Boolean, exitCode: Int): PrivilegedResult {
        val script = "{ printf 'UI mode manager commands:\\n'; " +
            "i=0; while [ \"\$i\" -lt ${outputSize / 10} ]; do printf 0123456789; i=\$((i+1)); done; " +
            "printf '\\n  night [yes|no]\\n'; }" + (if (stderr) " >&2" else "") + "; exit $exitCode"
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { ProcessBuilder("/bin/sh", "-c", script).start() }))
        return try { executor.execute(request) } finally { executor.shutdown() }
    }
}
