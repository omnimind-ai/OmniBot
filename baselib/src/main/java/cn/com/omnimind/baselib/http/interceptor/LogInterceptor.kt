package cn.com.omnimind.baselib.http.interceptor

import cn.com.omnimind.baselib.util.OmniLog
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class LogInterceptor : Interceptor {
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestBytes = runCatching { request.body?.contentLength() ?: 0L }.getOrDefault(-1L)
        OmniLog.i(
            "OkHttp",
            "Sending request: method=${request.method} url=${request.url} bodyBytes=$requestBytes"
        )

        val startTime = System.currentTimeMillis()
        val response = chain.proceed(request)
        val endTime = System.currentTimeMillis()
        OmniLog.i(
            "OkHttp",
            "Received response: url=${response.request.url} status=${response.code} " +
                "durationMs=${endTime - startTime} bodyBytes=${response.body?.contentLength() ?: -1L}"
        )
        return response
    }
}
