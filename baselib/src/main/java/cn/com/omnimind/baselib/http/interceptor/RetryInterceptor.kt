package cn.com.omnimind.baselib.http.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RetryInterceptor : Interceptor {
    private val maxRetries = 3
    
    override fun intercept(chain: Interceptor.Chain): Response {
        var retryCount = 0
        while (retryCount < maxRetries) {
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                retryCount++
                if (retryCount >= maxRetries) throw e
                Thread.sleep(1000L * retryCount) // Exponential backoff
            }
        }
        throw IOException("Max retries exceeded")
    }
}
