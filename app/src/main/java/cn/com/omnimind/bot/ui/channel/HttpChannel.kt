package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.http.OkHttpManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class HttpChannel {

    val CHANNEL_NAME = "cn.com.omnimind.bot/network"
    private val TAG = "HttpChannel"
    private var channel: MethodChannel? = null
    private var mainJob:CoroutineScope= CoroutineScope(SupervisorJob()+Dispatchers.IO)

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL_NAME
        )
        channel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "sendRequest" -> {
                    handleSendRequest( call, result)
                }

                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    private fun handleSendRequest(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        try {
            val arguments = call.arguments as? Map<*, *>
            val url = arguments?.get("url") as? String
            val method = arguments?.get("method") as? String ?: "GET"
            val headers = arguments?.get("headers") as? Map<*, *>
            val params = arguments?.get("params") as? Map<*, *>
            val body = arguments?.get("body") as? String

            val filePaths = arguments?.get("filePaths") as? List<*>

            if (url.isNullOrEmpty()) {
                result.error("INVALID_URL", "URL is required", null)
                return
            }

            // 在IO线程中执行网络请求
            mainJob.launch(Dispatchers.IO) {
                try {
                    val request = buildRequest(
                        url,
                        method,
                        headers,
                        params,
                        body,
                        filePaths
                    )
                    val response = OkHttpManager.enqueue(request)

                    val responseBody = response.body?.string()
                    val responseHeaders = mutableMapOf<String, String>()
                    response.headers.forEach { (name, value) ->
                        responseHeaders[name] = value
                    }

                    val responseMap = mapOf(
                        "statusCode" to response.code,
                        "body" to responseBody,
                        "headers" to responseHeaders
                    )

                    withContext(Dispatchers.Main) {
                        result.success(responseMap)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        NativeChannelErrorPrivacy.deliver(result, TAG, "NETWORK_ERROR", e)
                    }
                }
            }
        } catch (e: Exception) {
            NativeChannelErrorPrivacy.deliver(result, TAG, "INVALID_PARAMETERS", e)
        }
    }

    private fun buildRequest(
        url: String,
        method: String,
        headers: Map<*, *>?,
        params: Map<*, *>?,
        body: String?,
        filePaths: List<*>?
    ): Request {
        val builder = OkHttpManager.newBuilder()
            .url(url)

        // ✅ 处理文件上传
        if (filePaths != null && filePaths.isNotEmpty() && isMultipartContent(headers)) {
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
            
            filePaths.forEach { path ->
                if (path is String) {
                    val file = File(path)
                    if (file.exists()) {
                        val requestBody = file.asRequestBody("image/*".toMediaTypeOrNull())
                        multipartBuilder.addFormDataPart(
                            "files",
                            file.name,
                            requestBody
                        )
                    }
                }
            }
            
            builder.setRequestBody(multipartBuilder.build())
            builder.post()
        } else {
            // 添加请求方法
            when (method.uppercase()) {
                "GET" -> builder.get()
                "POST" -> builder.post()
                "PUT" -> builder.put()
                "DELETE" -> builder.delete()
                "PATCH" -> builder.patch()
            }

            // 添加请求体
            if (body != null) {
                when {
                    isJsonContent(headers) -> builder.setJsonBody(body)
                    else -> builder.setRequestBody(
                        okhttp3.RequestBody.create(
                            "text/plain".toMediaTypeOrNull(),
                            body
                        )
                    )
                }
            }
        }

        // 添加头部信息
        headers?.forEach { (key, value) ->
            if (key is String && value is String) {
                builder.addHeader(key, value)
            }
        }

        // 添加查询参数
        params?.forEach { (key, value) ->
            if (key is String && value is String) {
                builder.addParam(key, value)
            }
        }

        return builder.build()
    }

    // ✅ 新增：检查是否为 multipart 请求
    private fun isMultipartContent(headers: Map<*, *>?): Boolean {
        headers?.forEach { (key, value) ->
            if (key is String && value is String && key.equals("content-type", ignoreCase = true)) {
                return value.contains("multipart/form-data")
            }
        }
        return false
    }

    private fun isJsonContent(headers: Map<*, *>?): Boolean {
        headers?.forEach { (key, value) ->
            if (key is String && value is String && key.equals("content-type", ignoreCase = true)) {
                return value.contains("application/json")
            }
        }
        return false
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
