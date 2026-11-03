package cn.com.omnimind.baselib.privileged

import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal class PrivilegedDeviceController(private val context: Context, private val userId: Int) {
    private val displaySound = PrivilegedDisplaySoundControls(context)

    fun handles(action: String): Boolean = action in actions

    suspend fun execute(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        stillAllowed: () -> Boolean,
        command: suspend (String) -> PrivilegedResult,
    ): PrivilegedResult {
        val execution = DeviceActionExecution(request, userId, command, stillAllowed)
        return try {
            when (request.action) {
                PrivilegedActionPolicy.ACTION_SETTINGS_GET, PrivilegedActionPolicy.ACTION_SETTINGS_PUT -> setting(execution)
                PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE -> {
                    val target = target(request.arguments)
                    execution.query(readState(target, execution)?.let(::JsonPrimitive), scope(target))
                }
                PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED,
                PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> {
                    val target = when (request.action) {
                        PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED -> "wifi"
                        PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> "mobile_data"
                        else -> target(request.arguments)
                    }
                    if (target == "mobile_data" && backend != PrivilegedBackend.ROOT) {
                        throw DeviceActionException("unsupported_action", "Mobile data control requires a root backend.")
                    }
                    requireHardware(target)
                    if (target == "dark_mode" && userId != 0) {
                        throw DeviceActionException("unsupported_action", "This system night-mode command does not support selecting the app's Android user.")
                    }
                    val enabled = DeviceActionExecution.boolean(request.arguments, "enabled")
                    val command = stateCommand(target, enabled, execution)
                    execution.mutate(JsonPrimitive(enabled), scope(target), read = {
                        readState(target, execution)?.let(::JsonPrimitive)
                    }) { execution.run(*command.toTypedArray()) }
                }
                else -> displaySound.execute(execution)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: DeviceActionException) { execution.failure(error)
        } catch (error: IllegalArgumentException) { execution.failure("invalid_arguments", error.message.orEmpty())
        } catch (_: SecurityException) { execution.failure("permission_denied", "The system denied access to this device capability.")
        } catch (_: UnsupportedOperationException) { execution.failure("unsupported_action", "This device does not support the operation.")
        }
    }

    private suspend fun setting(execution: DeviceActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val namespace = args["namespace"].orEmpty()
        val key = args["key"].orEmpty()
        require(namespace in setOf("system", "secure", "global")) { "Unsupported settings namespace." }
        require(Regex("^[A-Za-z0-9_.:-]+$").matches(key)) { "Invalid settings key." }
        args["userId"]?.let { require(it.toIntOrNull() == userId) { "Cross-user settings access is not supported." } }
        val scope = if (namespace == "global") "device" else "app_user"
        suspend fun read(): JsonElement? {
            val result = execution.run("settings", "--user", userId.toString(), "get", namespace, key)
            return if (result.success && result.data["truncated"] != "true") {
                JsonPrimitive(result.stdout.removeSuffix("\n"))
            } else null
        }
        if (execution.request.action == PrivilegedActionPolicy.ACTION_SETTINGS_GET) {
            val value = read()
            return execution.query(value, scope).copy(data = mapOf("value" to (value as? JsonPrimitive)?.content.orEmpty()))
        }
        val value = requireNotNull(args["value"]) { "value is required." }
        require(value.length <= 2000) { "Settings value is too long." }
        return execution.mutate(JsonPrimitive(value), scope, read = ::read) {
            execution.run("settings", "--user", userId.toString(), "put", namespace, key, value)
        }
    }

    private suspend fun readState(target: String, execution: DeviceActionExecution): Boolean? {
        requireHardware(target)
        val api = try {
            when (target) {
                "wifi" -> when (context.getSystemService(WifiManager::class.java)?.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> true
                    WifiManager.WIFI_STATE_DISABLED -> false
                    else -> null
                }
                "bluetooth" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.let {
                    when (it.state) { 10 -> false; 12 -> true; else -> null }
                }
                "mobile_data" -> SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it >= 0 }?.let {
                    context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(it)?.isDataEnabled
                }
                "airplane_mode" -> settingBoolean(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1))
                "location" -> context.getSystemService(LocationManager::class.java)?.isLocationEnabled
                "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
                "battery_saver" -> context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
                "auto_rotate" -> settingBoolean(Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1))
                "auto_brightness" -> settingBoolean(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
                "dark_mode" -> context.getSystemService(UiModeManager::class.java)?.nightMode?.let {
                    when (it) { UiModeManager.MODE_NIGHT_YES -> true; UiModeManager.MODE_NIGHT_NO -> false; else -> null }
                }
                else -> null
            }
        } catch (_: SecurityException) { null }
        if (api != null) return api
        if (target == "nfc") {
            val result = execution.run("cmd", "nfc", "status")
            return if (result.success) when (result.stdout.trim()) {
                "Nfc is enabled" -> true
                "Nfc is disabled" -> false
                else -> null
            } else null
        }
        if (target != "bluetooth") return null
        val result = execution.run("dumpsys", "bluetooth_manager")
        return if (result.success && result.data["truncated"] != "true") bluetoothState(result.stdout) else null
    }

    private suspend fun stateCommand(target: String, enabled: Boolean, execution: DeviceActionExecution): List<String> {
        fun command(service: String, operation: String, vararg args: String) = listOf("cmd", service, operation) + args
        val value = if (enabled) "1" else "0"
        val selected = when (target) {
            "wifi" -> if (execution.supports("wifi", "set-wifi-enabled")) {
                command("wifi", "set-wifi-enabled", if (enabled) "enabled" else "disabled")
            } else listOf("svc", "wifi", if (enabled) "enable" else "disable")
            "bluetooth" -> command("bluetooth_manager", if (enabled) "enable" else "disable")
            "mobile_data" -> listOf("svc", "data", if (enabled) "enable" else "disable")
            "airplane_mode" -> command("connectivity", "airplane-mode", if (enabled) "enable" else "disable")
            "location" -> command("location", "set-location-enabled", enabled.toString(), "--user", userId.toString())
            "nfc" -> command("nfc", if (enabled) "enable-nfc" else "disable-nfc")
            "battery_saver" -> command("power", "set-mode", value)
            "auto_rotate" -> listOf("settings", "--user", userId.toString(), "put", "system", "accelerometer_rotation", value)
            "auto_brightness" -> listOf("settings", "--user", userId.toString(), "put", "system", "screen_brightness_mode", value)
            "dark_mode" -> command("uimode", "night", if (enabled) "yes" else "no")
            else -> throw IllegalArgumentException("Unsupported device state.")
        }
        if (selected.first() == "cmd") execution.requireCommand(selected[1], selected[2])
        if (selected.first() == "svc") {
            val help = execution.run("svc", "help")
            if (!(help.stdout + help.stderr).contains(Regex("\\b${selected[1]}\\b"))) {
                throw DeviceActionException("unsupported_action", "This system does not provide svc/${selected[1]}.")
            }
        }
        return selected
    }

    private fun requireHardware(target: String) {
        val feature = when (target) {
            "wifi" -> PackageManager.FEATURE_WIFI
            "bluetooth" -> PackageManager.FEATURE_BLUETOOTH
            "mobile_data" -> PackageManager.FEATURE_TELEPHONY
            "nfc" -> PackageManager.FEATURE_NFC
            "location" -> PackageManager.FEATURE_LOCATION
            else -> return
        }
        if (!context.packageManager.hasSystemFeature(feature)) throw DeviceActionException("unsupported_action", "The device does not support $target.")
    }

    companion object {
        val targets = PrivilegedActionPolicy.deviceStateTargets
        private val actions = setOf(
            PrivilegedActionPolicy.ACTION_SETTINGS_GET, PrivilegedActionPolicy.ACTION_SETTINGS_PUT,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE, PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED, PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_DISPLAY, PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT, PrivilegedActionPolicy.ACTION_DEVICE_GET_SOUND,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME, PrivilegedActionPolicy.ACTION_DEVICE_SET_DND,
        )
        private fun target(args: Map<String, String>): String = requireNotNull(args["target"]?.takeIf { it in targets }) { "Unsupported device state." }
        private fun scope(target: String): String = if (target in setOf("location", "auto_rotate", "auto_brightness", "dark_mode")) "app_user" else "device"
        private fun settingBoolean(value: Int): Boolean? = when (value) { 0 -> false; 1 -> true; else -> null }
        internal fun bluetoothState(output: String): Boolean? = when (
            Regex("(?m)^\\s*state:\\s*(ON|OFF|10|12)\\s*$").find(output)?.groupValues?.get(1)
        ) { "ON", "12" -> true; "OFF", "10" -> false; else -> null }
    }
}
