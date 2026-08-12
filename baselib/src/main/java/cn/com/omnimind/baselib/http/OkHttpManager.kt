package cn.com.omnimind.baselib.http

import android.annotation.SuppressLint
import android.util.Log
import androidx.annotation.VisibleForTesting
import cn.com.omnimind.baselib.http.interceptor.CommonParamsInterceptor
import cn.com.omnimind.baselib.http.interceptor.HeaderInterceptor
import cn.com.omnimind.baselib.service.DeviceInfoService
import cn.com.omnimind.baselib.util.APPPackageUtil
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("StaticFieldLeak")
object OkHttpManager {
    @Volatile
    private var mInstance: OkHttpClient? = null
    private const val DEFAULT_TIMEOUT: Long = 60

    val BASE_HTTP_URL: String
        get() {
            return getBaseUrl()
        }

    /**
     * 使用反射获取BASE_HTTP_URL
     */
    private fun getBaseUrl(): String {
        val baseUrl = getDefaultBaseUrl()
        if (baseUrl.isBlank()) {
            Log.w("OkHttpManager", "BASE_URL is empty; backend features are disabled in open-source mode")
        } else {
            Log.d("OkHttpManager", "Using configured base URL")
        }
        return baseUrl
    }

    private fun getDefaultBaseUrl(): String {
        try {
            // 明确指定 app 模块的 BuildConfig
            val appBuildConfig = Class.forName("cn.com.omnimind.bot.BuildConfig")
            val baseUrl = appBuildConfig.getField("BASE_URL").get(null) as String
            return baseUrl.trim().trimEnd('/')
        } catch (e: Exception) {
            return ""
        }
    }

    fun getAppVersionHeaders(): Map<String, String> {
        val appVersionInfo = DeviceInfoService.getAppVersion(BaseApplication.instance)
        return buildAppVersionHeaders(
            appVersionInfo = appVersionInfo,
            isDebug = APPPackageUtil.isAppDebug()
        )
    }

    @VisibleForTesting
    internal fun buildAppVersionHeaders(
        appVersionInfo: Map<String, Any>,
        isDebug: Boolean
    ): Map<String, String> {
        return mapOf(
            "App-Version-Name" to (appVersionInfo["versionName"]?.toString() ?: "1.0"),
            "App-Version-Code" to (appVersionInfo["versionCode"]?.toString() ?: "1"),
            "App-Platform" to (appVersionInfo["platform"]?.toString() ?: "android"),
            "App-IsDebug" to isDebug.toString()
        )
    }

    private fun getInstance(): OkHttpClient {
        return mInstance ?: synchronized(this) {
            mInstance ?: buildClient().also { mInstance = it }
        }
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor())
            .addInterceptor(CommonParamsInterceptor())
//            .addInterceptor(LogInterceptor())

