package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PrivilegedDisplaySoundControls(private val context: Context) {
    suspend fun execute(execution: DeviceActionExecution): PrivilegedResult = when (execution.request.action) {
        PrivilegedActionPolicy.ACTION_DEVICE_GET_DISPLAY -> execution.query(buildJsonObject {
            put("brightnessPercent", brightness(execution)?.let(::JsonPrimitive) ?: JsonNull)
            put("autoBrightness", booleanSetting(Settings.System.SCREEN_BRIGHTNESS_MODE))
            put("autoRotate", booleanSetting(Settings.System.ACCELEROMETER_ROTATION))
            put("screenTimeoutSeconds", timeout()?.let(::JsonPrimitive) ?: JsonNull)
        }, "app_user")
        PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS -> setBrightness(execution)
        PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT -> {
            val seconds = DeviceActionExecution.integer(execution.request.arguments, "seconds", 15..1800)
            execution.mutate(JsonPrimitive(seconds), "app_user", read = { timeout()?.let(::JsonPrimitive) }) {
                execution.run("settings", "--user", execution.userId.toString(), "put", "system", Settings.System.SCREEN_OFF_TIMEOUT, (seconds * 1000).toString())
            }
        }
        PrivilegedActionPolicy.ACTION_DEVICE_GET_SOUND -> sound(execution)
        PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME -> volume(execution)
        PrivilegedActionPolicy.ACTION_DEVICE_SET_DND -> dnd(execution)
        else -> execution.failure("unsupported_action", "Unsupported display or sound action.")
    }

    private suspend fun setBrightness(execution: DeviceActionExecution): PrivilegedResult {
        val percent = DeviceActionExecution.integer(execution.request.arguments, "percent", 0..100)
        execution.requireCommand("display", "set-brightness")
        if (brightness(execution) == null) return execution.failure("unsupported_action", "This system does not provide a readable normalized brightness value.")
        return execution.mutate(JsonPrimitive(percent), "device", read = {
            brightness(execution)?.let(::JsonPrimitive)
        }, matches = { value -> (value as? JsonPrimitive)?.doubleOrNull?.let { abs(it - percent) <= 1.0 } == true }) {
            execution.run("cmd", "display", "set-brightness", (percent / 100.0).toString())
        }
    }

    private suspend fun brightness(execution: DeviceActionExecution): Double? {
        // AOSP 的接口单位为 0..1，不把厂商私有整数范围猜成 0..255。
        val result = execution.run("cmd", "display", "get-brightness")
        return if (result.success && result.data["truncated"] != "true") {
            result.stdout.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }?.times(100)
        } else null
    }

    private fun sound(execution: DeviceActionExecution): PrivilegedResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return execution.failure("unsupported_action", "The audio service is unavailable.")
        return execution.query(buildJsonObject {
            put("ringerMode", audio.ringerMode)
            put("doNotDisturb", zenMode()?.let(::JsonPrimitive) ?: JsonNull)
            put("streams", buildJsonObject {
                streams.forEach { (name, stream) ->
                    put(name, buildJsonObject {
                        put("level", audio.getStreamVolume(stream))
                        put("minLevel", audio.getStreamMinVolume(stream))
                        put("maxLevel", audio.getStreamMaxVolume(stream))
                    })
                }
            })
        })
    }

    private suspend fun volume(execution: DeviceActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val stream = requireNotNull(streams[args["stream"]]) { "Unsupported volume stream." }
        val percent = DeviceActionExecution.integer(args, "percent", 0..100)
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return execution.failure("unsupported_action", "The audio service is unavailable.")
        val min = audio.getStreamMinVolume(stream)
        val max = audio.getStreamMaxVolume(stream)
        val level = volumeLevel(percent, min, max)
        return execution.mutate(JsonPrimitive(level), read = { JsonPrimitive(audio.getStreamVolume(stream)) }) {
            audio.setStreamVolume(stream, level, 0)
            PrivilegedResult(execution.request.requestId, execution.request.action, true, "ok", "Volume request submitted.")
        }
    }

    private suspend fun dnd(execution: DeviceActionExecution): PrivilegedResult {
        val mode = execution.request.arguments["mode"]
        val expected = requireNotNull(zenModes[mode]) { "mode must be off, priority, silent or alarms." }
        val commandMode = when (mode) { "off" -> "all"; "silent" -> "none"; else -> mode!! }
        execution.requireCommand("notification", "set_dnd")
        return execution.mutate(JsonPrimitive(expected), read = { zenMode()?.let(::JsonPrimitive) }) {
            execution.run("cmd", "notification", "set_dnd", commandMode)
        }
    }

    private fun timeout(): Long? = Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        .takeIf { it >= 0 }?.div(1000)

    private fun booleanSetting(key: String): JsonElement = when (Settings.System.getInt(context.contentResolver, key, -1)) {
        0 -> JsonPrimitive(false); 1 -> JsonPrimitive(true); else -> JsonNull
    }

    private fun zenMode(): Int? = Settings.Global.getInt(context.contentResolver, "zen_mode", -1).takeIf { it in 0..3 }

    companion object {
        private val streams = mapOf(
            "media" to AudioManager.STREAM_MUSIC, "alarm" to AudioManager.STREAM_ALARM,
            "ring" to AudioManager.STREAM_RING, "notification" to AudioManager.STREAM_NOTIFICATION,
            "voice_call" to AudioManager.STREAM_VOICE_CALL, "system" to AudioManager.STREAM_SYSTEM,
        )
        private val zenModes = mapOf("off" to 0, "priority" to 1, "silent" to 2, "alarms" to 3)
        internal fun volumeLevel(percent: Int, min: Int, max: Int): Int {
            require(percent in 0..100 && min >= 0 && max >= min)
            return (min + (max - min) * percent / 100.0).roundToInt().coerceIn(min, max)
        }
    }
}
