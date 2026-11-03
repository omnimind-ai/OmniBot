package cn.com.omnimind.bot.statistics

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.*
import cn.com.omnimind.bot.agent.ChatConversationPreferences
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.NA_AGENT_ID
import cn.com.omnimind.bot.webchat.ConversationDomainService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class UsageStatisticsDatabaseTest {
    @Test fun `Room counts full token usage and user sends with conversation ownership`() = runBlocking {
        DatabaseHelper.init(RuntimeEnvironment.getApplication())
        val start = 100L
        val week = 604_800_000L
        suspend fun insert(at: Long, completion: Int, reasoning: Int = 0, text: Int = 0,
            cached: Int = 0, prompt: Int = 0, cacheCreation: Int = 0) {
            DatabaseHelper.insertTokenUsageRecord(TokenUsageRecord(conversationId = 1, model = "p/model", createdAt = at,
                completionTokens = completion, reasoningTokens = reasoning, textTokens = text, cachedTokens = cached,
                promptTokens = prompt, cacheCreationTokens = cacheCreation))
        }
        insert(start - 1, 999)
        insert(start, 900, reasoning = 100, text = 200, cached = 400, prompt = 1000, cacheCreation = 100)
        insert(start + 1, 600) // Missing details do not discard reported output.
        insert(start + 2, -50, cached = -2) // Negative calls must not cancel positive calls.
        insert(start + 3, 0, prompt = 300) // Input-only usage must be visible.
        insert(start + 4, 0, cached = 80, cacheCreation = 20) // Cache is input, counted only once.
        insert(start + week, 70, reasoning = 30, text = 50)
        insert(start + 2 * week, 999)
        val buckets = DatabaseHelper.getTokenUsageWeeklyBuckets(start, start + 2 * week).sortedBy { it.weekIndex }
        assertEquals(listOf(TokenUsageBucket("p/model", 0, 2900, 480), TokenUsageBucket("p/model", 1, 80, 0)), buckets)

        val id = DatabaseHelper.insertConversation(Conversation(title = "Private title", contextSummary = "Private content", createdAt = 1))
        DatabaseHelper.insertConversation(Conversation(title = "Outside range", createdAt = 200))
        DatabaseHelper.upsertAgentSessionBinding(AgentSessionBinding(id, "session", "/workspace", 100, 100))
        suspend fun entry(conversation: Long, entryId: String, at: Long, type: String = "user_message", mode: String = "agent") {
            DatabaseHelper.upsertAgentConversationEntry(AgentConversationEntry(
                conversationId = conversation, conversationMode = mode, entryId = entryId,
                entryType = type, status = "success", summary = "Private text", payloadJson = "{}",
                createdAt = at, updatedAt = at,
            ))
        }
        entry(id, "first", 99)
        entry(id, "second", 100)
        entry(id, "second", 101, mode = "normal") // A legacy duplicate is one send.
        entry(id, "third", 150)
        entry(id, "reply", 151, type = "assistant_message")
        entry(id, "tool", 152, type = "tool_event")
        entry(id, "stream", 153, type = "stream_event")
        entry(id, "later", 200)
        val childId = DatabaseHelper.insertConversation(Conversation(title = "Child", mode = "subagent"))
        entry(childId, "subagent_schedule_175_fixture-user", 175, mode = "subagent")
        entry(childId, "manual-followup", 180, mode = "subagent")
        assertEquals(listOf(ConversationActivityRow(id, "normal", 100, "session"),
            ConversationActivityRow(id, "normal", 150, "session"),
            ConversationActivityRow(childId, "subagent", 180, null)),
            DatabaseHelper.getConversationActivityRows(100, 200).sortedBy { it.createdAt })

        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
        }
        val domain = ConversationDomainService(context)
        assertEquals(3, domain.listConversationActivity(100, 200).size)
        ChatConversationPreferences(context).hide(id)
        assertEquals(1, domain.listConversationActivity(100, 200).size)
        val cloudId = DatabaseHelper.insertConversation(Conversation(title = "Cloud", mode = "agent", createdAt = 150))
        AcpAgentProfileStore(context).bindConversation(cloudId, NA_AGENT_ID)
        entry(cloudId, "cloud-user", 150)
        // An unknown/logged-out cloud owner stays on disk and out of statistics.
        assertEquals(1, domain.listConversationActivity(100, 200).size)
    }
}
