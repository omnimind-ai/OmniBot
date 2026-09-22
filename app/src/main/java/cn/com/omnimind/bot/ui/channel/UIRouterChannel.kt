package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

data class RouteOptions(
    val noAnim: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf("noAnim" to noAnim)
}

/**
 * 路由
 */
class UIRouterChannel {
    var TAG = "[UIRouterChannel]"
    private val EVENT_CHANNEL = "ui_router_channel"
    private var channel: MethodChannel? = null

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
    }

    /** Completes when the compatibility page is popped; this is navigation only, never an ACP event. */
    fun openLegacyPage(route: String, onClosed: () -> Unit) {
        channel?.invokeMethod("openLegacyPage", mapOf("route" to route), object : MethodChannel.Result {
            override fun success(result: Any?) = onClosed()
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                OmniLog.e(TAG, "Unable to open native compatibility destination: $errorCode")
                onClosed()
            }
            override fun notImplemented() = onClosed()
        })
    }

    fun setInitialRouteAndNavigate(route: String, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "options" to options.toMap()
        )

        channel?.invokeMethod("setInitialRouteAndNavigate", arguments, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("$TAG setInitialRouteAndNavigate successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("$TAG setInitialRouteAndNavigate error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("$TAG setInitialRouteAndNavigate method not implemented")
            }
        })
    }

    fun go(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        channel?.invokeMethod("go", arguments, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("$TAG go successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("$TAG go error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("$TAG go method not implemented")
            }
        })
    }

    // 清理路由栈并跳转到指定路由
    fun clearAndNavigateTo(
        route: String,
        extra: Any? = null,
        queryParams: Map<String, Any>? = null,
        options: RouteOptions = RouteOptions()
    ) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        channel?.invokeMethod("clearAndNavigateTo", arguments, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("Navigation successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("Navigation error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("Method not implemented")
            }
        })
    }

    // 推送新路由（不清理栈）
    fun pushRoute(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        channel?.invokeMethod("push", arguments, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("Push successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("Push error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("Method not implemented")
            }
        })
    }

    // 重置到首页并推送新路由
    fun resetToHomeAndPush(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        OmniLog.d(TAG, "resetToHomeAndPush: $arguments")

        channel?.invokeMethod("resetToHomeAndPush", arguments, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("ResetToHomeAndPush successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("ResetToHomeAndPush error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("Method not implemented")
            }
        })
    }

    // 返回上一页
    fun popRoute(result: Any? = null) {
        channel?.invokeMethod("pop", result, object : MethodChannel.Result {
            override fun success(result: Any?) {
                println("Pop successful: $result")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("Pop error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("Method not implemented")
            }
        })
    }

    // 检查是否可以返回
    fun canPop(): Boolean {
        var canPopResult = false

        channel?.invokeMethod("canPop", null, object : MethodChannel.Result {
            override fun success(result: Any?) {
                if (result is Map<*, *>) {
                    canPopResult = result["canPop"] as? Boolean ?: false
                }
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                println("CanPop error: $errorCode - $errorMessage")
            }

            override fun notImplemented() {
                println("Method not implemented")
            }
        })

        return canPopResult
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

}
