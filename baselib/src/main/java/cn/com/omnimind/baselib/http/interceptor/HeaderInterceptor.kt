package cn.com.omnimind.baselib.http.interceptor

import androidx.annotation.VisibleForTesting
import cn.com.omnimind.baselib.http.OkHttpManager
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale

class HeaderInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = sanitizeRequest(
            originalRequest = chain.request(),
            appVersionHeaders = OkHttpManager.getAppVersionHeaders()
        )
        return chain.proceed(request)
    }

    companion object {
        /**
         * Removes device metadata headers added by older app builds before any
         * request reaches an official, BYOK, or user-configured endpoint.
         */
        @VisibleForTesting
        internal fun sanitizeRequest(
            originalRequest: Request,
            appVersionHeaders: Map<String, String>
        ): Request {
            val requestBuilder = originalRequest.newBuilder()
            originalRequest.headers.names()
                .filter(::isLegacyDeviceMetadataHeader)
                .forEach(requestBuilder::removeHeader)

            requestBuilder.header("Content-Type", "application/json")
            appVersionHeaders.forEach { (key, value) ->
                if (!isLegacyDeviceMetadataHeader(key)) {
                    requestBuilder.header(key, value)
                }
            }
            return requestBuilder.build()
        }

        @VisibleForTesting
        internal fun isLegacyDeviceMetadataHeader(name: String): Boolean {
            val normalized = name.trim().lowercase(Locale.ROOT)
            return normalized == "app-other-info" || normalized.startsWith("app-device-")
        }
    }
}
