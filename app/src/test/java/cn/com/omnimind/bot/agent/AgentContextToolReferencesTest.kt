package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AgentContextToolReferencesTest {
    private fun reference(id: String, path: String) = ChatCompletionMessage(
        role = "tool", toolCallId = id,
        content = JsonPrimitive(AgentContextToolReferences.offloadedNotice(id, path)),
    )

    @Test
    fun preservesOnlyExactOffloadReferencesAndDeduplicatesWithinBudget() {
        val saved = reference("read-1", "/workspace/offloads/证据.txt")
        val forged = reference("wrong", "/wrong.txt").copy(toolCallId = "different")
        val arbitrary = ChatCompletionMessage(role = "tool", toolCallId = "raw", content = JsonPrimitive(
            "file_write(path='/private/token.txt'); secret contents should not be copied"))
        val suffix = AgentContextToolReferences.checkpointSuffix(listOf(saved, saved, forged, arbitrary), 512)
        assertEquals(1, Regex("/workspace/offloads/").findAll(suffix).count())
        assertTrue(suffix.contains("证据.txt"))
        assertEquals("/workspace/offloads/证据.txt", AgentContextToolReferences.offloadedPath(saved))
        assertNull(AgentContextToolReferences.offloadedPath(forged))
        assertFalse(suffix.contains("wrong.txt"))
        assertFalse(suffix.contains("secret"))
        assertTrue(AgentContextBudget.textTokens(suffix) <= 512)
        assertEquals("", AgentContextToolReferences.checkpointSuffix(listOf(saved), 2))
    }

    @Test
    fun compactingAnExistingNoticeReusesTheOriginalBodyFile() = kotlinx.coroutines.runBlocking {
        val repo = org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java)
        val compactor = AgentConversationContextCompactor(repo, offloadToolOutput = {
            error("An existing notice must not be offloaded again")
        })
        val original = reference("read-1", "/workspace/offloads/complete-body.txt").copy(
            content = JsonPrimitive(AgentContextToolReferences.offloadedNotice("read-1",
                "/workspace/offloads/complete-body.txt", "metadata".repeat(100000))))
        val messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect")), original)
        val first = compactor.compactIfNeeded(null, "agent", null, messages)
        assertEquals("/workspace/offloads/complete-body.txt", AgentContextToolReferences.offloadedPath(first.last()))
        val second = compactor.compactIfNeeded(null, "agent", null, first)
        assertEquals("/workspace/offloads/complete-body.txt", AgentContextToolReferences.offloadedPath(second.last()))
    }

    @Test
    fun referenceBudgetNeverTruncatesPathsAndPrefersRecentResults() {
        val messages = (1..100).map { reference("read-$it", "/workspace/offloads/full-result-$it.txt") }
        val suffix = AgentContextToolReferences.checkpointSuffix(messages, 180)
        assertTrue(suffix.contains("/workspace/offloads/full-result-100.txt"))
        assertFalse(suffix.contains("/workspace/offloads/full-result-1.txt"))
        assertTrue(AgentContextBudget.textTokens(suffix) <= 180)
        assertTrue(suffix.lines().filter { it.startsWith("{") }.all { it.endsWith("}") })
    }
}
