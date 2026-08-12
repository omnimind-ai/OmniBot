package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.update.AppUpdateManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUpdateChannel {
    private val channelName = "cn.com.omnimind.bot/app_update"
    private var context: Context? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        channel?.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method == "isSelfUpdateAvailable") {
            result.success(AppUpdateManager.isSelfUpdateAvailable())
            return
        }
        if (!AppUpdateManager.isSelfUpdateAvailable()) {
            result.error(
                "SELF_UPDATE_UNAVAILABLE",
                "APK self-update is unavailable in this distribution.",
                null
            )
            return
        }

        val safeContext = context
        if (safeContext == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }

        when (call.method) {
            "getBetaOptIn" -> {
                result.success(AppUpdateManager.isBetaOptIn(safeContext))
            }

            "getApkDownloadSource" -> {
                result.success(AppUpdateManager.getApkDownloadSource(safeContext).value)
            }

            "setBetaOptIn" -> {
                val enabled = call.argument<Boolean>("enabled") == true
                result.success(AppUpdateManager.setBetaOptIn(safeContext, enabled))
            }

            "setApkDownloadSource" -> {
                val source = call.argument<String>("source")
                result.success(AppUpdateManager.setApkDownloadSource(safeContext, source).value)
            }

            "getCachedStatus" -> {
                result.success(AppUpdateManager.getCachedStatus(safeContext).toMap())
            }

            "checkNow" -> {
                val force = call.argument<Boolean>("force") == true
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val payload = AppUpdateManager.checkNow(safeContext, force = force).toMap()
                        withContext(Dispatchers.Main) {
                            result.success(payload)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        withContext(Dispatchers.Main) {
                            NativeChannelErrorPrivacy.deliver(
                                result,
                                "AppUpdateChannel",
                                "CHECK_FAILED",
                                error,
                            )
                        }
                    }
                }
            }

            "installLatestApk" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val installResult = AppUpdateManager.installLatestApk(safeContext)
                        withContext(Dispatchers.Main) {
                            result.success(
                                mapOf(
                                    "success" to installResult.success,
                                    "status" to installResult.status,
                                    "message" to installResult.message,
                                    "filePath" to installResult.filePath
                                )
                            )
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Exception) {
                        withContext(Dispatchers.Main) {
                            NativeChannelErrorPrivacy.deliver(
                                result,
                                "AppUpdateChannel",
                                "INSTALL_FAILED",
                                error,
                            )
                        }
                    }
                }
            }

            else -> result.notImplemented()
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
