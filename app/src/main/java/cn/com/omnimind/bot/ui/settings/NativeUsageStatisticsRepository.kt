package cn.com.omnimind.bot.ui.settings

import android.content.Context
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.LegacyFlutterPreferences
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.nativeui.settings.UsageTokenSample

/**
 * Read-only adapter for the 轨迹 page. Conversation history stays with
 * `ConversationDomainService` (the same path as the `getConversations`
 * channel, without `archiveBefore`, so nothing is archived) and token usage
 * stays with `TokenUsageRecordDao` (`getTokenUsageRecords`). No new store.
 */
internal class NativeUsageStatisticsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val conversations = ConversationDomainService(appContext)
    private val preferences = appContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    /** Creation times of all visible conversations, archived included, hidden Agent rows excluded. */
    suspend fun conversationCreatedAt(): List<Long> {
        val hiddenIds = HIDDEN_AGENT_KEYS
            .flatMap { LegacyFlutterPreferences.readStringList(preferences, "flutter.$it") }
            .mapNotNull(String::toLongOrNull).toSet()
        return conversations.listConversationPayloads(includeArchived = true).mapNotNull { row ->
            val id = (row["id"] as? Number)?.toLong()
            if (row["mode"] == "agent" && id in hiddenIds) return@mapNotNull null
            (row["createdAt"] as? Number)?.toLong()
        }
    }

    suspend fun tokenSamplesSince(since: Long): List<UsageTokenSample> =
        DatabaseHelper.getTokenUsageRecordsSince(since).map { record ->
            UsageTokenSample(
                createdAt = record.createdAt,
                model = record.model,
                completionTokens = record.completionTokens,
                reasoningTokens = record.reasoningTokens,
                textTokens = record.textTokens,
                cachedTokens = record.cachedTokens,
            )
        }

    private companion object {
        val HIDDEN_AGENT_KEYS = listOf("hidden_agent_conversation_ids", "hidden_codex_conversation_ids")
    }
}
