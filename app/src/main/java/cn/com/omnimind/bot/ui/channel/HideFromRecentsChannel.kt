package cn.com.omnimind.bot.ui.channel

import android.app.ActivityManager
import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 后台隐藏功能通道
 * 用于处理ActivityManager.setExcludeFromRecents()调用
 */
class HideFromRecentsChannel {
    companion object {
        private const val CHANNEL_NAME = "hide_from_recents"
        private const val TAG = "HideFromRecentsChannel"
    }

    private var methodChannel: MethodChannel? = null
    private var context: Context? = null

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "setExcludeFromRecents" -> {
                    try {
                        val exclude = call.argument<Boolean>("exclude") ?: false
                        setExcludeFromRecents(exclude)
                        result.success(true)
                    } catch (e: Exception) {
                        NativeChannelErrorPrivacy.deliver(
                            result,
                            TAG,
                            "HIDE_FROM_RECENTS_FAILED",
                            e,
                        )
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    fun onCreate(context: Context) {
        this.context = context
    }

    /**
     * 设置应用是否从最近任务中排除
     * @param exclude true-排除，false-不排除
     */
    private fun setExcludeFromRecents(exclude: Boolean) {
        try {
            val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                // 获取所有当前应用的任务
                val appTasks = activityManager.appTasks
                for (appTask in appTasks) {
                    appTask.setExcludeFromRecents(exclude)
                }

                OmniLog.d(TAG, "设置应用从最近任务中排除: $exclude")
            } else {
                OmniLog.e(TAG, "无法获取ActivityManager")
            }
        } catch (e: Exception) {
            throw e
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        context = null
    }
}