        return builder.build()
    }

    /**
     * Creates a client for requests that carry prompts, files, workspace data, or model output.
     * Redirects are deliberately disabled: a destination approved by the user must not be able to
     * move sensitive content or authorization headers to a different endpoint.
     */
    fun sensitiveContentClient(
        client: OkHttpClient,
        allowInsecureLoopback: Boolean = false,
    ): OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            try {
                ContentEndpointSecurity.requireSafe(
                    rawUrl = chain.request().url.toString(),
                    allowInsecureLoopback = allowInsecureLoopback,
                )
            } catch (_: IllegalArgumentException) {
                throw IOException("Sensitive content endpoint was rejected.")
            }
            chain.proceed(chain.request())
        }
        .build()

    /** Revalidates the final immutable request immediately before a network call is created. */
    fun sensitiveContentCall(
        client: OkHttpClient,
        request: Request,
        allowInsecureLoopback: Boolean = false,
    ): Call {
        ContentEndpointSecurity.requireSafe(
            rawUrl = request.url.toString(),
            allowInsecureLoopback = allowInsecureLoopback,
        )
        return sensitiveContentClient(client, allowInsecureLoopback).newCall(request)
    }

    /** Applies the same final-request gate to SSE, whose factory otherwise owns call creation. */
    fun sensitiveContentEventSource(
        client: OkHttpClient,
        request: Request,
        listener: EventSourceListener,
        allowInsecureLoopback: Boolean = false,
    ): EventSource {
        ContentEndpointSecurity.requireSafe(
            rawUrl = request.url.toString(),
            allowInsecureLoopback = allowInsecureLoopback,
        )
        return EventSources.createFactory(
            sensitiveContentClient(client, allowInsecureLoopback)
        ).newEventSource(request, listener)
    }

    /** Applies the same final-request gate to a WebSocket handshake. */
    fun sensitiveContentWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener,
        allowInsecureLoopback: Boolean = false,
    ): WebSocket {
        ContentEndpointSecurity.requireSafe(
            rawUrl = request.url.toString(),
            allowInsecureLoopback = allowInsecureLoopback,
        )
        return sensitiveContentClient(client, allowInsecureLoopback)
            .newWebSocket(request, listener)
    }

    // 执行请求
    fun execute(request: Request): Response {
        return getInstance().newCall(request).execute()
    }

    // 异步执行请求 - 协程方式
    suspend fun enqueue(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = getInstance().newCall(request)

        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {


            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.code == 429) {
                    continuation.resumeWithException(Http429Exception("小万忙不过来了，等会儿再试试吧!"))
                    return
                }
                if (continuation.isCancelled) return
                continuation.resume(response)
            }
        })
    }

    private val doTask = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 流式执行请求 - 协程方式，基于SSE实现
    suspend fun enqueueWithStream(request: Request, event: EventSourceListener): EventSource {
        val streamClient = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
            .build()

        return EventSources.createFactory(streamClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    event.onOpen(eventSource, response)
                }

                override fun onEvent(
                    eventSource: EventSource, id: String?, type: String?, data: String
                ) {
                    event.onEvent(eventSource, id, type, data)
                }

                override fun onClosed(eventSource: EventSource) {
                    event.onClosed(eventSource)
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    // 429错误通过onFailure回调传递，由调用方检查response?.code处理
                    event.onFailure(eventSource, t, response)
                }
            })
    }

    // 创建Builder实例
    fun newBuilder(): RequestBuilder {
        return RequestBuilder()
    }

    // Builder类用于链式调用
    class RequestBuilder {
        private var url: String? = null
        private var method: String = "GET"
        private var params: MutableMap<String, String>? = null
        private var formParams: MutableMap<String, String>? = null
        private var jsonBody: String? = null
        private var requestBody: RequestBody? = null
        private var headers: MutableMap<String, String>? = null

        fun url(url: String): RequestBuilder {
            this.url = url
            return this
        }

        fun addParam(key: String, value: String): RequestBuilder {
            if (params == null) {
                params = mutableMapOf()
            }
            params!![key] = value
            return this
        }

        fun addParams(params: Map<String, String>): RequestBuilder {
            if (this.params == null) {
                this.params = mutableMapOf()
            }
            this.params!!.putAll(params)
            return this
        }

        fun addFormParam(key: String, value: String): RequestBuilder {
            if (formParams == null) {
                formParams = mutableMapOf()
            }
            formParams!![key] = value
            return this
        }

        fun addFormParams(params: Map<String, String>): RequestBuilder {
            if (this.formParams == null) {
                this.formParams = mutableMapOf()
            }
            this.formParams!!.putAll(params)
            return this
        }

        fun setJsonBody(json: String): RequestBuilder {
            this.jsonBody = json
            return this
        }

        fun setRequestBody(requestBody: RequestBody): RequestBuilder {
            this.requestBody = requestBody
            return this
        }

        fun addHeader(key: String, value: String): RequestBuilder {
            if (headers == null) {
                headers = mutableMapOf()
            }
            headers!![key] = value
            return this
        }

        fun addHeaders(headers: Map<String, String>): RequestBuilder {
            if (this.headers == null) {
                this.headers = mutableMapOf()
            }
            this.headers!!.putAll(headers)
            return this
        }

        fun get(): RequestBuilder {
            this.method = "GET"
            return this
        }

        fun post(): RequestBuilder {
            this.method = "POST"
            return this
        }

        fun put(): RequestBuilder {
            this.method = "PUT"
            return this
        }

        fun delete(): RequestBuilder {
            this.method = "DELETE"
            return this
        }

        fun patch(): RequestBuilder {
            this.method = "PATCH"
            return this
        }

        fun build(): Request {
            if (url == null) {
                throw IllegalArgumentException("URL must be set")
            }

            val requestBuilder = Request.Builder()

            // 添加头部信息
            headers?.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            // 设置URL
            requestBuilder.url(url!!)

            // 根据方法类型构建请求体
            when (method) {
                "GET" -> {
                    val httpUrlBuilder = url!!.toHttpUrlOrNull()?.newBuilder()
                        ?: throw IllegalArgumentException("Invalid URL")

                    params?.forEach { (key, value) ->
                        httpUrlBuilder.addQueryParameter(key, value)
                    }

                    requestBuilder.url(httpUrlBuilder.build()).get()
                }

                "POST" -> {
                    when {
                        jsonBody != null -> {
                            val body =
                                jsonBody!!.toRequestBody("application/json; charset=utf-8".toMediaType())
                            requestBuilder.post(body)
                        }

                        formParams != null -> {
                            val formBodyBuilder = FormBody.Builder()
                            formParams!!.forEach { (key, value) ->
                                formBodyBuilder.add(key, value)
                            }
                            requestBuilder.post(formBodyBuilder.build())
                        }

                        requestBody != null -> {
                            requestBuilder.post(requestBody!!)
                        }

                        else -> {
                            requestBuilder.post("".toRequestBody("text/plain".toMediaType()))
                        }
                    }
                }

                "PUT" -> {
                    if (requestBody != null) {
                        requestBuilder.put(requestBody!!)
                    } else {
                        requestBuilder.put("".toRequestBody("text/plain".toMediaType()))
                    }
                }

                "DELETE" -> {
                    requestBuilder.delete()
                }

                "PATCH" -> {
                    if (requestBody != null) {
                        requestBuilder.patch(requestBody!!)
                    } else {
                        requestBuilder.patch("".toRequestBody("text/plain".toMediaType()))
                    }
                }
            }

            return requestBuilder.build()
        }

        companion object {
            fun newInstance(): RequestBuilder {
                return RequestBuilder()
            }
        }
    }

    // 文件下载回调接口
    interface DownloadCallback {
        fun onProgress(progress: Long, total: Long)
        fun onSuccess(file: File)
        fun onError(e: Exception)
    }

    // 下载文件
    fun downloadFile(url: String, destinationFile: File, callback: DownloadCallback?) {
        val request = Request.Builder()
            .url(url)
            .build()

        getInstance().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback?.onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    callback?.onError(IOException("Unexpected code $response"))
                    return
                }

                response.body?.let { responseBody ->
                    val totalBytes = responseBody.contentLength()
                    var downloadedBytes = 0L

                    try {
                        val inputStream = responseBody.byteStream()
                        val outputStream = FileOutputStream(destinationFile)

                        val buffer = ByteArray(8192) // 增加缓冲区大小
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // 回调进度（如果有提供回调）
                            if (callback != null && totalBytes > 0) {
                                callback.onProgress(downloadedBytes, totalBytes)
                            }

                            // 定期清理可能减少内存占用
                            if (downloadedBytes % (1024 * 1024) == 0L) {
                                System.gc()
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        // 下载完成回调
                        callback?.onSuccess(destinationFile)
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                } ?: run {
                    callback?.onError(IOException("Response body is null"))
                }
            }
        })
    }

    // 带进度监听的文件下载扩展函数
    suspend fun downloadFileWithProgress(
        url: String,
        destinationFile: File,
        onProgress: ((progress: Long, total: Long) -> Unit)? = null,
    ): Result<File> = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .build()

        val call = getInstance().newCall(request)

        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) return

                if (!response.isSuccessful) {
                    continuation.resumeWithException(IOException("Unexpected code $response"))
                    return
                }

                response.body?.let { responseBody ->
                    val totalBytes = responseBody.contentLength()
                    var downloadedBytes = 0L

                    try {
                        val inputStream = responseBody.byteStream()
                        val outputStream = FileOutputStream(destinationFile)

                        val buffer = ByteArray(8192) // 增加缓冲区大小
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // 回调进度（如果有提供回调）
                            if (onProgress != null && totalBytes > 0) {
                                onProgress(downloadedBytes, totalBytes)
                            }

                            // 定期清理可能减少内存占用
                            if (downloadedBytes % (1024 * 1024) == 0L) {
                                System.gc()
                            }
                        }

                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()

                        // 下载完成
                        continuation.resume(Result.success(destinationFile))
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                } ?: run {
                    continuation.resumeWithException(IOException("Response body is null"))
                }
            }
        })
    }

}

class Http429Exception(override val message: String) : Exception(message)
