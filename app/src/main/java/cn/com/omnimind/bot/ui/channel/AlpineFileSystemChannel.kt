package cn.com.omnimind.bot.ui.channel

import android.content.Context
import cn.com.omnimind.bot.terminal.AlpineFileSystemService
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlpineFileSystemChannel {
    companion object {
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/AlpineFileSystem"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var channel: MethodChannel? = null

    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
        appContext = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = appContext
        if (context == null) {
            result.error("ALPINE_FS_CONTEXT_ERROR", "Context not initialized", null)
            return
        }
        val service = AlpineFileSystemService(context)
        scope.launch {
            runCatching {
                when (call.method) {
                    "list" -> service.list(call.requiredPath())
                    "read" -> service.read(
                        path = call.requiredPath(),
                        maxBytes = call.argument<Number>("maxBytes")?.toInt() ?: 512 * 1024
                    )
                    "write" -> service.write(
                        path = call.requiredPath(),
                        content = call.argument<String>("content").orEmpty()
                    )
                    "createDirectory" -> service.createDirectory(call.requiredPath())
                    "createFile" -> service.createFile(call.requiredPath())
                    "move" -> service.move(
                        sourcePath = call.argument<String>("sourcePath").orEmpty(),
                        targetPath = call.argument<String>("targetPath").orEmpty()
                    )
                    "delete" -> service.delete(call.requiredPath())
                    else -> throw IllegalArgumentException("Unknown Alpine filesystem method: ${call.method}")
                }
            }.onSuccess(result::success)
                .onFailure { error ->
                    result.error(
                        "ALPINE_FS_OPERATION_FAILED",
                        error.message ?: error.javaClass.simpleName,
                        null
                    )
                }
        }
    }

    private fun MethodCall.requiredPath(): String {
        return argument<String>("path").orEmpty().also {
            require(it.isNotEmpty()) { "path is required" }
        }
    }
}
