package cn.com.omnimind.bot.ui.channel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore
import cn.com.omnimind.baselib.permission.PermissionRequest
import cn.com.omnimind.bot.voice.SpeechInputController
import cn.com.omnimind.bot.voice.SpeechProviderException
import cn.com.omnimind.bot.voice.SpeechProviders
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class SpeechInputChannel(private val activity: Activity) {
    private val permission = Manifest.permission.RECORD_AUDIO
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permissionPrefs by lazy {
        activity.getSharedPreferences("speech_input_permission", Activity.MODE_PRIVATE)
    }
    private var channel: MethodChannel? = null
    private val input = SpeechInputController(scope, resolveProvider = {
        val providerId = SceneVoiceConfigStore.getConfig().speechRecognitionProviderId.orEmpty()
        SpeechProviders.find(providerId)?.recognition
            ?: throw SpeechProviderException("configuration", "请先配置语音输入")
    })
    private var requestingPermission = false
    private var foreground = false

    fun bind(engine: FlutterEngine) {
        channel?.setMethodCallHandler(null)
        channel = MethodChannel(engine.dartExecutor.binaryMessenger, "cn.com.omnimind.bot/SpeechInput")
        channel?.setMethodCallHandler { call, result ->
            val id = call.argument<String>("id").orEmpty()
            when (call.method) {
                "permissionStatus" -> result.success(permissionStatus())
                "requestPermission" -> requestPermission(result)
                "openPermissionSettings" -> {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
                    )
                    result.success(null)
                }
                "start" -> start(id, result)
                "finish" -> result.success(input.finish(id))
                "cancel" -> {
                    input.cancel(id)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun onResume() {
        foreground = true
    }

    fun onPause() {
        foreground = false
        input.cancel()
    }

    fun clear() {
        onPause()
        scope.cancel()
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun permissionStatus(): String = when {
        PermissionRequest.isPermissionGranted(activity, permission) -> "granted"
        activity.shouldShowRequestPermissionRationale(permission) -> "rationale"
        permissionPrefs.getBoolean("denied", false) -> "settings"
        else -> "request"
    }

    private fun requestPermission(result: MethodChannel.Result) {
        val status = permissionStatus()
        if (!foreground || requestingPermission || status == "granted" || status == "settings") {
            result.success(status)
            return
        }
        requestingPermission = true
        PermissionRequest.requestPermissions(activity, arrayOf(permission)) { permissions ->
            requestingPermission = false
            permissions[permission]?.let { granted ->
                permissionPrefs.edit().putBoolean("denied", !granted).apply()
            }
            result.success(permissionStatus())
        }
    }

    private fun start(id: String, result: MethodChannel.Result) {
        if (!foreground || id.isBlank()) {
            result.success(null)
            return
        }
        if (permissionStatus() != "granted") {
            result.error("permission", "需要麦克风权限才能录音", null)
            return
        }
        scope.launch {
            try {
                result.success(input.start(id).await())
            } catch (error: CancellationException) {
                result.success(null)
                throw error
            } catch (error: SpeechProviderException) {
                result.error(error.code, error.message, null)
            } catch (_: Exception) {
                result.error("speech_input_failed", "语音转写失败，请重试", null)
            }
        }
    }
}
