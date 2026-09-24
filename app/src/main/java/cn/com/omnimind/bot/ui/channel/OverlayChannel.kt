package cn.com.omnimind.bot.ui.channel

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.preferences.PetAppearanceRepository
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Flutter adapter for the existing overlay runtime and shared pet appearance owner. */
class OverlayChannel {

    private val TAG = "OverlayChannel"
    private val CHANNEL = "cn.com.omnimind.bot/overlay"
    private val PREFS_NAME = "OmnibotSettings"
    private val KEY_PET_OVERLAY_VISIBLE = "pet_overlay_visible"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var methodChannel: MethodChannel? = null
    private var appContext: Context? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
        DraggableBallInstance.initialize(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            handleMethodCall(call, result)
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "showMessage" -> {
                try {
                    val message = call.argument<String>("message") ?: ""
                    // 尝试显示消息，如果控件未初始化，则等待并重试
                    showMessageWithRetry(message, result, maxRetries = 5, retryDelayMs = 100L)
                } catch (e: Exception) {
                    OmniLog.e(TAG, "showMessage failed: ${e.message}", e)
                    result.error("SHOW_MESSAGE_FAILED", e.message, null)
                }
            }
            "selectPetAppearance" -> petOperation(result) {
                PetAppearanceRepository.get(requireNotNull(appContext))
                    .select(requireNotNull(call.argument<String>("id"))).toMap()
            }
            "importPetPackage" -> petOperation(result) {
                PetAppearanceRepository.get(requireNotNull(appContext))
                    .import(requireNotNull(call.argument<String>("path"))).toMap()
            }
            "listPetAppearances" -> petOperation(result) {
                PetAppearanceRepository.get(requireNotNull(appContext)).state().toMap()
            }
            "playPetAction" -> {
                val action = call.argument<String>("action")?.trim().orEmpty()
                val loop = call.argument<Boolean>("loop") ?: true
                result.success(DraggableBallInstance.playPetAction(action, loop))
            }
            "showPetOverlay" -> {
                showPetOverlay(result)
            }
            "hidePetOverlay" -> {
                hidePetOverlay(result)
            }
            "isPetOverlayShowing" -> {
                result.success(DraggableBallInstance.isShowing())
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun clear() {
        scope.cancel()
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

    private fun petOperation(result: MethodChannel.Result, action: suspend () -> Any?) {
        scope.launch {
            try {
                result.success(action())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e(TAG, "Pet appearance operation failed", error)
                result.error("PET_APPEARANCE_FAILED", error.message, null)
            }
        }
    }

    private fun showPetOverlay(result: MethodChannel.Result) {
        val context = appContext
        Handler(Looper.getMainLooper()).post {
            try {
                val shown = DraggableBallInstance.loadBall()
                context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putBoolean(KEY_PET_OVERLAY_VISIBLE, shown)
                    ?.apply()
                result.success(shown)
            } catch (e: Exception) {
                OmniLog.e(TAG, "showPetOverlay failed: ${e.message}", e)
                result.error("SHOW_PET_FAILED", e.message, null)
            }
        }
    }

    private fun hidePetOverlay(result: MethodChannel.Result) {
        val context = appContext
        Handler(Looper.getMainLooper()).post {
            try {
                DraggableBallInstance.destroy()
                context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putBoolean(KEY_PET_OVERLAY_VISIBLE, false)
                    ?.apply()
                result.success(true)
            } catch (e: Exception) {
                OmniLog.e(TAG, "hidePetOverlay failed: ${e.message}", e)
                result.error("HIDE_PET_FAILED", e.message, null)
            }
        }
    }

    /**
     * 带重试机制的消息显示
     */
    private fun showMessageWithRetry(
        message: String,
        result: MethodChannel.Result,
        maxRetries: Int,
        retryDelayMs: Long,
        currentRetry: Int = 0
    ) {
        val instance = DraggableBallInstance.getInstance()
        if (instance == null) {
            if (currentRetry < maxRetries) {
                Handler(Looper.getMainLooper()).postDelayed({
                    showMessageWithRetry(message, result, maxRetries, retryDelayMs, currentRetry + 1)
                }, retryDelayMs)
            } else {
                //这里设置了1秒钟的重试，若1秒钟控件未初始化则记录。并抛出异常。
                OmniLog.e(TAG, "DraggableBallInstance is null after $maxRetries retries, overlay may not be initialized")
                result.error("OVERLAY_NOT_INITIALIZED", "Overlay is not initialized after retries", null)
            }
            return
        }
        //在4秒内快速结束并启动时，为了下次启动时防止上次异步定时器及动画未播放完成就隐藏，调用该方法直接停止定时器及动画
        instance.collapseNotChangeState()
        // overlay 已初始化，显示消息
        Handler(Looper.getMainLooper()).post {
            try {
                DraggableBallInstance.message(message)
                result.success(true)
            } catch (e: Exception) {
                OmniLog.e(TAG, "Failed to show message: ${e.message}", e)
                result.error("SHOW_MESSAGE_FAILED", e.message, null)
            }
        }
    }
}
