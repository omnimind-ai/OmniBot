package cn.com.omnimind.bot.ui.channel

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Overlay通道 - 处理Flutter与Android Overlay之间的通信
 * !!暂不使用!!
 */
class OverlayChannel {

    private val TAG = "OverlayChannel"
    private val CHANNEL = "cn.com.omnimind.bot/overlay"
    private val PREFS_NAME = "OmnibotSettings"
    private val KEY_PET_OVERLAY_IMAGE_PATH = "pet_overlay_image_path"
    private val KEY_PET_OVERLAY_SELECTED_ID = "pet_overlay_selected_id"
    private val KEY_PET_OVERLAY_VISIBLE = "pet_overlay_visible"

    private var methodChannel: MethodChannel? = null
    private var appContext: Context? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
        DraggableBallInstance.initialize(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
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
                    NativeChannelErrorPrivacy.deliver(result, TAG, "SHOW_MESSAGE_FAILED", e)
                }
            }
            "setPetOverlayImagePath" -> {
                try {
                    val path = call.argument<String>("path")?.trim().orEmpty()
                    val selectedId = call.argument<String>("selectedId")?.trim().orEmpty()
                    setPetOverlayImagePath(path, selectedId)
                    result.success(true)
                } catch (e: Exception) {
                    NativeChannelErrorPrivacy.deliver(result, TAG, "SET_PET_IMAGE_FAILED", e)
                }
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
            "getPetOverlayState" -> {
                result.success(getPetOverlayState())
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }

    private fun setPetOverlayImagePath(path: String, selectedId: String) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PET_OVERLAY_IMAGE_PATH, path)
            .putString(KEY_PET_OVERLAY_SELECTED_ID, selectedId)
            .apply()
        DraggableBallInstance.refreshPetAppearance()
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
                NativeChannelErrorPrivacy.deliver(result, TAG, "SHOW_PET_FAILED", e)
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
                NativeChannelErrorPrivacy.deliver(result, TAG, "HIDE_PET_FAILED", e)
            }
        }
    }

    private fun getPetOverlayState(): Map<String, Any?> {
        val context = appContext ?: return mapOf(
            "showing" to DraggableBallInstance.isShowing(),
            "selectedPath" to "",
            "selectedId" to "builtin:xiaowan"
        )
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val workspaceManager = AgentWorkspaceManager(context)
        workspaceManager.ensureRuntimeDirectories()
        return mapOf(
            "showing" to DraggableBallInstance.isShowing(),
            "selectedPath" to (prefs.getString(KEY_PET_OVERLAY_IMAGE_PATH, "") ?: ""),
            "selectedId" to (prefs.getString(KEY_PET_OVERLAY_SELECTED_ID, "builtin:xiaowan") ?: "builtin:xiaowan"),
            "visiblePreference" to prefs.getBoolean(KEY_PET_OVERLAY_VISIBLE, false),
            "workspaceRootPath" to AgentWorkspaceManager.androidRootPath(context),
            "shellWorkspaceRootPath" to AgentWorkspaceManager.SHELL_ROOT_PATH,
            "petsDirectoryPath" to workspaceManager.petsRoot().absolutePath
        )
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
                OmniLog.e(TAG, "channel_failure code=OVERLAY_NOT_INITIALIZED retryCount=$maxRetries")
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
                NativeChannelErrorPrivacy.deliver(result, TAG, "SHOW_MESSAGE_FAILED", e)
            }
        }
    }
}
