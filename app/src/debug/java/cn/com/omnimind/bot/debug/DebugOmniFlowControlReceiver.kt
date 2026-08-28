package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import cn.com.omnimind.bot.omniflow.OmniFlow
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugOmniFlowControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val requestId = intent?.getStringExtra("requestId").orEmpty()
        val resultFile = resultFileName(intent?.getStringExtra("resultFile"), requestId)
        scope.launch {
            val response = runCatching {
                require(requestId.isNotBlank()) { "control_request_id_required" }
                val operation = intent?.getStringExtra("operation")?.trim().orEmpty()
                require(operation in SUPPORTED_OPERATIONS) {
                    "control_operation_invalid:$operation"
                }
                val requestJson = intent?.getStringExtra("requestBase64")
                    ?.let { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }
                    .orEmpty()
                val payload = if (requestJson.isBlank()) {
                    emptyMap()
                } else {
                    gson.fromJson<Map<String, Any?>>(requestJson, mapType).orEmpty()
                }
                val result = OmniFlow.control(
                    context = appContext,
                    method = operation,
                    payload = payload,
                )
                linkedMapOf<String, Any?>(
                    "schema_version" to SCHEMA_VERSION,
                    "request_id" to requestId,
                    "operation" to operation,
                    "success" to true,
                    "result" to result,
                )
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "schema_version" to SCHEMA_VERSION,
                    "request_id" to requestId,
                    "success" to false,
                    "error" to (error.message ?: error.javaClass.simpleName),
                )
            }
            File(appContext.filesDir, resultFile).writeText(gson.toJson(response))
            Log.i(TAG, "control_finished requestId=$requestId success=${response["success"]}")
            pendingResult.finish()
        }
    }

    private companion object {
        const val SCHEMA_VERSION = "oob.control.v1"
        const val RESULT_FILE = "debug-omniflow-control-result.json"
        private val REQUEST_RESULT_FILE = Regex("debug-omniflow-control-result-[A-Za-z0-9_-]+\\.json")
        const val TAG = "DebugOmniFlowControlReceiver"
        val SUPPORTED_OPERATIONS = setOf("observe", "act", "reset")
        val gson = GsonBuilder().disableHtmlEscaping().create()
        val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun resultFileName(value: String?, requestId: String): String =
            value?.trim()?.takeIf { REQUEST_RESULT_FILE.matches(it) }
                ?: if (requestId.isNotBlank()) {
                    "debug-omniflow-control-result-$requestId.json"
                } else {
                    RESULT_FILE
                }
    }
}
