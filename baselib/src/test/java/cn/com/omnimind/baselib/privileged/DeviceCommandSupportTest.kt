package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceCommandSupportTest {
    private val request = PrivilegedRequest("support-probe", "device_control.set_state")
    private val nightHelp = """
        UiModeManager service (uimode) commands:
          help
            Print this help text.
          night [yes|no|auto|custom_schedule|custom_bedtime]
            Set or read night mode.
          car [yes|no]
            Set or read car mode.
    """.trimIndent()
    private val carOnlyHelp = """
        UiModeManager service (uimode) commands:
          help
            Print this help text.
          car [yes|no]
            Set or read car mode.
    """.trimIndent()

    private fun result(
        exitCode: Int? = 0,
        code: String = if (exitCode == 0) "ok" else "command_failed",
        stdout: String = nightHelp,
        stderr: String = "",
        truncated: Boolean = false,
    ) = PrivilegedResult(
        requestId = request.requestId,
        action = request.action,
        success = code == "ok" && exitCode == 0,
        code = code,
        message = "probe $code",
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        data = mapOf("truncated" to truncated.toString()),
    )

    private fun execution(result: PrivilegedResult, calls: MutableList<String> = mutableListOf()) =
        DeviceActionExecution(request, 0, { command -> calls += command; result }, { true })

    private suspend fun expectFailure(code: String, operation: suspend () -> Unit): DeviceActionException {
        try {
            operation()
            fail("Expected $code")
        } catch (error: DeviceActionException) {
            assertEquals(code, error.code)
            return error
        }
        error("Expected DeviceActionException")
    }

    @Test fun acceptsAospHelpWithZeroOrNegativeReturnConvention() = runBlocking {
        for (exitCode in listOf(0, 255, -1)) {
            val calls = mutableListOf<String>()
            assertTrue("help exit=$exitCode", execution(result(exitCode), calls).supports("uimode", "night"))
            assertEquals(listOf("'cmd' 'uimode' 'help'"), calls)
        }
    }

    @Test fun acceptsCommandDeclarationsOnStderr() = runBlocking {
        assertTrue(execution(result(255, stdout = "", stderr = nightHelp)).supports("uimode", "night"))
    }

    @Test fun anUnknownHelpPrefixDoesNotDiscardTheHelpThatFollows() = runBlocking {
        val stderr = "Unknown command: help\n$nightHelp"
        assertTrue(execution(result(255, stdout = "", stderr = stderr)).supports("uimode", "night"))
    }

    @Test fun supportsUnindentedOperationDeclarations() = runBlocking {
        val help = nightHelp.replace("  night [", "night [")
        assertTrue(execution(result(255, stdout = help)).supports("uimode", "night"))
    }

    @Test fun completeHelpWithoutTheTargetIsUnsupported() = runBlocking {
        val execution = execution(result(255, stdout = carOnlyHelp))
        assertFalse(execution.supports("uimode", "night"))
        expectFailure("unsupported_action") { execution.requireCommand("uimode", "night") }
        Unit
    }

    @Test fun commandNamesMustMatchExactlyRatherThanPrefixesOrDescriptions() = runBlocking {
        val help = """
            UiModeManager service (uimode) commands:
              help
                Print this help text.
              night-light [enable|disable]
                Set the night light.
              night_mode [yes|no]
                Read the night mode.
              car [yes|no]
                Use night [yes|no] to change the theme on another implementation.
        """.trimIndent()
        assertFalse(execution(result(255, stdout = help)).supports("uimode", "night"))
    }

    @Test fun aDeeperDescriptionStartingWithTheOperationNameIsNotADeclaration() = runBlocking {
        val help = """
            UiModeManager service (uimode) commands:
              help
                Print this help text.
              car [yes|no]
                night changes are described here but are not a supported command.
        """.trimIndent()
        assertFalse(execution(result(255, stdout = help)).supports("uimode", "night"))
    }

    @Test fun transportAndAuthorizationFailuresCannotBeOverriddenByHelpText() = runBlocking {
        for (code in listOf(
            "root_denied", "root_unavailable", "permission_denied", "timeout", "service_timeout",
            "service_call_failed", "service_bind_failed", "output_incomplete", "privileged_access_changed",
        )) {
            val error = expectFailure(code) {
                execution(result(255, code = code)).requireCommand("uimode", "night")
            }
            assertTrue(error.message.orEmpty().contains("probe $code"))
        }
    }

    @Test fun abnormalExitCodeCannotBeOverriddenByAnOperationDeclaration() = runBlocking {
        for (exitCode in listOf<Int?>(1, 2, 127, null)) {
            expectFailure("capability_probe_failed") {
                execution(result(exitCode)).supports("uimode", "night")
            }
        }
    }

    @Test fun exceptionDiagnosticsCannotBeOverriddenByHelpText() = runBlocking {
        val diagnostics = listOf(
            "Exception occurred while executing 'help':\n",
            "java.lang.SecurityException: Permission Denial\n",
        )
        for (stderr in diagnostics) {
            for (exitCode in listOf(0, 255)) {
                expectFailure("capability_probe_failed") {
                    execution(result(exitCode, stderr = stderr)).supports("uimode", "night")
                }
            }
        }
    }

    @Test fun unrecognizableResponseDoesNotClaimUnsupportedHardware() = runBlocking {
        for (stdout in listOf("", "Unknown command: help\n", "service returned no capability description\n")) {
            expectFailure("capability_probe_failed") {
                execution(result(255, stdout = stdout)).supports("uimode", "night")
            }
        }
    }

    @Test fun aTruncatedHelpResponseCannotProveThatAnOperationIsAbsent() = runBlocking {
        expectFailure("capability_probe_incomplete") {
            execution(result(255, stdout = carOnlyHelp, truncated = true)).supports("uimode", "night")
        }
        Unit
    }

    @Test fun anExactDeclarationRemainsPositiveEvidenceInTruncatedHelp() = runBlocking {
        assertTrue(execution(result(255, truncated = true)).supports("uimode", "night"))
        expectFailure("output_incomplete") {
            execution(result(255, code = "output_incomplete", truncated = true)).supports("uimode", "night")
        }
        Unit
    }

    @Test fun cancellationPropagatesWithoutTurningIntoUnsupported() = runBlocking {
        val expected = CancellationException("ACP request cancelled")
        val execution = DeviceActionExecution(request, 0, { throw expected }, { true })
        try {
            execution.requireCommand("uimode", "night")
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertTrue(actual === expected)
        }
    }

    @Test fun revokedAccessPreventsTheHelpProbeFromStarting() = runBlocking {
        var calls = 0
        val execution = DeviceActionExecution(request, 0, { calls++; result() }, { false })
        expectFailure("privileged_access_changed") { execution.supports("uimode", "night") }
        assertEquals(0, calls)
    }
}
