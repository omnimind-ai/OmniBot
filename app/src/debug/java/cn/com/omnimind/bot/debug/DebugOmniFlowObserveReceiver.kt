package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.com.omnimind.bot.omniflow.OmniFlow
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugOmniFlowObserveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val requestId = intent?.getStringExtra("requestId")?.trim().orEmpty()
        val resultFile = resultFileName(intent?.getStringExtra("resultFile"), requestId)
        scope.launch {
            try {
                val screenshot = intent?.getBooleanExtra("includeScreenshot", false) == true
                val waitToStabilize = intent?.getBooleanExtra("waitToStabilize", false) == true
                val state = OmniFlow.observe(
                    context = appContext,
                    captureScreenshot = screenshot,
                    waitToStabilize = waitToStabilize,
                )
                val payload = linkedMapOf<String, Any?>(
                    "schema_version" to "oob.observe.v1",
                    "success" to true,
                    "state" to state,
                )
                if (requestId.isNotBlank()) payload["request_id"] = requestId
                File(appContext.filesDir, resultFile).writeText(gson.toJson(payload))
                Log.i(TAG, "observe_finished success=true screenshot=$screenshot")
            } catch (error: Throwable) {
                val payload = linkedMapOf<String, Any?>(
                    "schema_version" to "oob.observe.v1",
                    "success" to false,
                    "error" to (error.message ?: error.javaClass.simpleName),
                )
                if (requestId.isNotBlank()) payload["request_id"] = requestId
                File(appContext.filesDir, resultFile).writeText(gson.toJson(payload))
                Log.e(TAG, "observe_finished success=false", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val RESULT_FILE = "debug-omniflow-observe-result.json"
        private val REQUEST_RESULT_FILE = Regex("debug-omniflow-observe-result-[A-Za-z0-9_-]+\\.json")
        const val TAG = "DebugOmniFlowObserveReceiver"
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun resultFileName(value: String?, requestId: String): String =
            value?.trim()?.takeIf { REQUEST_RESULT_FILE.matches(it) }
                ?: if (requestId.isNotBlank()) {
                    "debug-omniflow-observe-result-$requestId.json"
                } else {
                    RESULT_FILE
                }
    }
}
