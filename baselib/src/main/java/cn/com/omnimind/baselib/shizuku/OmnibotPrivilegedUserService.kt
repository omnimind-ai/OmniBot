package cn.com.omnimind.baselib.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlinx.serialization.json.Json

@Keep
class OmnibotPrivilegedUserService() : IOmnibotPrivilegedUserService.Stub() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("unused")
    @Keep
    constructor(context: Context) : this() {
        Log.d(TAG, "Privileged user service created")
    }

    override fun execute(requestJson: String?): String {
        val result = runCatching {
            val request = json.decodeFromString(PrivilegedRequest.serializer(), requestJson.orEmpty())
            PrivilegedCommandExecutor.execute(request)
        }.getOrElse { error ->
            PrivilegedResult(
                requestId = "",
                action = "",
                success = false,
                code = "service_error",
                message = error.message ?: "Privileged service failed.",
                backend = PrivilegedCommandExecutor.currentBackend()
            )
        }
        return json.encodeToString(PrivilegedResult.serializer(), result)
    }

    override fun destroy() {
        Log.i(TAG, "Privileged user service destroy requested")
        runCatching { PrivilegedCommandExecutor.shutdown() }
        System.exit(0)
    }

    private companion object {
        private const val TAG = "OmniPrivilegedSvc"
    }
}
