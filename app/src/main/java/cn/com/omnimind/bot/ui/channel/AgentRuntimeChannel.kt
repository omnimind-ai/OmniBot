package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.agent.AgentRuntimeErrorSupport
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgentRuntimeChannel {
    companion object {
        private const val METHOD_CHANNEL = "cn.com.omnimind.bot/AgentRuntime"
        private const val EVENT_CHANNEL = "cn.com.omnimind.bot/AgentRuntimeEvents"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var context: Context? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    fun onCreate(context: Context) {
        this.context = context.applicationContext
        if (eventSink != null) {
            AgentRuntimeManager.getInstance(context.applicationContext).setEventListener { payload ->
                eventSink?.success(payload)
            }
        }
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                val safeContext = context ?: return
                AgentRuntimeManager.getInstance(safeContext).setEventListener { payload ->
                    eventSink?.success(payload)
                }
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                context?.let {
                    AgentRuntimeManager.getInstance(it).setEventListener(null)
                }
            }
        })
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val safeContext = context
        if (safeContext == null) {
            result.error("AGENT_RUNTIME_CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        val arguments = (call.arguments as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()

        scope.launch {
            runCatching {
                AgentRuntimeManager
                    .getInstance(safeContext)
                    .handleMethod(call.method, arguments)
            }.onSuccess { payload ->
                result.success(payload)
            }.onFailure { error ->
                val detail = AgentRuntimeErrorSupport.safeDiagnosticMessage(error)
                val failureKind = AgentRuntimeErrorSupport.failureKind(error)
                result.error(
                    "AGENT_RUNTIME_CALL_FAILED",
                    AgentRuntimeErrorSupport.userFacingMessage(error)
                        ?: "${call.method}: ${detail.ifBlank { error.javaClass.simpleName }}",
                    buildMap {
                        put("method", call.method)
                        failureKind?.let { put("failureKind", it) }
                    }
                )
            }
        }
    }

    fun clear() {
        context?.let {
            AgentRuntimeManager.getInstance(it).setEventListener(null)
        }
        eventSink = null
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
    }
}
