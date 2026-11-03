package cn.com.omnimind.baselib.privileged

import android.app.UiAutomation
import android.app.UiModeManager
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 默认只读真实系统帮助；往返修改必须显式传入 instrumentation 参数
 * omnibot.verifyDarkModeRoundTrip=true。此测试后端不申请或改变产品的 Root 授权。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
@RequiresApi(34)
class AndroidShellCommandCompatibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val shell get() = ShellCapture(instrumentation.uiAutomation)

    @Test fun uiModeAdvertisesNightMode() = runBlocking { assertAdvertised("uimode", "night", required = true) }
    @Test fun displayAdvertisesBrightness() = runBlocking { assertAdvertised("display", "set-brightness") }
    @Test fun notificationsAdvertiseDoNotDisturb() = runBlocking { assertAdvertised("notification", "set_dnd") }
    @Test fun wifiAdvertisesItsSwitch() = runBlocking { assertAdvertised("wifi", "set-wifi-enabled") }
    @Test fun bluetoothAdvertisesEnable() = runBlocking { assertAdvertised("bluetooth_manager", "enable") }
    @Test fun connectivityAdvertisesAirplaneMode() = runBlocking { assertAdvertised("connectivity", "airplane-mode") }
    @Test fun locationAdvertisesItsSwitch() = runBlocking { assertAdvertised("location", "set-location-enabled") }
    @Test fun nfcAdvertisesItsSwitch() = runBlocking { assertAdvertised("nfc", "enable-nfc") }
    @Test fun powerAdvertisesBatterySaver() = runBlocking { assertAdvertised("power", "set-mode") }

    private suspend fun assertAdvertised(service: String, operation: String, required: Boolean = false) {
        val capture = shell
        val services = capture.execute("cmd -l")
        assertTrue("Could not enumerate system services: ${services.code}", services.success)
        assertFalse("Service inventory was truncated", services.data["truncated"] == "true")
        val exists = services.stdout.lineSequence().any { it.trim() == service }
        if (required) assertTrue("Required system service $service is absent", exists)
        else assumeTrue("Optional system service $service is absent; capability was not verified", exists)

        val raw = capture.execute("cmd $service help")
        assertTrue("$service help did not complete normally: ${raw.code}, exit=${raw.exitCode}",
            raw.code in setOf("ok", "command_failed") && raw.exitCode in setOf(0, 255, -1))
        assertFalse("$service help is incomplete; absence cannot be established", raw.data["truncated"] == "true")
        val declaration = Regex("(?m)^[ \\t]*${Regex.escape(operation)}(?=[ \\t\\[<]|$)")
        val advertised = declaration.containsMatchIn(raw.stdout) || declaration.containsMatchIn(raw.stderr)
        if (required) assertTrue("$service did not advertise $operation", advertised)
        else assumeTrue("$service does not advertise $operation; capability was not verified", advertised)

        val request = PrivilegedRequest("device-help-$service", PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE)
        // 固定已采集的真实结果，避免测试靠二次探测或修改设备来绕过错误。
        val execution = DeviceActionExecution(request, Process.myUid() / 100000, { raw }, { true })
        assertTrue("Production support detection rejected $service/$operation with exit=${raw.exitCode}",
            execution.supports(service, operation))
    }

    @Test fun darkModeRoundTripWhenExplicitlyEnabled() = runBlocking {
        assumeTrue("Device mutation requires explicit instrumentation opt-in",
            InstrumentationRegistry.getArguments().getString("omnibot.verifyDarkModeRoundTrip") == "true")
        val context = instrumentation.targetContext
        val userId = Process.myUid() / 100000
        assumeTrue("The production night-mode command only supports the primary Android user", userId == 0)
        val uiMode = requireNotNull(context.getSystemService(UiModeManager::class.java))
        val original = uiMode.nightMode
        assumeTrue("Automatic/custom night configuration is not changed by this test",
            original == UiModeManager.MODE_NIGHT_YES || original == UiModeManager.MODE_NIGHT_NO)
        val capture = shell
        val identity = capture.execute("id -u")
        assertTrue("Could not read the test shell identity", identity.success)
        val backend = when (identity.stdout.trim()) {
            "0" -> PrivilegedBackend.ROOT
            "2000" -> PrivilegedBackend.ADB
            else -> error("The test shell does not have a supported privileged identity")
        }
        val enable = original != UiModeManager.MODE_NIGHT_YES
        val expected = if (enable) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
        val request = PrivilegedRequest(
            requestId = "device-dark-mode-${UUID.randomUUID()}",
            action = PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            arguments = mapOf("target" to "dark_mode", "enabled" to enable.toString()),
            userId = userId,
        )
        var changes = 0
        var primaryFailure: Throwable? = null
        try {
            val result = PrivilegedDeviceController(context, userId).execute(request, backend, { true }) { command ->
                when (command) {
                    "'cmd' 'uimode' 'help'" -> Unit
                    "'cmd' 'uimode' 'night' '${if (enable) "yes" else "no"}'" -> changes++
                    else -> error("Unexpected command in the night-mode test: $command")
                }
                capture.execute(command)
            }
            assertTrue("Night-mode action failed: ${result.code}: ${result.message}", result.success)
            assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
            assertEquals("The requested modification must run exactly once", 1, changes)
            assertEquals(expected, uiMode.nightMode)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (changes > 0) {
                    val restoration = capture.execute("cmd uimode night ${if (original == UiModeManager.MODE_NIGHT_YES) "yes" else "no"}")
                    assertTrue("Could not restore original night mode: ${restoration.code}", restoration.success)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (uiMode.nightMode != original && System.nanoTime() < deadline) Thread.sleep(100)
                    assertEquals("Original system night mode was not restored", original, uiMode.nightMode)
                }
            } catch (restoreFailure: Throwable) {
                if (primaryFailure == null) throw restoreFailure
                primaryFailure.addSuppressed(restoreFailure)
            }
        }
    }

    /** Rwe 的 stdin 用于传递脚本，避免 Runtime.exec 的空白分词破坏引号。 */
    private class ShellCapture(private val automation: UiAutomation) {
        fun execute(command: String): PrivilegedResult {
            val token = "OMNI_TEST_EXIT_${UUID.randomUUID()}"
            val descriptors = automation.executeShellCommandRwe("/system/bin/sh")
            val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "omni-shell-test-drain").apply { isDaemon = true } }
            try {
                val stdout = pool.submit<Captured> {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use(::drain)
                }
                val stderr = pool.submit<Captured> {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).use(::drain)
                }
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).bufferedWriter().use { input ->
                    input.write("/system/bin/timeout 10 /system/bin/sh -c ${quote(command)}\n")
                    input.write("omni_test_exit=\$?\nprintf '\\n%s:%s\\n' ${quote(token)} \"\$omni_test_exit\" >&2\n")
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                val out = stdout.get(15, TimeUnit.SECONDS)
                val err = stderr.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                val marker = Regex("(?m)^${Regex.escape(token)}:([0-9]+)\\s*$")
                val exitCode = marker.find(err.tail)?.groupValues?.get(1)?.toIntOrNull()
                    ?: error("The test shell did not report its exit status")
                val errorText = err.text.replace(marker, "").trimEnd()
                return PrivilegedResult(
                    requestId = token,
                    action = PrivilegedActionPolicy.ACTION_DEVICE_COMMAND,
                    success = exitCode == 0,
                    code = when (exitCode) { 0 -> "ok"; 124 -> "timeout"; else -> "command_failed" },
                    message = "Device shell exited with $exitCode",
                    stdout = out.text,
                    stderr = errorText,
                    exitCode = exitCode,
                    command = command,
                    data = mapOf("truncated" to (out.truncated || err.truncated).toString()),
                )
            } finally {
                descriptors.forEach { runCatching { it.close() } }
                pool.shutdownNow()
            }
        }

        private fun drain(input: InputStream): Captured {
            val retained = ByteArrayOutputStream()
            val tail = StringBuilder()
            var truncated = false
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                val remaining = (256 * 1024 - retained.size()).coerceAtLeast(0)
                retained.write(buffer, 0, count.coerceAtMost(remaining))
                if (count > remaining) truncated = true
                tail.append(String(buffer, 0, count, Charsets.UTF_8))
                if (tail.length > 4096) tail.delete(0, tail.length - 4096)
            }
            return Captured(retained.toString(Charsets.UTF_8.name()), tail.toString(), truncated)
        }

        private data class Captured(val text: String, val tail: String, val truncated: Boolean)
        private fun quote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}
