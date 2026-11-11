package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ConversationTitleGeneratorTest {
    private fun entry(session: String = "s", turn: String = "t", final: Boolean = true, error: Boolean = false, text: String = "Visible answer") =
        AgentConversationEntry(
            conversationId = 1, conversationMode = "agent", entryId = "t-m-agent-message",
            entryType = "assistant_message", status = "success", summary = "not the title input",
            payloadJson = Gson().toJson(mapOf(
                "streamMeta" to mapOf("sessionId" to session, "turnId" to turn, "isFinal" to final),
                "isError" to error, "content" to mapOf("text" to text),
                "reasoning_content" to "Private thought", "tool" to "Private tool output",
            )),
        )

    @Test fun `reads only committed visible answer for exact session and turn`() {
        assertEquals("Visible answer", committedTitleAnswer(listOf(entry()), "s", "t"))
        for (candidate in listOf(entry(session = "other"), entry(turn = "other"), entry(final = false), entry(error = true), entry(text = ""))) {
            assertNull(committedTitleAnswer(listOf(candidate), "s", "t"))
        }
        assertEquals(8000, committedTitleAnswer(listOf(entry(text = "a".repeat(20000))), "s", "t")!!.length)
    }

    @Test fun `title normalization rejects empty and multiline responses and preserves unicode`() {
        assertNull(normalizeConversationTitle(" \n "))
        assertNull(normalizeConversationTitle("Title\nExplanation"))
        assertEquals("出行路线规划", normalizeConversationTitle("“出行路线规划”"))
        val title = normalizeConversationTitle("🌊".repeat(50))!!
        assertEquals(40, title.codePointCount(0, title.length))
    }

    @Test fun `answer selection uses the last ACP message identity across existing projections`() {
        assertEquals(listOf("t-m-agent-message", "m-agent-message"), titleAnswerEntryIds("t", "m"))
        assertEquals(listOf("t-agent-message"), titleAnswerEntryIds("t", null))
    }
}
