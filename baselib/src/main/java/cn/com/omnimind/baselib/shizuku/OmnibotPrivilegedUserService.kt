package cn.com.omnimind.baselib.shizuku

import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.baselib.privileged.PrivilegedProcessRunner
import cn.com.omnimind.baselib.privileged.PrivilegedProvider
import cn.com.omnimind.baselib.privileged.PrivilegedCommandExecutor

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlinx.serialization.json.Json

@Keep
class OmnibotPrivilegedUserService() : IOmnibotPrivilegedUserService.Stub() {

    private val backend = when (android.os.Process.myUid()) {
        0 -> ShizukuBackend.ROOT
        2000 -> ShizukuBackend.ADB
        else -> ShizukuBackend.NONE
    }
    private val executor = PrivilegedCommandExecutor(backend, PrivilegedProvider.SHIZUKU, PrivilegedProcessRunner())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("unused")
    @Keep
    constructor(context: Context) : this() {
        Log.d(TAG, "Privileged user service created with context: $context")
    }

    override fun execute(requestJson: String?): String {
        val result = runCatching {
            val request = json.decodeFromString(PrivilegedRequest.serializer(), requestJson.orEmpty())
            executor.execute(request)
        }.getOrElse { error ->
            if (error is InterruptedException) Thread.interrupted()
            PrivilegedResult(
                requestId = "",
                action = "",
                success = false,
                code = "service_error",
                message = error.message ?: "Privileged service failed.",
                backend = backend
            )
        }
        // Binder 线程会复用，请求取消不能把中断标志带入下一次调用。
        Thread.interrupted()
        return json.encodeToString(PrivilegedResult.serializer(), result)
    }

    override fun cancel(requestId: String?) {
        requestId?.let { executor.cancel(it) }
    }

    override fun destroy() {
        Log.i(TAG, "Privileged user service destroy requested")
        runCatching { executor.shutdown() }
        System.exit(0)
    }

    private companion object {
        private const val TAG = "OmniPrivilegedSvc"
    }
}
