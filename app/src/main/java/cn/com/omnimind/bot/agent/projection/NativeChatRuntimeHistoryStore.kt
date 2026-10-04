package cn.com.omnimind.bot.agent.projection

import android.content.Context
import android.util.Log
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.ConversationSummaryGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production history store: the existing Room owner
 * (`ConversationDomainService`), called directly instead of through the
 * Flutter `AssistCoreEvent` channel the Dart owner used.
 */
class NativeChatRuntimeHistoryStore(context: Context) : ChatRuntimeHistoryStore() {
    private val appContext = context.applicationContext
    private val conversations by lazy { ConversationDomainService(appContext) }
    private val flutterPreferences by lazy {
        appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    }

    override suspend fun replaceNativeConversationMessages(
        conversationId: Int,
        conversationMode: String,
        rows: List<Map<String, Any?>>,
        allowHistoryRemoval: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        if (conversationId <= 0) return@withContext false
        runCatching {
            conversations.replaceConversationMessages(
                conversationId = conversationId.toLong(),
                conversationMode = conversationMode,
                messages = rows,
                allowHistoryRemoval = allowHistoryRemoval,
            )
        }.onFailure { Log.w(TAG, "保存对话历史失败: ${it.message}") }.isSuccess
    }

    /**
     * Dart `_legacyConversationMessageKeys`: pre-native snapshots under the
     * Flutter preference namespace (`flutter.` prefix on Android).
     */
    override suspend fun clearLegacyConversationMessages(conversationId: Int, conversationMode: String) {
        val keys = linkedSetOf("$LEGACY_KEY_PREFIX${conversationMode}_$conversationId")
        if (conversationMode == ConversationModes.NORMAL || conversationMode == ConversationModes.AGENT) {
            keys += "$LEGACY_KEY_PREFIX${ConversationModes.NORMAL}_$conversationId"
            keys += "$LEGACY_KEY_PREFIX$conversationId"
            keys += "${LEGACY_KEY_PREFIX}agent_$conversationId"
            keys += "${LEGACY_KEY_PREFIX}codex_$conversationId"
        }
        val present = keys.map { "flutter.$it" }.filter { flutterPreferences.contains(it) }
        if (present.isEmpty()) return
        flutterPreferences.edit().apply { present.forEach { remove(it) } }.apply()
    }

    override suspend fun readConversation(conversationId: Int): Map<String, Any?>? = withContext(Dispatchers.IO) {
        runCatching { conversations.getConversationPayload(conversationId.toLong()) }.getOrNull()
    }

    override suspend fun writeConversation(conversation: Map<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        conversations.updateConversationFromPayload(conversation)
        true
    }

    override suspend fun completeConversation(conversationId: Int, conversationMode: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { conversations.completeConversation(conversationId.toLong()) }
                .onFailure { Log.w(TAG, "完成对话失败: ${it.message}") }
                .isSuccess
        }

    override suspend fun generateConversationSummary(conversationHistory: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { ConversationSummaryGenerator.generate(conversationHistory) }
                .onFailure { Log.w(TAG, "生成对话摘要失败: ${it.message}") }
                .getOrNull()
        }

    override suspend fun upsertConversationUiCard(
        conversationId: Int,
        conversationMode: String,
        entryId: String,
        cardData: Map<String, Any?>,
        createdAtMillis: Long,
    ) {
        val normalizedEntryId = entryId.trim()
        if (normalizedEntryId.isEmpty() || conversationId <= 0) return
        withContext(Dispatchers.IO) {
            runCatching {
                conversations.upsertConversationUiCard(
                    conversationId = conversationId.toLong(),
                    conversationMode = conversationMode,
                    entryId = normalizedEntryId,
                    cardData = cardData,
                    createdAt = createdAtMillis,
                )
            }.onFailure { Log.w(TAG, "保存 UI 卡片异常: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "NativeChatRuntimeHistory"
        const val LEGACY_KEY_PREFIX = "conversation_messages_"
    }
}
