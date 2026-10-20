package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.jackery.JackeryCesProvisioning
import cn.com.omnimind.bot.jackery.JackeryDemoReset
import cn.com.omnimind.bot.jackery.JackeryDemoResetFailure
import cn.com.omnimind.bot.jackery.JackeryDeviceStatus
import cn.com.omnimind.bot.jackery.JackeryTelemetryClient
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class JackeryCesChannel {
    private var context: Context? = null
    private var channel: MethodChannel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telemetry = JackeryTelemetryClient()

    fun onCreate(context: Context) { this.context = context.applicationContext }

    fun setChannel(engine: FlutterEngine) {
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "cn.com.omnimind.bot/jackery_ces")
        channel?.setMethodCallHandler { call, result ->
            val context = this.context ?: App.instance
            if (call.method == "getConfig") {
                try {
                    val image = File(context.filesDir, "jackery-ces/device.png")
                    result.success(mapOf(
                        "enabled" to (BuildConfig.APP_EDITION == "jackeryCes"),
                        "modelApiBase" to JackeryCesProvisioning.modelApiBase(context),
                        "modelId" to JackeryCesProvisioning.modelId(context),
                        "statusUrl" to JackeryCesProvisioning.statusUrl(context),
                        "deviceImagePath" to image.takeIf { it.isFile }?.absolutePath,
                    ))
                } catch (_: Exception) {
                    result.error("CES_CONFIG_INVALID", "The device configuration is unavailable. Please ask exhibition staff for help.", null)
                }
                return@setMethodCallHandler
            }
            if (BuildConfig.APP_EDITION != "jackeryCes") {
                result.error("CES_EDITION_REQUIRED", "This feature is only available in the CES edition.", null)
                return@setMethodCallHandler
            }
            when (call.method) {
                "getDeviceStatus" -> scope.launch {
                    val status = withContext(Dispatchers.IO) {
                        runCatching { telemetry.read(JackeryCesProvisioning.statusUrl(context)) }
                            .getOrElse { JackeryDeviceStatus(unavailableReason = "invalid_configuration") }
                    }
                    result.success(status.toPayload())
                }
                "resetDemo" -> scope.launch {
                    try {
                        JackeryDemoReset.reset(context) { flushFlutterPersistence() }
                        result.success(mapOf("success" to true))
                    } catch (error: Exception) {
                        result.error(
                            "DEMO_RESET_FAILED",
                            error.message ?: "Unable to reset the demo. Please try again.",
                            mapOf("contentChanged" to ((error as? JackeryDemoResetFailure)?.contentChanged == true)),
                        )
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private suspend fun flushFlutterPersistence() = withTimeout(15_000L) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val activeChannel = channel ?: error("The demo interface has closed. Try reset again.")
                activeChannel.invokeMethod("flushDemoPersistence", null, object : MethodChannel.Result {
                    override fun success(result: Any?) { if (continuation.isActive) continuation.resume(Unit) }
                    override fun error(code: String, message: String?, details: Any?) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message ?: code))
                    }
                    override fun notImplemented() {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The demo interface could not finish saving. Try reset again."))
                    }
                })
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        context = null
    }
}
