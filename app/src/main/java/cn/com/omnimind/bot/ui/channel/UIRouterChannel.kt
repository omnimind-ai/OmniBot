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

    fun setInitialRouteAndNavigate(route: String, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "options" to options.toMap()
        )

        channel?.invokeMethod(
            "setInitialRouteAndNavigate",
            arguments,
            metadataOnlyResult("setInitialRouteAndNavigate"),
        )
    }

    fun go(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        channel?.invokeMethod("go", arguments, metadataOnlyResult("go"))
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

        channel?.invokeMethod(
            "clearAndNavigateTo",
            arguments,
            metadataOnlyResult("clearAndNavigateTo"),
        )
    }

    // 推送新路由（不清理栈）
    fun pushRoute(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        channel?.invokeMethod("push", arguments, metadataOnlyResult("push"))
    }

    // 重置到首页并推送新路由
    fun resetToHomeAndPush(route: String, extra: Any? = null, queryParams: Map<String, Any>? = null, options: RouteOptions = RouteOptions()) {
        val arguments = mapOf(
            "route" to route,
            "extra" to extra,
            "queryParams" to queryParams,
            "options" to options.toMap()
        )

        OmniLog.d(
            TAG,
            "resetToHomeAndPush requested hasExtra=${extra != null} queryCount=${queryParams?.size ?: 0}",
        )

        channel?.invokeMethod(
            "resetToHomeAndPush",
            arguments,
            metadataOnlyResult("resetToHomeAndPush"),
        )
    }

    // 返回上一页
    fun popRoute(result: Any? = null) {
        channel?.invokeMethod("pop", result, metadataOnlyResult("pop"))
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
                OmniLog.w(TAG, "canPop failed code=$errorCode")
            }

            override fun notImplemented() {
                OmniLog.w(TAG, "canPop not implemented")
            }
        })

        return canPopResult
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    private fun metadataOnlyResult(action: String): MethodChannel.Result {
        return object : MethodChannel.Result {
            override fun success(result: Any?) {
                OmniLog.d(TAG, "$action succeeded")
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                OmniLog.w(TAG, "$action failed code=$errorCode")
            }

            override fun notImplemented() {
                OmniLog.w(TAG, "$action not implemented")
            }
        }
    }

}
