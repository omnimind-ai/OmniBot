package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.agent.projection.ChatRuntimeHost
import cn.com.omnimind.bot.agent.projection.asInt
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter adapter link of the native chat runtime owner
 * ([ChatRuntimeHost]). The Flutter side keeps only a read-only mirror built
 * from the snapshots on [EVENT_CHANNEL] and sends every write as a command
 * on [METHOD_CHANNEL]; it holds no reducer or runtime state of its own.
 */
class ChatRuntimeChannel {
    companion object {
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/ChatRuntime"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/ChatRuntimeEvents"
    }

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null

    fun onCreate(context: Context) {
        ChatRuntimeHost.initialize(context)
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        val messenger = flutterEngine.dartExecutor.binaryMessenger
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL).also {
            it.setMethodCallHandler(::handleMethodCall)
        }
        eventChannel = EventChannel(messenger, EVENT_CHANNEL).also {
            it.setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    ChatRuntimeHost.attachSink(events?.let { sink -> { payload -> sink.success(payload) } })
                }

                override fun onCancel(arguments: Any?) {
                    ChatRuntimeHost.attachSink(null)
                }
            })
        }
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val args = (call.arguments as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        when (call.method) {
            "flushAllPendingPersistence" -> ChatRuntimeHost.flushAllPendingPersistence { outcome ->
                outcome.fold({ result.success(null) }, { result.error("CHAT_RUNTIME_PERSIST_FAILED", it.message, null) })
            }
            "flushPendingPersistence" -> ChatRuntimeHost.flushPendingPersistence(
                asInt(args["conversationId"]) ?: 0,
                args["mode"]?.toString().orEmpty(),
            ) { outcome ->
                outcome.fold({ result.success(null) }, { result.error("CHAT_RUNTIME_PERSIST_FAILED", it.message, null) })
            }
            "launchTurn" -> ChatRuntimeHost.handleLaunchTurn(args) { outcome ->
                outcome.fold(
                    { result.success(it) },
                    { result.error("CHAT_TURN_LAUNCH_FAILED", it.message, call.method) },
                )
            }
            else -> ChatRuntimeHost.handleAsyncCommand(call.method, args) { outcome ->
                outcome.fold(
                    { result.success(it) },
                    {
                        if (it is UnsupportedOperationException) {
                            result.notImplemented()
                        } else {
                            result.error("CHAT_RUNTIME_COMMAND_FAILED", it.message, call.method)
                        }
                    },
                )
            }
        }
    }

    fun clear() {
        ChatRuntimeHost.attachSink(null)
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
    }
}
