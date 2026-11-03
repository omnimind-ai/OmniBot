package cn.com.omnimind.baselib.privileged

import android.app.UiModeManager
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedDeviceControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun settingsReadsAndWritesUseTheSameAndroidUserAndQuoteValues() = runBlocking {
        val calls = mutableListOf<String>()
        var value = "old"
        val expected = "a'; touch /data/local/tmp/never; #"
        val controller = PrivilegedDeviceController(context, 10)
        val result = controller.execute(
            PrivilegedRequest("settings", PrivilegedActionPolicy.ACTION_SETTINGS_PUT,
                arguments = mapOf("namespace" to "secure", "key" to "test_setting", "value" to expected), userId = 10),
            PrivilegedBackend.ROOT, { true },
        ) { command ->
            calls += command
            if (command.contains("'put'")) value = expected
            PrivilegedResult("settings", "", true, "ok", "", stdout = "$value\n")
        }
        assertTrue(result.success)
        assertTrue(calls.all { it.startsWith("'settings' '--user' '10'") })
        assertEquals(1, calls.count { it.contains("'put'") })
        assertEquals("'settings' '--user' '10' 'put' 'secure' 'test_setting' 'a'\"'\"'; touch /data/local/tmp/never; #'", calls[1])
        assertEquals(JsonPrimitive("app_user"), result.observation!!["scope"])
    }

    @Test fun settingsRejectCrossUserArgumentsBeforeRunningAnything() = runBlocking {
        val result = PrivilegedDeviceController(context, 10).execute(
            PrivilegedRequest("settings", PrivilegedActionPolicy.ACTION_SETTINGS_GET,
                mapOf("namespace" to "system", "key" to "test", "userId" to "0")),
            PrivilegedBackend.ROOT, { true },
        ) { error("Must not run cross-user command") }
        assertEquals("invalid_arguments", result.code)
    }

    @Test fun mobileDataCannotUseAdbViaTheGenericStateAction() = runBlocking {
        val result = PrivilegedDeviceController(context, 0).execute(
            PrivilegedRequest("mobile", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                mapOf("target" to "mobile_data", "enabled" to "true")),
            PrivilegedBackend.ADB, { true },
        ) { error("Must not run") }
        assertEquals("unsupported_action", result.code)
    }

    @Test fun missingNfcHardwareDoesNotRunCommands() = runBlocking {
        val result = PrivilegedDeviceController(context, 0).execute(
            PrivilegedRequest("nfc", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                mapOf("target" to "nfc", "enabled" to "true")),
            PrivilegedBackend.ROOT, { true },
        ) { error("Must not run") }
        assertEquals("unsupported_action", result.code)
    }

    @Test fun darkModeAcceptsAospHelpExitAndVerifiesOneWrite() = runBlocking {
        val uiMode = context.getSystemService(UiModeManager::class.java)!!
        uiMode.nightMode = UiModeManager.MODE_NIGHT_NO
        val commands = mutableListOf<String>()
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            commands += command
            when (command) {
                "'cmd' 'uimode' 'help'" -> PrivilegedResult(
                    "help", "device_control.command", false, "command_failed", "Help exited with -1.",
                    stdout = "UiModeManager service (uimode) commands:\n  night [yes|no|auto]\n    Set or read night mode.\n", exitCode = 255,
                )
                "'cmd' 'uimode' 'night' 'yes'" -> {
                    uiMode.nightMode = UiModeManager.MODE_NIGHT_YES
                    PrivilegedResult("write", "device_control.command", true, "ok", "", exitCode = 0)
                }
                else -> error("Unexpected command: $command")
            }
        }
        assertTrue(result.message, result.success)
        assertEquals(JsonPrimitive(true), result.observation!!["verified"])
        assertEquals(listOf("'cmd' 'uimode' 'help'", "'cmd' 'uimode' 'night' 'yes'"), commands)
    }

    @Test fun failedHelpPreservesDiagnosticsAndDoesNotWriteDeviceState() = runBlocking {
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        val commands = mutableListOf<String>()
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            commands += command
            PrivilegedResult("help", "device_control.command", false, "root_denied", "Root access denied.",
                stderr = "su: permission denied", exitCode = 1, provider = PrivilegedProvider.ROOT)
        }
        assertFalse(result.success)
        assertEquals("root_denied", result.code)
        assertEquals("su: permission denied", result.stderr)
        assertEquals(1, result.exitCode)
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.action, result.action)
        assertEquals(listOf("'cmd' 'uimode' 'help'"), commands)
    }

    @Test fun acceptingHelpDoesNotTurnFailedStateMutationIntoSuccess() = runBlocking {
        context.getSystemService(UiModeManager::class.java)!!.nightMode = UiModeManager.MODE_NIGHT_NO
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        var writes = 0
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            if (command.endsWith("'help'")) {
                PrivilegedResult("help", "device_control.command", false, "command_failed", "",
                    stdout = "UiModeManager commands:\n  night [yes|no]\n", exitCode = 255)
            } else {
                writes++
                PrivilegedResult("write", "device_control.command", false, "command_failed", "Write refused.", exitCode = 255)
            }
        }
        assertFalse(result.success)
        assertEquals("command_failed", result.code)
        assertEquals(JsonPrimitive(false), result.observation!!["commandAccepted"])
        assertEquals(1, writes)
    }

    @Test fun bluetoothParserDoesNotInferStateFromAnUnrelatedLine() {
        assertNull(PrivilegedDeviceController.bluetoothState("last_state: ON\nstate: TURNING_ON\n"))
        assertEquals(true, PrivilegedDeviceController.bluetoothState("  state: ON\n"))
        assertEquals(false, PrivilegedDeviceController.bluetoothState("state: 10\n"))
    }

    @Test fun volumePercentageHonorsDeviceMinimumAndMaximum() {
        assertEquals(1, PrivilegedDisplaySoundControls.volumeLevel(0, 1, 7))
        assertEquals(4, PrivilegedDisplaySoundControls.volumeLevel(50, 1, 7))
        assertEquals(7, PrivilegedDisplaySoundControls.volumeLevel(100, 1, 7))
        assertThrows(IllegalArgumentException::class.java) { PrivilegedDisplaySoundControls.volumeLevel(101, 0, 7) }
    }
}
