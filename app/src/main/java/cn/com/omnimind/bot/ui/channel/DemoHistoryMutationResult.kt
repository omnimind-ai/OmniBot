package cn.com.omnimind.bot.ui.channel

import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicBoolean

/** A storage write reservation, released only by the existing method result. */
internal class DemoHistoryMutationResult(
    private val delegate: MethodChannel.Result,
    private val release: () -> Unit,
) : MethodChannel.Result {
    private val completed = AtomicBoolean(false)

    override fun success(result: Any?) = finish { delegate.success(result) }
    override fun error(code: String, message: String?, details: Any?) =
        finish { delegate.error(code, message, details) }
    override fun notImplemented() = finish { delegate.notImplemented() }

    private fun finish(reply: () -> Unit) {
        if (!completed.compareAndSet(false, true)) return
        try { reply() } finally { release() }
    }

    companion object {
        val methods = setOf(
            "createConversation", "updateConversation", "deleteConversation",
            "replaceConversationMessages", "upsertConversationUiCard",
            "compactConversationContext", "clearConversationMessages",
            "updateConversationPromptTokenThreshold", "updateConversationTitle",
            "completeConversation", "setCurrentConversationId", "setVisibleChatConversation",
        )
        val persistenceMethods = setOf(
            "updateConversation", "replaceConversationMessages", "upsertConversationUiCard", "completeConversation",
        )
    }
}
