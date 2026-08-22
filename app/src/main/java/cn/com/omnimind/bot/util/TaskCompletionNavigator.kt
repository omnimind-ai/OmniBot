package cn.com.omnimind.bot.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.uikit.UIKit
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

object TaskCompletionNavigator {
    private const val TAG = "[TaskCompletionNavigator]"
    private const val FLUTTER_SHARED_PREFS_NAME = "FlutterSharedPreferences"
    private const val KEY_LAST_VISIBLE_THREAD_TARGET =
        "flutter.last_visible_conversation_target"
    private const val KEY_CURRENT_CONVERSATION_ID = "flutter.current_conversation_id"

    private data class ResolvedChatTarget(
        val conversationId: Long?,
        val mode: String
    )

    fun buildChatRoute(conversationId: Long?, mode: String?): String {
        val normalizedMode = normalizeConversationMode(mode)
        return if (conversationId != null && conversationId > 0) {
            "/home/chat?conversationId=$conversationId&mode=$normalizedMode"
        } else {
            "/home/chat"
        }
    }

    fun navigateBackToChat(context: Context, conversationId: Long?, mode: String?) {
        val resolvedTarget = resolveConversationTarget(context, conversationId, mode)
        navigateToMainRoute(
            context = context,
            route = buildChatRoute(resolvedTarget.conversationId, resolvedTarget.mode),
            needClear = false
        )
    }

    fun navigateToMainRoute(context: Context, route: String, needClear: Boolean) {
        val targetRoute = route.ifBlank { "/home/chat" }
        UIKit.uiChatEvent?.closeChatBotBgInMain()

        val halfScreenApi = UIKit.halfScreenApi
        if (halfScreenApi != null) {
            try {
                halfScreenApi.onNeedOpenAppMainParam(targetRoute, needClear)
                OmniLog.d(
                    TAG,
                    "通过 halfScreenApi 请求跳转主页面 route=$targetRoute needClear=$needClear"
                )
                return
            } catch (e: Exception) {
                OmniLog.e(TAG, "halfScreenApi 跳转失败，启用兜底跳转: ${e.message}")
            }
        } else {
            OmniLog.w(TAG, "halfScreenApi unavailable, using fallback route navigation")
        }

        routeMainEngineFallback(targetRoute, needClear)
        bringMainActivityToFront(context, targetRoute, needClear)
    }

    private fun routeMainEngineFallback(route: String, needClear: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                routeMainEngineFallback(route, needClear)
            }
            return
        }
        try {
            val routerChannel = MethodChannel(
                App.getCachedMainEngine().dartExecutor.binaryMessenger,
                "ui_router_channel"
            )
            val method = if (needClear) "clearAndNavigateTo" else "resetToHomeAndPush"
            val arguments = mapOf<String, Any>(
                "route" to route,
                "options" to mapOf("noAnim" to true)
            )
            routerChannel.invokeMethod(method, arguments)
            OmniLog.d(TAG, "兜底主引擎路由调用成功: method=$method route=$route")
        } catch (e: Exception) {
            OmniLog.e(TAG, "兜底主引擎路由调用失败: ${e.message}")
        }
    }

    private fun bringMainActivityToFront(context: Context, route: String, needClear: Boolean) {
        try {
            val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra("route", route)
                putExtra("needClear", needClear)
            }
            context.applicationContext.startActivity(intent)
            OmniLog.d(TAG, "兜底拉起 MainActivity 成功: route=$route needClear=$needClear")
        } catch (e: Exception) {
            OmniLog.e(TAG, "兜底拉起 MainActivity 失败: ${e.message}")
        }
    }

    private fun resolveConversationTarget(
        context: Context,
        preferredId: Long?,
        preferredMode: String?
    ): ResolvedChatTarget {
        val normalizedPreferredMode = normalizeConversationMode(preferredMode)
        if (preferredId != null && preferredId > 0) {
            return ResolvedChatTarget(preferredId, normalizedPreferredMode)
        }
        return try {
            val flutterPrefs = context.getSharedPreferences(
                FLUTTER_SHARED_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val rawThreadTarget = flutterPrefs.getString(KEY_LAST_VISIBLE_THREAD_TARGET, null)
            if (!rawThreadTarget.isNullOrBlank()) {
                try {
                    val json = JSONObject(rawThreadTarget)
                    val conversationId = when (val rawId = json.opt("conversationId")) {
                        is Int -> rawId.toLong()
                        is Long -> rawId
                        is String -> rawId.toLongOrNull()
                        else -> null
                    }?.takeIf { it > 0 }
                    val mode = normalizeConversationMode(json.optString("mode", "agent"))
                    val isNewConversation = json.optBoolean("isNewConversation", false)
                    if (isNewConversation) {
                        OmniLog.d(TAG, "使用 Flutter 上次可见空白线程兜底回跳: mode=$mode")
                        return ResolvedChatTarget(null, mode)
                    }
                    if (conversationId != null) {
                        OmniLog.d(TAG, "使用 Flutter 上次可见线程兜底回跳: id=$conversationId mode=$mode")
                        return ResolvedChatTarget(conversationId, mode)
                    }
                } catch (e: Exception) {
                    OmniLog.e(TAG, "解析上次可见线程失败: ${e.message}")
                }
            }
            val raw = flutterPrefs.all[KEY_CURRENT_CONVERSATION_ID]
            val parsedId = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                is String -> raw.toLongOrNull()
                else -> null
            }?.takeIf { it > 0 }
            if (parsedId != null) {
                OmniLog.d(TAG, "使用 Flutter 持久化会话ID兜底回跳: $parsedId")
            }
            ResolvedChatTarget(parsedId, "agent")
        } catch (e: Exception) {
            OmniLog.e(TAG, "读取 Flutter 会话ID兜底值失败: ${e.message}")
            ResolvedChatTarget(null, normalizedPreferredMode)
        }
    }

    private fun normalizeConversationMode(mode: String?): String {
        return when (mode?.trim()?.lowercase()) {
            null, "", "normal", "codex", "acp", "coding" -> "agent"
            "chat", "chatonly", "chat-only" -> "chat_only"
            else -> mode.trim().lowercase()
        }
    }
}
