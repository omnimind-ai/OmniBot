package cn.com.omnimind.baselib.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedActionPolicyTest {
    @Test
    fun `model confirmed flag never authorizes arbitrary shell or high risk action`() {
        listOf(
            PrivilegedRequest(
                requestId = "raw-shell",
                action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
                arguments = mapOf("confirmed" to "true"),
                command = "echo test",
            ),
            PrivilegedRequest(
                requestId = "high-risk",
                action = PrivilegedActionPolicy.ACTION_SETTINGS_PUT,
                arguments = mapOf(
                    "namespace" to "global",
                    "key" to "example",
                    "value" to "1",
                    "confirmed" to "true",
                ),
            ),
        ).forEach { request ->
            assertFalse(PrivilegedCommandExecutor.passesTrustedConfirmationGate(request))
        }
    }

    @Test
    fun `privileged session confirmed flag cannot bypass or replay`() {
        val requests = listOf(
            PrivilegedRequest(
                requestId = "session-start",
                action = PrivilegedActionPolicy.ACTION_SESSION_START,
                arguments = mapOf("confirmed" to "true"),
            ),
            PrivilegedRequest(
                requestId = "session-exec",
                action = PrivilegedActionPolicy.ACTION_SESSION_EXEC,
                arguments = mapOf("confirmed" to "true"),
                command = "id",
                sessionId = "session-1",
            ),
        )

        requests.forEach { request ->
            assertFalse(PrivilegedCommandExecutor.passesTrustedConfirmationGate(request))
            assertFalse(PrivilegedCommandExecutor.passesTrustedConfirmationGate(request))
        }
    }

    @Test
    fun `changing privileged arguments cannot turn an untrusted request into approval`() {
        val original = PrivilegedRequest(
            requestId = "replace-1",
            action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
            arguments = mapOf("confirmed" to "true"),
            command = "echo approved",
        )
        val changed = original.copy(
            requestId = "replace-2",
            command = "echo changed",
            arguments = mapOf("confirmed" to "true", "confirmationToken" to "invented"),
        )

        assertFalse(PrivilegedCommandExecutor.passesTrustedConfirmationGate(original))
        assertFalse(PrivilegedCommandExecutor.passesTrustedConfirmationGate(changed))
    }


    @Test
    fun adbBackendDoesNotExposeRootOnlyAction() {
        val actions = PrivilegedActionPolicy.visibleAgentActions(ShizukuBackend.ADB)

        assertFalse(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED))
        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED))
    }

    @Test
    fun rootBackendExposesRootOnlyAction() {
        val actions = PrivilegedActionPolicy.visibleAgentActions(ShizukuBackend.ROOT)

        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED))
        assertTrue(actions.contains(PrivilegedActionPolicy.ACTION_SHELL_EXEC))
    }

    @Test
    fun kernelLogcatRequiresRoot() {
        assertFalse(
            PrivilegedActionPolicy.isSupported(
                action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL,
                backend = ShizukuBackend.ADB,
                arguments = mapOf("buffer" to "kernel")
            )
        )

        assertTrue(
            PrivilegedActionPolicy.isSupported(
                action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL,
                backend = ShizukuBackend.ROOT,
                arguments = mapOf("buffer" to "kernel")
            )
        )
    }

    @Test
    fun everySupportedPrivilegedActionRequiresTrustedLocalConfirmation() {
        ShizukuBackend.entries.forEach { backend ->
            PrivilegedActionPolicy.supportedActions(backend, includeInternal = true).forEach { action ->
                assertTrue(action, PrivilegedActionPolicy.requiresConfirmation(action))
            }
        }

        assertTrue(PrivilegedActionPolicy.requiresConfirmation("future.unreviewed_action"))
    }

    @Test
    fun `confirmed arguments cannot bypass any visible or internal action`() {
        PrivilegedActionPolicy.supportedActions(
            backend = ShizukuBackend.ROOT,
            includeInternal = true,
        ).forEach { action ->
            val request = PrivilegedRequest(
                requestId = action,
                action = action,
                arguments = mapOf(
                    "confirmed" to "true",
                    "confirmationToken" to "model-invented",
                    "locallyApproved" to "true",
                ),
                command = if (action.contains("shell")) "id" else null,
                sessionId = if (action.contains("session")) "session-1" else null,
            )

            assertFalse(action, PrivilegedCommandExecutor.passesTrustedConfirmationGate(request))
        }
    }

    @Test
    fun dangerousCommandsAreBlockedBeforeExecution() {
        assertTrue(PrivilegedActionPolicy.blockedCommandReason("reboot now") != null)
        assertTrue(
            PrivilegedActionPolicy.blockedCommandReason(
                "dd if=/sdcard/boot.img of=/dev/block/by-name/boot"
            ) != null
        )
        assertTrue(
            PrivilegedActionPolicy.blockedCommandReason(
                "mount -o rw,remount /system"
            ) != null
        )
    }
}
