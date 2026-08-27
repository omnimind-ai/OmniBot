package cn.com.omnimind.bot.ui.channel

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.bot.voice.SceneVoicePlaybackManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * ACP voice scene bridge.  Keeping this separate from the general assists
 * channel makes the playback lifecycle explicit and lets Flutter subscribe to
 * state updates without polling.
 */
class VoicePlaybackChannel : EventChannel.StreamHandler {
    companion object {
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/VoicePlayback"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/VoicePlaybackEvents"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var manager: SceneVoicePlaybackManager? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    fun onCreate(context: Context) {
        if (manager == null) {
            manager = SceneVoicePlaybackManager(context.applicationContext)
        }
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        manager?.setEventEmitter { payload ->
            mainHandler.post { eventSink?.success(payload) }
        }
        methodChannel?.setMethodCallHandler { call, result -> handle(call, result) }
        eventChannel?.setStreamHandler(this)
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        val current = manager
        if (current == null) {
            result.error("VOICE_UNAVAILABLE", "语音服务尚未初始化", null)
            return
        }
        val messageId = call.argument<String>("messageId")?.trim().orEmpty()
        try {
            when (call.method) {
                "speakText" -> result.success(
                    current.speakText(
                        messageId = messageId,
                        text = call.argument<String>("text").orEmpty(),
                        enqueue = call.argument<Boolean>("enqueue") == true,
                        preferStreaming = call.argument<Boolean>("preferStreaming") != false,
                    ),
                )
                "replayText" -> result.success(
                    current.replayText(messageId, call.argument<String>("text").orEmpty()),
                )
                "pausePlayback" -> result.success(current.pause(messageId))
                "resumePlayback" -> result.success(current.resume(messageId))
                "stopPlayback" -> result.success(current.stop(messageId))
                else -> result.notImplemented()
            }
        } catch (error: Throwable) {
            result.error("VOICE_REQUEST_FAILED", error.message ?: "语音请求失败", null)
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    fun clear() {
        eventSink = null
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
        manager?.setEventEmitter(null)
        manager?.release()
        manager = null
    }
}
