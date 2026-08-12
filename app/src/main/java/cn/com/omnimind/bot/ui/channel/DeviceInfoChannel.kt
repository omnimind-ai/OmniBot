package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.baselib.service.DeviceInfoService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * 设备信息通道 - 处理Flutter与Android设备信息之间的通信
 */
class DeviceInfoChannel {

    private val TAG = "DeviceInfoChannel"
    private val CHANNEL = "device_info"

    private var context: Context? = null
    private var methodChannel: MethodChannel? = null


    fun onCreate(context: Context) {
        this.context = context
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getAndroidId" -> {
                getAndroidId(result)
            }

            "getDeviceInfo" -> {
                getDeviceInfo(result)
            }

            "getIpAddress" -> {
                getIpAddress(result)
            }

            "getAppVersion" -> {
                getAppVersion(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun getAndroidId(result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        try {
            val androidId = DeviceInfoService.getAndroidId(ctx)
            result.success(androidId)
        } catch (e: Exception) {
            NativeChannelErrorPrivacy.deliver(result, TAG, "ANDROID_ID_ERROR", e)
        }
    }

    private fun getDeviceInfo(result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        try {
            val deviceInfo = DeviceInfoService.getDeviceInfo(ctx)
            result.success(deviceInfo)
        } catch (e: Exception) {
            NativeChannelErrorPrivacy.deliver(result, TAG, "DEVICE_INFO_ERROR", e)
        }
    }

    private fun getIpAddress(result: MethodChannel.Result) {
        try {
            val ipAddress = DeviceInfoService.getIpAddress()
            result.success(ipAddress)
        } catch (e: Exception) {
            NativeChannelErrorPrivacy.deliver(result, TAG, "IP_ADDRESS_ERROR", e)
        }
    }

    private fun getAppVersion(result: MethodChannel.Result) {
        val ctx = context
        if (ctx == null) {
            result.error("CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        try {
            val versionInfo = DeviceInfoService.getAppVersion(ctx)
            result.success(versionInfo)
        } catch (e: Exception) {
            NativeChannelErrorPrivacy.deliver(result, TAG, "VERSION_ERROR", e)
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

}
