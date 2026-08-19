package cn.com.omnimind.bot.webchat

import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.plugin.common.MethodChannel

object FlutterChatSyncBridge {
    private const val TAG = "[FlutterChatSyncBridge]"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var currentChannel: MethodChannel? = null

    @Volatile
    private var mainChannel: MethodChannel? = null

    fun bindCurrentChannel(channel: MethodChannel?) {
        currentChannel = channel
    }

    fun bindMainChannel(channel: MethodChannel?) {
        mainChannel = channel
    }

    fun dispatchConversationListChanged(
        reason: String,
        conversation: Map<String, Any?>? = null
    ) {
        dispatch(
            method = "onConversationListChanged",
            arguments = linkedMapOf<String, Any?>(
                "reason" to reason,
                "conversation" to conversation
            )
        )
    }

    fun dispatchConversationMessagesChanged(
        conversationId: Long,
        mode: String,
        reason: String
    ) {
        dispatch(
            method = "onConversationMessagesChanged",
            arguments = mapOf(
                "conversationId" to conversationId,
                "mode" to mode,
                "reason" to reason
            )
        )
    }

    fun dispatchBrowserSnapshotUpdated(snapshot: Map<String, Any?>) {
        dispatch(
            method = "onBrowserSessionSnapshotUpdated",
            arguments = snapshot
        )
    }

    /**
     * 把一条已经落库的外部用户消息（IM/微信/Telegram 等）直接推送给 Flutter 端，
     * 让 runtime 立刻插入到 messages 列表里。
     *
     * 为什么需要直推：
     * - onConversationMessagesChanged 在 Flutter 端走的是 StreamController 微任务，
     *   而旧 Agent callback 是同步回调，常常先到达并把 hasInFlightTask 翻为 true，
     *   导致后续 messagesChanged 走 in-memory 分支吞掉用户消息；
     * - 即便强制 DB 重载，replaceConversationSnapshot 也会清掉 agent 流状态，引发
     *   连锁问题。直推可以把用户气泡确定无误地插入 runtime.messages，
     *   不依赖事件顺序，也不会触碰其它运行时状态。
     */
    fun dispatchExternalUserMessageAppended(
        conversationId: Long,
        mode: String,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>>,
        createdAt: Long
    ) {
        dispatch(
            method = "onExternalUserMessageAppended",
            arguments = mapOf(
                "conversationId" to conversationId,
                "mode" to mode,
                "entryId" to entryId,
                "text" to text,
                "attachments" to attachments,
                "createdAt" to createdAt
            )
        )
    }

    private fun dispatch(method: String, arguments: Any?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                dispatchToBoundChannels(method, arguments)
            }
            return
        }
        dispatchToBoundChannels(method, arguments)
    }

    private fun dispatchToBoundChannels(method: String, arguments: Any?) {
        val channels = listOfNotNull(currentChannel, mainChannel).distinct()
        channels.forEach { target ->
            runCatching {
                target.invokeMethod(method, arguments)
            }.onFailure {
                OmniLog.w(TAG, "dispatch $method failed: ${it.message}")
            }
        }
    }
}
