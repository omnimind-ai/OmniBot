package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.bot.agent.runtime.AgentHandoffContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeContextQueryTest {
    private val installedApps = linkedMapOf(
        "美团" to "com.sankuai.meituan",
        "饿了么" to "me.ele",
        "淘宝" to "com.taobao.taobao",
        "Google Maps" to "com.google.android.apps.maps",
        "Google Play 商店" to "com.android.vending",
        "设置" to "com.android.settings"
    )

    @Test
    fun `matches multiple app names from one query in query order`() {
        val result = AgentRuntimeContextQuery.filterApps(
            apps = installedApps,
            query = "美团 饿了么 淘宝",
            limit = 20
        )

        assertEquals(listOf("美团", "饿了么", "淘宝"), result.map { it.appName })
    }

    @Test
    fun `matches multiple app names separated by Chinese punctuation`() {
        val result = AgentRuntimeContextQuery.filterApps(
            apps = installedApps,
            query = "淘宝、美团，饿了么",
            limit = 20
        )

        assertEquals(listOf("淘宝", "美团", "饿了么"), result.map { it.appName })
    }

    @Test
    fun `applies limit after combining and deduplicating matches`() {
        val result = AgentRuntimeContextQuery.filterApps(
            apps = installedApps,
            query = "美团 com.sankuai.meituan 淘宝",
            limit = 2
        )

        assertEquals(listOf("美团", "淘宝"), result.map { it.appName })
    }

    @Test
    fun `preserves single query partial matching`() {
        val result = AgentRuntimeContextQuery.filterApps(
            apps = installedApps,
            query = "sankuai",
            limit = 20
        )

        assertEquals(listOf("美团"), result.map { it.appName })
    }

    @Test
    fun `preserves an app name that contains spaces as one query`() {
        val result = AgentRuntimeContextQuery.filterApps(
            apps = installedApps,
            query = "Google Maps",
            limit = 20
        )

        assertEquals(listOf("Google Maps"), result.map { it.appName })
    }

    @Test
    fun `formats persisted conversation context for ACP handoff`() {
        val handoff = AgentHandoffContext.format(
            conversationId = 42L,
            messages = listOf(
                ChatCompletionMessage("user", JsonPrimitive("先检查配置")),
                ChatCompletionMessage("assistant", JsonPrimitive("配置已检查"))
            )
        )

        assertTrue(handoff!!.contains("Conversation ID: 42"))
        assertTrue(handoff.contains("user: 先检查配置"))
        assertTrue(handoff.contains("assistant: 配置已检查"))
    }

    @Test
    fun `removes the current persisted user message from ACP handoff`() {
        val handoff = AgentHandoffContext.format(
            conversationId = 42L,
            messages = listOf(
                ChatCompletionMessage("user", JsonPrimitive("之前的问题")),
                ChatCompletionMessage("assistant", JsonPrimitive("之前的回答")),
                ChatCompletionMessage("user", JsonPrimitive("当前问题"))
            ),
            currentPrompt = "当前问题"
        )

        assertTrue(handoff!!.contains("user: 之前的问题"))
        assertFalse(handoff.contains("user: 当前问题"))
    }

    @Test
    fun `handoff keeps complete persisted context beyond the former character cap`() {
        val oldestFact = "oldest-fact-" + "a".repeat(100_000)
        val newestFact = "newest-fact-" + "z".repeat(10_000)

        val handoff = AgentHandoffContext.format(
            conversationId = 42L,
            messages = listOf(
                ChatCompletionMessage("user", JsonPrimitive(oldestFact)),
                ChatCompletionMessage("assistant", JsonPrimitive(newestFact))
            )
        )

        assertTrue(handoff!!.contains(oldestFact))
        assertTrue(handoff.contains(newestFact))
        assertFalse(handoff.contains("Older context was omitted"))
    }

}
